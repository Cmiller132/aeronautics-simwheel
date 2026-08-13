package dev.aeronauticssimwheel.network;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.content.SimControlBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: one hardware input frame for a SimControl block
 * (float precision on the wire; the block entity quantizes to link levels).
 * Server-side validation: the sender must be seated (a passenger) and within
 * interaction range of the block — the §5.3b occupancy rule.
 */
public record SimControlInputPacket(BlockPos pos, float steering, float throttle,
                                    float brake, int buttons) implements CustomPacketPayload {

    public static final Type<SimControlInputPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AeronauticsSimwheel.MOD_ID, "sim_control_input"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SimControlInputPacket> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SimControlInputPacket::pos,
                    ByteBufCodecs.FLOAT, SimControlInputPacket::steering,
                    ByteBufCodecs.FLOAT, SimControlInputPacket::throttle,
                    ByteBufCodecs.FLOAT, SimControlInputPacket::brake,
                    ByteBufCodecs.VAR_INT, SimControlInputPacket::buttons,
                    SimControlInputPacket::new);

    private static final double MAX_RANGE_SQ = 8 * 8;

    public static void handle(SimControlInputPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isPassenger()
                    || player.distanceToSqr(packet.pos().getCenter()) > MAX_RANGE_SQ) {
                return; // not seated at the block — silently drop (§5.3b)
            }
            if (Float.isNaN(packet.steering()) || Float.isNaN(packet.throttle())
                    || Float.isNaN(packet.brake())) {
                return;
            }
            if (player.level().getBlockEntity(packet.pos()) instanceof SimControlBlockEntity be) {
                be.applyInput(player.getUUID(), packet.steering(), packet.throttle(),
                        packet.brake(), packet.buttons());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
