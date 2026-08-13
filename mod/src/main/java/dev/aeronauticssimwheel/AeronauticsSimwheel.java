package dev.aeronauticssimwheel;

import com.mojang.logging.LogUtils;
import dev.aeronauticssimwheel.client.SimWheelClient;
import dev.aeronauticssimwheel.gametest.SimWheelGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * Mod entry point. The mod layer is a thin adapter (DESIGN.md §4): all input/FFB
 * logic lives in the pure-JVM engine module (hal/ + ffb/); the client/ package
 * wires it to the game. MVP scope (Phase 1): wheel input → latch-mode
 * SteeringWheelPacket injection + client-only FFB feel, no server addon yet.
 */
@Mod(AeronauticsSimwheel.MOD_ID)
public final class AeronauticsSimwheel {

    public static final String MOD_ID = "aeronautics_simwheel";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsSimwheel(IEventBus modEventBus) {
        modEventBus.addListener(this::onRegisterGameTests);
        if (FMLEnvironment.dist.isClient()) {
            SimWheelClient.init(modEventBus);
        }
        LOGGER.info("Aeronautics SimWheel 0.1.0 loaded");
    }

    private void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(SimWheelGameTests.class);
    }
}
