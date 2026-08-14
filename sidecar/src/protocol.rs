//! AWFB wire protocol v1 — the Rust mirror of the mod's `BridgeProtocol.java`
//! (engine/src/main/java/dev/aeronauticssimwheel/hal/bridge/BridgeProtocol.java).
//! UDP, localhost, little-endian. Header: magic "AWFB", u8 version, u8 frame
//! type, u32 sequence. Decoding is total: malformed input yields `None`, never
//! a panic — a buggy/hostile peer must not take the bridge down (the bridge is
//! the last thing standing between a 9 Nm motor and a human).

pub const MAGIC: u32 = u32::from_le_bytes(*b"AWFB");
pub const VERSION: u8 = 1;
pub const DEFAULT_PORT: u16 = 46910;
pub const MAX_FRAME_BYTES: usize = 64;

pub const TYPE_TORQUE: u8 = 1;
pub const TYPE_PANIC: u8 = 2;
pub const TYPE_START: u8 = 3;
pub const TYPE_STOP: u8 = 4;
pub const TYPE_STATE: u8 = 16;
pub const TYPE_HELLO: u8 = 17;

pub const FLAG_CONNECTED: u8 = 1;
pub const FLAG_FAULT: u8 = 2;
#[allow(dead_code)] // reserved in protocol v1, unused until hands-off detection
pub const FLAG_HANDS_OFF: u8 = 4;

const HEADER_BYTES: usize = 4 + 1 + 1 + 4;

#[derive(Debug, Clone, PartialEq)]
pub enum Frame {
    /// Mod → bridge, at the device write rate.
    Torque {
        sequence: u32,
        torque_nm: f32,
        max_torque_cap_nm: f32,
        watchdog_ms: u16,
    },
    /// Mod → bridge: immediate zero + stop, latched until START.
    Panic { sequence: u32 },
    Start { sequence: u32 },
    Stop { sequence: u32 },
    /// Bridge → mod, ~250 Hz.
    State {
        sequence: u32,
        steering_deg: f32,
        steering_vel_deg_per_s: f32,
        buttons: u32,
        flags: u8,
        device_id_hash: u32,
    },
    /// Bridge → mod on connect/START.
    Hello {
        sequence: u32,
        rated_torque_nm: f32,
        device_name: String,
    },
}

pub fn encode(frame: &Frame, out: &mut [u8; MAX_FRAME_BYTES]) -> usize {
    let mut w = Writer { buf: out, at: 0 };
    w.u32(MAGIC);
    w.u8(VERSION);
    w.u8(type_of(frame));
    match *frame {
        Frame::Torque {
            sequence,
            torque_nm,
            max_torque_cap_nm,
            watchdog_ms,
        } => {
            w.u32(sequence);
            w.f32(torque_nm);
            w.f32(max_torque_cap_nm);
            w.u16(watchdog_ms);
        }
        Frame::Panic { sequence } | Frame::Start { sequence } | Frame::Stop { sequence } => {
            w.u32(sequence);
        }
        Frame::State {
            sequence,
            steering_deg,
            steering_vel_deg_per_s,
            buttons,
            flags,
            device_id_hash,
        } => {
            w.u32(sequence);
            w.f32(steering_deg);
            w.f32(steering_vel_deg_per_s);
            w.u32(buttons);
            w.u8(flags);
            w.u32(device_id_hash);
        }
        Frame::Hello {
            sequence,
            rated_torque_nm,
            ref device_name,
        } => {
            w.u32(sequence);
            w.f32(rated_torque_nm);
            let name = device_name.as_bytes();
            let len = name.len().min(32);
            w.u8(len as u8);
            w.bytes(&name[..len]);
        }
    }
    w.at
}

/// `None` on anything malformed — never panics on peer data. Mirrors the Java
/// decoder's acceptance domain exactly: a valid frame prefix with trailing
/// bytes decodes (real reads cap at MAX_FRAME_BYTES anyway).
pub fn decode(buf: &[u8]) -> Option<Frame> {
    if buf.len() < HEADER_BYTES {
        return None;
    }
    let mut r = Reader { buf, at: 0 };
    if r.u32()? != MAGIC || r.u8()? != VERSION {
        return None;
    }
    let ty = r.u8()?;
    let sequence = r.u32()?;
    match ty {
        TYPE_TORQUE => Some(Frame::Torque {
            sequence,
            torque_nm: r.f32()?,
            max_torque_cap_nm: r.f32()?,
            watchdog_ms: r.u16()?,
        }),
        TYPE_PANIC => Some(Frame::Panic { sequence }),
        TYPE_START => Some(Frame::Start { sequence }),
        TYPE_STOP => Some(Frame::Stop { sequence }),
        TYPE_STATE => Some(Frame::State {
            sequence,
            steering_deg: r.f32()?,
            steering_vel_deg_per_s: r.f32()?,
            buttons: r.u32()?,
            flags: r.u8()?,
            device_id_hash: r.u32()?,
        }),
        TYPE_HELLO => {
            let rated_torque_nm = r.f32()?;
            let len = r.u8()? as usize;
            if len > 32 {
                return None;
            }
            let name = r.take(len)?;
            Some(Frame::Hello {
                sequence,
                rated_torque_nm,
                device_name: String::from_utf8_lossy(name).into_owned(),
            })
        }
        _ => None,
    }
}

fn type_of(frame: &Frame) -> u8 {
    match frame {
        Frame::Torque { .. } => TYPE_TORQUE,
        Frame::Panic { .. } => TYPE_PANIC,
        Frame::Start { .. } => TYPE_START,
        Frame::Stop { .. } => TYPE_STOP,
        Frame::State { .. } => TYPE_STATE,
        Frame::Hello { .. } => TYPE_HELLO,
    }
}

struct Writer<'a> {
    buf: &'a mut [u8; MAX_FRAME_BYTES],
    at: usize,
}

impl Writer<'_> {
    fn u8(&mut self, v: u8) {
        self.buf[self.at] = v;
        self.at += 1;
    }
    fn u16(&mut self, v: u16) {
        self.buf[self.at..self.at + 2].copy_from_slice(&v.to_le_bytes());
        self.at += 2;
    }
    fn u32(&mut self, v: u32) {
        self.buf[self.at..self.at + 4].copy_from_slice(&v.to_le_bytes());
        self.at += 4;
    }
    fn f32(&mut self, v: f32) {
        self.buf[self.at..self.at + 4].copy_from_slice(&v.to_le_bytes());
        self.at += 4;
    }
    fn bytes(&mut self, v: &[u8]) {
        self.buf[self.at..self.at + v.len()].copy_from_slice(v);
        self.at += v.len();
    }
}

struct Reader<'a> {
    buf: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let s = self.buf.get(self.at..self.at + n)?;
        self.at += n;
        Some(s)
    }
    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }
    fn u16(&mut self) -> Option<u16> {
        Some(u16::from_le_bytes(self.take(2)?.try_into().ok()?))
    }
    fn u32(&mut self) -> Option<u32> {
        Some(u32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }
    fn f32(&mut self) -> Option<f32> {
        Some(f32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn round_trip(f: Frame) {
        let mut buf = [0u8; MAX_FRAME_BYTES];
        let n = encode(&f, &mut buf);
        assert_eq!(decode(&buf[..n]), Some(f));
    }

    #[test]
    fn every_frame_type_round_trips() {
        round_trip(Frame::Torque {
            sequence: 7,
            torque_nm: 1.5,
            max_torque_cap_nm: 2.5,
            watchdog_ms: 100,
        });
        round_trip(Frame::Panic { sequence: 1 });
        round_trip(Frame::Start { sequence: 2 });
        round_trip(Frame::Stop { sequence: 3 });
        round_trip(Frame::State {
            sequence: 900,
            steering_deg: -123.4,
            steering_vel_deg_per_s: 55.5,
            buttons: 0b1010,
            flags: FLAG_CONNECTED | FLAG_FAULT,
            device_id_hash: 0xDEADBEEF,
        });
        round_trip(Frame::Hello {
            sequence: 4,
            rated_torque_nm: 9.0,
            device_name: "MOZA R9".into(),
        });
    }

    /// Golden bytes hand-derived from BridgeProtocol.java's layout — the
    /// cross-language contract. If either side drifts, this fails before the
    /// live conformance harness ever runs.
    #[test]
    fn golden_torque_frame_matches_java_layout() {
        let mut buf = [0u8; MAX_FRAME_BYTES];
        let n = encode(
            &Frame::Torque {
                sequence: 7,
                torque_nm: 1.5,
                max_torque_cap_nm: 2.5,
                watchdog_ms: 100,
            },
            &mut buf,
        );
        let expected: &[u8] = &[
            0x41, 0x57, 0x46, 0x42, // "AWFB"
            0x01, // version
            0x01, // TYPE_TORQUE
            0x07, 0x00, 0x00, 0x00, // sequence 7
            0x00, 0x00, 0xC0, 0x3F, // 1.5f
            0x00, 0x00, 0x20, 0x40, // 2.5f
            0x64, 0x00, // watchdog 100
        ];
        assert_eq!(&buf[..n], expected);
    }

    #[test]
    fn golden_state_frame_matches_java_layout() {
        let mut buf = [0u8; MAX_FRAME_BYTES];
        let n = encode(
            &Frame::State {
                sequence: 1,
                steering_deg: 90.0,
                steering_vel_deg_per_s: 0.0,
                buttons: 3,
                flags: FLAG_CONNECTED,
                device_id_hash: 42,
            },
            &mut buf,
        );
        let expected: &[u8] = &[
            0x41, 0x57, 0x46, 0x42, 0x01, 0x10, // header, TYPE_STATE=16
            0x01, 0x00, 0x00, 0x00, // seq
            0x00, 0x00, 0xB4, 0x42, // 90.0f
            0x00, 0x00, 0x00, 0x00, // 0.0f
            0x03, 0x00, 0x00, 0x00, // buttons
            0x01, // flags
            0x2A, 0x00, 0x00, 0x00, // deviceIdHash 42
        ];
        assert_eq!(&buf[..n], expected);
    }

    #[test]
    fn malformed_input_never_panics() {
        assert_eq!(decode(&[]), None);
        assert_eq!(decode(b"AWFB"), None);
        assert_eq!(decode(&[0xFF; MAX_FRAME_BYTES]), None);
        assert_eq!(decode(&[0u8; 128][..]), None); // bad magic, any size
        // Java parity: a valid prefix with trailing bytes decodes.
        let mut b = [0u8; MAX_FRAME_BYTES];
        let n = encode(&Frame::Start { sequence: 9 }, &mut b);
        let mut padded = vec![0u8; n + 20];
        padded[..n].copy_from_slice(&b[..n]);
        assert_eq!(decode(&padded), Some(Frame::Start { sequence: 9 }));
        // Truncated real frames at every length
        let mut buf = [0u8; MAX_FRAME_BYTES];
        let n = encode(
            &Frame::Hello {
                sequence: 4,
                rated_torque_nm: 9.0,
                device_name: "MOZA R9".into(),
            },
            &mut buf,
        );
        for cut in 0..n {
            let _ = decode(&buf[..cut]); // must simply not panic
        }
        // Hello with a lying length byte
        let mut lying = buf;
        lying[14] = 60; // len > 32
        assert_eq!(decode(&lying[..n]), None);
        // Wrong version
        let mut wrong_ver = buf;
        wrong_ver[4] = 2;
        assert_eq!(decode(&wrong_ver[..n]), None);
    }
}
