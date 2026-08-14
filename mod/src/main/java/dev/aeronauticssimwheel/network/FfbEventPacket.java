package dev.aeronauticssimwheel.network;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.client.SimWheelClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client, immediate (DESIGN.md §6.3): one contact transient — a curb
 * strike, a hard landing — bypassing the telemetry batch. Strictly a local
 * synthesis trigger: the client renders it as a decaying impulse through
 * {@code EventImpulses}, bounded queue, SafetyChain downstream. Rate-limited
 * at the source by {@code StrikeDetector}'s min-interval, gated again on the
 * receiving network thread, and clamped on receipt.
 */
public record FfbEventPacket(float peakNm, float tauSeconds) implements CustomPacketPayload {

    private static final PacketRateGate RATE = new PacketRateGate(60);

    public static final Type<FfbEventPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AeronauticsSimwheel.MOD_ID, "ffb_event"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FfbEventPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.FLOAT, FfbEventPacket::peakNm,
                    ByteBufCodecs.FLOAT, FfbEventPacket::tauSeconds,
                    FfbEventPacket::new);

    public static void handle(FfbEventPacket packet, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient() && RATE.tryAcquire()) {
            context.enqueueWork(() -> SimWheelClient.ffb().onEvent(packet));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
