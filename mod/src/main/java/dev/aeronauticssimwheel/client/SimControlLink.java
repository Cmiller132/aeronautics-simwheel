package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.content.SimControlBlock;
import dev.aeronauticssimwheel.hal.LogicalAxis;
import dev.aeronauticssimwheel.network.SimControlInputPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Latch onto a SimControl block (DESIGN.md §5.3b): the only control path.
 * Engage while seated and looking at the block; each client tick the hardware
 * frame goes out as a float packet when it changed (>0.5% on any axis) plus a
 * heartbeat every 10 ticks so the server's 30-tick input timeout stays fed.
 */
public final class SimControlLink {

    private static final float SEND_EPSILON = 0.005f;
    private static final int HEARTBEAT_TICKS = 10;

    private BlockPos pos;
    private boolean engaged;
    private float lastSteering = Float.NaN;
    private float lastThrottle;
    private float lastBrake;
    private int lastButtons;
    private int ticksSinceSend;
    private float commandedSteering;

    public void toggleEngage(Minecraft mc, WheelInput input) {
        if (engaged) {
            disengage(mc);
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (!input.hasInput()) {
            mc.player.displayClientMessage(
                    Component.literal("SimWheel: no input device (press K for demo input)"), true);
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit)
                || !(mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof SimControlBlock)) {
            mc.player.displayClientMessage(
                    Component.literal("SimWheel: look at a Sim Control block to engage"), true);
            return;
        }
        if (!mc.player.isPassenger()) {
            mc.player.displayClientMessage(
                    Component.literal("SimWheel: sit in the linked seat first"), true);
            return;
        }
        pos = hit.getBlockPos();
        engaged = true;
        lastSteering = Float.NaN;
        ticksSinceSend = 0;
        mc.player.displayClientMessage(
                Component.literal("SimWheel engaged @ " + pos.toShortString()), true);
    }

    public void tick(Minecraft mc, WheelInput input) {
        if (!engaged) {
            return;
        }
        if (mc.level == null || mc.player == null || pos == null || !input.hasInput()
                || !(mc.level.getBlockState(pos).getBlock() instanceof SimControlBlock)) {
            engaged = false;
            pos = null;
            return;
        }
        if (!mc.player.isPassenger()) {
            disengage(mc); // leaving the seat releases control (§5.3b occupancy)
            return;
        }

        float steering = input.adapter().value(LogicalAxis.STEERING);
        float throttle = input.adapter().unipolar(LogicalAxis.THROTTLE);
        float brake = input.adapter().unipolar(LogicalAxis.BRAKE);
        int buttons = 0;
        commandedSteering = steering;

        ticksSinceSend++;
        boolean changed = Float.isNaN(lastSteering)
                || Math.abs(steering - lastSteering) > SEND_EPSILON
                || Math.abs(throttle - lastThrottle) > SEND_EPSILON
                || Math.abs(brake - lastBrake) > SEND_EPSILON
                || buttons != lastButtons;
        if (changed || ticksSinceSend >= HEARTBEAT_TICKS) {
            PacketDistributor.sendToServer(
                    new SimControlInputPacket(pos, steering, throttle, brake, buttons));
            lastSteering = steering;
            lastThrottle = throttle;
            lastBrake = brake;
            lastButtons = buttons;
            ticksSinceSend = 0;
        }
    }

    public void disengage(Minecraft mc) {
        if (engaged && pos != null && mc.level != null) {
            // Neutral frame so the craft doesn't coast on the last input
            PacketDistributor.sendToServer(new SimControlInputPacket(pos, 0f, 0f, 0f, 0));
        }
        engaged = false;
        pos = null;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("SimWheel disengaged"), true);
        }
    }

    public boolean isEngaged() {
        return engaged;
    }

    public BlockPos latchedPos() {
        return pos;
    }

    /** Steering in display degrees (±450 shown as the hardware range). */
    public float commandedDeg() {
        return commandedSteering * 450f;
    }

    /** Direct-authority block: the in-game state is the command. */
    public float measuredDeg(Minecraft mc) {
        return commandedDeg();
    }
}
