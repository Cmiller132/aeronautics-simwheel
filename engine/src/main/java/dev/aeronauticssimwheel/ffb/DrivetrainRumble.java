package dev.aeronauticssimwheel.ffb;

/**
 * Client-side drivetrain vibration synthesizer (DESIGN.md §6.5, feel pass):
 * a subtle periodic hum keyed to the telemetry frame's wheel-mount kinetic
 * speed (Create shaft RPM). Honest input, synthesized rendering — the game
 * has no engine vibration to sample, but the drivetrain's actual RPM is real
 * state and the hands expect machinery to feel like machinery.
 *
 * <p>Four pulses per revolution (a shaft "beat" rather than a slow throb);
 * amplitude scales in with RPM and is bounded. FFB-thread only.
 */
public final class DrivetrainRumble {

    /** Pulses per shaft revolution. */
    public static final double PULSES_PER_REV = 4.0;
    /** RPM at which the rumble reaches full amplitude (Create max is 256). */
    public static final double RPM_REF = 128.0;
    /** Rendered frequency ceiling (Hz) — stays well under the 250 Hz loop's Nyquist. */
    public static final double MAX_HZ = 30.0;

    private double phase;

    /**
     * One FFB step.
     *
     * @param rpm   fastest wheel-mount kinetic speed, |RPM|
     * @param ampNm peak amplitude at {@link #RPM_REF} (0 disables)
     * @return rumble torque in Nm, bounded to ±ampNm
     */
    public float step(double dtSeconds, double rpm, float ampNm) {
        if (ampNm <= 0 || rpm < 1.0) {
            return 0f;
        }
        double hz = Math.min(MAX_HZ, rpm / 60.0 * PULSES_PER_REV);
        phase += 2.0 * Math.PI * hz * dtSeconds;
        if (phase > 2.0 * Math.PI) {
            phase -= 2.0 * Math.PI;
        }
        double amp = ampNm * Math.min(1.0, rpm / RPM_REF);
        return (float) (Math.sin(phase) * amp);
    }

    /** Rig teardown. */
    public void reset() {
        phase = 0;
    }
}
