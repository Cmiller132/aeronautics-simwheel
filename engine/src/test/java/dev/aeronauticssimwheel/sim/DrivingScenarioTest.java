package dev.aeronauticssimwheel.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression on the SHIPPING composition (terrain → substeps →
 * packets + events → FfbPipeline → rim). Locks in the §6.8 claims against the
 * same class the mod runs.
 */
class DrivingScenarioTest {

    private static final DrivingScenarioDemo.Result RESULT = DrivingScenarioDemo.run();

    @Test
    void safetyClampHoldsThroughTheWholeLap() {
        for (DrivingScenarioDemo.TraceRow r : RESULT.rows()) {
            assertTrue(Math.abs(r.outNm()) <= 2.5f + 1e-3,
                    "clamp violated at t=" + r.t() + ": " + r.outNm());
        }
    }

    @Test
    void curbStrikeReachesTheRimQuickly() {
        double latency = RESULT.curbOutPeakT() - RESULT.curbRawPeakT();
        // The event path can render a strike within one 4 ms client step of the
        // raw peak — a small negative measurement just means the rim peak came
        // from an impulse fired one substep before the raw-torque maximum.
        assertTrue(latency > -0.020, "rim response should track the raw event: " + latency);
        assertTrue(latency < 0.250,
                "telemetry-path transient too slow: " + latency * 1000 + " ms");
    }

    @Test
    void telemetryDropoutFadesToZeroNeverHolds() {
        List<DrivingScenarioDemo.TraceRow> rows = RESULT.rows();
        // Deep inside the dropout (started 17.0), reconstructed telemetry must be silent
        double minAbs = rows.stream()
                .filter(r -> r.t() > 17.6 && r.t() < 18.0)
                .mapToDouble(r -> Math.abs(r.telemetryNm()))
                .max().orElse(Double.NaN);
        assertEquals(0.0, minAbs, 1e-4, "stale telemetry must fade to zero");
    }

    @Test
    void corneringLoadsTheWheelMoreThanStraightline() {
        double corner = meanAbs(16.0, 17.0);
        double straight = meanAbs(8.0, 9.5);
        assertTrue(corner > straight,
                "steady cornering (" + corner + ") must load harder than straights (" + straight + ")");
    }

    @Test
    void bumpySectionIsRougherThanSmoothRoad() {
        // Compare high-frequency content (mean |Δout| between consecutive rows)
        double bumpy = meanDelta(6.0, 9.5);
        double smooth = meanDelta(1.0, 4.5);
        assertTrue(bumpy > smooth * 1.5,
                "block bumps (" + bumpy + ") must add texture over smooth road (" + smooth + ")");
    }

    @Test
    void softLockIsAWallBeyondTheConfiguredRange() {
        // Phase G: the wheel is physically at lock+40°; the stop must resist
        // (negative torque against the positive overshoot) at the full clamp.
        double meanOut = RESULT.rows().stream()
                .filter(r -> r.t() >= 25.0 && r.t() < 26.0)
                .mapToDouble(DrivingScenarioDemo.TraceRow::outNm)
                .average().orElse(0);
        assertTrue(meanOut < -2.0,
                "soft lock must saturate the clamp against the overshoot: " + meanOut);
    }

    @Test
    void strikesRenderThroughTheEventPath() {
        assertTrue(RESULT.strikesFired() > 0, "the curb must fire at least one strike event");
        double maxImpulse = RESULT.rows().stream()
                .filter(r -> r.t() >= 10.0 && r.t() <= 12.0)
                .mapToDouble(r -> Math.abs(r.impulseNm()))
                .max().orElse(0);
        assertTrue(maxImpulse > 0.3,
                "curb strikes must render as event impulses at the rim: " + maxImpulse);
    }

    private static double meanAbs(double from, double to) {
        return RESULT.rows().stream()
                .filter(r -> r.t() >= from && r.t() < to)
                .mapToDouble(r -> Math.abs(r.outNm()))
                .average().orElse(0);
    }

    private static double meanDelta(double from, double to) {
        List<DrivingScenarioDemo.TraceRow> rows = RESULT.rows().stream()
                .filter(r -> r.t() >= from && r.t() < to).toList();
        double sum = 0;
        for (int i = 1; i < rows.size(); i++) {
            sum += Math.abs(rows.get(i).outNm() - rows.get(i - 1).outNm());
        }
        return sum / Math.max(1, rows.size() - 1);
    }
}
