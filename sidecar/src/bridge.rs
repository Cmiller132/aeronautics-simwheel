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
//! - Watchdog expiry, STOP, PANIC, or a client change → torque zeroes
//!   immediately (an instant zero is always safe; an instant spike never is).
//!   PANIC latches until the next START.

use crate::device::FfbDevice;
use crate::protocol::{self, Frame};
use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

pub const STATE_HZ: f64 = 250.0;
/// Bridge-side hard ceiling, independent of anything the mod sends.
pub const DEFAULT_MAX_TORQUE_NM: f32 = 5.0;
/// If a client goes completely silent this long, treat it as gone: zero
/// torque and stop streaming to it (a fresh client re-latches instantly).
pub const CLIENT_SILENCE_TIMEOUT: Duration = Duration::from_secs(10);
/// Max datagrams processed per tick — a loopback flood must never starve
/// `run_safety` (adversarial finding: unbounded drain before the watchdog).
pub const MAX_FRAMES_PER_TICK: u32 = 64;

pub struct Bridge<D: FfbDevice> {
    socket: UdpSocket,
    device: D,
    max_torque_nm: f32,
    verbose: bool,

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
    seq: u32,

    // once-per-second status line state
    last_status: Instant,
    frames_in: u64,
    states_out: u64,
}

pub struct Tick {
    pub state_due: bool,
}

impl<D: FfbDevice> Bridge<D> {
    pub fn new(socket: UdpSocket, device: D, max_torque_nm: f32, verbose: bool) -> Self {
        socket
            .set_nonblocking(true)
            .expect("nonblocking UDP socket");
        Bridge {
            socket,
            device,
            max_torque_nm,
            verbose,
            client: None,
            last_client_frame: Instant::now(),
            last_rx_seq: None,
            started: false,
            panic_latched: false,
            watchdog_deadline: None,
            torque_active: false,
            seq: 0,
            last_status: Instant::now(),
            frames_in: 0,
            states_out: 0,
        }
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn device_mut(&mut self) -> &mut D {
        &mut self.device
    }

    /// One bridge tick: drain the socket, run safety, advance the device, and
    /// (if `tick.state_due`) emit STATE. Call at ≥ STATE_HZ.
    pub fn tick(&mut self, now: Instant, dt_s: f64, tick: Tick) {
        self.drain_socket(now);
        self.run_safety(now);

        let st = self.device.poll(dt_s);

        if tick.state_due {
            if let Some(client) = self.client {
                let mut flags = protocol::FLAG_CONNECTED;
                if st.fault {
                    flags |= protocol::FLAG_FAULT;
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
                "[bridge] client={:?} started={} panic={} torque_active={} angle={:.1} in={} out={}",
                self.client,
                self.started,
                self.panic_latched,
                self.torque_active,
                st.steering_deg,
                self.frames_in,
                self.states_out
            );
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
        self.last_client_frame = now;

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
            return;
        }
        self.last_rx_seq = Some(rx_seq);

        match frame {
            Frame::Start { .. } => {
                self.panic_latched = false;
                self.started = true;
                self.zero_torque("start");
                self.seq = self.seq.wrapping_add(1);
                let hello = Frame::Hello {
                    sequence: self.seq,
                    rated_torque_nm: self.device.rated_torque_nm(),
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
                let nm = torque_nm.clamp(-cap, cap);
                self.device.set_torque_nm(nm);
                self.torque_active = true;
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

    fn zero_torque(&mut self, why: &str) {
        if self.torque_active {
            eprintln!("[bridge] torque → 0 ({why})");
        }
        self.device.set_torque_nm(0.0);
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
            Bridge::new(server, SimDevice::new(), DEFAULT_MAX_TORQUE_NM, false),
            client,
            server_addr,
        )
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
            matches!(hello, Some(Frame::Hello { rated_torque_nm, .. }) if rated_torque_nm == 9.0),
            "expected HELLO, got {hello:?}"
        );
        let state = recv(&client);
        assert!(
            matches!(state, Some(Frame::State { flags, .. }) if flags & protocol::FLAG_CONNECTED != 0),
            "expected STATE, got {state:?}"
        );
    }

    #[test]
    fn torque_applies_with_double_clamp_and_watchdog_zeroes() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let mut now = Instant::now();
        tick(&mut bridge, now, false);

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
        now = Instant::now();
        tick(&mut bridge, now, false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 5.0);

        // Silence past the 50 ms watchdog: torque must zero.
        tick(&mut bridge, now + Duration::from_millis(60), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0);
    }

    #[test]
    fn panic_latches_until_start() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 1 });
        std::thread::sleep(Duration::from_millis(20));
        let now = Instant::now();
        tick(&mut bridge, now, false);

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
        tick(&mut bridge, Instant::now(), false);
        assert_eq!(
            bridge.device_mut().last_torque_nm(),
            0.0,
            "torque after PANIC must be ignored"
        );

        // START clears the latch; torque flows again.
        send(&client, addr, Frame::Start { sequence: 4 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);
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
        tick(&mut bridge, Instant::now(), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 1.0);
    }

    #[test]
    fn stale_and_duplicate_frames_are_dropped() {
        let (mut bridge, client, addr) = bridge_pair();
        send(&client, addr, Frame::Start { sequence: 10 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);

        // PANIC at 200 latches...
        send(&client, addr, Frame::Panic { sequence: 200 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);

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
        tick(&mut bridge, Instant::now(), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0,
            "stale frames must not defeat PANIC");

        // A genuinely fresh START clears it and torque flows again.
        send(&client, addr, Frame::Start { sequence: 300 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);
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
        tick(&mut bridge, Instant::now(), false);
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
        tick(&mut bridge, Instant::now(), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0);

        send(&client, addr, Frame::Start { sequence: 2 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);
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
        tick(&mut bridge, Instant::now(), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 2.0);

        send(&client, addr, Frame::Stop { sequence: 4 });
        std::thread::sleep(Duration::from_millis(20));
        tick(&mut bridge, Instant::now(), false);
        assert_eq!(bridge.device_mut().last_torque_nm(), 0.0);
    }
}
