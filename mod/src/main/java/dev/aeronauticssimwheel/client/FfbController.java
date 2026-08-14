package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.ffb.FfbPipeline;
import dev.aeronauticssimwheel.ffb.FfbService;
import dev.aeronauticssimwheel.ffb.FfbTuning;
import dev.aeronauticssimwheel.ffb.SafetyChain;
import dev.aeronauticssimwheel.hal.WheelDevice;
import dev.aeronauticssimwheel.network.FfbEventPacket;
import dev.aeronauticssimwheel.network.FfbTelemetryPacket;
import net.minecraft.client.Minecraft;

/**
 * Thin Minecraft adapter around the engine's FFB stack: {@link FfbService}
 * owns the 250 Hz loop, pacing and device lifecycle; {@link FfbPipeline} owns
 * every Newton-meter. This class only translates — game tick → input snapshot,
 * packets → pipeline ingress, engine callbacks → log lines. All the math and
 * threading it used to contain is engine code now, unit-tested as the same
 * composition the harness drives (DESIGN.md §10.5).
 */
public final class FfbController {

    private final FfbPipeline pipeline = new FfbPipeline();
    private final FfbService service = new FfbService(pipeline, new FfbService.Listener() {
        @Override
        public void onDeviceSwitch(WheelDevice from, WheelDevice to) {
            if (to != null) {
                AeronauticsSimwheel.LOGGER.info("FFB output → {}", to.id());
            } else if (from != null) {
                AeronauticsSimwheel.LOGGER.info("FFB output detached ({})", from.id());
            }
        }

        @Override
        public void onDeviceError(String stage, RuntimeException error) {
            AeronauticsSimwheel.LOGGER.error("FFB device {} failed; panicking", stage, error);
        }

        @Override
        public void onDeviceFault(WheelDevice device) {
            AeronauticsSimwheel.LOGGER.error(
                    "FFB backend reported a device fault ({}); torque stopped — "
                            + "disengage and re-engage to recover", device.id());
        }
    });

    private float prevCmdDeg;
    private long prevCmdNanos;

    public void start() {
        service.start();
    }

    public void stop() {
        service.stop();
    }

    /** Game thread, once per client tick: publish inputs for the FFB thread. */
    public void updateFromGame(Minecraft mc, WheelInput input, SimWheelLink link) {
        boolean engaged = link.isEngaged();

        // Tracked every tick, not just on engage edges: the bridge sidecar may
        // connect (or die) mid-session; the FFB thread applies the transition.
        service.setDesiredDevice(engaged ? input.activeDevice() : null);

        if (!engaged) {
            service.publishGameState(false, 0f, 0f, link.lockDeg(mc));
            prevCmdNanos = 0;
            return;
        }

        long now = System.nanoTime();
        float cmdDeg = link.commandedDeg(mc); // direct authority: command == position
        float cmdVel = 0f;
        if (prevCmdNanos != 0) {
            double dt = (now - prevCmdNanos) / 1e9; // measured, not an assumed tick length
            if (dt > 1e-4) {
                cmdVel = (float) ((cmdDeg - prevCmdDeg) / dt);
            }
        }
        prevCmdDeg = cmdDeg;
        prevCmdNanos = now;

        service.publishGameState(true, cmdDeg, cmdVel, link.lockDeg(mc));
    }

    // ------------------------------------------------------------------
    // Packet ingress (client main thread) — hygiene lives in the pipeline
    // ------------------------------------------------------------------

    public void onTelemetry(FfbTelemetryPacket packet) {
        float[] torques = packet.torqueNm();
        float[] offsets = packet.offsetsS();
        if (torques.length == 0 || torques.length != offsets.length
                || !Double.isFinite(packet.baseTimeS())) {
            return;
        }
        for (int i = 0; i < torques.length; i++) {
            pipeline.postTelemetry(packet.baseTimeS() + offsets[i], torques[i]);
        }
        pipeline.noteTelemetryBatch(packet.baseTimeS() + offsets[offsets.length - 1],
                System.nanoTime() / 1e9);
    }

    public void onEvent(FfbEventPacket packet) {
        pipeline.postEvent(packet.peakNm(), packet.tauSeconds());
    }

    // ------------------------------------------------------------------
    // Tuning + HUD
    // ------------------------------------------------------------------

    /** Any thread: swap the live tuning (hot-reloaded feel config). */
    public void setTuning(FfbTuning tuning) {
        pipeline.setTuning(tuning);
    }

    public FfbTuning tuning() {
        return pipeline.tuning();
    }

    public float lastOutputNm() {
        return pipeline.lastOutputNm();
    }

    /** Reconstructed telemetry torque currently in the mix (HUD). */
    public float lastTelemetryNm() {
        return pipeline.lastComponents().telemetryNm();
    }

    /** True while telemetry playback is serving stale/faded data (HUD). */
    public boolean telemetryStale() {
        return pipeline.telemetryStale();
    }

    public SafetyChain.State safetyState() {
        return pipeline.safetyState();
    }

    public FfbService.LoopStats loopStats() {
        return service.loopStats();
    }
}
