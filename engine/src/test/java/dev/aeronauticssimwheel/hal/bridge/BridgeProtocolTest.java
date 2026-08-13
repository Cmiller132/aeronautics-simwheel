package dev.aeronauticssimwheel.hal.bridge;

import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Frame;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Hello;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Panic;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Start;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.State;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Stop;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol.Torque;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeProtocolTest {

    @Test
    void everyFrameTypeRoundTrips() {
        List<Frame> frames = List.of(
                new Torque(7, -1.75f, 2.5f, 100),
                new Panic(8),
                new Start(9),
                new Stop(10),
                new State(11, -123.4f, 456.7f, 0b1011, BridgeProtocol.FLAG_CONNECTED, 42),
                new Hello(12, 9.0f, "MOZA R9"));
        for (Frame f : frames) {
            Optional<Frame> back = BridgeProtocol.decode(BridgeProtocol.encode(f));
            assertEquals(Optional.of(f), back, f.getClass().getSimpleName());
        }
    }

    @Test
    void malformedInputNeverThrows() {
        Random rng = new Random(1234);
        for (int i = 0; i < 10_000; i++) {
            byte[] junk = new byte[rng.nextInt(BridgeProtocol.MAX_FRAME_BYTES + 1)];
            rng.nextBytes(junk);
            BridgeProtocol.decode(ByteBuffer.wrap(junk)); // must not throw
        }
    }

    @Test
    void truncatedRealFramesAreRejectedNotCrashed() {
        ByteBuffer full = BridgeProtocol.encode(new State(1, 1f, 2f, 3, 1, 4));
        int len = full.remaining();
        for (int cut = 0; cut < len; cut++) {
            ByteBuffer truncated = full.duplicate();
            truncated.limit(cut);
            assertEquals(Optional.empty(), BridgeProtocol.decode(truncated), "cut=" + cut);
        }
    }

    @Test
    void wrongMagicAndVersionAreRejected() {
        ByteBuffer good = BridgeProtocol.encode(new Panic(1));
        ByteBuffer badMagic = good.duplicate();
        badMagic.put(0, (byte) 'X');
        assertEquals(Optional.empty(), BridgeProtocol.decode(badMagic));

        ByteBuffer badVersion = BridgeProtocol.encode(new Panic(1));
        badVersion.put(4, (byte) 99);
        assertEquals(Optional.empty(), BridgeProtocol.decode(badVersion));
    }

    @Test
    void helloNameIsLengthCapped() {
        Hello longName = new Hello(1, 9f, "x".repeat(200));
        Optional<Frame> back = BridgeProtocol.decode(BridgeProtocol.encode(longName));
        assertTrue(back.isPresent());
        assertEquals(32, ((Hello) back.get()).deviceName().length());
    }
}
