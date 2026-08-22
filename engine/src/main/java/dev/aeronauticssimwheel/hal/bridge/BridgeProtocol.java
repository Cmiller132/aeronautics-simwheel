package dev.aeronauticssimwheel.hal.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Wire protocol v1 between the mod and the native FFB bridge sidecar
 * (DESIGN.md §6.6). Backend-agnostic: the shipping Rust sidecar speaks it
 * over DirectInput on Windows and evdev on Linux ({@code sidecar/src/
 * protocol.rs} is the byte-for-byte mirror, golden-vector-pinned on both
 * sides); any future backend (SDL3, vendor SDK) speaks the same frames.
 *
 * <p>UDP, localhost, little-endian. Header: magic "AWFB", u8 version,
 * u8 frame type, u32 sequence. Malformed input never throws out of
 * {@link #decode} — it returns empty, because a hostile/buggy peer must not
 * be able to take down the FFB loop.
 */
public final class BridgeProtocol {

    public static final int MAGIC = 'A' | 'W' << 8 | 'F' << 16 | 'B' << 24;
    /** v2: HELLO carries range_deg; STATE gains FLAG_ARMED. Hard-matched. */
    public static final byte VERSION = 2;
    public static final int DEFAULT_PORT = 46910;
    public static final int MAX_FRAME_BYTES = 64;

    // Frame types. Mod → bridge:
    public static final byte TYPE_TORQUE = 1;
    public static final byte TYPE_PANIC = 2;
    public static final byte TYPE_START = 3;
    public static final byte TYPE_STOP = 4;
    // Bridge → mod:
    public static final byte TYPE_STATE = 16;
    public static final byte TYPE_HELLO = 17;

    // STATE flag bits
    public static final int FLAG_CONNECTED = 1;
    public static final int FLAG_FAULT = 2;
    public static final int FLAG_HANDS_OFF = 4;
    /** The bridge would currently accept TORQUE (started, no latch, socket clean). */
    public static final int FLAG_ARMED = 8;

    private static final int HEADER_BYTES = 4 + 1 + 1 + 4;

    public sealed interface Frame permits Torque, Panic, Start, Stop, State, Hello {
        int sequence();
    }

    /** Mod → bridge, at the device write rate. */
    public record Torque(int sequence, float torqueNm, float maxTorqueCapNm,
                         int watchdogMs) implements Frame {
    }

    /** Mod → bridge: immediate zero + stop, latched until START. */
    public record Panic(int sequence) implements Frame {
    }

    public record Start(int sequence) implements Frame {
    }

    public record Stop(int sequence) implements Frame {
    }

    /** Bridge → mod, ~250 Hz. */
    public record State(int sequence, float steeringDeg, float steeringVelDegPerS,
                        int buttons, int flags, int deviceIdHash) implements Frame {
    }

    /** Bridge → mod on connect/START. rangeDeg = the sidecar's --range setting,
     *  so both ends scale the reported angle identically (v2). */
    public record Hello(int sequence, float ratedTorqueNm, float rangeDeg,
                        String deviceName) implements Frame {
    }

    private BridgeProtocol() {
    }

    public static ByteBuffer encode(Frame frame) {
        ByteBuffer buf = ByteBuffer.allocate(MAX_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(MAGIC).put(VERSION).put(typeOf(frame)).putInt(frame.sequence());
        switch (frame) {
            case Torque t -> {
                buf.putFloat(t.torqueNm());
                buf.putFloat(t.maxTorqueCapNm());
                buf.putShort((short) t.watchdogMs());
            }
            case State s -> {
                buf.putFloat(s.steeringDeg());
                buf.putFloat(s.steeringVelDegPerS());
                buf.putInt(s.buttons());
                buf.put((byte) s.flags());
                buf.putInt(s.deviceIdHash());
            }
            case Hello h -> {
                buf.putFloat(h.ratedTorqueNm());
                buf.putFloat(h.rangeDeg());
                byte[] name = h.deviceName().getBytes(StandardCharsets.UTF_8);
                int len = Math.min(name.length, 32);
                buf.put((byte) len);
                buf.put(name, 0, len);
            }
            case Panic p -> {
            }
            case Start s -> {
            }
            case Stop s -> {
            }
        }
        return buf.flip();
    }

    /** Empty on anything malformed — never throws on bad peer data. */
    public static Optional<Frame> decode(ByteBuffer buf) {
        try {
            buf = buf.order(ByteOrder.LITTLE_ENDIAN);
            if (buf.remaining() < HEADER_BYTES || buf.getInt() != MAGIC || buf.get() != VERSION) {
                return Optional.empty();
            }
            byte type = buf.get();
            int seq = buf.getInt();
            return switch (type) {
                case TYPE_TORQUE -> Optional.of(new Torque(seq, buf.getFloat(), buf.getFloat(),
                        Short.toUnsignedInt(buf.getShort())));
                case TYPE_PANIC -> Optional.of(new Panic(seq));
                case TYPE_START -> Optional.of(new Start(seq));
                case TYPE_STOP -> Optional.of(new Stop(seq));
                case TYPE_STATE -> Optional.of(new State(seq, buf.getFloat(), buf.getFloat(),
                        buf.getInt(), Byte.toUnsignedInt(buf.get()), buf.getInt()));
                case TYPE_HELLO -> {
                    float rated = buf.getFloat();
                    float range = buf.getFloat();
                    int len = Byte.toUnsignedInt(buf.get());
                    if (len > buf.remaining() || len > 32) {
                        yield Optional.empty();
                    }
                    byte[] name = new byte[len];
                    buf.get(name);
                    yield Optional.of(new Hello(seq, rated, range,
                            new String(name, StandardCharsets.UTF_8)));
                }
                default -> Optional.empty();
            };
        } catch (RuntimeException e) { // underflow etc. on truncated frames
            return Optional.empty();
        }
    }

    private static byte typeOf(Frame frame) {
        return switch (frame) {
            case Torque t -> TYPE_TORQUE;
            case Panic p -> TYPE_PANIC;
            case Start s -> TYPE_START;
            case Stop s -> TYPE_STOP;
            case State s -> TYPE_STATE;
            case Hello h -> TYPE_HELLO;
        };
    }
}
