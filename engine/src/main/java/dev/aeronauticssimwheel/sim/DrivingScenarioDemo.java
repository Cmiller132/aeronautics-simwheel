package dev.aeronauticssimwheel.sim;

import dev.aeronauticssimwheel.ffb.FeelEffects;
import dev.aeronauticssimwheel.ffb.Mixer;
import dev.aeronauticssimwheel.ffb.SafetyChain;
import dev.aeronauticssimwheel.ffb.SyncSpring;
import dev.aeronauticssimwheel.ffb.TelemetryBuffer;
import dev.aeronauticssimwheel.ffb.VirtualWheelPredictor;
import dev.aeronauticssimwheel.hal.NullWheelDevice;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Headless end-to-end FFB run (DESIGN.md §10.5): the race car drives a scripted
 * 24 s lap over block terrain; hinge torque is sampled at the physics substep
 * rate (60 Hz), batched into 20 Hz "packets" like the real wire, and pushed
 * through the REAL client pipeline — TelemetryBuffer → VirtualWheelPredictor →
 * SyncSpring → Mixer → SafetyChain → device — at 1 kHz with 150 Hz device writes.
 *
 * <p>Phases: A smooth-road weave · B bumpy blocks + gravel · C curb strike ·
 * D steady corner · E telemetry dropout · F recovery weave.
 *
 * <p>Usage: DrivingScenarioDemo [output.csv]
 */
public final class DrivingScenarioDemo {

    public static final double SPEED_MS = 15.0;
    public static final double SUBSTEP_DT = 1.0 / 60.0;
    public static final double CLIENT_DT = 0.001;
    public static final double TICK_DT = 0.05;
    public static final double DEVICE_WRITE_DT = 1.0 / 150.0;
    public static final double TOTAL_S = 24.0;
    public static final double DROPOUT_FROM_S = 17.0;
    public static final double DROPOUT_TO_S = 18.0;

    public record TraceRow(double t, double steerCmdDeg, double virtualDeg,
                           double rawNm, double telemetryNm, double springNm,
                           double outNm) {
    }

    public record Result(java.util.List<TraceRow> rows, NullWheelDevice device,
                         double curbRawPeakT, double curbOutPeakT) {
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
        }
        return 25.0 * Math.sin(2.0 * Math.PI * 0.4 * (t - 19.0));     // F: recovery weave
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
        TelemetryBuffer buffer = new TelemetryBuffer();
        VirtualWheelPredictor predictor = new VirtualWheelPredictor();
        SyncSpring spring = new SyncSpring(SyncSpring.Config.defaults());
        Mixer mixer = new Mixer(2.5f);
        SafetyChain chain = new SafetyChain(SafetyChain.Config.defaults());
        NullWheelDevice device = NullWheelDevice.ffbCapable();

        device.ffbStart();
        chain.engage();

        // Server state
        double serverT = 0.0;
        double latestRawNm = 0.0;
        java.util.List<double[]> pendingSubsteps = new java.util.ArrayList<>();
        // The kinetic wheel the server actually has (96°/s slew, like the BE)
        VirtualWheelPredictor serverWheel = new VirtualWheelPredictor();
        double nextTickT = TICK_DT;

        // Client state
        double prevSteerDeg = 0.0;
        double nextDeviceWriteT = 0.0;
        double lastWrittenNm = 0.0;

        java.util.List<TraceRow> rows = new java.util.ArrayList<>();
        double curbRawPeak = 0.0, curbRawPeakT = Double.NaN;
        double curbOutPeak = 0.0, curbOutPeakT = Double.NaN;

        double q = 0.5 * 1.2 * SPEED_MS * SPEED_MS; // dynamic pressure at 15 m/s

        for (double t = 0.0; t < TOTAL_S; t += CLIENT_DT) {
            double steerCmd = steerCommandDeg(t);

            // --- SERVER (runs ahead of the client loop, substep rate) ---
            while (serverT <= t) {
                serverWheel.setCommandedTarget(steerCmd);
                serverWheel.step(SUBSTEP_DT);
                latestRawNm = car.substep(SUBSTEP_DT, SPEED_MS, serverWheel.angleDeg());
                pendingSubsteps.add(new double[]{serverT, latestRawNm});
                serverT += SUBSTEP_DT;
            }
            // Tick flush: deliver the batched substeps as one packet (unless dropped)
            if (t >= nextTickT) {
                boolean dropped = t >= DROPOUT_FROM_S && t < DROPOUT_TO_S;
                if (!dropped) {
                    for (double[] s : pendingSubsteps) {
                        buffer.addSample(s[0], (float) s[1]);
                    }
                    predictor.onMeasurement(serverWheel.angleDeg());
                }
                pendingSubsteps.clear();
                nextTickT += TICK_DT;
            }

            // --- CLIENT (1 kHz FFB loop, real pipeline code) ---
            predictor.setCommandedTarget(steerCmd);
            predictor.step(CLIENT_DT);

            double steerVel = (steerCmd - prevSteerDeg) / CLIENT_DT;
            prevSteerDeg = steerCmd;

            float telemetryNm = buffer.sample(t);
            float springNm = spring.torqueNm(steerCmd, steerVel, predictor.angleDeg(), q);
            float damperNm = FeelEffects.damper(0.004f, q, 500.0, steerVel);
            float frictionNm = FeelEffects.friction(0.15f, 5.0, steerVel);
            float mixNm = mixer.mix(telemetryNm, springNm, damperNm, frictionNm, 0f);
            float outNm = chain.step(mixNm, CLIENT_DT, true);

            if (t >= nextDeviceWriteT) {
                device.ffbUpdateTorque(outNm / 9f); // R9: 9 Nm device max
                lastWrittenNm = outNm;
                nextDeviceWriteT += DEVICE_WRITE_DT;
            }

            if (t >= 10.0 && t <= 12.0) { // curb window
                if (Math.abs(latestRawNm) > curbRawPeak) {
                    curbRawPeak = Math.abs(latestRawNm);
                    curbRawPeakT = t;
                }
                if (Math.abs(lastWrittenNm) > curbOutPeak) {
                    curbOutPeak = Math.abs(lastWrittenNm);
                    curbOutPeakT = t;
                }
            }

            if (Math.round(t * 1000) % 2 == 0) { // 500 Hz trace is plenty
                rows.add(new TraceRow(t, steerCmd, predictor.angleDeg(),
                        latestRawNm, telemetryNm, springNm, outNm));
            }
        }
        return new Result(rows, device, curbRawPeakT, curbOutPeakT);
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "build/driving-sim.csv");
        Files.createDirectories(out.toAbsolutePath().getParent());

        Result r = run();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("t,steerCmdDeg,virtualDeg,rawNm,telemetryNm,springNm,outNm");
            for (TraceRow row : r.rows()) {
                w.printf("%.3f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f%n",
                        row.t(), row.steerCmdDeg(), row.virtualDeg(),
                        row.rawNm(), row.telemetryNm(), row.springNm(), row.outNm());
            }
        }

        System.out.println("=== Driving scenario: race car, 15 m/s, 24 s ===");
        printPhase(r, "A smooth weave      ", 0.5, 5.0);
        printPhase(r, "B bumpy blocks      ", 5.0, 10.0);
        printPhase(r, "C curb strike       ", 10.0, 12.0);
        printPhase(r, "D steady corner     ", 13.0, 17.0);
        printPhase(r, "E telemetry dropout ", 17.2, 18.0);
        printPhase(r, "F recovery          ", 19.0, 24.0);
        System.out.printf("Curb strike: raw peak at t=%.3f s, rim peak at t=%.3f s -> latency %.0f ms%n",
                r.curbRawPeakT(), r.curbOutPeakT(),
                (r.curbOutPeakT() - r.curbRawPeakT()) * 1000);
        double maxOut = r.rows().stream().mapToDouble(x -> Math.abs(x.outNm())).max().orElse(0);
        System.out.printf("Max |rim torque| = %.3f Nm (clamp 2.5) | device writes: %d%n",
                maxOut, r.device().torqueWrites.size());
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
