package dev.aeronauticssimwheel.client;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.ffb.FfbTuning;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Hot-reloadable feel tuning (DESIGN.md §10.1): every {@link FfbTuning} gain in
 * one TOML file, written with commented defaults on first run and re-applied
 * live whenever the file's mtime changes (polled ~1 Hz on the client tick) —
 * edit, save, feel the difference on the next corner, no restart.
 *
 * <p>Safety: values pass through {@link FfbTuning#sanitized()} in the pipeline
 * (range-clamped, NaN-proof), a parse error keeps the last good tuning, and a
 * SafetyChain-parameter change re-ramps from zero — an edit can cause a dip,
 * never a spike.
 */
public final class FeelConfig {

    private static final String FILE_NAME = "aeronautics_simwheel-feel.toml";
    private static final int POLL_TICKS = 20;

    private final Path path;
    private final FfbController ffb;
    private FileTime lastModified;
    private int cooldown = POLL_TICKS;
    private String status = "feel: built-in defaults";

    public FeelConfig(FfbController ffb) {
        this(FMLPaths.CONFIGDIR.get().resolve(FILE_NAME), ffb);
    }

    FeelConfig(Path path, FfbController ffb) {
        this.path = path;
        this.ffb = ffb;
    }

    /** Client init: create the commented default file if missing, then apply it. */
    public void init() {
        if (!Files.exists(path)) {
            writeDefaultFile();
        }
        reload();
    }

    /** Client tick: cheap mtime poll; reload + apply on change. */
    public void tick() {
        if (--cooldown > 0) {
            return;
        }
        cooldown = POLL_TICKS;
        try {
            FileTime mtime = Files.getLastModifiedTime(path);
            if (!mtime.equals(lastModified)) {
                reload();
            }
        } catch (IOException e) {
            // File deleted mid-session: keep the last good tuning silently.
        }
    }

    /** One line for the HUD: what tuning is active and whether the file is healthy. */
    public String status() {
        return status;
    }

    private void reload() {
        try (CommentedFileConfig cfg = CommentedFileConfig.of(path)) {
            cfg.load();
            FfbTuning d = FfbTuning.defaults();
            FfbTuning t = new FfbTuning(
                    f(cfg, "masterGain", d.masterGain()),
                    f(cfg, "maxTorqueNm", d.maxTorqueNm()),
                    f(cfg, "slewNmPerSec", d.slewNmPerSec()),
                    f(cfg, "rampInSeconds", d.rampInSeconds()),
                    dbl(cfg, "watchdogSeconds", d.watchdogSeconds()),
                    f(cfg, "telemetryGain", d.telemetryGain()),
                    f(cfg, "damperNmPerDegPerS", d.damperNmPerDegPerS()),
                    f(cfg, "frictionNm", d.frictionNm()),
                    dbl(cfg, "frictionEpsDegPerS", d.frictionEpsDegPerS()),
                    f(cfg, "kneeFraction", d.kneeFraction()),
                    f(cfg, "kneeRatio", d.kneeRatio()),
                    f(cfg, "lockStiffnessNmPerDeg", d.lockStiffnessNmPerDeg()),
                    f(cfg, "lockDampingNmPerDegPerS", d.lockDampingNmPerDegPerS()));
            ffb.setTuning(t);
            lastModified = Files.getLastModifiedTime(path);
            status = "feel.toml active";
            AeronauticsSimwheel.LOGGER.info("SimWheel feel tuning applied from {}", path);
        } catch (Exception e) {
            status = "feel.toml ERROR (last good kept): " + e.getMessage();
            AeronauticsSimwheel.LOGGER.error("SimWheel feel config failed to load; "
                    + "keeping the previous tuning", e);
        }
    }

    private static float f(CommentedFileConfig cfg, String key, float fallback) {
        Object v = cfg.get(key);
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    private static double dbl(CommentedFileConfig cfg, String key, double fallback) {
        Object v = cfg.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private void writeDefaultFile() {
        FfbTuning d = FfbTuning.defaults();
        String content = """
                # Aeronautics SimWheel — feel tuning. Hot-reloaded: save, feel it next corner.
                # Every value is range-clamped on load; a broken file keeps the last good tuning.

                # ---- Safety chain (changes re-ramp from zero: an edit dips, never spikes) ----
                # Overall output gain (0..2).
                masterGain = %s
                # Hard torque clamp at the rim, Nm (0..10). Raise deliberately.
                maxTorqueNm = %s
                # Max torque change rate, Nm/s (1..500).
                slewNmPerSec = %s
                # Ramp-in after engaging, seconds (0.05..5).
                rampInSeconds = %s
                # Stale-input watchdog, seconds (0.02..1).
                watchdogSeconds = %s

                # ---- Feel (applied live) ----
                # Scale on the game's tire-force telemetry (0..4). The main strength knob.
                telemetryGain = %s
                # Baseline damper, Nm per deg/s (0..0.05). Hides link quantization noise.
                damperNmPerDegPerS = %s
                # Baseline friction, Nm (0..1). The wheel never feels dead.
                frictionNm = %s
                # Friction linear core width, deg/s (0.5..50) — larger = less chatter at rest.
                frictionEpsDegPerS = %s
                # Soft-knee compressor: knee at this fraction of maxTorqueNm (0.3..1)…
                kneeFraction = %s
                # …compressing by this ratio above the knee (1..10).
                kneeRatio = %s
                # Soft-lock end stop: spring, Nm per deg past the range (0..2)…
                lockStiffnessNmPerDeg = %s
                # …and one-way damping into the stop, Nm per deg/s (0..0.05).
                lockDampingNmPerDegPerS = %s
                """.formatted(
                trim(d.masterGain()), trim(d.maxTorqueNm()), trim(d.slewNmPerSec()),
                trim(d.rampInSeconds()), trim(d.watchdogSeconds()), trim(d.telemetryGain()),
                trim(d.damperNmPerDegPerS()), trim(d.frictionNm()), trim(d.frictionEpsDegPerS()),
                trim(d.kneeFraction()), trim(d.kneeRatio()), trim(d.lockStiffnessNmPerDeg()),
                trim(d.lockDampingNmPerDegPerS()));
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
            AeronauticsSimwheel.LOGGER.info("SimWheel wrote default feel config: {}", path);
        } catch (IOException e) {
            AeronauticsSimwheel.LOGGER.error("SimWheel could not write {}", path, e);
        }
    }

    private static String trim(double v) {
        String s = String.valueOf(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) + ".0" : s;
    }
}
