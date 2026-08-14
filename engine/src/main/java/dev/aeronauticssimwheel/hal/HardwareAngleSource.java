package dev.aeronauticssimwheel.hal;

/**
 * A device that reports the physical wheel's angle at its own native rate,
 * independent of the game-tick input snapshot. The FFB loop prefers this over
 * the 20 Hz commanded angle so damper/friction/soft-lock react at loop rate —
 * and so the soft lock can see a wheel physically pushed past the configured
 * range (the commanded angle is clamped to ±lock by the axis processor and can
 * never get there).
 */
public interface HardwareAngleSource {

    /** True while the reported angle is live (e.g. bridge STATE within the staleness window). */
    boolean hardwareAngleValid();

    /** Physical wheel angle, ± degrees from center. */
    float hardwareDeg();

    /** Physical wheel angular velocity, deg/s. */
    float hardwareVelDegPerS();
}
