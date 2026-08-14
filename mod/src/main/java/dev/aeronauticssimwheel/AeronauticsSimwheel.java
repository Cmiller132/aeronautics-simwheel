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

    public AeronauticsSimwheel(IEventBus modEventBus) {
        SimWheelRegistry.init(modEventBus);
        modEventBus.addListener(this::onRegisterGameTests);
        modEventBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1")
                .playToServer(SimWheelInputPacket.TYPE, SimWheelInputPacket.CODEC,
                        SimWheelInputPacket::handle)
                .playToClient(FfbTelemetryPacket.TYPE, FfbTelemetryPacket.CODEC,
                        FfbTelemetryPacket::handle)
                .playToClient(FfbEventPacket.TYPE, FfbEventPacket.CODEC,
                        FfbEventPacket::handle));
        if (FMLEnvironment.dist.isClient()) {
            SimWheelClient.init(modEventBus);
        }
        LOGGER.info("Aeronautics SimWheel 0.2.0 loaded");
    }

    private void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(SimWheelGameTests.class);
    }
}
