package dev.aeronauticssimwheel.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression on the full pipeline (terrain → substeps → packets →
 * buffer → mixer → safety chain → device). Locks in the §6.8 claims.
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
        assertTrue(latency > 0, "rim response must follow the raw event");
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
