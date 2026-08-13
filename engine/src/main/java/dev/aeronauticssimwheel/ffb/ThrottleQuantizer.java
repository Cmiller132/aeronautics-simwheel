package dev.aeronauticssimwheel.ffb;

/**
 * Pedal → 0–15 throttle signal with hysteresis (DESIGN.md §5.4): the value only
 * changes when the analog input crosses a step boundary by more than 0.35 step,
 * so the quantized signal never chatters at boundaries. Send-on-change is the
 * caller's job.
 */
public final class ThrottleQuantizer {

    public static final int STEPS = 15; // signal range 0..15
    public static final float HYSTERESIS = 0.35f;

    private int current;
    private boolean primed;

    /** @param analog01 pedal position 0..1 */
    public int update(float analog01) {
        float pos = clamp01(analog01) * STEPS;
        if (!primed) {
            current = Math.round(pos);
            primed = true;
            return current;
        }
        while (current < STEPS && pos > current + 0.5f + HYSTERESIS) {
            current++;
        }
        while (current > 0 && pos < current - 0.5f - HYSTERESIS) {
            current--;
        }
        return current;
    }

    public int current() {
        return current;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
