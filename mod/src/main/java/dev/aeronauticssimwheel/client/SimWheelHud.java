package dev.aeronauticssimwheel.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

/**
 * Minimal debug HUD (stand-in for the DESIGN.md §8 status widget): device,
 * engagement, commanded vs. server wheel angle within the configured lock,
 * and safety-chain output. Drawn only while an input device is active or a
 * wheel is latched.
 */
public final class SimWheelHud implements LayeredDraw.Layer {

    private final WheelInput input;
    private final SimWheelLink link;
    private final FfbController ffb;
    private final FeelConfig feel;

    public SimWheelHud(WheelInput input, SimWheelLink link, FfbController ffb, FeelConfig feel) {
        this.input = input;
        this.link = link;
        this.ffb = ffb;
        this.feel = feel;
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || (!input.hasInput() && !link.isEngaged())) {
            return;
        }

        int y = 40;
        g.drawString(mc.font, "SimWheel: " + input.label(), 4, y, 0xFFFFFFFF);
        y += 10;
        if (link.isEngaged()) {
            g.drawString(mc.font, "engaged @ " + link.latchedPos().toShortString(), 4, y, 0xFF80FF80);
            y += 10;
            float game = link.serverAngleDeg(mc);
            g.drawString(mc.font, String.format("cmd %+.1f°  game %+.1f°  lock ±%.0f°",
                    link.commandedDeg(mc), Float.isNaN(game) ? 0f : game, link.lockDeg(mc)),
                    4, y, 0xFFFFFFFF);
            y += 10;
            g.drawString(mc.font, String.format("FFB %+.2f Nm [%s]",
                    ffb.lastOutputNm(), ffb.safetyState()), 4, y, 0xFFFFD080);
            y += 10;
            g.drawString(mc.font, String.format("telemetry %+.2f Nm [%s]",
                    ffb.lastTelemetryNm(), ffb.telemetryStale() ? "stale" : "live"),
                    4, y, 0xFF80D0FF);
            y += 10;
            var stats = ffb.loopStats();
            var tuning = ffb.tuning();
            g.drawString(mc.font, String.format(
                    "loop %.0f Hz late %.2f/%.1f ms | clamp %.1f Nm gain %.2f tele ×%.2f",
                    stats.actualHz(), stats.avgLateMs(), stats.maxLateMs(),
                    tuning.maxTorqueNm(), tuning.masterGain(), tuning.telemetryGain()),
                    4, y, 0xFFB0B0B0);
            y += 10;
            g.drawString(mc.font, feel.status(), 4, y, 0xFFB0B0B0);
        } else {
            g.drawString(mc.font, "sit in a seat, look at the Sim Steering Wheel, J = engage",
                    4, y, 0xFFA0A0A0);
        }
    }
}
