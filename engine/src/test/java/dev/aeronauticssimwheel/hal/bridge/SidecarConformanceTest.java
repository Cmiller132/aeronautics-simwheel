package dev.aeronauticssimwheel.hal.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-language conformance: the REAL mod-side client ({@link
 * BridgeWheelDevice}) against the REAL native sidecar (Rust, {@code
 * sidecar/}) in {@code --sim} mode — full UDP loop, no hardware. The sim
 * device is a spring–damper wheel driven by commanded torque, so these
 * assertions close the physical loop: torque out ⇒ motion back in STATE.
 *
 * <p>Skipped (not failed) when the sidecar binary hasn't been built —
 * {@code cd sidecar && cargo build --release} — so plain {@code :engine:test}
 * stays hermetic on machines without Rust. Override the binary with
 * {@code -Dsimwheel.sidecar.exe=...}.
 */
class SidecarConformanceTest {

    private Process sidecar;
    private BridgeWheelDevice device;

    private static Path findSidecar() {
        String prop = System.getProperty("simwheel.sidecar.exe");
        if (prop != null) {
            return Path.of(prop);
        }
        // engine/ is the test working dir; the crate lives beside it. Cargo
        // names the binary `simwheel-bridge.exe` on Windows and plain
        // `simwheel-bridge` elsewhere — probing only .exe silently skipped
        // the whole conformance suite on Linux/macOS (found by review); the
        // target-linux dir is the documented WSL/cross build location.
        return Stream.of(
                        "../sidecar/target/release/simwheel-bridge.exe",
                        "../sidecar/target/release/simwheel-bridge",
                        "../sidecar/target/debug/simwheel-bridge.exe",
                        "../sidecar/target/debug/simwheel-bridge",
                        "../sidecar/target-linux/release/simwheel-bridge")
                .map(Path::of)
                .filter(Files::exists)
                .findFirst()
                .orElse(null);
    }

    @BeforeEach
    void launch() throws Exception {
        Path exe = findSidecar();
        if (Boolean.getBoolean("simwheel.sidecar.require")) {
            // :engine:sidecarConformance just built it — absence is a FAILURE
            // there, so conformance can never silently not-run in CI.
            assertTrue(exe != null, "sidecar binary missing after buildSidecar");
        }
        assumeTrue(exe != null, "sidecar not built (cd sidecar && cargo build --release)");

        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        Path log = Path.of("build/sidecar-conformance.log");
        Files.createDirectories(log.getParent());
        sidecar = new ProcessBuilder(exe.toAbsolutePath().toString(),
                "--sim", "--verbose", "--port", String.valueOf(port))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        device = new BridgeWheelDevice(new InetSocketAddress("127.0.0.1", port),
                BridgeWheelDevice.Config.defaults());
    }

    @AfterEach
    void teardown() throws Exception {
        if (device != null) {
            device.close();
        }
        if (sidecar != null) {
            sidecar.destroy();
            if (!sidecar.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                sidecar.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void fullLoop_hello_state_torque_watchdog_panic() throws Exception {
        // --- START → HELLO + STATE stream ---------------------------------
        // Re-send START while waiting: the first datagram races the sidecar's
        // socket bind, and UDP START is idempotent by protocol design.
        waitFor("HELLO + STATE stream", 3000, () -> {
            device.ffbStart();
            return device.connected() && device.id().contains("Simulated");
        });
        assertEquals(9.0f, device.ratedTorqueNm(), 1e-6, "HELLO must carry rated torque");

        // --- Torque deflects the sim wheel through the real UDP loop -------
        // Hold long enough to SETTLE (≈15° steady state) so the watchdog
        // deadline below measures pure decay, not leftover rise momentum.
        float start = device.steeringDeg();
        long until = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < until) {
            device.ffbUpdateTorque(2.25f); // Nm on the wire, inside the 2.5 cap
            Thread.sleep(10);
        }
        float deflected = device.steeringDeg();
        assertTrue(deflected - start > 8f,
                "sustained torque must deflect the sim wheel: " + start + " → " + deflected);

        // --- Watchdog: silence must zero torque — with a DEADLINE ----------
        // The 100 ms watchdog (+ loop latency) must have cut torque well
        // within 400 ms: the critically-damped sim then already shows a
        // measurable drop. A broken watchdog holds the deflection steady.
        Thread.sleep(400);
        float early = Math.abs(device.steeringDeg());
        assertTrue(early < Math.abs(deflected) - 1.0f,
                "torque must be cut within the watchdog deadline: "
                        + deflected + " → " + early + " after 400 ms of silence");
        Thread.sleep(1600);
        float recentred = Math.abs(device.steeringDeg());
        assertTrue(recentred < Math.abs(deflected) / 3f,
                "the spring must recenter once torque is cut: "
                        + deflected + " → " + recentred);

        // --- PANIC, then recovery via START --------------------------------
        // (The bridge-side latch semantics themselves — post-PANIC torque
        // ignored from the same client — are pinned by the sidecar's own
        // `panic_latches_until_start` unit test; here we prove the round trip
        // through the real client, which latches too.)
        device.panic();
        Thread.sleep(200);
        device.ffbStart();
        Thread.sleep(200); // both latches cleared; fresh session running
        float before = device.steeringDeg();
        until = System.currentTimeMillis() + 800;
        while (System.currentTimeMillis() < until) {
            device.ffbUpdateTorque(2.25f);
            Thread.sleep(10);
        }
        assertTrue(device.steeringDeg() - before > 5f,
                "START must clear both panic latches: " + before + " → " + device.steeringDeg());
    }

    private void waitFor(String what, long timeoutMs, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(cond.getAsBoolean(), "timed out waiting for " + what);
    }
}
