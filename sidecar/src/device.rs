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
}
