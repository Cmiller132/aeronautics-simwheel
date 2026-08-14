package dev.aeronauticssimwheel.network;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.content.SimSteeringWheelBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: one hardware input frame for the Sim Steering Wheel
 * (float precision on the wire; steering −1…1 of the block's configured lock,
 * pedals 0…1, buttons as a mask of {@code BTN_1..8}).
 *
 * <p>Server-side validation — the seat-occupancy rule (DESIGN.md §5):
 * the sender must be riding something (a seat), and must be within reach of
 * the wheel's <i>effective</i> position. Because assembled craft are Sable
 * sub-levels (chunks simulated at a dynamic pose), the wheel's stored
 * BlockPos is transformed through its sub-level pose before the distance
 * check — a plain BlockPos distance would reject every moving craft.
 */
public record SimWheelInputPacket(BlockPos pos, float steering, float throttle,
                                  float brake, float clutch, int buttons)
        implements CustomPacketPayload {

    public static final Type<SimWheelInputPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AeronauticsSimwheel.MOD_ID, "sim_wheel_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SimWheelInputPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SimWheelInputPacket::pos,
                    ByteBufCodecs.FLOAT, SimWheelInputPacket::steering,
                    ByteBufCodecs.FLOAT, SimWheelInputPacket::throttle,
                    ByteBufCodecs.FLOAT, SimWheelInputPacket::brake,
                    ByteBufCodecs.FLOAT, SimWheelInputPacket::clutch,
                    ByteBufCodecs.VAR_INT, SimWheelInputPacket::buttons,
                    SimWheelInputPacket::new);

    private static final double MAX_RANGE_SQ = 8 * 8;

    /**
     * Per-sender ingress budget, checked on the network thread BEFORE
     * enqueueWork — a hostile client must not queue unbounded server
     * main-thread tasks (the legitimate client sends ≤20 Hz + heartbeat).
     */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, PacketRateGate>
            SENDER_GATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_FRAMES_PER_SECOND = 60;

    public static void handle(SimWheelInputPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer sender
                && !SENDER_GATES.computeIfAbsent(sender.getUUID(),
                        id -> new PacketRateGate(MAX_FRAMES_PER_SECOND)).tryAcquire()) {
            return;
        }
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!finite(packet.steering()) || !finite(packet.throttle())
                    || !finite(packet.brake()) || !finite(packet.clutch())) {
                return;
            }
            Entity seat = player.getVehicle();
            if (seat == null) {
                return; // not seated — silently drop
            }
            if (!(player.level().getBlockEntity(packet.pos())
                    instanceof SimSteeringWheelBlockEntity be)) {
                return;
            }
            if (player.distanceToSqr(effectiveWheelPos(player, packet.pos())) > MAX_RANGE_SQ) {
                return;
            }
            be.applyInput(player.getUUID(), seat.blockPosition(), packet.steering(),
                    packet.throttle(), packet.brake(), packet.clutch(), packet.buttons());
        });
    }

    /** The wheel's world-space position: sub-level pose applied when assembled. */
    private static Vec3 effectiveWheelPos(ServerPlayer player, BlockPos pos) {
        SubLevel subLevel = Sable.HELPER.getContaining(player.level(), pos);
        Vec3 center = pos.getCenter();
        return subLevel == null ? center : subLevel.logicalPose().transformPosition(center);
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
