package dev.aeronauticssimwheel.hal.bridge;

import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Frame;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Hello;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Panic;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Start;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.State;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Torque;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for the native sidecar: loopback UDP peer that records every
 * mod→bridge frame and can stream STATE/HELLO back. `silent` simulates a
 * crashed/hung bridge for watchdog tests.
 */
public final class FakeBridgeServer implements AutoCloseable {

    public final List<Torque> torques = new CopyOnWriteArrayList<>();
    public final List<Frame> control = new CopyOnWriteArrayList<>(); // START/STOP/PANIC
    public final AtomicBoolean silent = new AtomicBoolean(false);

    private final DatagramChannel channel;
    private final Thread thread;
    private final AtomicInteger seq = new AtomicInteger();
    private volatile SocketAddress client;
    private volatile float steeringDeg;
    private volatile float steeringVelDegPerS;

    public FakeBridgeServer() {
        try {
            channel = DatagramChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        thread = new Thread(this::loop, "fake-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    public InetSocketAddress address() {
        try {
            return (InetSocketAddress) channel.getLocalAddress();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void setSteering(float deg, float degPerS) {
        this.steeringDeg = deg;
        this.steeringVelDegPerS = degPerS;
    }

    /** Push one STATE frame to the connected client (if any, and not silent). */
    public void emitState() {
        SocketAddress to = client;
        if (to == null || silent.get()) {
            return;
        }
        send(new State(seq.incrementAndGet(), steeringDeg, steeringVelDegPerS,
                0, BridgeProtocol.FLAG_CONNECTED, 42), to);
    }

    private void loop() {
        ByteBuffer buf = ByteBuffer.allocate(BridgeProtocol.MAX_FRAME_BYTES);
        while (channel.isOpen()) {
            try {
                buf.clear();
                SocketAddress from = channel.receive(buf);
                if (from == null) {
                    continue;
                }
                client = from;
                buf.flip();
                BridgeProtocol.decode(buf).ifPresent(frame -> {
                    switch (frame) {
                        case Torque t -> torques.add(t);
                        case Start s -> {
                            control.add(s);
                            if (!silent.get()) {
                                send(new Hello(seq.incrementAndGet(), 9.0f, "Fake MOZA R9"), from);
                            }
                        }
                        case Panic p -> control.add(p);
                        default -> control.add(frame);
                    }
                });
            } catch (IOException e) {
                if (!channel.isOpen()) {
                    return;
                }
            }
        }
    }

    private void send(Frame frame, SocketAddress to) {
        try {
            channel.send(BridgeProtocol.encode(frame), to);
        } catch (IOException ignored) {
        }
    }

    public Torque lastTorque() {
        return torques.isEmpty() ? null : torques.get(torques.size() - 1);
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
