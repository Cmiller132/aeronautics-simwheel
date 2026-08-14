//! Linux evdev backend: drives any kernel force-feedback wheel (MOZA R9 via
//! `hid-universal-pidff`, mainline since 6.15 / backported to 6.12.24,
//! 6.13.12, 6.14.3) — scan `/dev/input/event*` for FF_CONSTANT capability,
//! gain to full, autocenter off, one constant effect whose level is updated
//! with `EVIOCSFF`.
//!
//! Safety posture (mirrors dinput.rs; hardened by a THIRD adversarial review
//! that found the original escalation broken — every property below is
//! deliberate):
//! - The effect runs with a FINITE `replay.length` (250 ms). While torque is
//!   nonzero it is re-played every 100 ms; if this process hangs or dies the
//!   hardware self-zeroes without another line of code executing.
//! - **The effect is never played while the confirmed level is zero, and
//!   never re-played while parameters are unconfirmed.** This makes lease
//!   expiry the zero-state guarantee even against the failure mode userspace
//!   cannot see: `EVIOCSFF` success means the KERNEL accepted the report —
//!   the usbhid transport is asynchronous, so a "successful" zero write can
//!   still be lost on the wire. Since a believed-zero effect is never
//!   restarted, a lost zero decays within one lease (≤250 ms) instead of
//!   being replayed forever.
//! - The level cache updates ONLY on `EVIOCSFF` success; a failed ZERO write
//!   escalates to a stop write + `EVIOCRMFF` erase (id passed by VALUE — the
//!   kernel takes the effect id as the ioctl argument itself, not a
//!   pointer), and only a successful erase or a later confirmed zero upload
//!   re-authorizes anything. Unconfirmed parameters quarantine the effect:
//!   the retrigger loop's only permitted action is forcing a ZERO.
//! - Rated torque is explicit: USB product id first (MOZA VID 0x346e; the
//!   shared R16/R21 ids 0x0000 and 0x0010 are never guessed), name second,
//!   `--rated-torque` required otherwise.
//! - Device loss (ENODEV) bumps the connection epoch — the bridge disarms
//!   the session and requires a fresh client START (deliberate re-engage).
//!   Re-attach verifies device identity (uniq/phys captured at open) on the
//!   new read-write fd, re-runs the full init gate, and must upload a fresh
//!   zero-level effect before the fd is committed.
//! - No exclusive access exists for evdev FF writers (unlike DirectInput's
//!   exclusive cooperative level); a second local process could add its own
//!   effects. Local processes are inside the trust boundary (see the README
//!   trust model); the accidental two-bridges case is blocked by the UDP
//!   port singleton.
//!
//! Numeric input.h constants and ioctl encodings are defined locally (stable
//! kernel ABI — same policy as the dinput.h constants on the Windows side)
//! and pinned by unit tests against known-good values. The encoding assumes
//! the asm-generic ioctl layout and 64-bit little-endian struct layout, so
//! the build is gated to x86_64/aarch64 below.

use crate::device::{resolve_rated_nm, FfbDevice, WheelState};
use std::ffi::c_void;
use std::fs::{File, OpenOptions};
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

#[cfg(not(all(
    any(target_arch = "x86_64", target_arch = "aarch64"),
    target_endian = "little",
    target_pointer_width = "64"
)))]
compile_error!(
    "the evdev backend hand-encodes the kernel ABI for 64-bit little-endian \
     asm-generic targets (x86_64/aarch64); port the ioctl encoding before \
     enabling other architectures or endiannesses"
);

const EV_FF: u16 = 0x15;
const ABS_X: u32 = 0x00;
const FF_CONSTANT: usize = 0x52;
const FF_GAIN: u16 = 0x60;
const FF_AUTOCENTER: u16 = 0x61;
const FF_CNT: usize = 0x80;
const KEY_CNT: usize = 0x300;
const BTN_JOYSTICK: usize = 0x120;
const BTN_TRIGGER_HAPPY: usize = 0x2c0;

/// Effect duration (ms) and re-trigger cadence: the hardware self-zeroes
/// ≤250 ms after re-triggering stops, whatever the reason.
const EFFECT_DURATION_MS: u16 = 250;
const EFFECT_RETRIGGER_EVERY: Duration = Duration::from_millis(100);
const REOPEN_EVERY: Duration = Duration::from_secs(1);
/// Bound on queued-event reads per poll — a noisy device must not starve
/// the 250 Hz loop (the bridge bounds its UDP drain the same way).
const MAX_DRAIN_READS_PER_POLL: u32 = 64;

const MOZA_VID: u16 = 0x346e;

// linux/ioctl.h encoding, type 'E' (input): dir<<30 | size<<16 | 'E'<<8 | nr.
const IOC_WRITE: u64 = 1;
const IOC_READ: u64 = 2;
const fn ioc(dir: u64, nr: u64, size: usize) -> u64 {
    (dir << 30) | ((size as u64) << 16) | ((b'E' as u64) << 8) | nr
}
const EVIOCGID: u64 = ioc(IOC_READ, 0x02, std::mem::size_of::<InputId>());
const EVIOCGNAME_256: u64 = ioc(IOC_READ, 0x06, 256);
const EVIOCGPHYS_256: u64 = ioc(IOC_READ, 0x07, 256);
const EVIOCGUNIQ_256: u64 = ioc(IOC_READ, 0x08, 256);
const EVIOCGKEY_BUF: u64 = ioc(IOC_READ, 0x18, KEY_CNT / 8);
const fn eviocgbit(ev: u16, len: usize) -> u64 {
    ioc(IOC_READ, 0x20 + ev as u64, len)
}
const EVIOCGABS_X: u64 = ioc(IOC_READ, 0x40 + ABS_X as u64, std::mem::size_of::<AbsInfo>());
const EVIOCSFF: u64 = ioc(IOC_WRITE, 0x80, std::mem::size_of::<FfEffect>());
const EVIOCRMFF: u64 = ioc(IOC_WRITE, 0x81, std::mem::size_of::<libc::c_int>());
const EVIOCGEFFECTS: u64 = ioc(IOC_READ, 0x84, std::mem::size_of::<libc::c_int>());

#[repr(C)]
#[derive(Default, Clone, Copy, PartialEq)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
#[derive(Default, Clone, Copy)]
struct AbsInfo {
    value: i32,
    minimum: i32,
    maximum: i32,
    fuzz: i32,
    flat: i32,
    resolution: i32,
}

#[repr(C)]
struct InputEvent {
    time_sec: i64,
    time_usec: i64,
    type_: u16,
    code: u16,
    value: i32,
}

/// `struct ff_effect` with the union as an opaque, 8-aligned blob: the u64
/// alignment reproduces the C layout (union at offset 16, total size 48 on
/// 64-bit), pinned by tests below. The constant-force `level` (i16) sits at
/// union offset 0; envelope stays zeroed. Little-endian assumed (enforced by
/// the arch gate above).
#[repr(C)]
struct FfEffect {
    type_: u16,
    id: i16,
    direction: u16,
    trigger_button: u16,
    trigger_interval: u16,
    replay_length: u16,
    replay_delay: u16,
    u: [u64; 4],
}

const _: () = assert!(std::mem::size_of::<FfEffect>() == 48);
const _: () = assert!(std::mem::size_of::<InputEvent>() == 24);
const _: () = assert!(std::mem::size_of::<AbsInfo>() == 24);
const _: () = assert!(std::mem::size_of::<InputId>() == 8);

fn xioctl(fd: RawFd, req: u64, arg: *mut c_void) -> std::io::Result<()> {
    // SAFETY: callers pass a pointer to storage matching the request's size.
    let r = unsafe { libc::ioctl(fd, req as libc::c_ulong, arg) };
    if r < 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

/// `EVIOCRMFF` takes the effect id AS the ioctl argument (by value), not a
/// pointer to it — see the kernel's evdev handler. Passing a pointer here
/// was a review-caught critical: the kernel would have interpreted the
/// pointer address as an id and the erase escalation would never have fired.
fn erase_effect(fd: RawFd, id: i16) -> std::io::Result<()> {
    // SAFETY: scalar-argument ioctl per the evdev UAPI contract.
    let r = unsafe { libc::ioctl(fd, EVIOCRMFF as libc::c_ulong, id as libc::c_ulong) };
    if r < 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn write_event(fd: RawFd, type_: u16, code: u16, value: i32) -> std::io::Result<()> {
    let ev = InputEvent { time_sec: 0, time_usec: 0, type_, code, value };
    // SAFETY: ev is a plain #[repr(C)] value of the exact kernel struct size.
    let n = unsafe {
        libc::write(fd, &ev as *const _ as *const c_void, std::mem::size_of::<InputEvent>())
    };
    if n == std::mem::size_of::<InputEvent>() as isize {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

/// Upload (id == -1) or update the constant-force effect on `fd`; returns
/// the kernel-assigned id. Success means the KERNEL accepted the report,
/// not that the hardware applied it — see the module doc for why the play
/// policy makes that distinction safe.
fn upload_effect_fd(fd: RawFd, id: i16, level: i16) -> std::io::Result<i16> {
    let mut eff = FfEffect {
        type_: FF_CONSTANT as u16,
        id,
        direction: 0x4000, // "left" per input.h; sign convention rides the level
        trigger_button: 0,
        trigger_interval: 0,
        replay_length: EFFECT_DURATION_MS,
        replay_delay: 0,
        u: [0; 4],
    };
    eff.u[0] = level as u16 as u64; // ff_constant_effect.level at union offset 0 (LE)
    xioctl(fd, EVIOCSFF, &mut eff as *mut _ as *mut c_void)?;
    Ok(eff.id)
}

fn bit(bits: &[u8], n: usize) -> bool {
    n / 8 < bits.len() && bits[n / 8] & (1 << (n % 8)) != 0
}

fn is_enodev(e: &std::io::Error) -> bool {
    e.raw_os_error() == Some(libc::ENODEV)
}

fn read_string_ioctl(fd: RawFd, req: u64) -> String {
    let mut buf = [0u8; 256];
    match xioctl(fd, req, buf.as_mut_ptr() as *mut c_void) {
        Ok(()) => {
            let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
            String::from_utf8_lossy(&buf[..end]).into_owned()
        }
        Err(_) => String::new(),
    }
}

/// Everything that names a physical device, read from the fd that will
/// actually receive output (not from an earlier scan — no TOCTOU).
#[derive(Clone, PartialEq, Default)]
struct Identity {
    id: InputId,
    name: String,
    uniq: String,
    phys: String,
}

fn read_identity(fd: RawFd) -> Identity {
    let mut id = InputId::default();
    let _ = xioctl(fd, EVIOCGID, &mut id as *mut _ as *mut c_void);
    let name = {
        let s = read_string_ioctl(fd, EVIOCGNAME_256);
        if s.is_empty() { String::from("(unnamed)") } else { s }
    };
    Identity {
        id,
        name,
        uniq: read_string_ioctl(fd, EVIOCGUNIQ_256),
        phys: read_string_ioctl(fd, EVIOCGPHYS_256),
    }
}

/// Re-attach identity check: serial (`uniq`) wins when the original had one;
/// else the physical topology path (`phys` — stable across replug into the
/// same port); else vendor/product/name only, which the caller must guard
/// with an ambiguity check (two identical serial-less wheels are
/// indistinguishable — refuse rather than guess which human gets torque).
fn identity_matches(stored: &Identity, cur: &Identity) -> bool {
    if cur.id.vendor != stored.id.vendor
        || cur.id.product != stored.id.product
        || cur.name != stored.name
    {
        return false;
    }
    if !stored.uniq.is_empty() {
        return cur.uniq == stored.uniq;
    }
    if !stored.phys.is_empty() {
        return cur.phys == stored.phys;
    }
    true
}

/// Never guess between the R16 and R21 — they share product ids (0x0000
/// first-gen, 0x0010 second-gen), so those fall through to name matching /
/// `--rated-torque`. Second-generation ids per the kernel's hid-ids.h.
fn rated_by_usb_id(id: &InputId) -> Option<f32> {
    if id.vendor != MOZA_VID {
        return None;
    }
    match id.product {
        0x0002 | 0x0012 => Some(9.0),  // R9 (gen 1 / gen 2)
        0x0004 | 0x0014 => Some(5.5),  // R5
        0x0005 | 0x0015 => Some(3.9),  // R3
        0x0006 | 0x0016 => Some(12.0), // R12
        _ => None,
    }
}

struct Candidate {
    path: PathBuf,
    name: String,
    id: InputId,
}

/// Enumerate `/dev/input/event*` nodes advertising FF_CONSTANT, in stable
/// numeric order. Also reports how many nodes we could not open (permission),
/// so error messages can point at the udev/group fix instead of shrugging.
fn scan() -> (Vec<Candidate>, usize) {
    let mut nodes: Vec<(u32, PathBuf)> = match std::fs::read_dir("/dev/input") {
        Ok(rd) => rd
            .flatten()
            .filter_map(|e| {
                let p = e.path();
                let num: u32 = p.file_name()?.to_str()?.strip_prefix("event")?.parse().ok()?;
                Some((num, p))
            })
            .collect(),
        Err(_) => return (Vec::new(), 0),
    };
    nodes.sort_by_key(|(n, _)| *n);

    let mut out = Vec::new();
    let mut denied = 0usize;
    for (_, path) in nodes {
        let f = match OpenOptions::new().read(true).custom_flags(libc::O_NONBLOCK).open(&path) {
            Ok(f) => f,
            Err(e) if e.kind() == std::io::ErrorKind::PermissionDenied => {
                denied += 1;
                continue;
            }
            Err(_) => continue,
        };
        let fd = f.as_raw_fd();
        let mut ff_bits = [0u8; FF_CNT / 8];
        if xioctl(fd, eviocgbit(EV_FF, ff_bits.len()), ff_bits.as_mut_ptr() as *mut c_void)
            .is_err()
            || !bit(&ff_bits, FF_CONSTANT)
        {
            continue;
        }
        let ident = read_identity(fd);
        out.push(Candidate { path, name: ident.name, id: ident.id });
    }
    (out, denied)
}

fn permission_hint(denied: usize) -> String {
    format!(
        "{denied} input device(s) exist but could not be opened (permission). \
         Fix: add yourself to the 'input' group — sudo usermod -aG input $USER — \
         then log out and back in (or install a udev rule granting rw on the \
         wheelbase's /dev/input/event* node)."
    )
}

pub fn list_devices() -> Result<Vec<String>, String> {
    let (cands, denied) = scan();
    if cands.is_empty() && denied > 0 {
        return Err(format!("no readable FFB devices. {}", permission_hint(denied)));
    }
    Ok(cands
        .iter()
        .map(|c| format!("{} ({:04x}:{:04x}) — {}", c.name, c.id.vendor, c.id.product, c.path.display()))
        .collect())
}

fn torque_to_level(nm: f32, rated_nm: f32) -> i16 {
    if !nm.is_finite() {
        return 0;
    }
    ((nm / rated_nm) * 32767.0).clamp(-32767.0, 32767.0) as i16
}

pub struct EvdevDevice {
    file: Option<File>,
    ident: Identity,
    id_hash: u32,
    range_deg: f32,
    rated_nm: f32,
    ack_autocenter: bool,
    abs: AbsInfo,
    prev_deg: f32,
    vel_deg_per_s: f32,
    /// Suppress the velocity sample right after (re)open — a stale prev_deg
    /// against a fresh angle would synthesize an enormous spike.
    skip_vel_sample: bool,
    output_fault: bool,
    effect_id: Option<i16>,
    /// The level the KERNEL last accepted (cache advances only on success).
    last_level: i16,
    /// False = the device may hold parameters we did not confirm. While
    /// false the effect is QUARANTINED: it is never played, and the only
    /// permitted write is one that forces a known zero.
    params_valid: bool,
    /// Play needed on the next opportunity (0 → nonzero transition).
    needs_start: bool,
    effect_started_at: Option<Instant>,
    /// Throttles play retries after a failed play so a marginal driver isn't
    /// hammered at loop rate; the first play after a fresh write is never
    /// delayed by it (the previous attempt is long past).
    last_play_attempt: Option<Instant>,
    last_reopen_attempt: Option<Instant>,
    /// Bumped on every connection loss; the bridge disarms on change.
    epoch: u32,
}

/// Open + run the full output-safety gate on one event node: FF_CONSTANT
/// capability, ≥1 effect slot, gain to full, autocenter handling, ABS_X
/// sanity, and a KERNEL-ACCEPTED zero-level effect upload. The fd is only
/// returned once all of that holds — used both at first open and at
/// re-attach, so a replugged device re-earns output from scratch.
fn open_and_init(
    path: &Path,
    ack_autocenter: bool,
    expected: Option<&Identity>,
) -> Result<(File, Identity, AbsInfo, i16), String> {
    let f = OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_NONBLOCK)
        .open(path)
        .map_err(|e| {
            if e.kind() == std::io::ErrorKind::PermissionDenied {
                format!("cannot open {} read-write. {}", path.display(), permission_hint(1))
            } else {
                format!("cannot open {}: {e}", path.display())
            }
        })?;
    let fd = f.as_raw_fd();
    let ident = read_identity(fd);
    // Identity gate FIRST — before gain, autocenter, or any effect write.
    // Probing must never mutate a device that turns out to be someone
    // else's wheel (review-caught ordering hazard with identical models).
    if let Some(exp) = expected {
        if !identity_matches(exp, &ident) {
            return Err(format!(
                "identity mismatch at {} (a different physical device)",
                path.display()
            ));
        }
    }

    let mut n_effects: libc::c_int = 0;
    xioctl(fd, EVIOCGEFFECTS, &mut n_effects as *mut _ as *mut c_void)
        .map_err(|e| format!("EVIOCGEFFECTS failed: {e}"))?;
    if n_effects < 1 {
        return Err("device reports zero simultaneous FF effects".into());
    }

    let mut ff_bits = [0u8; FF_CNT / 8];
    xioctl(fd, eviocgbit(EV_FF, ff_bits.len()), ff_bits.as_mut_ptr() as *mut c_void)
        .map_err(|e| format!("cannot read FF capabilities: {e}"))?;
    if !bit(&ff_bits, FF_CONSTANT) {
        return Err("device does not support constant-force effects".into());
    }

    // Full gain so our Nm→level mapping is not silently rescaled. A failure
    // can only make forces weaker than commanded — warn, don't refuse.
    if write_event(fd, EV_FF, FF_GAIN, 0xFFFF).is_err() {
        eprintln!("[bridge] warning: could not set FF gain to full (forces may be weaker than commanded)");
    }

    // The base must not fight the game with its own centering spring.
    // Advertised + write failed → hard gate (the kernel affirmatively
    // reports a controllable autocenter we could not turn off). Capability
    // ABSENT is the common no-mechanism case under hid-pidff and gets a
    // loud warning instead of a gate: the mandatory operator step (vendor
    // tool: spring/centering to zero, game-FFB mode) owns the in-base
    // spring on every platform — the same position Windows is in, where a
    // driver may accept the autocenter property without really controlling
    // the base's firmware centering.
    if bit(&ff_bits, FF_AUTOCENTER as usize) {
        if write_event(fd, EV_FF, FF_AUTOCENTER, 0).is_err() {
            eprintln!(
                "[bridge] !!! could not disable device autocenter via evdev.\n\
                 [bridge] !!! If the base's own centering is active it will fight and\n\
                 [bridge] !!! add to game torque OUTSIDE every software clamp."
            );
            if !ack_autocenter {
                return Err(
                    "refusing to drive this device: verify centering is disabled in the \
                     vendor tool (MOZA Pit House), then re-run with --ack-autocenter"
                        .into(),
                );
            }
            eprintln!("[bridge] continuing on --ack-autocenter (operator-acknowledged)");
        }
    } else {
        eprintln!(
            "[bridge] note: device does not expose an evdev autocenter control — \
             make sure centering/spring is OFF in the vendor tool (MOZA Pit House)."
        );
    }

    let mut abs = AbsInfo::default();
    xioctl(fd, EVIOCGABS_X, &mut abs as *mut _ as *mut c_void)
        .map_err(|e| format!("cannot read the steering axis (ABS_X): {e}"))?;
    if abs.maximum <= abs.minimum {
        return Err(format!(
            "degenerate ABS_X range {}..{}",
            abs.minimum, abs.maximum
        ));
    }

    // The zero gate: output is only armed once the kernel has accepted a
    // zero-level effect. Not played — a zero needs no playing effect.
    let effect_id = upload_effect_fd(fd, -1, 0)
        .map_err(|e| format!("cannot upload the initial zero-force effect: {e}"))?;

    Ok((f, ident, abs, effect_id))
}

impl EvdevDevice {
    pub fn open(
        index: usize,
        range_deg: f32,
        rated_nm: Option<f32>,
        ack_autocenter: bool,
    ) -> Result<Self, String> {
        let (cands, denied) = scan();
        if cands.is_empty() {
            return Err(if denied > 0 {
                format!("no readable FFB devices. {}", permission_hint(denied))
            } else {
                "no force-feedback devices found under /dev/input — is the wheelbase \
                 plugged in, and does the kernel have its FFB driver? (MOZA needs \
                 hid-universal-pidff: mainline 6.15+, or 6.12.24 / 6.13.12 / 6.14.3)"
                    .into()
            });
        }
        let c = cands
            .get(index)
            .ok_or_else(|| format!("no FFB device at index {index} (see --list)"))?;

        // Guard the (narrow) scan→open hotplug race: the node we open must
        // still be the model the scan selected. uniq/phys are empty here, so
        // this compares vendor/product/name — exactly what scan knew.
        let scanned = Identity {
            id: c.id,
            name: c.name.clone(),
            uniq: String::new(),
            phys: String::new(),
        };
        let (file, ident, abs, effect_id) =
            open_and_init(&c.path, ack_autocenter, Some(&scanned))?;
        // Rated torque from the identity on the fd that will get output
        // (scan's snapshot could be stale if udev renumbered in between).
        let rated_nm = match rated_nm {
            Some(nm) => nm,
            None => match rated_by_usb_id(&ident.id) {
                Some(nm) => nm,
                None => resolve_rated_nm(&ident.name, None)?,
            },
        };

        let mut hasher: u32 = 0x811C_9DC5; // FNV-1a over USB id + name
        for b in ident
            .id
            .vendor
            .to_le_bytes()
            .into_iter()
            .chain(ident.id.product.to_le_bytes())
            .chain(ident.name.bytes())
        {
            hasher ^= b as u32;
            hasher = hasher.wrapping_mul(0x0100_0193);
        }

        Ok(EvdevDevice {
            file: Some(file),
            ident,
            id_hash: hasher,
            range_deg,
            rated_nm,
            ack_autocenter,
            abs,
            prev_deg: 0.0,
            vel_deg_per_s: 0.0,
            skip_vel_sample: true,
            output_fault: false,
            effect_id: Some(effect_id),
            last_level: 0,
            params_valid: true,
            needs_start: false,
            effect_started_at: None,
            last_play_attempt: None,
            last_reopen_attempt: None,
            epoch: 0,
        })
    }

    /// The retrigger/recovery loop, run every poll.
    ///
    /// - Quarantined (`!params_valid`): the ONLY permitted action is forcing
    ///   a known zero — try a zero upload (or a fresh upload if the effect
    ///   was erased); never play. Until that succeeds, the finite lease is
    ///   the backstop: an unplayed effect dies within 250 ms.
    /// - Confirmed zero: nothing to play. Letting the lease lapse (instead
    ///   of replaying a "zero" effect) is what protects against a zero
    ///   update that the kernel accepted but the async HID transport lost.
    /// - Confirmed nonzero: (re)play ahead of lease expiry.
    fn ensure_effect_running(&mut self) {
        let Some(fd) = self.file.as_ref().map(|f| f.as_raw_fd()) else {
            return;
        };

        if !self.params_valid {
            match upload_effect_fd(fd, self.effect_id.unwrap_or(-1), 0) {
                Ok(id) => {
                    self.effect_id = Some(id);
                    self.last_level = 0;
                    self.params_valid = true;
                    self.needs_start = false;
                    self.effect_started_at = None;
                    // Belt and braces: also stop playback of the (now zero)
                    // effect in case a previous nonzero level is still live.
                    let _ = write_event(fd, EV_FF, id as u16, 0);
                    self.output_fault = false;
                }
                Err(e) => {
                    if let Some(id) = self.effect_id {
                        let _ = write_event(fd, EV_FF, id as u16, 0);
                        if erase_effect(fd, id).is_ok() {
                            self.effect_id = None;
                            self.last_level = 0;
                            self.params_valid = true;
                            self.needs_start = false;
                            self.effect_started_at = None;
                        }
                    }
                    if is_enodev(&e) {
                        self.device_lost();
                    }
                }
            }
            return;
        }

        if self.last_level == 0 {
            return; // zero needs no playing effect — let the lease lapse
        }

        let now = Instant::now();
        let due = self.needs_start
            || self
                .effect_started_at
                .is_none_or(|t| now.duration_since(t) >= EFFECT_RETRIGGER_EVERY);
        if !due {
            return;
        }
        // Don't hammer a failing driver at loop rate: a retry after a failed
        // play waits out the retrigger period. A first play right after a
        // fresh parameter write is unaffected (last attempt is long past).
        if self
            .last_play_attempt
            .is_some_and(|t| now.duration_since(t) < EFFECT_RETRIGGER_EVERY)
        {
            return;
        }
        // Refresh the parameters on every PERIODIC retrigger, even though
        // the level is unchanged: the HID transport is asynchronous, so an
        // earlier accepted-but-lost update would otherwise let the base
        // replay a stale stored level forever behind the dedup cache
        // (review-caught). A lost nonzero write is bounded by one retrigger
        // period. Skipped when the effect isn't playing yet — the 0→nonzero
        // path just wrote these exact parameters.
        if self.effect_started_at.is_some() {
            match upload_effect_fd(fd, self.effect_id.unwrap_or(-1), self.last_level) {
                Ok(id) => self.effect_id = Some(id),
                Err(e) => {
                    self.params_valid = false;
                    self.output_fault = true;
                    if is_enodev(&e) {
                        self.device_lost();
                    }
                    return;
                }
            }
        }
        if let Some(id) = self.effect_id {
            self.last_play_attempt = Some(now);
            match write_event(fd, EV_FF, id as u16, 1) {
                Ok(()) => {
                    self.effect_started_at = Some(now);
                    self.needs_start = false;
                    self.output_fault = false;
                }
                Err(e) => {
                    self.needs_start = true;
                    self.output_fault = true;
                    if is_enodev(&e) {
                        self.device_lost();
                    }
                }
            }
        }
    }

    /// The fd is gone (unplug): physical torque is off by definition — the
    /// kernel destroys a device's effects with the device. Bump the epoch so
    /// the bridge disarms; poll() rescans at 1 Hz.
    fn device_lost(&mut self) {
        eprintln!("[bridge] device lost (unplugged?) — forces off, session disarmed, rescanning at 1 Hz");
        self.file = None;
        self.effect_id = None;
        self.needs_start = false;
        self.effect_started_at = None;
        self.output_fault = true;
        self.last_level = 0;
        self.params_valid = true;
        self.vel_deg_per_s = 0.0;
        self.epoch = self.epoch.wrapping_add(1);
    }

    fn try_reopen(&mut self) {
        let now = Instant::now();
        if self
            .last_reopen_attempt
            .is_some_and(|t| now.duration_since(t) < REOPEN_EVERY)
        {
            return;
        }
        self.last_reopen_attempt = Some(now);

        let (cands, _) = scan();
        let matches: Vec<&Candidate> = cands
            .iter()
            .filter(|c| {
                c.id.vendor == self.ident.id.vendor
                    && c.id.product == self.ident.id.product
                    && c.name == self.ident.name
            })
            .collect();
        if matches.is_empty() {
            return;
        }
        // Serial-less identity + several lookalikes → refuse rather than
        // guess which physical wheel (= which human) gets torque.
        if self.ident.uniq.is_empty() && self.ident.phys.is_empty() && matches.len() > 1 {
            eprintln!(
                "[bridge] {} indistinguishable candidates for re-attach and no \
                 serial/phys to tell them apart — refusing; restart the bridge \
                 with an explicit --device index",
                matches.len()
            );
            return;
        }

        for c in matches {
            // Full init gate again — a replugged device must re-earn output.
            // Identity is verified INSIDE open_and_init, on the very fd that
            // would receive output and before anything is written to it.
            match open_and_init(&c.path, self.ack_autocenter, Some(&self.ident)) {
                Ok((file, ident, abs, effect_id)) => {
                    eprintln!("[bridge] device re-attached at {}", c.path.display());
                    self.file = Some(file);
                    self.ident = ident;
                    self.abs = abs;
                    self.effect_id = Some(effect_id);
                    self.needs_start = false;
                    self.effect_started_at = None;
                    self.last_level = 0;
                    self.params_valid = true;
                    self.skip_vel_sample = true;
                    // The connection changed AGAIN (absent → present): bump
                    // the epoch on the RETURN too, not just the loss. A
                    // START that arrived while the device was gone must not
                    // silently become authority over the re-attached wheel —
                    // and its ramp-in has already elapsed (review-caught).
                    self.epoch = self.epoch.wrapping_add(1);
                    return;
                }
                Err(e) => eprintln!("[bridge] re-attach failed: {e}"),
            }
        }
    }

    fn read_buttons(&self, fd: RawFd) -> u32 {
        let mut keys = [0u8; KEY_CNT / 8];
        if xioctl(fd, EVIOCGKEY_BUF, keys.as_mut_ptr() as *mut c_void).is_err() {
            return 0;
        }
        let mut b = 0u32;
        for i in 0..16 {
            if bit(&keys, BTN_JOYSTICK + i) {
                b |= 1 << i;
            }
            if bit(&keys, BTN_TRIGGER_HAPPY + i) {
                b |= 1 << (16 + i);
            }
        }
        b
    }
}

impl FfbDevice for EvdevDevice {
    fn name(&self) -> &str {
        &self.ident.name
    }

    fn rated_torque_nm(&self) -> f32 {
        self.rated_nm
    }

    fn id_hash(&self) -> u32 {
        self.id_hash
    }

    fn connection_epoch(&self) -> u32 {
        self.epoch
    }

    fn poll(&mut self, dt_s: f64) -> WheelState {
        if self.file.is_none() {
            self.try_reopen();
        }
        let Some(fd) = self.file.as_ref().map(|f| f.as_raw_fd()) else {
            return WheelState {
                steering_deg: self.prev_deg,
                steering_vel_deg_per_s: 0.0,
                buttons: 0,
                fault: true,
            };
        };

        // Drain the event queue (state comes from ioctls; an unread queue
        // would just accumulate server-side). Bounded: a noisy device must
        // not starve the 250 Hz loop.
        let mut buf = [0u8; std::mem::size_of::<InputEvent>() * 32];
        let mut reads = 0u32;
        while reads < MAX_DRAIN_READS_PER_POLL {
            reads += 1;
            // SAFETY: plain byte buffer, non-blocking fd.
            let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut c_void, buf.len()) };
            if n <= 0 {
                if n < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::ENODEV) {
                    self.device_lost();
                    return WheelState {
                        steering_deg: self.prev_deg,
                        steering_vel_deg_per_s: 0.0,
                        buttons: 0,
                        fault: true,
                    };
                }
                break;
            }
        }

        self.ensure_effect_running();
        // ensure_effect_running may have detected device loss and closed the
        // fd — never touch the stale descriptor number after that.
        if self.file.is_none() {
            return WheelState {
                steering_deg: self.prev_deg,
                steering_vel_deg_per_s: 0.0,
                buttons: 0,
                fault: true,
            };
        }

        let mut abs = AbsInfo::default();
        match xioctl(fd, EVIOCGABS_X, &mut abs as *mut _ as *mut c_void) {
            Ok(()) => {
                let center = (self.abs.minimum as f64 + self.abs.maximum as f64) / 2.0;
                let half = ((self.abs.maximum as f64 - self.abs.minimum as f64) / 2.0).max(1.0);
                let deg =
                    (((abs.value as f64 - center) / half) * (self.range_deg as f64 / 2.0)) as f32;
                if self.skip_vel_sample {
                    // First sample after (re)open: seed, don't differentiate.
                    self.skip_vel_sample = false;
                    self.vel_deg_per_s = 0.0;
                } else if dt_s > 0.0 {
                    // Light smoothing on the derivative: raw HID deltas are steppy.
                    let v = (deg - self.prev_deg) / dt_s as f32;
                    self.vel_deg_per_s += (v - self.vel_deg_per_s) * 0.3;
                }
                self.prev_deg = deg;
                WheelState {
                    steering_deg: deg,
                    steering_vel_deg_per_s: self.vel_deg_per_s,
                    buttons: self.read_buttons(fd),
                    fault: self.output_fault,
                }
            }
            Err(e) => {
                if is_enodev(&e) {
                    self.device_lost();
                }
                WheelState {
                    steering_deg: self.prev_deg,
                    steering_vel_deg_per_s: 0.0,
                    buttons: 0,
                    fault: true,
                }
            }
        }
    }

    fn set_torque_nm(&mut self, nm: f32) {
        let level = torque_to_level(nm, self.rated_nm);
        if level == self.last_level && self.params_valid {
            return; // known accepted — don't spam the driver
        }
        if !self.params_valid && level != 0 {
            // Quarantined: only ZERO may be written. A nonzero upload here
            // would re-validate the cache without ever forcing the confirmed
            // zero the quarantine exists to obtain (review-caught bypass —
            // the bridge drains TORQUE frames before the recovery loop runs).
            self.output_fault = true;
            return;
        }
        let Some(fd) = self.file.as_ref().map(|f| f.as_raw_fd()) else {
            // No device: physical torque is already off. A zero request is
            // therefore satisfied; anything else stays unmet and faulted.
            if level == 0 {
                self.last_level = 0;
                self.params_valid = true;
            } else {
                self.output_fault = true;
            }
            return;
        };
        match upload_effect_fd(fd, self.effect_id.unwrap_or(-1), level) {
            Ok(id) => {
                // Cache updates ONLY on kernel acceptance (a failed write may
                // leave the OLD level in place, per the driver contract).
                self.effect_id = Some(id);
                self.last_level = level;
                self.params_valid = true;
                self.output_fault = false;
                if level == 0 {
                    // Stop playback too (belt and braces against an async
                    // transport losing the zero update), and let the lease
                    // policy keep the effect unplayed while zero.
                    let _ = write_event(fd, EV_FF, id as u16, 0);
                    self.effect_started_at = None;
                    self.needs_start = false;
                    // Clear the retry throttle too: the next nonzero writes
                    // fresh parameters and must play immediately, or a rapid
                    // zero crossing would cost up to a retrigger period of
                    // dead torque (review-caught).
                    self.last_play_attempt = None;
                } else if self.effect_started_at.is_none() {
                    // 0 → nonzero transition: the effect isn't playing.
                    self.needs_start = true;
                    self.ensure_effect_running();
                }
            }
            Err(e) => {
                self.params_valid = false;
                self.output_fault = true;
                let lost = is_enodev(&e);
                if level == 0 && !lost {
                    // A zero MUST take effect by any available means: stop
                    // playback, then erase the effect outright. Only a
                    // successful ERASE re-authorizes (stop alone leaves the
                    // stale parameters stored — quarantine keeps them
                    // unplayable until a zero upload is confirmed).
                    if let Some(id) = self.effect_id {
                        let _ = write_event(fd, EV_FF, id as u16, 0);
                        if erase_effect(fd, id).is_ok() {
                            self.effect_id = None;
                            self.last_level = 0;
                            self.params_valid = true;
                            self.needs_start = false;
                            self.effect_started_at = None;
                        }
                        // else: quarantined — ensure_effect_running retries
                        // the forced zero every poll, and the finite lease
                        // (≤250 ms, never re-played) is the backstop.
                    }
                }
                if lost {
                    self.device_lost();
                }
            }
        }
    }
}

impl Drop for EvdevDevice {
    fn drop(&mut self) {
        if let Some(f) = &self.file {
            let fd = f.as_raw_fd();
            if let Some(id) = self.effect_id {
                let _ = write_event(fd, EV_FF, id as u16, 0);
                let _ = erase_effect(fd, id);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The hand-encoded ioctl numbers, pinned against values computed by the
    /// kernel's own macros (independently verified constants — if any of
    /// these fires, the ABI encoding above is wrong, not the literal).
    #[test]
    fn ioctl_numbers_match_kernel_abi() {
        assert_eq!(EVIOCGID, 0x8008_4502);
        assert_eq!(EVIOCGNAME_256, 0x8100_4506);
        assert_eq!(EVIOCGPHYS_256, 0x8100_4507);
        assert_eq!(EVIOCGUNIQ_256, 0x8100_4508);
        assert_eq!(EVIOCGKEY_BUF, 0x8060_4518);
        assert_eq!(eviocgbit(EV_FF, FF_CNT / 8), 0x8010_4535);
        assert_eq!(EVIOCGABS_X, 0x8018_4540);
        assert_eq!(EVIOCSFF, 0x4030_4580);
        assert_eq!(EVIOCRMFF, 0x4004_4581);
        assert_eq!(EVIOCGEFFECTS, 0x8004_4584);
    }

    #[test]
    fn ff_effect_layout_matches_kernel_struct() {
        let eff = FfEffect {
            type_: 0,
            id: 0,
            direction: 0,
            trigger_button: 0,
            trigger_interval: 0,
            replay_length: 0,
            replay_delay: 0,
            u: [0; 4],
        };
        let base = &eff as *const _ as usize;
        assert_eq!(&eff.replay_length as *const _ as usize - base, 10);
        assert_eq!(&eff.u as *const _ as usize - base, 16, "union must sit at offset 16");
    }

    #[test]
    fn torque_level_mapping_is_clamped_and_finite() {
        assert_eq!(torque_to_level(0.0, 9.0), 0);
        assert_eq!(torque_to_level(9.0, 9.0), 32767);
        assert_eq!(torque_to_level(-9.0, 9.0), -32767);
        assert_eq!(torque_to_level(90.0, 9.0), 32767); // saturates, never wraps
        assert_eq!(torque_to_level(f32::NAN, 9.0), 0);
        assert_eq!(torque_to_level(f32::INFINITY, 9.0), 0);
        assert_eq!(torque_to_level(f32::NEG_INFINITY, 9.0), 0);
        let quarter = torque_to_level(2.25, 9.0);
        assert!((8000..=8400).contains(&quarter), "2.25/9 Nm ≈ quarter scale, got {quarter}");
    }

    #[test]
    fn moza_usb_id_table() {
        let id = |product| InputId { bustype: 3, vendor: MOZA_VID, product, version: 0 };
        // Gen 1
        assert_eq!(rated_by_usb_id(&id(0x0002)), Some(9.0));
        assert_eq!(rated_by_usb_id(&id(0x0004)), Some(5.5));
        assert_eq!(rated_by_usb_id(&id(0x0005)), Some(3.9));
        assert_eq!(rated_by_usb_id(&id(0x0006)), Some(12.0));
        // Gen 2 (kernel hid-ids.h)
        assert_eq!(rated_by_usb_id(&id(0x0012)), Some(9.0));
        assert_eq!(rated_by_usb_id(&id(0x0014)), Some(5.5));
        assert_eq!(rated_by_usb_id(&id(0x0015)), Some(3.9));
        assert_eq!(rated_by_usb_id(&id(0x0016)), Some(12.0));
        // R16 and R21 share ids in BOTH generations — must NOT be guessed.
        assert_eq!(rated_by_usb_id(&id(0x0000)), None);
        assert_eq!(rated_by_usb_id(&id(0x0010)), None);
        let other = InputId { bustype: 3, vendor: 0x046d, product: 0x0002, version: 0 };
        assert_eq!(rated_by_usb_id(&other), None);
    }

    #[test]
    fn reattach_identity_rules() {
        let base = Identity {
            id: InputId { bustype: 3, vendor: MOZA_VID, product: 0x0002, version: 1 },
            name: "MOZA R9 Base".into(),
            uniq: "SN123".into(),
            phys: "usb-0000:00:14.0-2/input0".into(),
        };
        // Serial present: serial decides, phys may change (different port).
        let mut moved = base.clone();
        moved.phys = "usb-0000:00:14.0-4/input0".into();
        assert!(identity_matches(&base, &moved));
        let mut other_serial = base.clone();
        other_serial.uniq = "SN999".into();
        assert!(!identity_matches(&base, &other_serial));

        // No serial: phys (same port) decides.
        let mut stored = base.clone();
        stored.uniq = String::new();
        let mut same_port = stored.clone();
        same_port.uniq = "whatever".into();
        assert!(identity_matches(&stored, &same_port));
        let mut other_port = stored.clone();
        other_port.phys = "usb-0000:00:14.0-9/input0".into();
        assert!(!identity_matches(&stored, &other_port));

        // Different model never matches.
        let mut other_model = base.clone();
        other_model.id.product = 0x0006;
        assert!(!identity_matches(&base, &other_model));
    }

    #[test]
    fn scan_survives_missing_dev_input() {
        // In containers/WSL /dev/input may not exist; scan must not panic
        // and --list must degrade to "no devices".
        let (cands, _) = scan();
        // No assertion on contents — just that it returns.
        let _ = cands.len();
    }
}
