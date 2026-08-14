package dev.aeronauticssimwheel.content;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.ffb.GroundTorqueModel;
import dev.aeronauticssimwheel.ffb.GroundTorqueModel.MountSample;
import dev.aeronauticssimwheel.ffb.StrikeDetector;
import dev.aeronauticssimwheel.network.FfbEventPacket;
import dev.aeronauticssimwheel.network.FfbTelemetryPacket;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlock;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import dev.ryanhcode.offroad.content.components.TireLike;
import dev.ryanhcode.offroad.index.OffroadDataComponents;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2a server-side rig (DESIGN.md §6.1–6.3): while a driver is latched,
 * every Sable physics substep reads the craft's wheel mounts — the same
 * numbers Offroad's own {@code sable$physicsTick} computes forces from — and
 * reflects them through {@link GroundTorqueModel} into one column-torque
 * sample. Samples ring-buffer here and flush once per game tick as an
 * {@link FfbTelemetryPacket} to the latched driver; suspension-compression
 * spikes bypass the batch as rate-limited {@link FfbEventPacket}s.
 *
 * <p>Mount enumeration is free: wheel mounts implement Sable's
 * {@code BlockEntitySubLevelActor}, so the sub-level's plot already tracks
 * them ({@code plot.getBlockEntityActors()}).
 *
 * <p><b>Reflection surface (the Phase 2a §2 additions)</b>: two private
 * fields of {@code WheelMountBlockEntity} have no public accessor —
 * {@code touchingFriction} (per-block μ under the tire) and
 * {@code chasingYaw} (the actual steered yaw). Resolved once at class load;
 * on upstream churn the sampler degrades (μ=1, yaw recomputed from the
 * public redstone signal without the 0.4/tick lerp) with one loud log line —
 * never a crash (§10.4).
 *
 * <p>Not thread-crossing: substep sampling and the tick flush both run on the
 * server thread.
 */
public final class GroundTelemetrySampler {

    /** Mirrors Offroad: mounts steer −signal/15 × π/4 × 2/3 (±30°). */
    private static final double YAW_PER_SIGNAL_RAD = -(Math.PI / 4.0) * (2.0 / 3.0) / 15.0;
    /**
     * Server-side, WheelMountBlockEntity.extension holds the RAW raycast
     * distance from the wheel center to terrain (≈ radius + droop when
     * grounded — NOT the 0–0.65 client spring length). Its physicsTick skips
     * all forces when that distance exceeds 0.65 + radius + 0.25 and parks the
     * field at exactly 0.65 — both mirrored here as the airborne test.
     */
    private static final double AIRBORNE_PARKED = 0.65;
    private static final int MAX_PENDING_EVENTS = 4;

    private static final Field TOUCHING_FRICTION = resolve("touchingFriction");
    private static final Field CHASING_YAW = resolve("chasingYaw");

    private static Field resolve(String name) {
        try {
            Field f = WheelMountBlockEntity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            AeronauticsSimwheel.LOGGER.error(
                    "SimWheel: WheelMountBlockEntity.{} not reachable — ground telemetry "
                            + "degrades (Offroad update?): {}", name, e.toString());
            return null;
        }
    }

    private final GroundTorqueModel model = new GroundTorqueModel(GroundTorqueModel.Config.defaults());
    private final StrikeDetector strikes = new StrikeDetector(StrikeDetector.Config.defaults());

    /** Per-mount previous suspension extension, for bump texture deltas. */
    private final Map<BlockPos, Double> prevExtension = new HashMap<>();
    /** Sticky per-rig "backdrives the column" flags (§6.2 v1 heuristic). */
    private final Set<BlockPos> steeredMounts = new HashSet<>();

    private final List<MountSample> scratch = new ArrayList<>();
    private final ArrayDeque<StrikeDetector.Strike> pendingStrikes = new ArrayDeque<>();

    private double[] pendingTimes = new double[FfbTelemetryPacket.MAX_SAMPLES];
    private float[] pendingTorques = new float[FfbTelemetryPacket.MAX_SAMPLES];
    private int pendingCount;

    private int substepInTick;
    private long lastSampleGameTime = Long.MIN_VALUE;

    // Test/HUD introspection
    private long totalSamples;
    private float[] lastFlush = new float[0];
    private int dbgMounts;
    private int dbgTires;
    private int dbgSteered;
    private double dbgMaxAbsVSide;
    private double dbgMaxAbsLateral;
    private double dbgStrengthMul;
    private double dbgMu;
    private double dbgYaw;
    private int dbgAirborne;

    /** One physics substep while the rig is live (server thread). */
    public void sampleSubstep(SimSteeringWheelBlockEntity wheel, ServerSubLevel subLevel,
                              double timeStep) {
        ServerLevel level = (ServerLevel) wheel.getLevel();
        long gameTime = level.getGameTime();
        if (gameTime != lastSampleGameTime) {
            lastSampleGameTime = gameTime;
            substepInTick = 0;
        }
        double timeS = gameTime * 0.05 + substepInTick * timeStep;
        substepInTick++;

        scratch.clear();
        double maxCompressionRate = 0;
        dbgMounts = 0;
        dbgTires = 0;
        dbgSteered = 0;
        dbgMaxAbsVSide = 0;
        dbgMaxAbsLateral = 0;
        dbgAirborne = 0;

        for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof WheelMountBlockEntity mount) || mount.isRemoved()) {
                continue;
            }
            dbgMounts++;
            TireLike tire = mount.getBlockState().getBlock() instanceof WheelMountBlock
                    ? mount.getHeldItem().get(OffroadDataComponents.TIRE) : null;
            if (tire == null) {
                continue;
            }
            dbgTires++;
            BlockPos pos = mount.getBlockPos();
            Direction facing = mount.getBlockState().getValue(WheelMountBlock.HORIZONTAL_FACING);
            // Handedness of Offroad's per-axis basis (side × rolling)·ŷ: X-axis
            // facings −1, Z-axis +1 — the kingpin cross-product sign flips with
            // it (GroundTorqueModel class doc).
            double kingpinSign = facing.getAxis() == Direction.Axis.X ? -1 : 1;

            // Steering signal, recomputed from the same public reads the mount uses.
            Direction cw = facing.getClockWise();
            Direction ccw = facing.getCounterClockWise();
            int signal = level.getSignal(pos.relative(cw), cw) - level.getSignal(pos.relative(ccw), ccw);
            if (signal != 0) {
                steeredMounts.add(pos);
            }
            boolean steered = steeredMounts.contains(pos);

            // Suspension: getLerpedExtension(1) is exactly the server-side extension
            // (the raw wheel-center→terrain distance; see AIRBORNE_PARKED).
            double ext = mount.getLerpedExtension(1.0f);
            double prev = prevExtension.getOrDefault(pos, ext);
            prevExtension.put(pos, ext);
            boolean airborne = ext == AIRBORNE_PARKED
                    || ext > AIRBORNE_PARKED + tire.radius() + 0.25;
            double suspVel = airborne ? 0 : (ext - prev) / timeStep;
            if (steered) {
                maxCompressionRate = Math.max(maxCompressionRate, -suspVel);
            }

            if (steered) {
                dbgSteered++;
            }
            if (airborne) {
                dbgAirborne++;
                scratch.add(new MountSample(0, kingpinSign, 0, 0, steered));
                continue;
            }

            double yaw = readYaw(mount, signal);
            double mu = readFriction(mount);

            // The game's own lateral term: −v_side × 0.6 × μ × strengthMul,
            // v_side in the steered wheel frame (WheelMountBlockEntity mirror).
            Vec3 wheelCenter = pos.relative(facing).getCenter();
            Vec3 localVel = subLevel.logicalPose()
                    .transformNormalInverse(Sable.HELPER.getVelocity(level, wheelCenter));
            Vec3i base = Direction.get(Direction.AxisDirection.POSITIVE, facing.getAxis()).getNormal();
            Vector3d side = new Vector3d(base.getX(), base.getY(), base.getZ()).rotateY(yaw);
            double vSide = localVel.x * side.x + localVel.y * side.y + localVel.z * side.z;

            double strengthMul = strengthMul(mount, subLevel, wheelCenter);
            if (!Double.isFinite(strengthMul)) {
                continue;
            }
            double lateralForce = -vSide * 0.6 * mu * strengthMul;

            dbgMaxAbsVSide = Math.max(dbgMaxAbsVSide, Math.abs(vSide));
            dbgMaxAbsLateral = Math.max(dbgMaxAbsLateral, Math.abs(lateralForce));
            dbgStrengthMul = strengthMul;
            dbgMu = mu;
            dbgYaw = yaw;

            scratch.add(new MountSample(lateralForce, kingpinSign, suspVel, yaw, steered));
        }

        float torque = model.columnTorqueNm(scratch, wheel.lockDeg());
        if (pendingCount == pendingTorques.length) {
            // Overflow (a tick with no flush): drop the oldest sample.
            System.arraycopy(pendingTimes, 1, pendingTimes, 0, pendingCount - 1);
            System.arraycopy(pendingTorques, 1, pendingTorques, 0, pendingCount - 1);
            pendingCount--;
        }
        pendingTimes[pendingCount] = timeS;
        pendingTorques[pendingCount] = torque;
        pendingCount++;
        totalSamples++;

        StrikeDetector.Strike strike = strikes.step(maxCompressionRate, timeStep);
        if (strike != null && pendingStrikes.size() < MAX_PENDING_EVENTS) {
            pendingStrikes.add(strike);
        }
    }

    /**
     * Offroad's exact load factor: strength × min(normalMass/strength, 1) × 10 × 2,
     * with normalMass from the sub-level mass tracker at the wheel position.
     */
    private static double strengthMul(WheelMountBlockEntity mount, ServerSubLevel subLevel,
                                      Vec3 wheelCenter) {
        ScrollValueBehaviour strength = mount.getBehaviour(ScrollValueBehaviour.TYPE);
        double strengthVal = strength != null ? strength.getValue() : 10;
        MassData mass = subLevel.getMassTracker();
        if (mass == null || mass.isInvalid()) {
            return Double.NaN;
        }
        double invNormalMass = mass.getInverseNormalMass(
                new Vector3d(wheelCenter.x, wheelCenter.y, wheelCenter.z), OrientedBoundingBox3d.UP);
        if (!(invNormalMass > 0)) {
            return Double.NaN;
        }
        double normalMassScaling = Math.min(1.0 / invNormalMass / strengthVal, 1.0) * 10.0;
        return strengthVal * normalMassScaling * 2.0;
    }

    private static double readYaw(WheelMountBlockEntity mount, int signal) {
        if (CHASING_YAW != null) {
            try {
                return CHASING_YAW.getDouble(mount);
            } catch (IllegalAccessException ignored) {
            }
        }
        return signal * YAW_PER_SIGNAL_RAD; // degraded: no 0.4/tick lerp
    }

    private static double readFriction(WheelMountBlockEntity mount) {
        if (TOUCHING_FRICTION != null) {
            try {
                double mu = TOUCHING_FRICTION.getDouble(mount);
                if (Double.isFinite(mu) && mu >= 0) {
                    return mu;
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return 1.0; // degraded: default surface
    }

    /** Once per game tick while engaged (server thread): ship what accumulated. */
    public void flush(ServerLevel level, UUID driver) {
        if (pendingCount == 0 && pendingStrikes.isEmpty()) {
            return;
        }
        ServerPlayer player = driver != null && level.getPlayerByUUID(driver)
                instanceof ServerPlayer sp ? sp : null;

        if (pendingCount > 0) {
            float[] torques = new float[pendingCount];
            System.arraycopy(pendingTorques, 0, torques, 0, pendingCount);
            // Ship the true recorded instants (as offsets from the first), not
            // a uniform-dt assumption — under server lag Sable can pack extra
            // substeps into one tick and dt can vary between ticks.
            float[] offsets = new float[pendingCount];
            for (int i = 0; i < pendingCount; i++) {
                offsets[i] = (float) (pendingTimes[i] - pendingTimes[0]);
            }
            lastFlush = torques;
            if (player != null) {
                PacketDistributor.sendToPlayer(player,
                        new FfbTelemetryPacket(pendingTimes[0], offsets, torques));
            }
            pendingCount = 0;
        }
        StrikeDetector.Strike strike;
        while ((strike = pendingStrikes.poll()) != null) {
            if (player != null) {
                PacketDistributor.sendToPlayer(player,
                        new FfbEventPacket(strike.peakNm(), strike.tauSeconds()));
            }
        }
    }

    /** Rig teardown (driver released / timeout / block gone). */
    public void reset() {
        prevExtension.clear();
        steeredMounts.clear();
        pendingStrikes.clear();
        pendingCount = 0;
        substepInTick = 0;
        lastSampleGameTime = Long.MIN_VALUE;
        strikes.reset();
    }

    // ------------------------------------------------------------------
    // Introspection (gametests, HUD-side debugging)
    // ------------------------------------------------------------------

    public long totalSamples() {
        return totalSamples;
    }

    /** The torque array most recently flushed (empty before the first flush). */
    public float[] lastFlush() {
        return lastFlush;
    }

    /** One-line state dump of the most recent substep (gametest diagnostics). */
    public String debugSummary() {
        return String.format(
                "mounts=%d tires=%d steered=%d airborne=%d |vSide|=%.3f |latF|=%.1f "
                        + "strengthMul=%.1f mu=%.2f yaw=%.4f samples=%d",
                dbgMounts, dbgTires, dbgSteered, dbgAirborne, dbgMaxAbsVSide,
                dbgMaxAbsLateral, dbgStrengthMul, dbgMu, dbgYaw, totalSamples);
    }

    /** True when both reflective reads resolved against the loaded Offroad. */
    public static boolean fullFidelity() {
        return TOUCHING_FRICTION != null && CHASING_YAW != null;
    }

    /** Marker so the BE can cheaply detect mounts (used by gametests too). */
    public static boolean isWheelMount(BlockEntity be) {
        return be instanceof WheelMountBlockEntity;
    }
}
