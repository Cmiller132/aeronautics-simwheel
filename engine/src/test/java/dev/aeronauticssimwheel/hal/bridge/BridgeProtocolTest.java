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
                new State(11, -123.4f, 456.7f, 0b1011,
                        BridgeProtocol.FLAG_CONNECTED | BridgeProtocol.FLAG_ARMED, 42),
                new Hello(12, 9.0f, 1080f, "MOZA R9"));
        for (Frame f : frames) {
            Optional<Frame> back = BridgeProtocol.decode(BridgeProtocol.encode(f));
            assertEquals(Optional.of(f), back, f.getClass().getSimpleName());
        }
    }

    @Test
    void v2GoldenVectors() {
        // Byte-for-byte pins shared with sidecar/src/protocol.rs — the two
        // sides must agree on these exact layouts (little-endian, version 2).
        ByteBuffer torque = BridgeProtocol.encode(new Torque(0x01020304, 1.5f, 2.5f, 100));
        assertArrayHex(torque,
                "41 57 46 42" // magic "AWFB"
                        + " 02 01"         // version 2, TYPE_TORQUE
                        + " 04 03 02 01"   // sequence LE
                        + " 00 00 C0 3F"   // 1.5f
                        + " 00 00 20 40"   // 2.5f
                        + " 64 00");       // watchdog 100 LE

        ByteBuffer hello = BridgeProtocol.encode(new Hello(1, 9.0f, 1080f, "R9"));
        assertArrayHex(hello,
                "41 57 46 42"
                        + " 02 11"         // version 2, TYPE_HELLO (17)
                        + " 01 00 00 00"
                        + " 00 00 10 41"   // 9.0f
                        + " 00 00 87 44"   // 1080.0f
                        + " 02 52 39");    // len 2, "R9"

        ByteBuffer state = BridgeProtocol.encode(new State(2, 90.0f, -45.0f, 0b101,
                BridgeProtocol.FLAG_CONNECTED | BridgeProtocol.FLAG_ARMED, 0x51D3CA12));
        assertArrayHex(state,
                "41 57 46 42"
                        + " 02 10"         // version 2, TYPE_STATE (16)
                        + " 02 00 00 00"
                        + " 00 00 B4 42"   // 90.0f
                        + " 00 00 34 C2"   // -45.0f
                        + " 05 00 00 00"   // buttons
                        + " 09"            // flags: CONNECTED|ARMED
                        + " 12 CA D3 51"); // device id hash LE
    }

    private static void assertArrayHex(ByteBuffer buf, String hex) {
        byte[] expected = new byte[hex.replace(" ", "").length() / 2];
        String clean = hex.replace(" ", "");
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        byte[] actual = new byte[buf.remaining()];
        buf.duplicate().get(actual);
        assertEquals(java.util.HexFormat.ofDelimiter(" ").formatHex(expected),
                java.util.HexFormat.ofDelimiter(" ").formatHex(actual));
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
        Hello longName = new Hello(1, 9f, 1080f, "x".repeat(200));
        Optional<Frame> back = BridgeProtocol.decode(BridgeProtocol.encode(longName));
        assertTrue(back.isPresent());
        assertEquals(32, ((Hello) back.get()).deviceName().length());
    }
}
