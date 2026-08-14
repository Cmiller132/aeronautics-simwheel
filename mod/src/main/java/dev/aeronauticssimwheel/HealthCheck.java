package dev.aeronauticssimwheel;

import com.simibubi.create.Create;
import dev.aeronauticssimwheel.content.GroundTelemetrySampler;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.SubLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Startup compatibility health check (DESIGN.md §10.4): verify every §2
 * integration surface that can silently drift when Simulated/Offroad/Sable
 * update — the method- and field-level reaches that survive classloading but
 * would misbehave at runtime. One loud line per failure, one summary line
 * always; where a degrade path exists (the S8 reflective fields) the feature
 * downgrades in place instead of misbehaving.
 */
public final class HealthCheck {

    public record Result(String surface, boolean ok, String detail) {
    }

    private HealthCheck() {
    }

    /** Run at common setup (registries frozen, all mod classes loaded). */
    public static void runAndLog() {
        List<Result> results = run();
        long failed = results.stream().filter(r -> !r.ok()).count();
        for (Result r : results) {
            if (!r.ok()) {
                AeronauticsSimwheel.LOGGER.error(
                        "SimWheel health check FAILED [{}]: {} — an upstream update likely "
                                + "changed this surface; the matching feature degrades or "
                                + "misreports until this mod is updated", r.surface(), r.detail());
            }
        }
        if (failed == 0) {
            AeronauticsSimwheel.LOGGER.info(
                    "SimWheel health check: all {} integration surfaces verified", results.size());
        } else {
            AeronauticsSimwheel.LOGGER.error(
                    "SimWheel health check: {}/{} surfaces FAILED (see above)",
                    failed, results.size());
        }
    }

    public static List<Result> run() {
        List<Result> results = new ArrayList<>();

        results.add(check("S7 sable$physicsTick", () -> {
            var m = java.util.Arrays.stream(BlockEntitySubLevelActor.class.getMethods())
                    .filter(x -> x.getName().equals("sable$physicsTick")
                            && x.getParameterCount() == 3)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException(
                            "BlockEntitySubLevelActor.sable$physicsTick(3 args)"));
            return "present" + (m.isDefault() ? " (default)" : "");
        }));

        results.add(check("S6 SubLevel.logicalPose", () -> {
            SubLevel.class.getMethod("logicalPose");
            return "present";
        }));

        results.add(check("S8 reflective mount fields", () -> {
            if (!GroundTelemetrySampler.fullFidelity()) {
                throw new IllegalStateException(
                        "touchingFriction/chasingYaw not reachable — telemetry degraded "
                                + "(μ=1, yaw without lerp)");
            }
            return "touchingFriction + chasingYaw resolved";
        }));

        results.add(check("S8 mount state reads", () -> {
            WheelMountBlockEntity.class.getMethod("getLerpedExtension", float.class);
            WheelMountBlockEntity.class.getMethod("getHeldItem");
            return "getLerpedExtension + getHeldItem present";
        }));

        results.add(check("S3 TIRE component", () -> {
            if (OffroadDataComponents.TIRE == null) {
                throw new IllegalStateException("OffroadDataComponents.TIRE is null");
            }
            return "registered";
        }));

        results.add(check("S1 redstone link network", () -> {
            if (Create.REDSTONE_LINK_NETWORK_HANDLER == null) {
                throw new IllegalStateException("Create.REDSTONE_LINK_NETWORK_HANDLER is null");
            }
            return "handler present";
        }));

        results.add(check("S9 mount-linking mixin target", () -> {
            WheelMountBlockEntity.class.getDeclaredMethod("computeYaw");
            return "computeYaw present (the brake LVT target degrades silently "
                    + "via the mixin's require=0 if it drifts)";
        }));

        return results;
    }

    private static Result check(String surface, Callable<String> probe) {
        try {
            return new Result(surface, true, probe.call());
        } catch (Throwable t) {
            return new Result(surface, false, t.toString());
        }
    }
}
