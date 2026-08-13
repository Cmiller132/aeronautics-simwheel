package dev.aeronauticssimwheel.ffb;

/**
 * Dead-reckons the in-game kinetic wheel (DESIGN.md §6.5): we know the commanded
 * target (we sent it) and the fixed 16 RPM slew (~96°/s), so the sync-spring can
 * run against a zero-latency estimate. Telemetry corrects with a small gain and
 * snaps on large disagreement (e.g. after a kinetic network change).
 */
public final class VirtualWheelPredictor {

    /** 16 RPM fixed generator speed (Simulated S3) = 96°/s. */
    public static final double SLEW_DEG_PER_S = 96.0;
    public static final double SNAP_THRESHOLD_DEG = 5.0;
    public static final double CORRECTION_GAIN = 0.2;

    private double angleDeg;
    private double targetDeg;

    /** The angle we just commanded via SteeringWheelPacket. */
    public void setCommandedTarget(double deg) {
        targetDeg = deg;
    }

    /** Advance the model by dt: slew toward the target, never overshoot. */
    public void step(double dtSeconds) {
        double d = targetDeg - angleDeg;
        double maxStep = SLEW_DEG_PER_S * dtSeconds;
        angleDeg += Math.abs(d) <= maxStep ? d : Math.copySign(maxStep, d);
    }

    /** Correct against the authoritative angle (telemetry, or the BE's synced angle in degraded mode). */
    public void onMeasurement(double measuredDeg) {
        double error = measuredDeg - angleDeg;
        if (Math.abs(error) > SNAP_THRESHOLD_DEG) {
            angleDeg = measuredDeg;
        } else {
            angleDeg += CORRECTION_GAIN * error;
        }
    }

    public double angleDeg() {
        return angleDeg;
    }
}
