package dev.aeronauticssimwheel.hal.bridge;

import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.WheelDevice;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Frame;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Hello;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.State;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link WheelDevice} backed by the native FFB bridge sidecar over the
 * DESIGN.md §6.6 UDP protocol. Input (steering angle/velocity, buttons) comes
 * from the bridge's STATE stream — on Windows this replaces GLFW entirely so
 * there is one device and one truth. Torque writes go out as TORQUE frames
 * carrying our clamp and the bridge-side watchdog interval, so the base zeroes
 * itself even if this JVM dies mid-stream.
 *
 * <p>Thread-safe: a daemon receiver thread owns the socket reads; the FFB
 * thread calls {@link #ffbUpdateTorque}; the game thread reads axes.
 */
public final class BridgeWheelDevice implements WheelDevice, AutoCloseable {

    public record Config(float rotationRangeDeg, float maxTorqueCapNm, int bridgeWatchdogMs,
                         long staleAfterNanos) {
        /**
         * Fail closed on nonsense: a negative cap would invert Math.min/max
         * clamping into a CONSTANT full-cap command, and an out-of-range
         * watchdog defeats the bridge's dead-man behavior (adversarial
         * finding — validated here, at the single choke point).
         */
        public Config {
            if (!Float.isFinite(rotationRangeDeg) || rotationRangeDeg <= 0) {
                throw new IllegalArgumentException("rotationRangeDeg must be finite/positive");
            }
            if (!Float.isFinite(maxTorqueCapNm) || maxTorqueCapNm < 0 || maxTorqueCapNm > 25) {
                throw new IllegalArgumentException("maxTorqueCapNm must be within 0..25");
            }
            if (bridgeWatchdogMs < 10 || bridgeWatchdogMs > 1000) {
                throw new IllegalArgumentException("bridgeWatchdogMs must be within 10..1000");
            }
            if (staleAfterNanos <= 0) {
                throw new IllegalArgumentException("staleAfterNanos must be positive");
            }
        }

        /** ±540° default range; 2.5 Nm cap mirrors the SafetyChain default. */
        public static Config defaults() {
            return new Config(1080f, 2.5f, 100, 300_000_000L);
        }
    }

    private final DatagramChannel channel;
    private final Config cfg;
    private final Thread receiver;
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile float steeringDeg;
    private volatile float steeringVelDegPerS;
    private volatile int buttons;
    private volatile int flags;
    private volatile long lastStateNanos;
    private volatile float ratedTorqueNm = 9f; // MOZA R9 until HELLO says otherwise
    private volatile String deviceName = "bridge (no hello yet)";
    private volatile boolean panicLatched;

    public BridgeWheelDevice(InetSocketAddress bridgeAddress, Config cfg) {
        this.cfg = cfg;
        try {
            this.channel = DatagramChannel.open();
            this.channel.connect(bridgeAddress);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open bridge socket", e);
        }
        this.receiver = new Thread(this::receiveLoop, "simwheel-bridge-rx");
        this.receiver.setDaemon(true);
        this.receiver.start();
    }

    private void receiveLoop() {
        ByteBuffer buf = ByteBuffer.allocate(BridgeProtocol.MAX_FRAME_BYTES);
        while (channel.isOpen()) {
            try {
                buf.clear();
                channel.receive(buf);
                buf.flip();
                Optional<Frame> frame = BridgeProtocol.decode(buf);
                if (frame.isPresent()) {
                    switch (frame.get()) {
                        case State s -> {
                            steeringDeg = s.steeringDeg();
                            steeringVelDegPerS = s.steeringVelDegPerS();
                            buttons = s.buttons();
                            flags = s.flags();
                            lastStateNanos = System.nanoTime();
                        }
                        case Hello h -> {
                            ratedTorqueNm = h.ratedTorqueNm();
                            deviceName = h.deviceName();
                        }
                        default -> {
                        }
                    }
                }
            } catch (PortUnreachableException e) {
                // bridge not up yet — staleness handles it; keep listening
            } catch (IOException e) {
                if (channel.isOpen()) {
                    // transient; staleness covers a dead peer
                }
            } catch (RuntimeException e) {
                // decode is total, but never let anything kill the rx thread
            }
        }
    }

    private void send(Frame frame) {
        try {
            channel.write(BridgeProtocol.encode(frame));
        } catch (IOException e) {
            // stale-input handling covers a dead bridge; nothing useful to throw
        }
    }

    /** True while STATE frames are arriving within the staleness window. */
    public boolean connected() {
        return (System.nanoTime() - lastStateNanos) < cfg.staleAfterNanos()
                && lastStateNanos != 0;
    }

    public float steeringDeg() {
        return steeringDeg;
    }

    public float steeringVelDegPerS() {
        return steeringVelDegPerS;
    }

    public float ratedTorqueNm() {
        return ratedTorqueNm;
    }

    @Override
    public String id() {
        return "bridge/" + deviceName;
    }

    /** Axis 0 = steering normalized to the configured rotation range. */
    @Override
    public float axis(int index) {
        if (index != 0) {
            return 0f;
        }
        float half = cfg.rotationRangeDeg() / 2f;
        return Math.max(-1f, Math.min(1f, steeringDeg / half));
    }

    @Override
    public int axisCount() {
        return 1;
    }

    @Override
    public boolean button(int index) {
        return index >= 0 && index < 32 && (buttons & (1 << index)) != 0;
    }

    @Override
    public EnumSet<Capability> capabilities() {
        return EnumSet.of(Capability.FFB_CONSTANT, Capability.FFB_SPRING, Capability.FFB_DAMPER);
    }

    @Override
    public void ffbStart() {
        panicLatched = false;
        send(new BridgeProtocol.Start(sequence.incrementAndGet()));
    }

    /**
     * @param normalized torque in [-1, 1] of the device's rated torque —
     *                   converted to Nm on the wire, clamped by our cap and,
     *                   independently, by the bridge programming the base.
     */
    @Override
    public void ffbUpdateTorque(float normalized) {
        if (panicLatched || !Float.isFinite(normalized)) {
            return; // ±Inf would clamp into a constant full-cap command
        }
        float nm = normalized * ratedTorqueNm;
        float cap = cfg.maxTorqueCapNm();
        nm = Math.max(-cap, Math.min(cap, nm));
        send(new BridgeProtocol.Torque(sequence.incrementAndGet(), nm, cap, cfg.bridgeWatchdogMs()));
    }

    @Override
    public void ffbStop() {
        send(new BridgeProtocol.Stop(sequence.incrementAndGet()));
    }

    @Override
    public void panic() {
        panicLatched = true;
        send(new BridgeProtocol.Panic(sequence.incrementAndGet()));
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
