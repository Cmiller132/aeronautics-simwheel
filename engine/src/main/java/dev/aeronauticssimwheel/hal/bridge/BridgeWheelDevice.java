package dev.aeronauticssimwheel.hal.bridge;

import dev.aeronauticssimwheel.hal.Capability;
import dev.aeronauticssimwheel.hal.FaultingDevice;
import dev.aeronauticssimwheel.hal.HardwareAngleSource;
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
public final class BridgeWheelDevice
        implements WheelDevice, HardwareAngleSource, FaultingDevice, AutoCloseable {

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
    /** The sidecar's --range from HELLO (v2) — wins over the local config so
     *  both ends scale the angle identically; 0 until a HELLO arrives. */
    private volatile float helloRangeDeg;
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
                            // Peer data: a NaN/absurd rated torque must never
                            // enter any arithmetic — keep the previous value.
                            float rated = h.ratedTorqueNm();
                            if (Float.isFinite(rated) && rated >= 0.5f && rated <= 30f) {
                                ratedTorqueNm = rated;
                            }
                            float range = h.rangeDeg();
                            if (Float.isFinite(range) && range >= 90f && range <= 2880f) {
                                helloRangeDeg = range;
                            }
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

    /** Rated torque from HELLO — informational (HUD); torque math is Nm end-to-end. */
    public float ratedTorqueNm() {
        return ratedTorqueNm;
    }

    // --- HardwareAngleSource: the STATE stream IS the physical wheel at ~250 Hz ---

    @Override
    public boolean hardwareAngleValid() {
        return connected();
    }

    @Override
    public float hardwareDeg() {
        return steeringDeg;
    }

    @Override
    public float hardwareVelDegPerS() {
        return steeringVelDegPerS;
    }

    /** Sidecar-reported output fault (STATE FLAG_FAULT), only while the stream is live. */
    @Override
    public boolean deviceFault() {
        return connected() && (flags & BridgeProtocol.FLAG_FAULT) != 0;
    }

    /**
     * True while the bridge session would accept TORQUE (STATE FLAG_ARMED, v2).
     * After an epoch disarm the STATE stream keeps flowing while TORQUE is
     * silently discarded — this makes that state observable so the input layer
     * can re-arm with a START instead of waiting out a silence timeout.
     */
    public boolean armed() {
        return connected() && (flags & BridgeProtocol.FLAG_ARMED) != 0;
    }

    @Override
    public String id() {
        return "bridge/" + deviceName;
    }

    /** Axis 0 = steering normalized to the rotation range (HELLO's when known). */
    @Override
    public float axis(int index) {
        if (index != 0) {
            return 0f;
        }
        float range = helloRangeDeg > 0 ? helloRangeDeg : cfg.rotationRangeDeg();
        float half = range / 2f;
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
     * @param torqueNm torque in Nm at the rim — clamped by our cap here and,
     *                 independently, by the bridge programming the base.
     */
    @Override
    public void ffbUpdateTorque(float torqueNm) {
        if (panicLatched || !Float.isFinite(torqueNm)) {
            return; // ±Inf would clamp into a constant full-cap command
        }
        float cap = cfg.maxTorqueCapNm();
        float nm = Math.clamp(torqueNm, -cap, cap);
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
