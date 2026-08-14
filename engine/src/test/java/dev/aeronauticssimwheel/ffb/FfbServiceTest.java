package dev.aeronauticssimwheel.ffb;

import dev.aeronauticssimwheel.hal.NullWheelDevice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop's device lifecycle and input selection, driven deterministically
 * through {@link FfbService#runOnce} — the threading contract (all ffb* calls
 * serialized on the loop thread) is enforced structurally by that being the
 * only call site, so these tests pin the transitions themselves.
 */
class FfbServiceTest {

    private static final double DT = 1 / 250.0;

    private record Fault(String stage, RuntimeException error) {
    }

    private static final class RecordingListener implements FfbService.Listener {
        final List<Fault> errors = new ArrayList<>();
        int faults;

        @Override
        public void onDeviceError(String stage, RuntimeException error) {
            errors.add(new Fault(stage, error));
        }

        @Override
        public void onDeviceFault(dev.aeronauticssimwheel.hal.WheelDevice device) {
            faults++;
        }
    }

    private static void spin(FfbService service, int steps) {
        for (int i = 0; i < steps; i++) {
            service.runOnce(System.nanoTime(), DT);
        }
    }

    @Test
    void deviceTransitionsStopOldThenStartNew() {
        FfbService service = new FfbService(new FfbPipeline(), new RecordingListener());
        NullWheelDevice first = NullWheelDevice.ffbCapable();
        NullWheelDevice second = NullWheelDevice.ffbCapable();

        service.setDesiredDevice(first);
        spin(service, 1);
        assertTrue(first.ffbStarted);
        assertFalse(first.ffbStopped);

        service.setDesiredDevice(second);
        spin(service, 1);
        assertTrue(first.ffbStopped, "old device must be stopped on handoff");
        assertTrue(second.ffbStarted);
    }

    @Test
    void torqueWriteFailurePanicsPipelineAndDevice() {
        FfbPipeline pipeline = new FfbPipeline();
        RecordingListener listener = new RecordingListener();
        FfbService service = new FfbService(pipeline, listener);
        NullWheelDevice device = NullWheelDevice.ffbCapable();

        service.setDesiredDevice(device);
        service.publishGameState(true, 0f, 0f, 450f);
        spin(service, 5);

        device.nextTorqueFailure = new IllegalStateException("USB fell out");
        spin(service, 1);

        assertEquals(1, device.panicCount, "device must be panicked on a write failure");
        assertEquals(SafetyChain.State.FAULT, pipeline.safetyState());
        assertEquals(1, listener.errors.size());
        assertEquals("torque write", listener.errors.get(0).stage());

        // Dropped: no further writes reach the failed device
        int writes = device.torqueWrites.size();
        spin(service, 5);
        assertEquals(writes, device.torqueWrites.size());
    }

    @Test
    void hardwareAngleIsPreferredOverTheCommandedSnapshot() {
        FfbPipeline pipeline = new FfbPipeline();
        FfbService service = new FfbService(pipeline, new RecordingListener());
        NullWheelDevice device = NullWheelDevice.ffbCapable();

        // Commanded angle says centered; the physical wheel is shoved past the
        // 450° stop. Only the hardware angle can make the soft lock fire.
        service.setDesiredDevice(device);
        service.publishGameState(true, 0f, 0f, 450f);
        device.hardwareAngleValid = true;
        device.hardwareDeg = 480f;

        spin(service, 500); // ≫ ramp-in
        float out = device.torqueWrites.get(device.torqueWrites.size() - 1);
        assertTrue(out < -2.0f, "soft lock must fire from the hardware angle: " + out);

        // Hardware stream dies → fall back to the commanded angle → stop releases
        device.hardwareAngleValid = false;
        spin(service, 500);
        out = device.torqueWrites.get(device.torqueWrites.size() - 1);
        assertEquals(0f, out, 1e-3, "commanded angle is centered; no lock torque");
    }

    @Test
    void staleGameSnapshotFadesOutputToZero() {
        FfbPipeline pipeline = new FfbPipeline();
        FfbService service = new FfbService(pipeline, new RecordingListener());
        NullWheelDevice device = NullWheelDevice.ffbCapable();

        service.setDesiredDevice(device);
        service.publishGameState(true, 0f, 0f, 450f);
        device.hardwareAngleValid = true;
        device.hardwareDeg = 480f;
        spin(service, 500);
        assertTrue(device.torqueWrites.get(device.torqueWrites.size() - 1) < -2.0f);

        // Game thread dies (no more publishes): 200 ms later the snapshot is
        // stale and the watchdog must fade output to zero — never hold it.
        long dead = System.nanoTime() + 200_000_000L;
        for (int i = 0; i < 500; i++) {
            service.runOnce(dead + (long) (i * DT * 1e9), DT);
        }
        assertEquals(0f, device.torqueWrites.get(device.torqueWrites.size() - 1), 1e-3,
                "stale snapshot must zero the output");
    }

    @Test
    void backendFaultPanicsOnceAndRefusesTheDeviceWhileFaulted() {
        FfbPipeline pipeline = new FfbPipeline();
        RecordingListener listener = new RecordingListener();
        FfbService service = new FfbService(pipeline, listener);
        NullWheelDevice device = NullWheelDevice.ffbCapable();

        service.setDesiredDevice(device);
        service.publishGameState(true, 0f, 0f, 450f);
        spin(service, 5);

        device.deviceFault = true;
        service.setDesiredDevice(device); // game thread still wants it every tick
        spin(service, 10);
        assertEquals(1, listener.faults, "fault edge must fire exactly once");
        assertEquals(1, device.panicCount);
        assertEquals(SafetyChain.State.FAULT, pipeline.safetyState());

        // While the backend still reports the fault, the device is not re-attached
        int starts = device.torqueWrites.size();
        service.setDesiredDevice(device);
        spin(service, 10);
        assertEquals(starts, device.torqueWrites.size(),
                "no writes to a device still reporting a fault");
    }

    @Test
    void teardownNeverLeavesTorqueLatched() {
        FfbService service = new FfbService(new FfbPipeline(), new RecordingListener());
        NullWheelDevice device = NullWheelDevice.ffbCapable();

        service.setDesiredDevice(device);
        service.publishGameState(true, 0f, 0f, 450f);
        spin(service, 5);

        service.teardown();
        assertTrue(device.ffbStopped, "loop exit must stop the active device");
    }
}
