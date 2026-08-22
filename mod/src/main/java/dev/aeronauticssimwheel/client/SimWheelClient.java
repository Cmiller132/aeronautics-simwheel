package dev.aeronauticssimwheel.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aeronauticssimwheel.AeronauticsSimwheel;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client bootstrap: keybinds, tick wiring, HUD layer, FFB thread lifecycle. */
public final class SimWheelClient {

    public static final KeyMapping KEY_ENGAGE = new KeyMapping(
            "key.aeronautics_simwheel.engage", InputConstants.Type.KEYSYM,
            InputConstants.KEY_J, "key.categories.aeronautics_simwheel");
    public static final KeyMapping KEY_DEMO = new KeyMapping(
            "key.aeronautics_simwheel.demo", InputConstants.Type.KEYSYM,
            InputConstants.KEY_K, "key.categories.aeronautics_simwheel");
    /** Commissioning: cycle the FFB test signal (off → sweep → step → off). */
    public static final KeyMapping KEY_TEST_SIGNAL = new KeyMapping(
            "key.aeronautics_simwheel.test_signal", InputConstants.Type.KEYSYM,
            InputConstants.KEY_L, "key.categories.aeronautics_simwheel");

    private static WheelInput input;
    private static SimWheelLink link;
    private static FfbController ffb;
    private static FeelConfig feel;

    /** -Dsimwheel.selftest=N: log input/FFB state and quit after N client ticks. */
    private static final int SELFTEST_TICKS = Integer.getInteger("simwheel.selftest", 0);
    private static int tickCount;

    private SimWheelClient() {
    }

    /** The FFB pipeline — packet handlers feed telemetry/events through this. */
    public static FfbController ffb() {
        return ffb;
    }

    public static void init(IEventBus modBus) {
        input = new WheelInput();
        link = new SimWheelLink();
        ffb = new FfbController();
        feel = new FeelConfig(ffb);
        feel.init();

        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(KEY_ENGAGE);
            e.register(KEY_DEMO);
            e.register(KEY_TEST_SIGNAL);
        });
        modBus.addListener((RegisterGuiLayersEvent e) -> e.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(AeronauticsSimwheel.MOD_ID, "hud"),
                new SimWheelHud(input, link, ffb, feel)));

        NeoForge.EVENT_BUS.addListener(SimWheelClient::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (link.isEngaged()) {
                link.disengage(mc);
            }
        });

        ffb.start();
        // The client README promises this: never leave the loop thread's device
        // attached on JVM exit (the daemon thread would otherwise just die; the
        // sidecar watchdog is the backstop, not the plan).
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> ffb.stopAndJoin(200), "simwheel-ffb-shutdown"));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        feel.tick();
        input.setBindings(feel.bindings());
        input.tick(link.isEngaged());

        while (KEY_DEMO.consumeClick()) {
            input.toggleDemo();
        }
        while (KEY_ENGAGE.consumeClick()) {
            link.toggleEngage(mc, input);
        }
        while (KEY_TEST_SIGNAL.consumeClick()) {
            var mode = ffb.cycleTestSignal();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "SimWheel test signal: " + mode), true);
            }
        }

        if (mc.level != null) {
            link.tick(mc, input);
        }
        ffb.updateFromGame(mc, input, link);

        if (SELFTEST_TICKS > 0 && ++tickCount >= SELFTEST_TICKS) {
            AeronauticsSimwheel.LOGGER.info(
                    "SimWheel selftest: ticks={} device='{}' ffbState={} ffbThreadAlive={}",
                    tickCount, input.label(), ffb.safetyState(),
                    Thread.getAllStackTraces().keySet().stream()
                            .anyMatch(t -> "simwheel-ffb".equals(t.getName())));
            mc.stop();
        }
    }
}
