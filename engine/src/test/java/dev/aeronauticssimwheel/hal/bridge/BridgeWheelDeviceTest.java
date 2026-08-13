package dev.aeronauticssimwheel.hal.bridge;

import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Panic;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Start;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Torque;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end over real loopback UDP against {@link FakeBridgeServer} —
 * the JVM half of the DESIGN.md §11 Phase 2b conformance series.
 */
class BridgeWheelDeviceTest {

    private static final BridgeWheelDevice.Config FAST_STALE =
            new BridgeWheelDevice.Config(1080f, 2.5f, 100, 200_000_000L);

    @Test
    void stateStreamDrivesInputAndConnectedness() throws Exception {
        try (FakeBridgeServer bridge = new FakeBridgeServer();
             BridgeWheelDevice device = new BridgeWheelDevice(bridge.address(), FAST_STALE)) {

            assertFalse(device.connected(), "no STATE yet → not connected");

            device.ffbStart(); // registers the client address at the fake bridge
            bridge.setSteering(270f, 10f);
            await(() -> {
                bridge.emitState();
                return device.connected();
            }, "device should see STATE frames");

            assertEquals(270f, device.steeringDeg(), 0.001);
            assertEquals(0.5f, device.axis(0), 0.001, "270° of ±540 = 0.5 normalized");
            await(() -> "bridge/Fake MOZA R9".equals(device.id()), "HELLO should set the name");
            assertEquals(9.0f, device.ratedTorqueNm(), 0.001);
        }
    }

    @Test
    void torqueFramesCarryNmCapAndWatchdog() throws Exception {
        try (FakeBridgeServer bridge = new FakeBridgeServer();
             BridgeWheelDevice device = new BridgeWheelDevice(bridge.address(), FAST_STALE)) {

            device.ffbStart();
            await(() -> bridge.control.stream().anyMatch(f -> f instanceof Start), "START arrives");

            device.ffbUpdateTorque(0.1f); // 0.1 × 9 Nm rated = 0.9 Nm
            await(() -> bridge.lastTorque() != null, "TORQUE arrives");
            Torque t = bridge.lastTorque();
            assertEquals(0.9f, t.torqueNm(), 0.001);
            assertEquals(2.5f, t.maxTorqueCapNm(), 0.001);
            assertEquals(100, t.watchdogMs());

            // Requests beyond the cap are clamped on the wire too, not just in SafetyChain
            device.ffbUpdateTorque(1.0f); // would be 9 Nm
            await(() -> bridge.torques.size() >= 2, "second TORQUE arrives");
            assertEquals(2.5f, bridge.lastTorque().torqueNm(), 0.001);
        }
    }

    @Test
    void panicLatchesAndStopsTorqueWrites() throws Exception {
        try (FakeBridgeServer bridge = new FakeBridgeServer();
             BridgeWheelDevice device = new BridgeWheelDevice(bridge.address(), FAST_STALE)) {

            device.ffbStart();
            device.panic();
            await(() -> bridge.control.stream().anyMatch(f -> f instanceof Panic), "PANIC arrives");

            int torquesBefore = bridge.torques.size();
            device.ffbUpdateTorque(0.5f);
            Thread.sleep(80);
            assertEquals(torquesBefore, bridge.torques.size(),
                    "no torque frames after panic until ffbStart clears the latch");

            device.ffbStart();
            device.ffbUpdateTorque(0.05f);
            await(() -> bridge.torques.size() > torquesBefore, "torque flows again after restart");
        }
    }

    @Test
    void silentBridgeGoesStale() throws Exception {
        try (FakeBridgeServer bridge = new FakeBridgeServer();
             BridgeWheelDevice device = new BridgeWheelDevice(bridge.address(), FAST_STALE)) {

            device.ffbStart();
            await(() -> {
                bridge.emitState();
                return device.connected();
            }, "connect first");

            bridge.silent.set(true); // simulated sidecar crash/hang
            await(() -> !device.connected(),
                    "no STATE within the staleness window → disconnected; SafetyChain sees stale input");
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }
}
