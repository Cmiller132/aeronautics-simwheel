package dev.aeronauticssimwheel.network;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.client.SimWheelClient;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client, once per game tick while a rig is live (DESIGN.md §6.3):
 * the substep column-torque samples produced since the last flush. The irFFB
 * trick — a 40+ Hz torque signal reconstructed client-side from 20 Hz packets
 * by the {@code TelemetryBuffer}'s delayed interpolation.
 *
 * <p>Wire: base server-timeline instant (seconds) of the first sample, then
 * each sample's true offset from it plus its torque — actual recorded
 * instants, not a uniform-dt assumption (under lag Sable packs extra substeps
 * into a tick and dt varies between ticks). Hostile-input hygiene, enforced at
 * decode so garbage never reaches game code: length cap, finite non-decreasing
 * offsets inside a sane window. Torque magnitude is clamped at ingress by the
 * FfbController instead (a wire-legal value must never be able to overflow
 * float arithmetic downstream).
 */
public record FfbTelemetryPacket(double baseTimeS, float[] offsetsS, float[] torqueNm)
        implements CustomPacketPayload {

    public static final int MAX_SAMPLES = 64;
    /** All offsets must fit one sane flush window (seconds). */
    public static final float MAX_OFFSET_S = 10f;

    private static final PacketRateGate RATE = new PacketRateGate(120);

    public static final Type<FfbTelemetryPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AeronauticsSimwheel.MOD_ID, "ffb_telemetry"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FfbTelemetryPacket> CODEC =
            StreamCodec.of(FfbTelemetryPacket::write, FfbTelemetryPacket::read);

    private static void write(RegistryFriendlyByteBuf buf, FfbTelemetryPacket pkt) {
        buf.writeDouble(pkt.baseTimeS);
        buf.writeVarInt(pkt.torqueNm.length);
        for (int i = 0; i < pkt.torqueNm.length; i++) {
            buf.writeFloat(pkt.offsetsS[i]);
            buf.writeFloat(pkt.torqueNm[i]);
        }
    }

    private static FfbTelemetryPacket read(RegistryFriendlyByteBuf buf) {
        double base = buf.readDouble();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_SAMPLES) {
            throw new DecoderException("telemetry sample count out of range: " + n);
        }
        float[] offsets = new float[n];
        float[] torques = new float[n];
        float prev = 0f;
        for (int i = 0; i < n; i++) {
            offsets[i] = buf.readFloat();
            torques[i] = buf.readFloat();
            if (!Float.isFinite(offsets[i]) || offsets[i] < prev || offsets[i] > MAX_OFFSET_S) {
                throw new DecoderException("telemetry offsets not sane at " + i);
            }
            prev = offsets[i];
        }
        return new FfbTelemetryPacket(base, offsets, torques);
    }

    public static void handle(FfbTelemetryPacket packet, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient() && RATE.tryAcquire()) {
            context.enqueueWork(() -> SimWheelClient.ffb().onTelemetry(packet));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
