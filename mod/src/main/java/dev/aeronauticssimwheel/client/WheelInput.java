package dev.aeronauticssimwheel.client;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.hal.LogicalAxis;
import dev.aeronauticssimwheel.hal.WheelAdapter;
import dev.aeronauticssimwheel.hal.WheelDevice;
import dev.aeronauticssimwheel.hal.bridge.BridgeProtocol;
import dev.aeronauticssimwheel.hal.bridge.BridgeWheelDevice;
import dev.aeronauticssimwheel.hal.glfw.GlfwWheelDevice;
import org.lwjgl.glfw.GLFW;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the input devices + adapter. Devices COMPOSE — per-axis bindings, not
 * one-device-owns-everything (DESIGN.md §5.1):
 *
 * <ol>
 *   <li><b>Demo</b> (K key) — sine sweep, hardware-free testing.</li>
 *   <li><b>Bridge</b> — when the native sidecar is running, its STATE stream
 *       is steering input AND the torque path. The bridge carries no pedal
 *       axes, so while it is active a GLFW joystick provides the pedals into
 *       the same adapter — steering from the wheelbase, pedals from the pedal
 *       set, FFB out. Auto-detected via a cheap START probe.</li>
 *   <li><b>GLFW</b> — any joystick Minecraft's own LWJGL can see (input only;
 *       steering + pedals, no FFB).</li>
 *   <li>None — the link layer still drives via keyboard (W/S/A/D).</li>
 * </ol>
 *
 * <p>Several joysticks may be attached at once (a wheelbase the bridge owns
 * plus a standalone USB pedal set, e.g. Simagic P700 / MOZA SR-P). The scan
 * keeps them apart: the <b>pedal device</b> is chosen by the config's
 * {@code bindings.pedalDevice} substring, else a name containing "pedal",
 * else a pedal-shaped device (axes but no buttons); the <b>primary device</b>
 * (steering) is the first joystick that is NOT the pedal set. Pedal
 * axes/inversion come from the feel config's bindings section (hot-reloaded
 * via {@link #setBindings}); {@code AUTO} axes count from 0 on a dedicated
 * pedal set and from 1 (after steering) on a shared wheel device.
 */
public final class WheelInput {

    private static final int RESCAN_TICKS = 20;
    private static final int BRIDGE_PROBE_TICKS = 40; // ~2 s between probes
    private static final int BRIDGE_KEEPALIVE_TICKS = 100; // ~5 s, under the 10 s timeout

    private GlfwWheelDevice glfw;
    /** Dedicated pedal set when one is attached; distinct from {@link #glfw}. */
    private GlfwWheelDevice glfwPedals;
    private DemoWheelDevice demo;
    private BridgeWheelDevice bridge;
    private boolean bridgeActive;
    private WheelAdapter adapter;
    private InputBindings bindings = InputBindings.defaults();
    private int rescanCooldown;
    private int bridgeProbeCooldown;
    private int bridgeKeepaliveCooldown;
    private long lastPollNanos;

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
        // Rescan while anything is missing: no primary joystick, or no
        // dedicated pedal set yet (one may be plugged in after launch).
        if (demo == null && --rescanCooldown <= 0) {
            rescanCooldown = RESCAN_TICKS;
            if (glfw == null || !glfw.present()
                    || (glfwPedals == null || !glfwPedals.present())) {
                scan();
            }
        }
        if (demo == null) {
            if (glfw != null && glfw.present()) {
                glfw.poll();
            }
            if (glfwPedals != null && glfwPedals.present()) {
                glfwPedals.poll();
            }
        }
        if (demo != null) {
            demo.tick();
        }
        if (adapter != null) {
            long now = System.nanoTime();
            double dt = lastPollNanos == 0 ? 1 / 20.0
                    : Math.clamp((now - lastPollNanos) / 1e9, 1e-3, 0.25);
            lastPollNanos = now;
            adapter.poll(dt);
        }
    }

    /** Hot-reloaded pedal bindings from the feel config; rebinds on change. */
    public void setBindings(InputBindings next) {
        if (next != null && !next.equals(bindings)) {
            bindings = next;
            scan(); // pedalDevice may have changed which joystick is the pedal set
            rebindAdapter();
        }
    }

    /** Sidecar auto-detection; prefers the bridge for steering while it's alive. */
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
        // v2 FLAG_ARMED: an epoch disarm (device unplug/return) silently drops
        // TORQUE while STATE keeps flowing — re-arm promptly instead of
        // waiting out timeouts. The mod's SafetyChain FAULT remains the
        // deliberate re-engage gate; this is transport recovery.
        if (alive && ffbStreaming && !bridge.armed() && --bridgeProbeCooldown <= 0) {
            bridgeProbeCooldown = BRIDGE_PROBE_TICKS / 2;
            bridge.ffbStart();
        }
        if (alive != bridgeActive) {
            bridgeActive = alive;
            rebindAdapter();
            if (alive) {
                AeronauticsSimwheel.LOGGER.info(
                        "SimWheel input: FFB bridge connected ({}){}", bridge.id(),
                        pedalSource() != null
                                ? ", pedals from " + pedalSource().id() : ", pedals on keyboard");
            } else {
                AeronauticsSimwheel.LOGGER.warn(
                        "SimWheel input: FFB bridge lost, falling back to {}",
                        glfw != null && glfw.present() ? glfw.id() : "keyboard");
            }
        }
    }

    /**
     * Walk every attached joystick and sort out which is the pedal set and
     * which is the primary (steering-capable) device. Multiple devices are
     * normal here: the wheelbase enumerates in GLFW even while the bridge
     * owns it, and a standalone USB pedal set is its own joystick.
     */
    private void scan() {
        List<GlfwWheelDevice> present = new ArrayList<>();
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid)) {
                // Reuse live wrappers so processor state (smoothing) survives.
                if (glfw != null && glfw.present() && glfw.jid() == jid) {
                    present.add(glfw);
                } else if (glfwPedals != null && glfwPedals.present()
                        && glfwPedals.jid() == jid) {
                    present.add(glfwPedals);
                } else {
                    present.add(new GlfwWheelDevice(jid));
                }
            }
        }
        GlfwWheelDevice pedals = null;
        for (GlfwWheelDevice d : present) {
            if (bindings.matchesPedalDevice(d.id())) {
                pedals = d;
                break;
            }
        }
        if (pedals == null) {
            // Shape fallback: a dedicated pedal set is axes with no buttons.
            for (GlfwWheelDevice d : present) {
                if (d.axisCount() >= 2 && d.buttonCount() == 0) {
                    pedals = d;
                    break;
                }
            }
        }
        GlfwWheelDevice primary = null;
        for (GlfwWheelDevice d : present) {
            if (d != pedals) {
                primary = d;
                break;
            }
        }
        boolean changed = primary != glfw || pedals != glfwPedals;
        glfw = primary;
        glfwPedals = pedals;
        if (changed) {
            rebindAdapter();
            if (glfw != null || glfwPedals != null) {
                AeronauticsSimwheel.LOGGER.info("SimWheel input devices: primary {} pedals {}",
                        glfw != null ? glfw.id() : "-",
                        glfwPedals != null ? glfwPedals.id() : "-");
            }
        }
    }

    /** The joystick pedals should read from (dedicated set first), or null. */
    private GlfwWheelDevice pedalSource() {
        if (glfwPedals != null && glfwPedals.present()) {
            return glfwPedals;
        }
        return glfw != null && glfw.present() ? glfw : null;
    }

    /**
     * Build the adapter from what's attached: demo > bridge (steering/FFB)
     * composed with GLFW pedals > GLFW steering + pedals > pedals alone
     * (steering on keyboard) > nothing.
     */
    private void rebindAdapter() {
        if (demo != null) {
            adapter = WheelAdapter.autoBind(demo);
            return;
        }
        boolean glfwPresent = glfw != null && glfw.present();
        boolean pedalsPresent = glfwPedals != null && glfwPedals.present();
        if (bridgeActive) {
            adapter = WheelAdapter.autoBind(bridge);
            if (pedalSource() != null) {
                bindConfiguredPedals(pedalSource(), pedalSource() == glfwPedals);
            }
        } else if (glfwPresent) {
            adapter = WheelAdapter.autoBind(glfw);
            if (pedalsPresent) {
                bindConfiguredPedals(glfwPedals, true);
            } else {
                bindConfiguredPedals(glfw, false);
            }
        } else if (pedalsPresent) {
            // Pedals are the only hardware: never bind their axis 0 as
            // steering — leave STEERING unbound so keyboard A/D takes over.
            adapter = new WheelAdapter();
            bindConfiguredPedals(glfwPedals, true);
        } else {
            adapter = null;
        }
    }

    /**
     * @param dedicated true when {@code pedals} is its own pedal set (axes
     *                  count from 0); false when it shares the wheel device
     *                  (axis 0 is steering, pedals follow at 1/2/3).
     */
    private void bindConfiguredPedals(WheelDevice pedals, boolean dedicated) {
        int base = dedicated ? 0 : 1;
        WheelAdapter.bindPedal(adapter, LogicalAxis.THROTTLE, pedals,
                InputBindings.resolveAxis(bindings.throttleAxis(), base), bindings.throttleInvert());
        WheelAdapter.bindPedal(adapter, LogicalAxis.BRAKE, pedals,
                InputBindings.resolveAxis(bindings.brakeAxis(), base + 1), bindings.brakeInvert());
        WheelAdapter.bindPedal(adapter, LogicalAxis.CLUTCH, pedals,
                InputBindings.resolveAxis(bindings.clutchAxis(), base + 2), bindings.clutchInvert());
    }

    /** Demo keybind: swap the sine-sweep device in/out. */
    public void toggleDemo() {
        if (demo == null) {
            demo = new DemoWheelDevice();
            rebindAdapter();
            AeronauticsSimwheel.LOGGER.info("SimWheel demo input ON");
        } else {
            demo = null;
            bridgeActive = false; // re-detect naturally next tick
            rescanCooldown = 0;
            // Fall straight back to a still-present joystick — leaving the
            // adapter null here stranded input on the keyboard until a replug,
            // because the rescan guard only fires when no joystick is present.
            rebindAdapter();
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
        GlfwWheelDevice pedals = pedalSource();
        if (bridgeActive) {
            return bridge.id() + (pedals != null
                    ? " + pedals: " + pedals.id() : " (pedals: keyboard)");
        }
        if (glfw != null && glfw.present()) {
            return glfw.id() + (pedals != null && pedals != glfw
                    ? " + pedals: " + pedals.id() : "");
        }
        if (pedals != null) {
            return "keyboard steering + pedals: " + pedals.id();
        }
        return "keyboard (W/S/A/D; K = demo wheel)";
    }
}
