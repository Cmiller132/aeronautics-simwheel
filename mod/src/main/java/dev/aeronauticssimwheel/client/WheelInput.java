package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.hal.WheelAdapter;
import dev.aeronauticssimwheel.hal.WheelDevice;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol;
import dev.aeronauticssimwheel.hal.bridge.BridgeWheelDevice;
import dev.aeronauticssimwheel.hal.glfw.GlfwWheelDevice;
import org.lwjgl.glfw.GLFW;

import java.net.InetSocketAddress;

/**
 * Owns the active input device + adapter, in priority order:
 *
 * <ol>
 *   <li><b>Demo</b> (K key) — sine sweep, hardware-free testing.</li>
 *   <li><b>Bridge</b> — when the native sidecar is running, its STATE stream
 *       is the input AND the torque path (one device, one truth; on Windows
 *       this replaces GLFW so FFB works out of the box). Auto-detected: a
 *       cheap START probe goes out every couple of seconds until the sidecar
 *       answers; if the sidecar dies mid-session the stale stream drops us
 *       back to GLFW automatically.</li>
 *   <li><b>GLFW</b> — any joystick Minecraft's own LWJGL can see (input only).</li>
 *   <li>None — the link layer still drives via keyboard (W/S/A/D).</li>
 * </ol>
 */
public final class WheelInput {

    private static final int RESCAN_TICKS = 20;
    private static final int BRIDGE_PROBE_TICKS = 40; // ~2 s between probes
    private static final int BRIDGE_KEEPALIVE_TICKS = 100; // ~5 s, under the 10 s timeout

    private GlfwWheelDevice glfw;
    private DemoWheelDevice demo;
    private BridgeWheelDevice bridge;
    private boolean bridgeActive;
    private WheelAdapter adapter;
    private int rescanCooldown;
    private int bridgeProbeCooldown;
    private int bridgeKeepaliveCooldown;

    /**
     * Client tick (render thread — GLFW calls are legal here).
     *
     * @param ffbStreaming true while the FFB loop is writing torque frames —
     *                     they keep the sidecar session alive on their own;
     *                     when idle, we send a periodic keepalive instead so
     *                     the sidecar's client-silence timeout doesn't flap
     *                     the connection.
     */
    public void tick(boolean ffbStreaming) {
        if (demo == null) {
            tickBridge(ffbStreaming);
        }
        if (demo == null && !bridgeActive
                && (glfw == null || !glfw.present()) && --rescanCooldown <= 0) {
            rescanCooldown = RESCAN_TICKS;
            scan();
        }
        if (demo == null && !bridgeActive && glfw != null && glfw.present()) {
            glfw.poll();
        }
        if (demo != null) {
            demo.tick();
        }
        if (adapter != null) {
            adapter.poll(1 / 20.0);
        }
    }

    /** Sidecar auto-detection; prefers the bridge over GLFW while it's alive. */
    private void tickBridge(boolean ffbStreaming) {
        if (bridge == null) {
            bridge = new BridgeWheelDevice(
                    new InetSocketAddress("127.0.0.1", BridgeProtocol.DEFAULT_PORT),
                    BridgeWheelDevice.Config.defaults());
        }
        boolean alive = bridge.connected();
        if (!alive && --bridgeProbeCooldown <= 0) {
            bridgeProbeCooldown = BRIDGE_PROBE_TICKS;
            bridge.ffbStart(); // harmless UDP probe; the sidecar answers HELLO+STATE
        }
        if (alive && !ffbStreaming && --bridgeKeepaliveCooldown <= 0) {
            bridgeKeepaliveCooldown = BRIDGE_KEEPALIVE_TICKS;
            bridge.ffbStart(); // idle keepalive (never sent while torque streams)
        }
        if (alive != bridgeActive) {
            bridgeActive = alive;
            if (alive) {
                adapter = WheelAdapter.autoBind(bridge);
                AeronauticsSimwheel.LOGGER.info("SimWheel input: FFB bridge connected ({})",
                        bridge.id());
            } else {
                adapter = glfw != null && glfw.present() ? WheelAdapter.autoBind(glfw) : null;
                AeronauticsSimwheel.LOGGER.warn(
                        "SimWheel input: FFB bridge lost, falling back to {}",
                        adapter != null ? glfw.id() : "keyboard");
            }
        }
    }

    private void scan() {
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid)) {
                glfw = new GlfwWheelDevice(jid);
                adapter = WheelAdapter.autoBind(glfw);
                AeronauticsSimwheel.LOGGER.info("SimWheel input device: {}", glfw.id());
                return;
            }
        }
    }

    /** Demo keybind: swap the sine-sweep device in/out. */
    public void toggleDemo() {
        if (demo == null) {
            demo = new DemoWheelDevice();
            adapter = WheelAdapter.autoBind(demo);
            AeronauticsSimwheel.LOGGER.info("SimWheel demo input ON");
        } else {
            demo = null;
            adapter = null;
            bridgeActive = false; // re-detect naturally next tick
            AeronauticsSimwheel.LOGGER.info("SimWheel demo input OFF");
        }
    }

    public boolean hasInput() {
        return adapter != null;
    }

    public WheelAdapter adapter() {
        return adapter;
    }

    /** The device torque writes should go to (null when nothing is attached). */
    public WheelDevice activeDevice() {
        if (demo != null) {
            return demo;
        }
        return bridgeActive ? bridge : glfw;
    }

    public String label() {
        if (demo != null) {
            return demo.id();
        }
        if (bridgeActive) {
            return bridge.id();
        }
        if (glfw != null && glfw.present()) {
            return glfw.id();
        }
        return "keyboard (W/S/A/D; K = demo wheel)";
    }
}
