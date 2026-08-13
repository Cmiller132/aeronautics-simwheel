package dev.aeronauticssimwheel.client;

import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import dev.simulated_team.simulated.index.SimClickInteractions;
import dev.simulated_team.simulated.network.packets.SteeringWheelPacket;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The latch-mode injector (DESIGN.md §5.4): once engaged on a steering wheel,
 * the hardware axis drives the exact packet the vanilla hold-interaction sends —
 * {@code SteeringWheelPacket(false, targetAngle, pos)} — with no need to keep
 * holding right-click. Packets go out on change (>0.25°) plus a 0.5 s heartbeat;
 * disengaging sends the stop packet just like releasing the wheel does.
 *
 * <p>Latching survives distance (the server handler does no range check and the
 * driver moves with the craft); it breaks only on keybind, dimension change, or
 * the block entity disappearing.
 */
public final class SteeringWheelLink {

    private static final float SEND_EPSILON_DEG = 0.25f;
    private static final int HEARTBEAT_TICKS = 10;

    private BlockPos pos;
    private boolean engaged;
    private float lastSentDeg = Float.NaN;
    private float commandedDeg;
    private int ticksSinceSend;

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

        BlockPos target = null;
        var vanillaHold = SimClickInteractions.STEERING_WHEEL_MANAGER;
        if (vanillaHold.isActive()) {
            target = vanillaHold.getInteractionPos();
            vanillaHold.stop(); // clean hand-over: vanilla sends its own stop packet
        } else if (mc.hitResult instanceof BlockHitResult hit
                && mc.level.getBlockEntity(hit.getBlockPos()) instanceof SteeringWheelBlockEntity) {
            target = hit.getBlockPos();
        }

        if (target == null) {
            mc.player.displayClientMessage(
                    Component.literal("SimWheel: look at a steering wheel to engage"), true);
            return;
        }

        pos = target;
        engaged = true;
        lastSentDeg = Float.NaN;
        ticksSinceSend = 0;
        mc.player.displayClientMessage(
                Component.literal("SimWheel engaged @ " + target.toShortString()), true);
    }

    /** Client tick while the world is up. */
    public void tick(Minecraft mc, WheelInput input) {
        if (!engaged) {
            return;
        }
        SteeringWheelBlockEntity be = resolve(mc);
        if (be == null || !input.hasInput()) {
            engaged = false;
            pos = null;
            return;
        }

        float limit = be.angleInput.getValue();
        float sgn = be.directionConvert(1);
        float cmd = Mth.clamp(input.adapter().steeringDeg(limit) * sgn, -limit, limit);
        commandedDeg = cmd;

        ticksSinceSend++;
        boolean changed = Float.isNaN(lastSentDeg) || Math.abs(cmd - lastSentDeg) > SEND_EPSILON_DEG;
        if (changed || ticksSinceSend >= HEARTBEAT_TICKS) {
            VeilPacketManager.server().sendPacket(new SteeringWheelPacket(false, cmd, pos));
            lastSentDeg = cmd;
            ticksSinceSend = 0;
        }

        // Client-side mirror, same as the vanilla handler: keeps the local render
        // and the BE's clientAngle chaser in step with what we commanded.
        be.targetAngleToUpdate = cmd;
        be.held = true;
    }

    public void disengage(Minecraft mc) {
        SteeringWheelBlockEntity be = resolve(mc);
        if (be != null && pos != null) {
            VeilPacketManager.server().sendPacket(new SteeringWheelPacket(true, commandedDeg, pos));
            be.held = false;
        }
        engaged = false;
        pos = null;
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("SimWheel disengaged"), true);
        }
    }

    private SteeringWheelBlockEntity resolve(Minecraft mc) {
        if (pos == null || mc.level == null) {
            return null;
        }
        return mc.level.getBlockEntity(pos) instanceof SteeringWheelBlockEntity be && !be.isRemoved()
                ? be : null;
    }

    public boolean isEngaged() {
        return engaged;
    }

    public BlockPos latchedPos() {
        return pos;
    }

    /** Last commanded target, wheel-space degrees. */
    public float commandedDeg() {
        return commandedDeg;
    }

    /** The BE's authoritative (server-synced) wheel angle, or NaN. */
    public float measuredDeg(Minecraft mc) {
        SteeringWheelBlockEntity be = resolve(mc);
        return be == null ? Float.NaN : be.getAngle();
    }
}
