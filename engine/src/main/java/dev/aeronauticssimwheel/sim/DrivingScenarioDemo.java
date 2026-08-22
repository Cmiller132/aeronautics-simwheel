package dev.aeronauticssimwheel.sim;

import dev.aeronauticssimwheel.ffb.FfbPipeline;
import dev.aeronauticssimwheel.ffb.StrikeDetector;
import dev.aeronauticssimwheel.ffb.TelemetryFrame;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless end-to-end FFB run (DESIGN.md §10.5): the race car drives a scripted
 * 26 s lap over block terrain; hinge torque is sampled at the physics substep
 * rate (60 Hz), batched into 20 Hz "packets" like the real wire, strikes fire
 * through the immediate event path, and everything plays through the REAL
 * shipping composition — {@link FfbPipeline}: TelemetryBuffer + soft lock +
 * damper/friction + EventImpulses → soft-knee mixer → SafetyChain — at the
 * 250 Hz loop rate the mod runs.
 *
 * <p>Steering is direct authority (the sim wheel block, DESIGN.md §5.3): the
 * hardware command IS the column angle — no slew chase, no predictor.
 *
 * <p>Phases: A smooth-road weave · B bumpy blocks + gravel · C curb strike ·
 * D steady corner · E telemetry dropout · F recovery weave · G into the soft
 * lock (the wheel physically pushed past the block's ±450° range).
 *
 * <p>Usage: DrivingScenarioDemo [output.csv]
 */
public final class DrivingScenarioDemo {

    public static final double SPEED_MS = 15.0;
    public static final double SUBSTEP_DT = 1.0 / 60.0;
    /** The shipping FFB loop rate (FfbService.LOOP_HZ). */
    public static final double CLIENT_DT = 1.0 / 250.0;
    public static final double TICK_DT = 0.05;
    public static final double TOTAL_S = 26.0;
    public static final double DROPOUT_FROM_S = 17.0;
    public static final double DROPOUT_TO_S = 18.0;
    public static final double LOCK_DEG = 450.0;
    /** Frame context: asphalt-like grip, wheel RPM from a 0.5 m tire at 15 m/s. */
    public static final double MU = 0.9;
    public static final double WHEEL_RPM = SPEED_MS / Math.PI * 60.0;

    public record TraceRow(double t, double steerCmdDeg, double rawNm,
                           double telemetryNm, double impulseNm, double outNm) {
    }

    public record Result(java.util.List<TraceRow> rows,
                         double curbRawPeakT, double curbOutPeakT, int strikesFired) {
    }

    /** Scripted driver: what the hardware wheel is doing, in column degrees. */
    public static double steerCommandDeg(double t) {
        if (t < 5.0) {
            return 20.0 * Math.sin(2.0 * Math.PI * 0.4 * t);          // A: gentle weave
        } else if (t < 12.0) {
            return 8.0 * Math.sin(2.0 * Math.PI * 0.3 * t);           // B/C: mostly straight
        } else if (t < 17.0) {
            return Math.min(90.0, (t - 12.0) * 90.0);                 // D: wind into a corner
        } else if (t < 19.0) {
            return 90.0;                                              // E: hold through dropout
        } else if (t < 24.0) {
            return 25.0 * Math.sin(2.0 * Math.PI * 0.4 * (t - 19.0)); // F: recovery weave
        }
        return Math.min(LOCK_DEG + 40.0, (t - 24.0) * 400.0);         // G: shove into the stop
    }

    public static Result run() {
        long seed = 20260813L;
        int lengthM = (int) (SPEED_MS * TOTAL_S) + 10;
        // Bumpy block section 75–150 m (t = 5–10 s); gravel texture on top;
        // curb on the RIGHT lane only at 157 m (t ≈ 10.5 s)
        TerrainProfile leftLane = TerrainProfile.builder(lengthM, seed)
                .bumpyBlocks(75, 150, 0.30f, 0.25f)
                .rough(75, 150, 0.03f)
                .build();
        TerrainProfile rightLane = TerrainProfile.builder(lengthM, seed + 1)
                .bumpyBlocks(75, 150, 0.30f, 0.25f)
                .rough(75, 150, 0.03f)
                .curb(157, 2, 0.5f)
                .build();

        RaceCarFfbSim car = new RaceCarFfbSim(leftLane, rightLane);
        FfbPipeline pipeline = new FfbPipeline();
        StrikeDetector strikes = new StrikeDetector(StrikeDetector.Config.defaults());

        // Server state
        record Pending(double t, TelemetryFrame frame) {
        }
        double serverT = 0.0;
        double latestRawNm = 0.0;
        java.util.List<Pending> pendingSubsteps = new java.util.ArrayList<>();
        double nextTickT = TICK_DT;
        int strikesFired = 0;

        // Client state
        double prevSteerDeg = 0.0;

        java.util.List<TraceRow> rows = new java.util.ArrayList<>();
        double curbRawPeak = 0.0, curbRawPeakT = Double.NaN;

        for (double t = 0.0; t < TOTAL_S; t += CLIENT_DT) {
            double steerCmd = steerCommandDeg(t);
            boolean dropped = t >= DROPOUT_FROM_S && t < DROPOUT_TO_S;

            // --- SERVER (runs ahead of the client loop, substep rate) ---
            while (serverT <= t) {
                // Direct authority: the command IS the column angle (§5.3)
                RaceCarFfbSim.SubstepTorques torques =
                        car.substep(SUBSTEP_DT, SPEED_MS, steerCmd);
                latestRawNm = torques.totalNm();
                pendingSubsteps.add(new Pending(serverT, new TelemetryFrame(
                        (float) torques.satNm(), (float) torques.textureNm(),
                        (float) SPEED_MS, (float) car.slipProxy(steerCmd),
                        (float) MU, (float) WHEEL_RPM)));

                // Immediate event path: the fastest-compressing steered corner,
                // signed by its side (left = −1, right = +1; curb is right-lane)
                double compL = car.leftCompressionRateMS();
                double compR = car.rightCompressionRateMS();
                double compression = Math.max(compL, compR);
                double side = compR >= compL ? 1 : -1;
                StrikeDetector.Strike strike = strikes.step(compression, side, SUBSTEP_DT);
                if (strike != null && !dropped) {
                    pipeline.postEvent(strike.peakNm(), strike.tauSeconds());
                    strikesFired++;
                }
                serverT += SUBSTEP_DT;
            }
            // Tick flush: deliver the batched substeps as one packet (unless dropped)
            if (t >= nextTickT) {
                if (!dropped && !pendingSubsteps.isEmpty()) {
                    for (Pending s : pendingSubsteps) {
                        pipeline.postTelemetry(s.t(), s.frame());
                    }
                    pipeline.noteTelemetryBatch(
                            pendingSubsteps.get(pendingSubsteps.size() - 1).t(), t);
                }
                pendingSubsteps.clear();
                nextTickT += TICK_DT;
            }

            // --- CLIENT (250 Hz FFB loop, the real shipping composition) ---
            double steerVel = (steerCmd - prevSteerDeg) / CLIENT_DT;
            prevSteerDeg = steerCmd;

            float outNm = pipeline.step(true, t, steerCmd, steerVel, LOCK_DEG, true, CLIENT_DT);
            FfbPipeline.Components c = pipeline.lastComponents();

            if (t >= 10.0 && t <= 12.0) { // curb window
                if (Math.abs(latestRawNm) > curbRawPeak) {
                    curbRawPeak = Math.abs(latestRawNm);
                    curbRawPeakT = t;
                }
            }

            rows.add(new TraceRow(t, steerCmd, latestRawNm,
                    c.telemetryNm(), c.impulseNm(), outNm));
        }

        // The rim response to THE CURB specifically: peak |out| in a tight
        // window around the raw peak — a global window max would catch the
        // bumpy section's tail instead, now that transients render sharply.
        double curbOutPeak = 0.0, curbOutPeakT = Double.NaN;
        for (TraceRow row : rows) {
            if (row.t() >= curbRawPeakT - 0.05 && row.t() <= curbRawPeakT + 0.30
                    && Math.abs(row.outNm()) > curbOutPeak) {
                curbOutPeak = Math.abs(row.outNm());
                curbOutPeakT = row.t();
            }
        }
        return new Result(rows, curbRawPeakT, curbOutPeakT, strikesFired);
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/driving-sim.csv");
        Files.createDirectories(out.toAbsolutePath().getParent());

        Result r = run();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("t,steerCmdDeg,rawNm,telemetryNm,impulseNm,outNm");
            for (TraceRow row : r.rows()) {
                w.printf("%.3f,%.2f,%.4f,%.4f,%.4f,%.4f%n",
                        row.t(), row.steerCmdDeg(), row.rawNm(),
                        row.telemetryNm(), row.impulseNm(), row.outNm());
            }
        }

        System.out.println("=== Driving scenario: race car, 15 m/s, 26 s ===");
        printPhase(r, "A smooth weave      ", 0.5, 5.0);
        printPhase(r, "B bumpy blocks      ", 5.0, 10.0);
        printPhase(r, "C curb strike       ", 10.0, 12.0);
        printPhase(r, "D steady corner     ", 13.0, 17.0);
        printPhase(r, "E telemetry dropout ", 17.2, 18.0);
        printPhase(r, "F recovery          ", 19.0, 24.0);
        printPhase(r, "G into the stop     ", 25.0, 26.0);
        System.out.printf("Curb strike: raw peak at t=%.3f s, rim peak at t=%.3f s -> latency %.0f ms%n",
                r.curbRawPeakT(), r.curbOutPeakT(),
                (r.curbOutPeakT() - r.curbRawPeakT()) * 1000);
        System.out.printf("Strike events fired: %d%n", r.strikesFired());
        double maxOut = r.rows().stream().mapToDouble(x -> Math.abs(x.outNm())).max().orElse(0);
        System.out.printf("Max |rim torque| = %.3f Nm (clamp 2.5)%n", maxOut);
        System.out.println("CSV: " + out.toAbsolutePath());
    }

    private static void printPhase(Result r, String name, double from, double to) {
        double rawRms = 0, outRms = 0, rawPeak = 0, outPeak = 0;
        int n = 0;
        for (TraceRow row : r.rows()) {
            if (row.t() < from || row.t() >= to) {
                continue;
            }
            rawRms += row.rawNm() * row.rawNm();
            outRms += row.outNm() * row.outNm();
            rawPeak = Math.max(rawPeak, Math.abs(row.rawNm()));
            outPeak = Math.max(outPeak, Math.abs(row.outNm()));
            n++;
        }
        rawRms = Math.sqrt(rawRms / Math.max(1, n));
        outRms = Math.sqrt(outRms / Math.max(1, n));
        System.out.printf("%s raw RMS %6.3f  peak %6.3f  |  rim RMS %6.3f  peak %6.3f Nm%n",
                name, rawRms, rawPeak, outRms, outPeak);
    }
}
