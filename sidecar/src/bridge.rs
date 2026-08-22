//! The bridge session: one UDP socket, one device, one safety state machine.
//!
//! Behavioral contract (DESIGN.md §6.6, mirrored against the mod's
//! `BridgeWheelDevice` + `FakeBridgeServer`):
//! - STATE streams at ~250 Hz to the last client that sent any valid frame —
//!   input keeps flowing whether or not FFB is engaged (the bridge replaces
//!   GLFW as the input source on Windows).
//! - START → reply HELLO (rated torque, device name), clear the panic latch,
//!   zero output.
//! - TORQUE applies only when started and not panicked; magnitude is clamped
//!   by BOTH the frame's own cap and the bridge's `--max-torque`; each frame
//!   re-arms the watchdog with its `watchdog_ms`.
//! - Drained TORQUE frames are COALESCED: every frame runs through the state
//!   machine in order (sequence gate, watchdog re-arm, latches, clamps), but
//!   only the final commanded torque reaches the device — at most one device
//!   write per tick, issued after the drain through the ramp + slew stages.
//! - Output shaping, both bypassed by every zeroing path (an instant zero is
//!   always safe; an instant spike never is):
//!   ramp — after a transition into the armed state, output torque scales
//!   0→1 linearly over 200 ms; slew — per tick the commanded device torque
//!   moves toward the target by at most `--max-slew` × dt.
//! - Watchdog expiry, STOP, PANIC, or a client change → torque zeroes
//!   immediately. PANIC latches until the next START.

use crate::device::FfbDevice;
use crate::protocol::{self, Frame};
use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

pub const STATE_HZ: f64 = 250.0;
/// Bridge-side hard ceiling, independent of anything the mod sends.
pub const DEFAULT_MAX_TORQUE_NM: f32 = 5.0;
/// Default output slew limit (`--max-slew`), Nm per second. Applied AFTER the
/// double clamp; zeroing paths bypass it (an instant zero is always safe).
pub const DEFAULT_MAX_SLEW_NM_PER_S: f32 = 500.0;
/// Linear 0→1 output ramp after every transition into the armed state.
pub const START_RAMP: Duration = Duration::from_millis(200);
/// Default wheel rotation range (`--range`), degrees — reported in HELLO.
pub const DEFAULT_RANGE_DEG: f32 = 1080.0;
/// If a client goes completely silent this long, treat it as gone: zero
/// torque and stop streaming to it (a fresh client re-latches instantly).
pub const CLIENT_SILENCE_TIMEOUT: Duration = Duration::from_secs(10);
/// Max datagrams processed per tick — a loopback flood must never starve
/// `run_safety` (adversarial finding: unbounded drain before the watchdog).
pub const MAX_FRAMES_PER_TICK: u32 = 64;
/// Upper bound on receive ATTEMPTS in one flush — the socket buffer is
/// finite, and an unbounded loop here would be a starvation hazard. Counts
/// every iteration, including errors, so a storm of ICMP-driven
/// `ConnectionReset`s cannot spin past the bound.
const MAX_FLUSH_ATTEMPTS: u32 = 4096;

pub struct Bridge<D: FfbDevice> {
    socket: UdpSocket,
    device: D,
    max_torque_nm: f32,
    /// The wheel's configured rotation range (`--range`), reported in HELLO.
    range_deg: f32,
    /// Output slew limit (`--max-slew`), Nm/s — see `apply_output`.
    max_slew_nm_per_s: f32,
    verbose: bool,
    /// Operator-requested torque sign flip (`--invert-ffb`) — the escape
    /// hatch if a platform/firmware combination turns out polarity-reversed.
    invert: bool,
    /// Device connection epoch at the last tick; any change disarms.
    last_device_epoch: u32,
    /// Set when a post-disarm flush could NOT confirm the socket was empty
    /// (attempt budget or an unexpected error). Frames may still be queued
    /// from before the connection change, so START is refused until a later
    /// flush drains the queue to confirmed-empty.
    rearm_blocked: bool,

    client: Option<SocketAddr>,
    last_client_frame: Instant,
    /// Wrap-aware highest sequence accepted from the current client; stale or
    /// duplicate frames (late/replayed UDP) are dropped so an old TORQUE can
    /// never defeat a newer PANIC/STOP, and duplicates can't re-arm the
    /// watchdog. `None` until the first frame of a session. Residual risk
    /// (same-port client restart within the silence window) is documented in
    /// the README's trust model.
    last_rx_seq: Option<u32>,
    started: bool,
    panic_latched: bool,
    watchdog_deadline: Option<Instant>,
    torque_active: bool,
    /// The latest accepted, double-clamped (and invert-applied) torque target.
    /// The device write happens once per tick in `apply_output`, through the
    /// ramp and slew stages — never directly from the drain.
    target_torque_nm: f32,
    /// The torque last commanded to the device through `apply_output` — the
    /// slew limiter's "where the output actually is". Zeroing paths reset it
    /// to 0 together with their immediate device write.
    output_torque_nm: f32,
    /// Set on every transition into the armed state; drives the 200 ms
    /// linear 0→1 output ramp. Cleared once the ramp completes.
    ramp_started: Option<Instant>,
    /// TORQUE frames accepted from the drain in the current tick — >0 forces
    /// an output write this tick, and everything past the first is coalesced.
    torque_frames_this_tick: u32,
    seq: u32,

    // once-per-second status line state
    last_status: Instant,
    frames_in: u64,
    states_out: u64,
    device_writes: u64,
    frames_coalesced: u64,
    seq_gate_drops: u64,
    // snapshots at the last status line, for per-second rates
    last_device_writes: u64,
    last_frames_coalesced: u64,
    last_seq_gate_drops: u64,
}

pub struct Tick {
    pub state_due: bool,
}

impl<D: FfbDevice> Bridge<D> {
    pub fn new(
        socket: UdpSocket,
        device: D,
        max_torque_nm: f32,
        range_deg: f32,
        max_slew_nm_per_s: f32,
        verbose: bool,
    ) -> Self {
        socket
            .set_nonblocking(true)
            .expect("nonblocking UDP socket");
        let last_device_epoch = device.connection_epoch();
        Bridge {
            socket,
            device,
            max_torque_nm,
            range_deg,
            max_slew_nm_per_s,
            verbose,
            invert: false,
            last_device_epoch,
            rearm_blocked: false,
            client: None,
            last_client_frame: Instant::now(),
            last_rx_seq: None,
            started: false,
            panic_latched: false,
            watchdog_deadline: None,
            torque_active: false,
            target_torque_nm: 0.0,
            output_torque_nm: 0.0,
            ramp_started: None,
            torque_frames_this_tick: 0,
            seq: 0,
            last_status: Instant::now(),
            frames_in: 0,
            states_out: 0,
            device_writes: 0,
            frames_coalesced: 0,
            seq_gate_drops: 0,
            last_device_writes: 0,
            last_frames_coalesced: 0,
            last_seq_gate_drops: 0,
        }
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn device_mut(&mut self) -> &mut D {
        &mut self.device
    }

    pub fn set_invert(&mut self, invert: bool) {
        self.invert = invert;
        if invert {
            eprintln!("[bridge] --invert-ffb: torque sign flipped at the output");
        }
    }

    /// One bridge tick: drain the socket, run safety, advance the device, and
    /// (if `tick.state_due`) emit STATE. Call at ≥ STATE_HZ.
    pub fn tick(&mut self, now: Instant, dt_s: f64, tick: Tick) {
        // Device connection changed (unplug/acquisition loss) → disarm the
        // whole session. Torque may only resume after a fresh client START —
        // never automatically on re-attach (someone may be holding the rim).
        let epoch = self.device.connection_epoch();
        if epoch != self.last_device_epoch {
            self.last_device_epoch = epoch;
            self.zero_torque("device connection changed");
            // Frames that arrived while the connection was changing are
            // still queued in the socket: discard them, or the very next
            // drain would re-arm the session with a START the operator sent
            // before the wheel came back (review-caught). This flush IS the
            // guarantee — everything read afterwards demonstrably arrived
            // after the change. A wall-clock grace period was tried and
            // removed: it is measured when frames are PROCESSED, not when
            // they arrive, so a delayed tick defeats it, and it can drop a
            // genuine re-engage (which the client, streaming torque, would
            // not repeat).
            let (dropped, confirmed_empty) = self.flush_socket();
            self.rearm_blocked = !confirmed_empty;
            if self.started {
                self.started = false;
                eprintln!(
                    "[bridge] device connection changed — session disarmed \
                     ({dropped} queued frames dropped); re-engage in game to \
                     restore forces"
                );
            }
        }

        // A flush that could not prove the queue was empty leaves the door
        // open to pre-change frames: keep flushing (and refusing START)
        // until one does.
        if self.rearm_blocked {
            let (_, confirmed_empty) = self.flush_socket();
            if confirmed_empty {
                self.rearm_blocked = false;
            }
        }

        self.torque_frames_this_tick = 0;
        self.drain_socket(now);
        self.frames_coalesced += u64::from(self.torque_frames_this_tick.saturating_sub(1));
        self.run_safety(now);
        self.apply_output(now, dt_s);

        let st = self.device.poll(dt_s);

        if tick.state_due {
            if let Some(client) = self.client {
                let mut flags = protocol::FLAG_CONNECTED;
                if st.fault {
                    flags |= protocol::FLAG_FAULT;
                }
                if self.armed() {
                    flags |= protocol::FLAG_ARMED;
                }
                self.seq = self.seq.wrapping_add(1);
                let frame = Frame::State {
                    sequence: self.seq,
                    steering_deg: st.steering_deg,
                    steering_vel_deg_per_s: st.steering_vel_deg_per_s,
                    buttons: st.buttons,
                    flags,
                    device_id_hash: self.device.id_hash(),
                };
                self.send(&frame, client);
                self.states_out += 1;
            }
        }

        if self.verbose && now.duration_since(self.last_status) > Duration::from_secs(1) {
            self.last_status = now;
            eprintln!(
                "[bridge] client={:?} started={} panic={} torque_active={} angle={:.1} in={} out={} \
                 writes/s={} coalesced/s={} seqdrop/s={}",
                self.client,
                self.started,
                self.panic_latched,
                self.torque_active,
                st.steering_deg,
                self.frames_in,
                self.states_out,
                self.device_writes - self.last_device_writes,
                self.frames_coalesced - self.last_frames_coalesced,
                self.seq_gate_drops - self.last_seq_gate_drops,
            );
            self.last_device_writes = self.device_writes;
            self.last_frames_coalesced = self.frames_coalesced;
            self.last_seq_gate_drops = self.seq_gate_drops;
        }
    }

    /// Would the bridge currently ACCEPT a torque frame? Mirrored on the wire
    /// as STATE's FLAG_ARMED.
    fn armed(&self) -> bool {
        self.started && !self.panic_latched && !self.rearm_blocked
    }

    /// The one device write per tick: the latest accepted target, shaped by
    /// the START ramp and the slew limiter. Runs AFTER the drain and safety
    /// pass, so a STOP/PANIC/watchdog zero in the same tick wins (those paths
    /// write an instant zero directly and clear `torque_active`). Skipped
    /// entirely while no torque session is active — nothing here may ever
    /// re-start output on its own.
    fn apply_output(&mut self, now: Instant, dt_s: f64) {
        if !self.torque_active {
            return;
        }
        // Linear 0→1 ramp for START_RAMP after arming, then done.
        let ramp = match self.ramp_started {
            Some(t) => {
                let x = now.saturating_duration_since(t).as_secs_f32()
                    / START_RAMP.as_secs_f32();
                if x >= 1.0 {
                    self.ramp_started = None;
                    1.0
                } else {
                    x
                }
            }
            None => 1.0,
        };
        let desired = self.target_torque_nm * ramp;
        // Slew: per tick the output moves toward the target by at most
        // max_slew × dt. dt is already bounded by the caller (run() caps it
        // at 100 ms), and a spike-limited rise is the whole point here —
        // zeroing paths never come through this function.
        let max_step = self.max_slew_nm_per_s * (dt_s as f32);
        let step = (desired - self.output_torque_nm).clamp(-max_step, max_step);
        let next = self.output_torque_nm + step;
        if next != self.output_torque_nm || self.torque_frames_this_tick > 0 {
            self.device.set_torque_nm(next);
            self.output_torque_nm = next;
            self.device_writes += 1;
        }
    }

    fn drain_socket(&mut self, now: Instant) {
        let mut buf = [0u8; protocol::MAX_FRAME_BYTES];
        let mut budget = MAX_FRAMES_PER_TICK;
        while budget > 0 {
            budget -= 1;
            match self.socket.recv_from(&mut buf) {
                Ok((n, from)) => {
                    if let Some(frame) = protocol::decode(&buf[..n]) {
                        self.frames_in += 1;
                        self.on_frame(frame, from, now);
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => return,
                // Windows: ICMP port-unreachable from a previous send surfaces
                // as ConnReset on recv — the peer is just not up; ignore.
                Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => continue,
                Err(_) => return,
            }
        }
    }

    /// Discard everything queued on the socket without interpreting it.
    /// Returns (datagrams dropped, queue CONFIRMED empty). "Confirmed" means
    /// the loop ended because a receive would have blocked — the only proof
    /// that nothing from before the connection change is still waiting.
    /// Exhausting the attempt budget or hitting an unexpected error is NOT
    /// proof, and the caller must keep the session disarmed (review-caught:
    /// otherwise normal draining resumes immediately over residual frames).
    fn flush_socket(&mut self) -> (u32, bool) {
        let mut buf = [0u8; protocol::MAX_FRAME_BYTES];
        let mut dropped = 0;
        for _ in 0..MAX_FLUSH_ATTEMPTS {
            match self.socket.recv_from(&mut buf) {
                Ok(_) => dropped += 1,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => return (dropped, true),
                // Windows surfaces a previous send's ICMP port-unreachable
                // here; it says nothing about the queue, so keep draining
                // (bounded by the attempt count, not by successes).
                Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => continue,
                Err(_) => return (dropped, false),
            }
        }
        (dropped, false)
    }

    /// Wrap-aware "is `seq` newer than the session's latest" (RFC 1982 style).
    fn seq_is_fresh(&self, seq: u32) -> bool {
        match self.last_rx_seq {
            None => true,
            Some(last) => (seq.wrapping_sub(last) as i32) > 0,
        }
    }

    fn on_frame(&mut self, frame: Frame, from: SocketAddr, now: Instant) {
        // Client latch: a NEW client address displaces the old one and starts
        // from a safe state — never inherit an ongoing torque session (or its
        // sequence baseline).
        if self.client != Some(from) {
            if self.client.is_some() {
                self.zero_torque("client changed");
                self.started = false;
            }
            self.client = Some(from);
            self.last_rx_seq = None;
        }

        // Anti-replay: late or duplicated datagrams are dropped wholesale —
        // an old TORQUE must never outlive a newer PANIC/STOP, and a
        // duplicate must never re-arm the watchdog.
        let rx_seq = match frame {
            Frame::Torque { sequence, .. }
            | Frame::Panic { sequence }
            | Frame::Start { sequence }
            | Frame::Stop { sequence } => sequence,
            // Bridge→mod frame types arriving here are peer confusion; ignore.
            Frame::State { .. } | Frame::Hello { .. } => return,
        };
        if !self.seq_is_fresh(rx_seq) {
            self.seq_gate_drops += 1;
            return;
        }
        self.last_rx_seq = Some(rx_seq);
        // The silence timer refreshes ONLY on frames that pass the sequence
        // gate. Refreshing it before the gate wedged a restarted client that
        // reused the same UDP source port: its low-seq frames were all
        // dropped as stale, yet still kept the 10 s silence timeout from
        // ever firing — so the stale baseline never reset and the client
        // never recovered. Now the dropped frames leave the timer alone, the
        // timeout drops the dead session, and the new client latches fresh.
        self.last_client_frame = now;

        match frame {
            Frame::Start { .. } => {
                if self.rearm_blocked {
                    // Can't prove this START post-dates the connection
                    // change — refuse rather than guess. An instant zero is
                    // always safe; an unearned re-arm never is.
                    return;
                }
                let was_armed = self.armed();
                self.panic_latched = false;
                self.started = true;
                if !was_armed {
                    // Transition into armed: torque ramps 0→1 over 200 ms.
                    // A keepalive START while already armed does NOT restart
                    // the ramp (the mod probes with START periodically).
                    self.ramp_started = Some(now);
                }
                self.zero_torque("start");
                self.seq = self.seq.wrapping_add(1);
                let hello = Frame::Hello {
                    sequence: self.seq,
                    rated_torque_nm: self.device.rated_torque_nm(),
                    range_deg: self.range_deg,
                    device_name: self.device.name().to_string(),
                };
                self.send(&hello, from);
                eprintln!("[bridge] START from {from} → hello '{}'", self.device.name());
            }
            Frame::Stop { .. } => {
                self.started = false;
                self.zero_torque("stop");
            }
            Frame::Panic { .. } => {
                self.panic_latched = true;
                self.started = false;
                self.zero_torque("PANIC");
                eprintln!("[bridge] PANIC latched (until next START)");
            }
            Frame::Torque {
                torque_nm,
                max_torque_cap_nm,
                watchdog_ms,
                ..
            } => {
                if !self.started || self.panic_latched {
                    return;
                }
                // Fail closed on anything questionable: non-finite values or
                // a negative cap are a confused peer, not a request to guess.
                if !torque_nm.is_finite()
                    || !max_torque_cap_nm.is_finite()
                    || max_torque_cap_nm < 0.0
                {
                    return;
                }
                // Double clamp: the frame's own cap AND the bridge ceiling.
                let cap = max_torque_cap_nm.min(self.max_torque_nm);
                let mut nm = torque_nm.clamp(-cap, cap);
                if self.invert {
                    nm = -nm;
                }
                // No direct device write here: the drain only updates the
                // TARGET, and `apply_output` issues at most one device write
                // per tick (through ramp + slew) after the drain — N queued
                // frames coalesce into their final value.
                self.target_torque_nm = nm;
                self.torque_active = true;
                self.torque_frames_this_tick += 1;
                // Watchdog interval from the frame, sanity-bounded 10..=1000 ms.
                let ms = u64::from(watchdog_ms).clamp(10, 1000);
                self.watchdog_deadline = Some(now + Duration::from_millis(ms));
            }
            Frame::State { .. } | Frame::Hello { .. } => unreachable!(),
        }
    }

    fn run_safety(&mut self, now: Instant) {
        if self.torque_active {
            if let Some(deadline) = self.watchdog_deadline {
                if now >= deadline {
                    self.zero_torque("watchdog expired");
                }
            }
        }
        if self.client.is_some() && now.duration_since(self.last_client_frame) > CLIENT_SILENCE_TIMEOUT
        {
            self.zero_torque("client silent too long");
            self.client = None;
            self.started = false;
        }
    }

    /// Every zeroing path (panic, stop, watchdog expiry, epoch disarm, client
    /// change/timeout, START reset) comes through here: an IMMEDIATE device
    /// write of zero, bypassing both the ramp and the slew limiter — an
    /// instant zero is always safe, an instant spike never is.
    fn zero_torque(&mut self, why: &str) {
        if self.torque_active {
            eprintln!("[bridge] torque → 0 ({why})");
        }
        self.device.set_torque_nm(0.0);
        self.device_writes += 1;
        self.target_torque_nm = 0.0;
        self.output_torque_nm = 0.0;
        self.torque_active = false;
        self.watchdog_deadline = None;
    }

    fn send(&self, frame: &Frame, to: SocketAddr) {
        let mut buf = [0u8; protocol::MAX_FRAME_BYTES];
        let n = protocol::encode(frame, &mut buf);
        let _ = self.socket.send_to(&buf[..n], to);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device::SimDevice;
    use std::net::UdpSocket;

    fn bridge_pair() -> (Bridge<SimDevice>, UdpSocket, SocketAddr) {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let server_addr = server.local_addr().unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        client
            .set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        (
            Bridge::new(
                server,
                SimDevice::new(),
                DEFAULT_MAX_TORQUE_NM,
                DEFAULT_RANGE_DEG,
                DEFAULT_MAX_SLEW_NM_PER_S,
                false,
            ),
            client,
            server_addr,
        )
    }

    /// Fabricated-time helper: the START ramp (200 ms) and the slew limiter
    /// (2 Nm per 4 ms tick at the 500 Nm/s default) mean a torque target is
    /// reached a few ticks after the frame — tests tick at `start`, then step
    /// 4 ms at a time until the output settles. Returns the last tick time.
    fn tick_until_settled(bridge: &mut Bridge<SimDevice>, start: Instant, steps: u32) -> Instant {
        let mut now = start;
        tick(bridge, now, false);
        for _ in 0..steps {
            now += Duration::from_millis(4);
            tick(bridge, now, false);
        }
        now
    }

    fn send(client: &UdpSocket, to: SocketAddr, frame: Frame) {
        let mut buf = [0u8; protocol::MAX_FRAME_BYTES];
        let n = protocol::encode(&frame, &mut buf);
        client.send_to(&buf[..n], to).unwrap();
    }

    fn recv(client: &UdpSocket) -> Option<Frame> {
        let mut buf = [0u8; protocol::MAX_FRAME_BYTES];
        match client.recv_from(&mut buf) {
            Ok((n, _)) => protocol::decode(&buf[..n]),
            Err(_) => None,
        }
    }

    fn tick(bridge: &mut Bridge<SimDevice>, now: Instant, state_due: bool) {
        bridge.tick(now, 0.004, Tick { state_due });
    }

    #[test]
    fn start_gets_hello_then_state_streams() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let now = Instant::now();
        tick(&mut bridge, now, true);

        let hello = recv(&client);
        assert!(
            matches!(
                hello,
                Some(Frame::Hello { rated_torque_nm, range_deg, .. })
                    if rated_torque_nm == 9.0 && range_deg == DEFAULT_RANGE_DEG
            ),
            "expected HELLO with rated + range, got {hello:?}"
        );
        let state = recv(&client);
        assert!(
            matches!(state, Some(Frame::State { flags, .. }) if flags & protocol::FLAG_CONNECTED != 0),
            "expected STATE, got {state:?}"
        );
    }

    /// `--range` must reach the wire: HELLO carries the configured value.
    #[test]
    fn hello_reports_configured_range() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = server.local_addr().unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        client
            .set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        let mut bridge = Bridge::new(
            server,
            SimDevice::new(),
            DEFAULT_MAX_TORQUE_NM,
            900.0,
            DEFAULT_MAX_SLEW_NM_PER_S,
            false,
        );
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);
        let hello = recv(&client);
        assert!(
            matches!(hello, Some(Frame::Hello { range_deg, .. }) if range_deg == 900.0),
            "expected HELLO with range 900, got {hello:?}"
        );
    }

    #[test]
    fn torque_applies_with_double_clamp_and_watchdog_zeroes() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false); // ramp starts here

        // 100 Nm requested with a 50 Nm frame cap: bridge ceiling (5) must win.
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 100.0,
                max_torque_cap_nm: 50.0,
                watchdog_ms: 50,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        // Past the 200 ms ramp; the slew limiter still needs 3 ticks to 5 Nm.
        let now = tick_until_settled(&mut bridge, t0 + Duration::from_millis(300), 2);
        assert_eq!(bridge.device_mut().last_torque_nm(), 5.0);

        // Silence past the 50 ms watchdog (armed when the frame was drained
        // at t0+300ms): torque must zero.
        tick(&mut bridge, now + Duration::from_millis(60), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0);
    }

    #[test]
    fn panic_latches_until_start() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false);

        send(&client, addr, Frame::Panic { sequence: 2 });
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 3,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t0 + Duration::from_millis(20), false);
        assert_eq!(
            bridge.device_mut().last_torque_nm(),
            0.0,
            "torque after PANIC must be ignored"
        );

        // START clears the latch; torque flows again (after the fresh ramp).
        send(&client, addr, Frame::Start { sequence: 4 });
        std::thread::sleep(Duration::from_millis(20));
        let t1 = t0 + Duration::from_millis(40);
        tick(&mut bridge, t1, false);
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 5,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t1 + Duration::from_millis(300), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 1.0);
    }

    #[test]
    fn stale_and_duplicate_frames_are_dropped() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 10 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false);

        // PANIC at 200 latches...
        send(&client, addr, Frame::Panic { sequence: 200 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t0 + Duration::from_millis(20), false);

        // ...and a DELAYED old START (100) must not clear the latch,
        // nor may an old in-flight TORQUE (101) apply.
        send(&client, addr, Frame::Start { sequence: 100 });
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 101,
                torque_nm: 2.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t0 + Duration::from_millis(40), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0,
            "stale frames must not defeat PANIC");

        // A genuinely fresh START clears it and torque flows again.
        send(&client, addr, Frame::Start { sequence: 300 });
        std::thread::sleep(Duration::from_millis(20));
        let t1 = t0 + Duration::from_millis(60);
        tick(&mut bridge, t1, false);
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 301,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t1 + Duration::from_millis(300), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 1.0);
    }

    #[test]
    fn negative_wire_cap_fails_closed() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 2.0,
                max_torque_cap_nm: -2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0,
            "a negative cap is a confused peer — reject, don't abs()");
    }

    /// SimDevice with a test-controllable connection epoch.
    struct EpochDevice {
        inner: SimDevice,
        epoch: u32,
    }

    impl crate::device::FfbDevice for EpochDevice {
        fn name(&self) -> &str {
            "epoch test device"
        }
        fn rated_torque_nm(&self) -> f32 {
            9.0
        }
        fn id_hash(&self) -> u32 {
            1
        }
        fn poll(&mut self, dt_s: f64) -> crate::device::WheelState {
            self.inner.poll(dt_s)
        }
        fn set_torque_nm(&mut self, nm: f32) {
            self.inner.set_torque_nm(nm);
        }
        fn connection_epoch(&self) -> u32 {
            self.epoch
        }
    }

    #[test]
    fn device_connection_change_disarms_until_fresh_start() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = server.local_addr().unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        client
            .set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        let dev = EpochDevice { inner: SimDevice::new(), epoch: 0 };
        let mut bridge = Bridge::new(
            server,
            dev,
            DEFAULT_MAX_TORQUE_NM,
            DEFAULT_RANGE_DEG,
            DEFAULT_MAX_SLEW_NM_PER_S,
            false,
        );

        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        bridge.tick(t0, 0.004, Tick { state_due: false });
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        // Past the START ramp; 1.0 Nm is within one slew step.
        let t1 = t0 + Duration::from_millis(300);
        bridge.tick(t1, 0.004, Tick { state_due: false });
        assert_eq!(bridge.device_mut().inner.last_torque_nm(), 1.0);

        // The device connection changes (unplug/replug)...
        bridge.device_mut().epoch += 1;
        bridge.tick(t1 + Duration::from_millis(4), 0.004, Tick { state_due: false });
        assert_eq!(
            bridge.device_mut().inner.last_torque_nm(),
            0.0,
            "connection change must zero torque"
        );

        // ...and torque must NOT resume on its own, even with fresh frames.
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 3,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        bridge.tick(t1 + Duration::from_millis(8), 0.004, Tick { state_due: false });
        assert_eq!(
            bridge.device_mut().inner.last_torque_nm(),
            0.0,
            "torque must stay off until a deliberate re-START"
        );

        // A fresh START (a fresh client engage) restores the session.
        send(&client, addr, Frame::Start { sequence: 4 });
        std::thread::sleep(Duration::from_millis(20));
        let t2 = t1 + Duration::from_millis(12);
        bridge.tick(t2, 0.004, Tick { state_due: false });
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 5,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        bridge.tick(t2 + Duration::from_millis(300), 0.004, Tick { state_due: false });
        assert_eq!(bridge.device_mut().inner.last_torque_nm(), 1.0);
    }

    /// Frames sent while the device was away must not survive the disarm:
    /// they sit in the socket buffer and would otherwise be drained (and
    /// honoured) on the very tick that disarms.
    #[test]
    fn frames_queued_during_a_connection_change_cannot_rearm() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = server.local_addr().unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        client
            .set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        let dev = EpochDevice { inner: SimDevice::new(), epoch: 0 };
        let mut bridge = Bridge::new(
            server,
            dev,
            DEFAULT_MAX_TORQUE_NM,
            DEFAULT_RANGE_DEG,
            DEFAULT_MAX_SLEW_NM_PER_S,
            false,
        );

        // Engaged and driving before the wheel drops off the bus.
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        bridge.tick(t0, 0.004, Tick { state_due: false });

        // The connection changes, and — before the bridge next ticks — a
        // START and a TORQUE arrive (the operator was mid-re-engage, or the
        // client never noticed). Both are queued in the socket.
        bridge.device_mut().epoch += 1;
        send(&client, addr, Frame::Start { sequence: 2 });
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 3,
                torque_nm: 2.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        bridge.tick(t0 + Duration::from_millis(20), 0.004, Tick { state_due: false });
        assert_eq!(
            bridge.device_mut().inner.last_torque_nm(),
            0.0,
            "queued pre-reattach frames must not re-arm the session"
        );

        // Still disarmed on the following tick, too.
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 4,
                torque_nm: 2.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        bridge.tick(t0 + Duration::from_millis(40), 0.004, Tick { state_due: false });
        assert_eq!(bridge.device_mut().inner.last_torque_nm(), 0.0);

        // A START sent AFTER the change (so, read after the flush) is a
        // genuine fresh engage and re-arms.
        send(&client, addr, Frame::Start { sequence: 5 });
        std::thread::sleep(Duration::from_millis(20));
        let t1 = t0 + Duration::from_millis(60);
        bridge.tick(t1, 0.004, Tick { state_due: false });
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 6,
                torque_nm: 2.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        bridge.tick(t1 + Duration::from_millis(300), 0.004, Tick { state_due: false });
        assert_eq!(bridge.device_mut().inner.last_torque_nm(), 2.0);
    }

    #[test]
    fn invert_flag_flips_output_sign_inside_the_clamps() {
        let (mut bridge, client, addr) = bridge_pair();
        bridge.set_invert(true);
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false);
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t0 + Duration::from_millis(300), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), -1.0);
    }

    #[test]
    fn torque_before_start_is_ignored_and_stop_zeroes() {
        let (mut bridge, client, addr) = bridge_pair();
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 1,
                torque_nm: 2.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0);

        send(&client, addr, Frame::Start { sequence: 2 });
        std::thread::sleep(Duration::from_millis(20));
        let t1 = t0 + Duration::from_millis(20);
        tick(&mut bridge, t1, false);
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 3,
                torque_nm: 2.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t1 + Duration::from_millis(300), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 2.0);

        send(&client, addr, Frame::Stop { sequence: 4 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t1 + Duration::from_millis(320), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0);
    }

    /// W1 regression: a client that restarts and reuses the same UDP source
    /// port sends from a LOW sequence again. Those frames must be dropped as
    /// stale (they cannot be told from replays of the dead session) — but
    /// they must NOT keep refreshing the silence timer, or the session never
    /// times out and the restarted client is wedged forever. The timer only
    /// refreshes on frames that PASS the sequence gate, so the 10 s timeout
    /// fires, the dead session drops, and the new client latches fresh.
    #[test]
    fn restarted_client_on_same_port_recovers_after_silence_timeout() {
        let (mut bridge, client, addr) = bridge_pair();

        // Old session, engaged at high sequence numbers.
        send(&client, addr, Frame::Start { sequence: 5000 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false);
        assert!(matches!(recv(&client), Some(Frame::Hello { .. })));
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 5001,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        let t1 = t0 + Duration::from_millis(300);
        tick(&mut bridge, t1, false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 1.0);

        // The client process restarts on the same port: sequences start over.
        // Its frames are dropped by the sequence gate...
        send(&client, addr, Frame::Start { sequence: 1 });
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 2.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t1 + Duration::from_secs(3), false);
        assert_eq!(bridge.seq_gate_drops, 2, "restart frames must be gated");
        assert!(recv(&client).is_none(), "no HELLO for a gated START");
        assert_eq!(
            bridge.device_mut().last_torque_nm(),
            0.0,
            "watchdog must have zeroed the dead session's torque"
        );

        // ...and keep being dropped while the client retries...
        send(&client, addr, Frame::Start { sequence: 3 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t1 + Duration::from_secs(6), false);
        assert_eq!(bridge.seq_gate_drops, 3);
        assert!(bridge.client.is_some(), "dead session still latched");

        // ...but they no longer refresh the silence timer, so 10 s after the
        // last ACCEPTED frame the dead session is dropped...
        tick(&mut bridge, t1 + Duration::from_millis(10_500), false);
        assert!(bridge.client.is_none(), "silence timeout must fire");
        assert!(!bridge.started);

        // ...and the restarted client latches from sequence 1 like any new one.
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t2 = t1 + Duration::from_millis(11_000);
        tick(&mut bridge, t2, false);
        assert!(
            matches!(recv(&client), Some(Frame::Hello { .. })),
            "fresh session must get its HELLO"
        );
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t2 + Duration::from_millis(300), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 1.0);
    }

    /// The slew limiter shapes the RISE (at most max_slew × dt per tick) but
    /// zeroing paths bypass it: PANIC is an instant zero.
    #[test]
    fn slew_limits_rise_but_zeroing_is_instant() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false);

        // Target 5 Nm (bridge ceiling), past the ramp. 500 Nm/s × 4 ms = 2 Nm
        // per tick: the output must step 2 → 4 → 5, never jump.
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 100.0,
                max_torque_cap_nm: 50.0,
                watchdog_ms: 1000,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        let t1 = t0 + Duration::from_millis(300);
        tick(&mut bridge, t1, false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 2.0);
        tick(&mut bridge, t1 + Duration::from_millis(4), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 4.0);
        tick(&mut bridge, t1 + Duration::from_millis(8), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 5.0);

        // PANIC: instant zero, no slew-down.
        send(&client, addr, Frame::Panic { sequence: 3 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t1 + Duration::from_millis(12), false);
        assert_eq!(
            bridge.device_mut().last_torque_nm(),
            0.0,
            "zeroing must bypass the slew limiter"
        );
    }

    /// After a transition into armed, output scales linearly 0→1 over 200 ms.
    /// A keepalive START while already armed must NOT restart the ramp.
    #[test]
    fn start_ramp_scales_output_for_200ms() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        tick(&mut bridge, t0, false); // armed here — ramp runs t0..t0+200ms

        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 2,
                torque_nm: 4.0,
                max_torque_cap_nm: 5.0,
                watchdog_ms: 1000,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        // 25% through the ramp: 4 Nm target → 1 Nm (within one slew step).
        tick(&mut bridge, t0 + Duration::from_millis(50), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 1.0);
        // 50%: 2 Nm.
        tick(&mut bridge, t0 + Duration::from_millis(100), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 2.0);
        // Ramp done: full 4 Nm (one 2 Nm slew step from 2).
        tick(&mut bridge, t0 + Duration::from_millis(250), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 4.0);

        // Keepalive START while armed: zeroes (START semantics) but does NOT
        // restart the ramp — the next torque climbs at slew rate, unscaled.
        send(&client, addr, Frame::Start { sequence: 3 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t0 + Duration::from_millis(254), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0);
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 4,
                torque_nm: 4.0,
                max_torque_cap_nm: 5.0,
                watchdog_ms: 1000,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, t0 + Duration::from_millis(258), false);
        assert_eq!(
            bridge.device_mut().last_torque_nm(),
            2.0,
            "one slew step, NOT rescaled by a restarted ramp"
        );
    }

    /// SimDevice wrapper that counts set_torque_nm calls.
    struct CountingDevice {
        inner: SimDevice,
        writes: u32,
    }

    impl crate::device::FfbDevice for CountingDevice {
        fn name(&self) -> &str {
            "counting test device"
        }
        fn rated_torque_nm(&self) -> f32 {
            9.0
        }
        fn id_hash(&self) -> u32 {
            2
        }
        fn poll(&mut self, dt_s: f64) -> crate::device::WheelState {
            self.inner.poll(dt_s)
        }
        fn set_torque_nm(&mut self, nm: f32) {
            self.writes += 1;
            self.inner.set_torque_nm(nm);
        }
    }

    /// Coalescing: N TORQUE frames drained in one tick all run through the
    /// state machine, but the device sees exactly ONE write — the final value.
    #[test]
    fn queued_torque_frames_coalesce_to_one_device_write() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = server.local_addr().unwrap();
        let client = UdpSocket::bind("127.0.0.1:0").unwrap();
        client
            .set_read_timeout(Some(Duration::from_millis(200)))
            .unwrap();
        let dev = CountingDevice { inner: SimDevice::new(), writes: 0 };
        let mut bridge = Bridge::new(
            server,
            dev,
            DEFAULT_MAX_TORQUE_NM,
            DEFAULT_RANGE_DEG,
            DEFAULT_MAX_SLEW_NM_PER_S,
            false,
        );

        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let t0 = Instant::now();
        bridge.tick(t0, 0.004, Tick { state_due: false });

        // Four frames queue up (e.g. after a scheduling stall)...
        let baseline = bridge.device_mut().writes;
        for (seq, nm) in [(2u32, 0.5f32), (3, 0.8), (4, 1.2), (5, 1.5)] {
            send(
                &client,
                addr,
                Frame::Torque {
                    sequence: seq,
                    torque_nm: nm,
                    max_torque_cap_nm: 2.5,
                    watchdog_ms: 1000,
                },
            );
        }
        std::thread::sleep(Duration::from_millis(20));
        bridge.tick(t0 + Duration::from_millis(300), 0.004, Tick { state_due: false });

        // ...one device write, carrying the final frame's value.
        assert_eq!(
            bridge.device_mut().writes - baseline,
            1,
            "N drained frames must coalesce into one device write"
        );
        assert_eq!(bridge.device_mut().inner.last_torque_nm(), 1.5);
        assert_eq!(bridge.frames_coalesced, 3);
    }

    /// FLAG_ARMED mirrors "a torque frame would be accepted right now".
    #[test]
    fn state_reports_armed_flag() {
        let (mut bridge, client, addr) = bridge_pair();

        // Latched but not started: CONNECTED without ARMED.
        send(
            &client,
            addr,
            Frame::Torque {
                sequence: 1,
                torque_nm: 1.0,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
        );
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), true);
        let state = recv(&client);
        assert!(
            matches!(state, Some(Frame::State { flags, .. })
                if flags & protocol::FLAG_CONNECTED != 0 && flags & protocol::FLAG_ARMED == 0),
            "not armed before START, got {state:?}"
        );

        // Started: ARMED set.
        send(&client, addr, Frame::Start { sequence: 2 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), true);
        assert!(matches!(recv(&client), Some(Frame::Hello { .. })));
        let state = recv(&client);
        assert!(
            matches!(state, Some(Frame::State { flags, .. })
                if flags & protocol::FLAG_ARMED != 0),
            "armed after START, got {state:?}"
        );

        // Panicked: ARMED cleared until the next START.
        send(&client, addr, Frame::Panic { sequence: 3 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), true);
        let state = recv(&client);
        assert!(
            matches!(state, Some(Frame::State { flags, .. })
                if flags & protocol::FLAG_ARMED == 0),
            "not armed while panic-latched, got {state:?}"
        );
    }
}
