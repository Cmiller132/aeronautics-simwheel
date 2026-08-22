package dev.aeronauticssimwheel;

import com.mojang.logging.LogUtils;
import dev.aeronauticssimwheel.client.SimWheelClient;
import dev.aeronauticssimwheel.gametest.SimWheelGameTests;
import dev.aeronauticssimwheel.network.FfbEventPacket;
import dev.aeronauticssimwheel.network.FfbTelemetryPacket;
import dev.aeronauticssimwheel.network.SimWheelInputPacket;
import dev.aeronauticssimwheel.registry.SimWheelRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

/**
 * Mod entry point. The mod layer is a thin adapter (DESIGN.md §4): all input/FFB
 * logic lives in the pure-JVM engine module (hal/ + ffb/); the client/ package
 * wires it to the game. The Sim Steering Wheel block is the only control
 * surface — sim hardware talks to it and to nothing else.
 */
@Mod(AeronauticsSimwheel.MOD_ID)
public final class AeronauticsSimwheel {

    public static final String MOD_ID = "aeronautics_simwheel";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsSimwheel(IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
        SimWheelRegistry.init(modEventBus);
        modEventBus.addListener(this::onRegisterGameTests);
        // Registrar "2": the telemetry packet carries component frames now.
        modEventBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("2")
                .playToServer(SimWheelInputPacket.TYPE, SimWheelInputPacket.CODEC,
                        SimWheelInputPacket::handle)
                .playToClient(FfbTelemetryPacket.TYPE, FfbTelemetryPacket.CODEC,
                        FfbTelemetryPacket::handle)
                .playToClient(FfbEventPacket.TYPE, FfbEventPacket.CODEC,
                        FfbEventPacket::handle));
        modEventBus.addListener((FMLCommonSetupEvent e) -> HealthCheck.runAndLog());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                dev.aeronauticssimwheel.content.MountLinkerInteraction::onRightClickBlock);
        // Per-player server state must not outlive the player (leak hygiene).
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
                    SimWheelInputPacket.evictSender(e.getEntity().getUUID());
                    dev.aeronauticssimwheel.content.MountLinks.clearSession(e.getEntity().getUUID());
                });
        if (FMLEnvironment.dist.isClient()) {
            SimWheelClient.init(modEventBus);
        }
        LOGGER.info("Aeronautics SimWheel {} loaded",
                container.getModInfo().getVersion());
    }

    private void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(SimWheelGameTests.class);
    }
}
