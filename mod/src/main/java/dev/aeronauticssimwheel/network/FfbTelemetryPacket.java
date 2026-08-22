package dev.aeronauticssimwheel.network;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.client.SimWheelClient;
import dev.aeronauticssimwheel.ffb.TelemetryFrame;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client, once per game tick while a rig is live (DESIGN.md §6.3),
 * v2: the substep component frames produced since the last flush — SAT at
 * reference trail, differential texture, speed, slip, μ, rpm — instead of a
 * pre-mixed torque scalar. Composition happens client-side where it is
 * hot-reload tunable. The irFFB trick — a 40+ Hz signal reconstructed
 * client-side from 20 Hz packets by the {@code TelemetryBuffer}'s delayed
 * interpolation.
 *
 * <p>Wire: base server-timeline instant (seconds) of the first sample, then
 * each sample's true offset from it plus its six channels — actual recorded
 * instants, not a uniform-dt assumption (under lag Sable packs extra substeps
 * into a tick and dt varies between ticks). Hostile-input hygiene, enforced at
 * decode so garbage never reaches game code: length cap, finite offsets inside
 * a sane window. Out-of-ORDER offsets are clamped monotonic rather than thrown
 * — a decode throw tears down the whole connection over a feel packet, which
 * is worse than one blurred sample (the sampler also guards its timeline at
 * the source). Channel magnitudes are clamped at ingress by the FfbPipeline
 * (a wire-legal value must never be able to overflow float arithmetic
 * downstream).
 */
public record FfbTelemetryPacket(double baseTimeS, float[] offsetsS, TelemetryFrame[] frames)
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
        buf.writeVarInt(pkt.frames.length);
        for (int i = 0; i < pkt.frames.length; i++) {
            buf.writeFloat(pkt.offsetsS[i]);
            TelemetryFrame f = pkt.frames[i];
            buf.writeFloat(f.satNm());
            buf.writeFloat(f.textureNm());
            buf.writeFloat(f.speedMS());
            buf.writeFloat(f.slip());
            buf.writeFloat(f.mu());
            buf.writeFloat(f.driveRpm());
        }
    }

    private static FfbTelemetryPacket read(RegistryFriendlyByteBuf buf) {
        double base = buf.readDouble();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_SAMPLES) {
            throw new DecoderException("telemetry sample count out of range: " + n);
        }
        float[] offsets = new float[n];
        TelemetryFrame[] frames = new TelemetryFrame[n];
        float prev = 0f;
        for (int i = 0; i < n; i++) {
            float offset = buf.readFloat();
            if (!Float.isFinite(offset) || offset < 0f || offset > MAX_OFFSET_S) {
                throw new DecoderException("telemetry offset not sane at " + i);
            }
            // Tolerate mis-ordering (clamp) — a throw here drops the connection.
            offsets[i] = Math.max(offset, prev);
            prev = offsets[i];
            frames[i] = new TelemetryFrame(buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
        return new FfbTelemetryPacket(base, offsets, frames);
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
