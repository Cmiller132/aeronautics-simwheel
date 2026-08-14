package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.content.SimSteeringWheelBlock;
import dev.aeronauticssimwheel.content.SimSteeringWheelBlockEntity;
import dev.aeronauticssimwheel.hal.LogicalAxis;
import dev.aeronauticssimwheel.hal.WheelDevice;
import dev.aeronauticssimwheel.network.SimWheelInputPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side latch onto a Sim Steering Wheel (DESIGN.md §5): sit in the
 * craft's seat, look at the wheel, press ENGAGE. Each client tick the hardware
 * frame goes out as a float packet when anything changed (>0.5% on any axis or
 * any button) plus a heartbeat every 10 ticks so the server's 30-tick input
 * timeout stays fed. Leaving the seat releases control (the seat is the mutex).
 *
 * <p>Wheel buttons: device buttons 2–9 map to BTN_1..8 (0/1 are ENGAGE and
 * DETENT_MODIFIER), all momentary — the receiving redstone decides latching,
 * exactly like a lever vs. a button. (Client-side toggle modes were scaffolding
 * with no UI to reach them; removed until the config screen actually lands.)
 */
public final class SimWheelLink {

    private static final float SEND_EPSILON = 0.005f;
    private static final int HEARTBEAT_TICKS = 10;
    private static final int FIRST_BTN_DEVICE_INDEX = 2;
    private static final int BTN_COUNT = 8;
    /** A/D fallback steer: half lock — brisk but controllable at speed. */
    private static final float KEYBOARD_STEER = 0.5f;

    private BlockPos pos;
    private boolean engaged;
    private float lastSteering = Float.NaN;
    private float lastThrottle;
    private float lastBrake;
    private float lastClutch;
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
        if (!mc.player.isPassenger()) {
            mc.player.displayClientMessage(
                    Component.literal("SimWheel: sit in the craft's seat first"), true);
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit)
                || !(mc.level.getBlockState(hit.getBlockPos()).getBlock()
                        instanceof SimSteeringWheelBlock)) {
            mc.player.displayClientMessage(
                    Component.literal("SimWheel: look at a Sim Steering Wheel to engage"), true);
            return;
        }
        pos = hit.getBlockPos();
        engaged = true;
        lastSteering = Float.NaN;
        ticksSinceSend = 0;
        mc.player.displayClientMessage(Component.literal("SimWheel engaged @ "
                + pos.toShortString()
                + (input.hasInput() ? "" : " — keyboard control (W/S/A/D)")), true);
    }

    public void tick(Minecraft mc, WheelInput input) {
        if (!engaged) {
            return;
        }
        if (mc.level == null || mc.player == null || pos == null
                || !(mc.level.getBlockState(pos).getBlock() instanceof SimSteeringWheelBlock)) {
            engaged = false;
            pos = null;
            return;
        }
        if (!mc.player.isPassenger()) {
            disengage(mc); // leaving the seat releases control
            return;
        }

        float steering = 0f;
        float throttle = 0f;
        float brake = 0f;
        float clutch = 0f;
        int buttons = 0;
        if (input.hasInput()) {
            steering = input.adapter().value(LogicalAxis.STEERING);
            throttle = input.adapter().unipolar(LogicalAxis.THROTTLE);
            brake = input.adapter().unipolar(LogicalAxis.BRAKE);
            clutch = input.adapter().unipolar(LogicalAxis.CLUTCH);
            buttons = buttonMask(input.activeDevice());
        }

        // Keyboard merge: pedals for wheelbase-only rigs (no pedal set yet),
        // and full W/S/A/D control with no hardware at all. Keys use the
        // vanilla movement binds, which do nothing while seated.
        if (mc.options.keyUp.isDown()) {
            throttle = Math.max(throttle, 1f);
        }
        if (mc.options.keyDown.isDown()) {
            brake = Math.max(brake, 1f);
        }
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();
        if (Math.abs(steering) < 0.02f && (left ^ right)) {
            steering = right ? KEYBOARD_STEER : -KEYBOARD_STEER;
        }
        commandedSteering = steering;

        ticksSinceSend++;
        boolean changed = Float.isNaN(lastSteering)
                || Math.abs(steering - lastSteering) > SEND_EPSILON
                || Math.abs(throttle - lastThrottle) > SEND_EPSILON
                || Math.abs(brake - lastBrake) > SEND_EPSILON
                || Math.abs(clutch - lastClutch) > SEND_EPSILON
                || buttons != lastButtons;
        if (changed || ticksSinceSend >= HEARTBEAT_TICKS) {
            PacketDistributor.sendToServer(
                    new SimWheelInputPacket(pos, steering, throttle, brake, clutch, buttons));
            lastSteering = steering;
            lastThrottle = throttle;
            lastBrake = brake;
            lastClutch = clutch;
            lastButtons = buttons;
            ticksSinceSend = 0;
        }
    }

    private int buttonMask(WheelDevice device) {
        if (device == null) {
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < BTN_COUNT; i++) {
            if (device.button(FIRST_BTN_DEVICE_INDEX + i)) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    public void disengage(Minecraft mc) {
        if (engaged && pos != null && mc.level != null) {
            // Neutral frame so the craft doesn't coast on the last input
            PacketDistributor.sendToServer(new SimWheelInputPacket(pos, 0f, 0f, 0f, 0f, 0));
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

    /** The block's configured lock, from the client-synced BE (default until synced). */
    public float lockDeg(Minecraft mc) {
        if (pos != null && mc.level != null
                && mc.level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            return be.lockDeg();
        }
        return SimSteeringWheelBlockEntity.DEFAULT_LOCK_DEG;
    }

    /** Commanded steering in wheel degrees (direct authority: this is the target). */
    public float commandedDeg(Minecraft mc) {
        return commandedSteering * lockDeg(mc);
    }

    /** The server-synced in-game angle (chases the command at the sanity clamp only). */
    public float serverAngleDeg(Minecraft mc) {
        if (pos != null && mc.level != null
                && mc.level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            return be.angleDeg();
        }
        return Float.NaN;
    }
}
