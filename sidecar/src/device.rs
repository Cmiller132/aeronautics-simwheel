//! The wheelbase behind the bridge: real DirectInput hardware or the
//! simulated wheel used for protocol conformance testing without hardware.

/// One snapshot of the physical wheel, for STATE frames.
#[derive(Debug, Clone, Copy, Default)]
pub struct WheelState {
    pub steering_deg: f32,
    pub steering_vel_deg_per_s: f32,
    pub buttons: u32,
    pub fault: bool,
}

/// MOZA Racing's USB vendor id — shared by both platform backends (evdev
/// reads it from EVIOCGID, DirectInput from DIPROP_VIDPID).
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub const MOZA_VID: u16 = 0x346e;
/// Simagic's own vendor id (EVO-era bases, 2025+). Older Simagic bases
/// enumerate under STMicroelectronics 0x0483 with the shared pid 0x0522.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub const SIMAGIC_VID: u16 = 0x3670;
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub const SIMAGIC_LEGACY_VID: u16 = 0x0483;
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub const SIMAGIC_LEGACY_PID: u16 = 0x0522;

/// USB id → rated torque, the preferred resolution path on both platforms
/// (names are localizable and driver-dependent; ids are not). Never guess
/// when ids are shared across ratings:
/// - MOZA R16 and R21 share product ids (0x0000 first-gen, 0x0010
///   second-gen). Second-generation ids per the kernel's hid-ids.h.
/// - Simagic Alpha EVO bases (0x3670:0x0500..0x0502) — public sources
///   CONFLICT on which pid is which model (PCGamingWiki: Sport+EVO=0x0500,
///   Pro=0x0501, Ultra=0x0502; linux-steering-wheels: EVO=0x0501,
///   Pro=0x0502), and Sport (9 Nm) vs EVO (12 Nm) share a pid and a device
///   name either way. All fall through to `--rated-torque`.
/// - Legacy Simagic (0x0483:0x0522) is shared by M10/Alpha Mini/Alpha/
///   Alpha Ultimate — same story.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub fn rated_by_usb_id(vendor: u16, product: u16) -> Option<f32> {
    if vendor != MOZA_VID {
        return None;
    }
    match product {
        0x0002 | 0x0012 => Some(9.0),  // R9 (gen 1 / gen 2)
        0x0004 | 0x0014 => Some(5.5),  // R5
        0x0005 | 0x0015 => Some(3.9),  // R3
        0x0006 | 0x0016 => Some(12.0), // R12
        _ => None,
    }
}

/// True when the USB id says "a Simagic wheelbase" — recognized, but the
/// rating still must come from `--rated-torque` (ids/names are shared
/// across ratings; see `rated_by_usb_id`). Backends use this to produce
/// the tailored error below instead of the generic one.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub fn is_simagic_usb_id(vendor: u16, product: u16) -> bool {
    vendor == SIMAGIC_VID || (vendor == SIMAGIC_LEGACY_VID && product == SIMAGIC_LEGACY_PID)
}

/// The exact fix for a recognized-but-ambiguous Simagic base.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
fn simagic_rated_torque_error(name: &str) -> String {
    format!(
        "Simagic wheelbase '{name}': Simagic bases share USB ids and device names \
         across torque ratings (Alpha EVO Sport 9 / EVO 12 / EVO Pro 18 / EVO \
         Ultra 28 Nm; legacy Alpha family likewise), so the rating cannot be \
         auto-detected. Pass --rated-torque <Nm> for YOUR base (e.g. \
         --rated-torque 9 for an Alpha EVO Sport). Also set SimPro Manager's \
         force-feedback gain to 100% — it rescales all game torque, and any \
         other value breaks the Nm calibration this flag establishes."
    )
}

/// Rated torque resolution shared by the platform backends: explicit flag
/// wins; known MOZA R-series names carry vendor numbers; anything else must
/// be told (a wrong rating rescales every Nm cap in the whole chain).
/// Backends prefer `rated_by_usb_id` and use this as the name fallback.
/// (cfg: only the real-device backends call this — sim-only platforms like
/// macOS otherwise flag it dead; tests still cover it everywhere.)
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub fn resolve_rated_nm(name: &str, explicit: Option<f32>) -> Result<f32, String> {
    if let Some(nm) = explicit {
        return Ok(nm);
    }
    let n = name.to_ascii_lowercase();
    for (needle, nm) in [
        ("r3 ", 3.9f32), ("r5 ", 5.5), ("r9 ", 9.0), ("r12 ", 12.0),
        ("r16 ", 16.0), ("r21 ", 21.0),
    ] {
        if n.contains("moza") && (n.contains(needle) || n.ends_with(needle.trim_end())) {
            return Ok(nm);
        }
    }
    if n.contains("simagic") || n.contains("alpha evo") {
        return Err(simagic_rated_torque_error(name));
    }
    Err(format!(
        "unknown wheelbase '{name}': pass --rated-torque <Nm> (the base's rated \
         maximum) so torque caps scale correctly"
    ))
}

/// Name fallback with USB-id context: same as `resolve_rated_nm`, but a
/// Simagic USB id upgrades the generic unknown-wheelbase error to the
/// tailored Simagic one even when the localized device name says nothing.
#[cfg_attr(not(any(windows, target_os = "linux")), allow(dead_code))]
pub fn resolve_rated_nm_with_usb_id(
    name: &str,
    vendor: u16,
    product: u16,
    explicit: Option<f32>,
) -> Result<f32, String> {
    if explicit.is_none() && is_simagic_usb_id(vendor, product) {
        return Err(simagic_rated_torque_error(name));
    }
    resolve_rated_nm(name, explicit)
}

pub trait FfbDevice {
    /// Human-readable identity for HELLO / logs.
    fn name(&self) -> &str;
    /// Rated (maximum continuous) torque for HELLO — normalizes the mod side.
    fn rated_torque_nm(&self) -> f32;
    /// Stable identity hash for STATE frames.
    fn id_hash(&self) -> u32;
    /// Called every bridge tick: advance/poll and return the current state.
    fn poll(&mut self, dt_s: f64) -> WheelState;
    /// Apply a torque command (Nm, already capped upstream). The device layer
    /// clamps once more against its own rated torque — defense in depth.
    fn set_torque_nm(&mut self, nm: f32);
    /// Monotonic counter that advances whenever the physical connection is
    /// lost (unplug, acquisition loss). The bridge watches it and DISARMS the
    /// session on any change — forces may only resume after the client sends
    /// a fresh START (i.e., a deliberate re-engage), never automatically on
    /// re-attach while someone may be handling the wheel.
    fn connection_epoch(&self) -> u32 {
        0
    }
}

/// `--sim`: a spring–damper–inertia wheel driven by the commanded torque, so a
/// conformance harness can close the whole loop with physics-plausible motion:
/// positive torque must swing the reported steering angle positive, silence
/// (watchdog) must let the centering spring bring it back.
pub struct SimDevice {
    angle_deg: f64,
    vel_deg_per_s: f64,
    torque_nm: f64,
    rated_nm: f32,
}

impl SimDevice {
    /// Rim inertia, centering spring, and damping tuned critically damped
    /// (ζ ≈ 1, ωn ≈ 2.7 rad/s) so conformance assertions see monotonic, fast
    /// motion: sustained 2.25 Nm settles ≈15°, recentering is ~92% done in
    /// 1.5 s — comfortable margins for wall-clock test windows.
    const INERTIA: f64 = 0.02;
    const SPRING_NM_PER_DEG: f64 = 0.15;
    const DAMPER_NM_PER_DEG_S: f64 = 0.11;

    pub fn new() -> Self {
        SimDevice {
            angle_deg: 0.0,
            vel_deg_per_s: 0.0,
            torque_nm: 0.0,
            rated_nm: 9.0,
        }
    }

    #[cfg_attr(not(test), allow(dead_code))]
    pub fn last_torque_nm(&self) -> f64 {
        self.torque_nm
    }
}

impl FfbDevice for SimDevice {
    fn name(&self) -> &str {
        "Simulated wheel (no hardware)"
    }

    fn rated_torque_nm(&self) -> f32 {
        self.rated_nm
    }

    fn id_hash(&self) -> u32 {
        0x51D3_CA12
    }

    fn poll(&mut self, dt_s: f64) -> WheelState {
        // τ_net = command − spring − damper; integrate semi-implicitly.
        let net = self.torque_nm
            - self.angle_deg * Self::SPRING_NM_PER_DEG
            - self.vel_deg_per_s * Self::DAMPER_NM_PER_DEG_S;
        // deg/s² — the inertia is "per degree" here on purpose: this is a test
        // double tuned for legible motion, not a physical rim model.
        let accel = net / Self::INERTIA;
        self.vel_deg_per_s += accel * dt_s;
        self.angle_deg += self.vel_deg_per_s * dt_s;
        self.angle_deg = self.angle_deg.clamp(-540.0, 540.0);

        WheelState {
            steering_deg: self.angle_deg as f32,
            steering_vel_deg_per_s: self.vel_deg_per_s as f32,
            buttons: 0,
            fault: false,
        }
    }

    fn set_torque_nm(&mut self, nm: f32) {
        let cap = self.rated_nm as f64;
        let v = nm as f64;
        // Finiteness first: clamp(±Inf) would silently saturate to the cap.
        self.torque_nm = if v.is_finite() { v.clamp(-cap, cap) } else { 0.0 };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn positive_torque_swings_positive_and_spring_recenters() {
        let mut sim = SimDevice::new();
        sim.set_torque_nm(2.0);
        for _ in 0..200 {
            sim.poll(0.004); // 250 Hz for 800 ms (past the critical-damping rise)
        }
        let deflected = sim.poll(0.004).steering_deg;
        assert!(deflected > 5.0, "torque must deflect the wheel, got {deflected}");

        sim.set_torque_nm(0.0);
        for _ in 0..2000 {
            sim.poll(0.004);
        }
        let recentred = sim.poll(0.004).steering_deg;
        assert!(
            recentred.abs() < deflected.abs() / 2.0,
            "spring must pull back toward center: {deflected} → {recentred}"
        );
    }

    #[test]
    fn non_finite_torque_is_rejected() {
        let mut sim = SimDevice::new();
        sim.set_torque_nm(f32::NAN);
        assert_eq!(sim.last_torque_nm(), 0.0);
        sim.set_torque_nm(f32::INFINITY);
        assert_eq!(sim.last_torque_nm(), 0.0);
    }

    #[test]
    fn rated_torque_resolution() {
        assert_eq!(resolve_rated_nm("anything", Some(7.5)), Ok(7.5));
        assert_eq!(resolve_rated_nm("MOZA R9 Base", None), Ok(9.0));
        assert_eq!(resolve_rated_nm("Gudsen MOZA R16", None), Ok(16.0));
        assert!(resolve_rated_nm("Some Unknown Wheel", None).is_err());
        // "moza" alone must not match — the model number carries the rating.
        assert!(resolve_rated_nm("MOZA mystery base", None).is_err());
    }

    /// Simagic bases are RECOGNIZED but never rated automatically: the EVO
    /// family shares pids and one device name across 9/12/18/28 Nm models
    /// (and public pid tables conflict), and the legacy 0x0483:0x0522 pid is
    /// shared by four bases. The error must hand the operator the exact fix.
    #[test]
    fn simagic_is_recognized_but_never_guessed() {
        assert!(is_simagic_usb_id(SIMAGIC_VID, 0x0500)); // Alpha EVO Sport (and EVO?)
        assert!(is_simagic_usb_id(SIMAGIC_VID, 0x0501));
        assert!(is_simagic_usb_id(SIMAGIC_VID, 0x0502));
        assert!(is_simagic_usb_id(SIMAGIC_LEGACY_VID, SIMAGIC_LEGACY_PID));
        assert!(!is_simagic_usb_id(SIMAGIC_LEGACY_VID, 0x5740)); // other STM device
        assert_eq!(rated_by_usb_id(SIMAGIC_VID, 0x0500), None);

        let by_name = resolve_rated_nm("SIMAGIC Alpha EVO Wheelbase", None).unwrap_err();
        assert!(by_name.contains("--rated-torque") && by_name.contains("SimPro"),
                "tailored error must name the fix: {by_name}");

        // A localized/blank name still gets the tailored error via the usb id.
        let by_id = resolve_rated_nm_with_usb_id("??", SIMAGIC_VID, 0x0500, None).unwrap_err();
        assert!(by_id.contains("--rated-torque") && by_id.contains("Simagic"));
        // Explicit rating always wins, id or not.
        assert_eq!(resolve_rated_nm_with_usb_id("??", SIMAGIC_VID, 0x0500, Some(9.0)), Ok(9.0));
        // Non-Simagic ids keep the generic path.
        assert!(resolve_rated_nm_with_usb_id("??", 0x046d, 0xc262, None).is_err());
        assert_eq!(resolve_rated_nm_with_usb_id("MOZA R5", 0, 0, None), Ok(5.5));
    }

    /// The shared VID/PID table (both backends resolve through this — evdev
    /// via EVIOCGID, DirectInput via DIPROP_VIDPID).
    #[test]
    fn moza_usb_id_table() {
        // Gen 1
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0002), Some(9.0));
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0004), Some(5.5));
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0005), Some(3.9));
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0006), Some(12.0));
        // Gen 2 (kernel hid-ids.h)
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0012), Some(9.0));
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0014), Some(5.5));
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0015), Some(3.9));
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0016), Some(12.0));
        // R16 and R21 share ids in BOTH generations — must NOT be guessed.
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0000), None);
        assert_eq!(rated_by_usb_id(MOZA_VID, 0x0010), None);
        // Foreign vendor never matches, even on a known product id.
        assert_eq!(rated_by_usb_id(0x046d, 0x0002), None);
    }
}
