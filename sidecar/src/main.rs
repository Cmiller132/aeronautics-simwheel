//! simwheel-bridge — native FFB sidecar for Aeronautics SimWheel.
//!
//! Speaks the AWFB UDP protocol (DESIGN.md §6.6) on localhost and drives a
//! force-feedback wheelbase (MOZA R-series, Simagic Alpha EVO with
//! `--rated-torque`, or any PID FFB wheel) — DirectInput on Windows, kernel
//! evdev on Linux. No vendor SDK: these are standard PID force-feedback
//! devices on both.
//!
//! Modes:
//!   simwheel-bridge --list           enumerate FFB-capable devices, exit
//!   simwheel-bridge --sim            simulated wheel (protocol conformance)
//!   simwheel-bridge [--device N]     drive a real wheel (default: first FFB)
//!
//! Options: --port <udp port> (default 46910), --max-torque <Nm> (bridge-side
//! hard ceiling, default 5.0), --max-slew <Nm/s> (output slew limit, default
//! 500 — zeroing paths bypass it), --range <deg> (wheel rotation range as set
//! in the vendor tool, default 1080; reported to the mod in HELLO),
//! --rated-torque <Nm> (the base's rated maximum — required for wheelbases
//! the sidecar doesn't recognize), --ack-autocenter (proceed despite an
//! un-disableable device autocenter), --invert-ffb (flip torque sign — for
//! platform/firmware combinations with reversed polarity), --verbose.

mod bridge;
mod device;
mod protocol;
#[cfg(windows)]
mod dinput;
#[cfg(target_os = "linux")]
mod evdev;

use bridge::{Bridge, Tick, DEFAULT_MAX_SLEW_NM_PER_S, DEFAULT_MAX_TORQUE_NM, DEFAULT_RANGE_DEG, STATE_HZ};
use device::FfbDevice;
use std::net::UdpSocket;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

/// Cleared by SIGINT/SIGTERM (or Ctrl-C on Windows) so the main loop exits and
/// the device Drop impls run (stop + effect erase) — without this, the only
/// thing between a Ctrl-C and residual torque was the 250 ms hardware lease.
static RUNNING: AtomicBool = AtomicBool::new(true);

struct Args {
    list: bool,
    sim: bool,
    device_index: usize,
    port: u16,
    max_torque_nm: f32,
    max_slew_nm_per_s: f32,
    range_deg: f32,
    rated_torque_nm: Option<f32>,
    ack_autocenter: bool,
    invert_ffb: bool,
    verbose: bool,
}

fn parse_args() -> Args {
    let mut args = Args {
        list: false,
        sim: false,
        device_index: 0,
        port: protocol::DEFAULT_PORT,
        max_torque_nm: DEFAULT_MAX_TORQUE_NM,
        max_slew_nm_per_s: DEFAULT_MAX_SLEW_NM_PER_S,
        range_deg: DEFAULT_RANGE_DEG,
        rated_torque_nm: None,
        ack_autocenter: false,
        invert_ffb: false,
        verbose: false,
    };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        let mut num = |name: &str| -> f64 {
            it.next()
                .and_then(|v| v.parse().ok())
                .unwrap_or_else(|| die(&format!("{name} needs a numeric value")))
        };
        match a.as_str() {
            "--list" => args.list = true,
            "--sim" => args.sim = true,
            // Integers parse as integers: the old f64-then-cast path silently
            // saturated (--port 99999 → 65535, --device -1 → 0) instead of
            // erroring like every other refused-nonsense flag below.
            "--device" => {
                args.device_index = it
                    .next()
                    .and_then(|v| v.parse::<usize>().ok())
                    .unwrap_or_else(|| die("--device needs a non-negative integer"));
            }
            "--port" => {
                args.port = it
                    .next()
                    .and_then(|v| v.parse::<u16>().ok())
                    .filter(|p| *p != 0)
                    .unwrap_or_else(|| die("--port needs an integer in 1..=65535"));
            }
            "--max-torque" => args.max_torque_nm = num("--max-torque") as f32,
            "--max-slew" => args.max_slew_nm_per_s = num("--max-slew") as f32,
            "--range" => args.range_deg = num("--range") as f32,
            "--rated-torque" => args.rated_torque_nm = Some(num("--rated-torque") as f32),
            "--ack-autocenter" => args.ack_autocenter = true,
            "--invert-ffb" => args.invert_ffb = true,
            "--verbose" | "-v" => args.verbose = true,
            "--help" | "-h" => {
                eprintln!("see the module doc / sidecar/README.md for usage");
                std::process::exit(0);
            }
            other => die(&format!("unknown argument: {other}")),
        }
    }
    // A wheelbase can hurt you; refuse silly numbers rather than obey them.
    if !(0.0..=25.0).contains(&args.max_torque_nm) {
        die("--max-torque must be within 0..=25 Nm");
    }
    if !(10.0..=10000.0).contains(&args.max_slew_nm_per_s) {
        die("--max-slew must be within 10..=10000 Nm/s");
    }
    if !(90.0..=2880.0).contains(&args.range_deg) {
        die("--range must be within 90..=2880 degrees");
    }
    if let Some(nm) = args.rated_torque_nm {
        if !(1.0..=30.0).contains(&nm) {
            die("--rated-torque must be within 1..=30 Nm");
        }
    }
    args
}

fn die(msg: &str) -> ! {
    eprintln!("simwheel-bridge: {msg}");
    std::process::exit(2);
}

fn main() {
    let args = parse_args();

    if args.list {
        #[cfg(windows)]
        let listed = dinput::list_devices().map_err(|e| e.to_string());
        #[cfg(target_os = "linux")]
        let listed = evdev::list_devices();
        #[cfg(not(any(windows, target_os = "linux")))]
        let listed: Result<Vec<String>, String> =
            Err("--list is supported on Windows and Linux only".into());
        match listed {
            Ok(list) if list.is_empty() => println!("no force-feedback devices attached"),
            Ok(list) => {
                for (i, name) in list.iter().enumerate() {
                    println!("{i}: {name}");
                }
            }
            Err(e) => die(&format!("device enumeration failed: {e}")),
        }
        return;
    }

    let socket = UdpSocket::bind(("127.0.0.1", args.port))
        .unwrap_or_else(|e| die(&format!("cannot bind udp 127.0.0.1:{}: {e}", args.port)));
    eprintln!(
        "[bridge] listening on 127.0.0.1:{} (max torque {} Nm)",
        args.port, args.max_torque_nm
    );

    if args.sim {
        eprintln!("[bridge] SIMULATED device — protocol conformance mode, no hardware");
        let mut bridge = Bridge::new(
            socket,
            device::SimDevice::new(),
            args.max_torque_nm,
            args.range_deg,
            args.max_slew_nm_per_s,
            args.verbose,
        );
        bridge.set_invert(args.invert_ffb);
        run(bridge);
    } else {
        #[cfg(windows)]
        {
            let dev = dinput::DirectInputDevice::open(
                args.device_index,
                args.range_deg,
                args.rated_torque_nm,
                args.ack_autocenter,
            )
            .unwrap_or_else(|e| die(&format!("cannot open FFB device: {e}")));
            eprintln!(
                "[bridge] device: '{}' (rated {} Nm — set the base's own limits too)",
                dev.name(),
                dev.rated_torque_nm()
            );
            let mut bridge = Bridge::new(
                socket,
                dev,
                args.max_torque_nm,
                args.range_deg,
                args.max_slew_nm_per_s,
                args.verbose,
            );
            bridge.set_invert(args.invert_ffb);
            run(bridge);
        }
        #[cfg(target_os = "linux")]
        {
            let dev = evdev::EvdevDevice::open(
                args.device_index,
                args.range_deg,
                args.rated_torque_nm,
                args.ack_autocenter,
            )
            .unwrap_or_else(|e| die(&format!("cannot open FFB device: {e}")));
            eprintln!(
                "[bridge] device: '{}' (rated {} Nm — set the base's own limits too)",
                dev.name(),
                dev.rated_torque_nm()
            );
            let mut bridge = Bridge::new(
                socket,
                dev,
                args.max_torque_nm,
                args.range_deg,
                args.max_slew_nm_per_s,
                args.verbose,
            );
            bridge.set_invert(args.invert_ffb);
            run(bridge);
        }
        #[cfg(not(any(windows, target_os = "linux")))]
        die("real-device mode is supported on Windows and Linux; use --sim elsewhere");
    }
}

fn run<D: FfbDevice>(mut bridge: Bridge<D>) {
    install_signal_handlers();
    let sleeper = Sleeper::new();
    let period = Duration::from_secs_f64(1.0 / STATE_HZ);
    let mut prev = Instant::now();
    while RUNNING.load(Ordering::Relaxed) {
        let now = Instant::now();
        let dt = now.duration_since(prev).as_secs_f64().min(0.1);
        prev = now;
        bridge.tick(now, dt, Tick { state_due: true });
        let elapsed = now.elapsed();
        if elapsed < period {
            sleeper.sleep(period - elapsed);
        }
    }
    eprintln!("[bridge] shutdown signal — stopping output");
    drop(bridge); // device Drop runs: stop + effect erase, not just the lease
}

#[cfg(unix)]
fn install_signal_handlers() {
    // Async-signal-safe: the handler only stores an atomic.
    unsafe extern "C" fn on_signal(_sig: libc::c_int) {
        RUNNING.store(false, Ordering::SeqCst);
    }
    unsafe {
        libc::signal(libc::SIGINT, on_signal as *const () as libc::sighandler_t);
        libc::signal(libc::SIGTERM, on_signal as *const () as libc::sighandler_t);
    }
}

#[cfg(windows)]
fn install_signal_handlers() {
    use windows::Win32::Foundation::BOOL;
    use windows::Win32::System::Console::SetConsoleCtrlHandler;
    // Ctrl-C / Ctrl-Break: returning TRUE claims the event and the loop exits
    // cleanly within one 4 ms period. Window-close still hard-kills after the
    // handler returns — that path stays covered by the 250 ms effect lease.
    unsafe extern "system" fn on_ctrl(_event: u32) -> BOOL {
        RUNNING.store(false, Ordering::SeqCst);
        BOOL(1)
    }
    unsafe {
        let _ = SetConsoleCtrlHandler(Some(on_ctrl), true);
    }
}

#[cfg(not(any(unix, windows)))]
fn install_signal_handlers() {}

/// Sub-millisecond sleep on Windows. Plain `thread::sleep` rounds to the
/// ~15.6 ms timer quantum (turning 250 Hz into ~64 Hz), and Windows 11 may
/// revoke `timeBeginPeriod` from processes with occluded windows — so use a
/// HIGH_RESOLUTION waitable timer, with `timeBeginPeriod(1)` + plain sleep as
/// the fallback. The loop is deadline-based either way, so even the fallback
/// stays safe (watchdog latency grows by at most one quantum).
struct Sleeper {
    #[cfg(windows)]
    timer: Option<windows::Win32::Foundation::HANDLE>,
}

impl Sleeper {
    fn new() -> Self {
        #[cfg(windows)]
        unsafe {
            use windows::Win32::System::Threading::{
                CreateWaitableTimerExW, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS,
            };
            let _ = windows::Win32::Media::timeBeginPeriod(1);
            let timer = CreateWaitableTimerExW(
                None,
                None,
                CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
                TIMER_ALL_ACCESS.0,
            )
            .ok();
            Sleeper { timer }
        }
        #[cfg(not(windows))]
        {
            Sleeper {}
        }
    }

    fn sleep(&self, d: Duration) {
        #[cfg(windows)]
        unsafe {
            use windows::Win32::System::Threading::{SetWaitableTimer, WaitForSingleObject};
            if let Some(timer) = self.timer {
                // Negative due time = relative, in 100 ns units.
                let due = -((d.as_nanos() / 100) as i64);
                if SetWaitableTimer(timer, &due, 0, None, None, false).is_ok() {
                    let _ = WaitForSingleObject(timer, 100);
                    return;
                }
            }
            std::thread::sleep(d);
        }
        #[cfg(not(windows))]
        std::thread::sleep(d);
    }
}
