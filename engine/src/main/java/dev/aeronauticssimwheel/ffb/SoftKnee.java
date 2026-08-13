package dev.aeronauticssimwheel.ffb;

/**
 * Soft-knee compressor (DESIGN.md §6.5): identity below the knee, compressed by
 * {@code ratio} above it. Continuous, monotonic, symmetric. Keeps heavily loaded
 * craft proportional near the limit instead of hitting a wall; the safety chain's
 * hard clamp stays downstream.
 */
public final class SoftKnee {

    private SoftKnee() {
    }

    public static float apply(float x, float kneeNm, float ratio) {
        float a = Math.abs(x);
        if (a <= kneeNm) {
            return x;
        }
        return Math.copySign(kneeNm + (a - kneeNm) / ratio, x);
    }
}
