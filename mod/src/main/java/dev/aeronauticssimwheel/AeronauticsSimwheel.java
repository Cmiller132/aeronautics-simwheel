package dev.aeronauticssimwheel;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Mod entry point. The mod layer is a thin adapter (DESIGN.md §4): all input/FFB
 * logic lives in the pure-JVM engine module (hal/ + ffb/); client/ and server/
 * packages wire it to the game. Phase 1 wiring (input routing, engagement,
 * injectors, HUD) lands here next.
 */
@Mod(AeronauticsSimwheel.MOD_ID)
public final class AeronauticsSimwheel {

    public static final String MOD_ID = "aeronautics_simwheel";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsSimwheel() {
        LOGGER.info("Aeronautics SimWheel {} loaded (engine skeleton)", "0.1.0");
    }
}
