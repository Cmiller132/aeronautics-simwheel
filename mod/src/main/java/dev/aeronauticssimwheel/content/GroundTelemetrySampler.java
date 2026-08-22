package dev.aeronauticssimwheel.content;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.ffb.GroundTorqueModel;
import dev.aeronauticssimwheel.ffb.GroundTorqueModel.MountSample;
import dev.aeronauticssimwheel.ffb.StrikeDetector;
import dev.aeronauticssimwheel.ffb.TelemetryFrame;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2a server-side rig (DESIGN.md §6.1–6.3), v2: while a driver is
 * latched, every Sable physics substep reads the craft's wheel mounts — the
 * same numbers Offroad's own {@code sable$physicsTick} computes forces from —
 * and reflects them through {@link GroundTorqueModel} into one
 * {@link TelemetryFrame} (SAT, differential texture, speed, slip, μ, rpm).
 * Frames ring-buffer here and flush once per game tick as an
 * {@link FfbTelemetryPacket} to the latched driver; suspension-compression
 * spikes and craft collisions bypass the batch as rate-limited
 * {@link FfbEventPacket}s.
 *
 * <p>Mount enumeration is free: wheel mounts implement Sable's
 * {@code BlockEntitySubLevelActor}, so the sub-level's plot already tracks
 * them ({@code plot.getBlockEntityActors()}).
 *
 * <p><b>Side attribution</b>: each mount is assigned to a side of the craft
 * centerline (right of the driver = +1) so texture is differential and
 * strikes kick toward their originating side. "Right" is derived from the
 * wheel block's facing; the first hardware trip verifies the polarity (a
 * single sign, {@code RIGHT_IS_COUNTERCLOCKWISE_OF_FACING}, flips it).
 *
 * <p><b>Steered mounts</b>: explicit mount links override the heuristic —
 * when the wheel has links, only linked mounts backdrive the column.
 * Otherwise the signal heuristic applies with a decay: a mount counts as
 * steered while it has seen a steering signal within the last
 * {@value #STEER_DECAY_TICKS} ticks (the old sticky-forever flag made any
 * rear-steer wiring permanently backdrive the column).
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
    /** Signal-heuristic decay: steered while a signal was seen this recently. */
    public static final int STEER_DECAY_TICKS = 100;
    /** Driver's right relative to the wheel block's facing (HIL-verified sign). */
    private static final boolean RIGHT_IS_COUNTERCLOCKWISE_OF_FACING = true;
    /** Mounts within this many blocks of the centerline read as center (side 0). */
    private static final double CENTERLINE_DEADBAND = 0.5;

    // Collision events: a horizontal Δv spike within one substep is a hit.
    private static final double COLLISION_DELTA_V_MS = 3.0;
    private static final double COLLISION_PEAK_NM_PER_MS = 0.8;
    private static final double COLLISION_MAX_PEAK_NM = 3.0;
    private static final double COLLISION_TAU_S = 0.06;
    private static final double COLLISION_MIN_INTERVAL_S = 0.3;

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
    /** Signal heuristic: last game time each mount saw a steering signal. */
    private final Map<BlockPos, Long> lastSteerSignal = new HashMap<>();

    private final List<MountSample> scratch = new ArrayList<>();
    private final List<WheelMountBlockEntity> mountScratch = new ArrayList<>();
    private final ArrayDeque<FfbEventPacket> pendingEvents = new ArrayDeque<>();

    private final double[] pendingTimes = new double[FfbTelemetryPacket.MAX_SAMPLES];
    private final TelemetryFrame[] pendingFrames = new TelemetryFrame[FfbTelemetryPacket.MAX_SAMPLES];
    private int pendingCount;

    private int substepInTick;
    private long lastSampleGameTime = Long.MIN_VALUE;
    /** Monotonic wire-timeline guard: under lag, tick-derived time can step back. */
    private double lastTimeS = Double.NEGATIVE_INFINITY;

    private Vec3 prevCraftVel;
    private double sinceCollisionS = Double.MAX_VALUE;

    // Test/HUD introspection
    private long totalSamples;
    private TelemetryFrame lastSampledFrame = TelemetryFrame.ZERO;
    private float[] lastFlush = new float[0];
    private int dbgMounts;
    private int dbgTires;
    private int dbgSteered;
    private double dbgMaxAbsVSide;
    private double dbgMaxAbsLateral;
    private double dbgMaxStrengthMul;
    private double dbgMinMu;
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
        // Wire timeline: tick-derived, clamped monotonic — under server lag
        // Sable can pack extra substeps into one tick and the next tick's
        // first sample could otherwise land before this one (decoder rejects
        // non-monotonic offsets).
        double timeS = Math.max(gameTime * 0.05 + substepInTick * timeStep,
                lastTimeS + 1e-4);
        lastTimeS = timeS;
        substepInTick++;

        // Pass 1: collect live tire mounts, so side attribution has a centroid.
        mountScratch.clear();
        double centroidX = 0;
        double centroidZ = 0;
        dbgMounts = 0;
        for (BlockEntitySubLevelActor actor : subLevel.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof WheelMountBlockEntity mount) || mount.isRemoved()) {
                continue;
            }
            dbgMounts++;
            if (mount.getBlockState().getBlock() instanceof WheelMountBlock
                    && mount.getHeldItem().get(OffroadDataComponents.TIRE) != null) {
                mountScratch.add(mount);
                centroidX += mount.getBlockPos().getX();
                centroidZ += mount.getBlockPos().getZ();
            }
        }
        dbgTires = mountScratch.size();
        if (!mountScratch.isEmpty()) {
            centroidX /= mountScratch.size();
            centroidZ /= mountScratch.size();
        }

        Direction wheelFacing = wheel.facing();
        Direction rightDir = RIGHT_IS_COUNTERCLOCKWISE_OF_FACING
                ? wheelFacing.getCounterClockWise() : wheelFacing.getClockWise();
        Vec3i right = rightDir.getNormal();

        // Explicit links override the signal heuristic entirely (§6.2).
        Set<BlockPos> linked = wheel.linkedMountPositions();

        // Craft velocity at the wheel (plot coordinates → world velocity):
        // frame speed, and the Δv collision detector.
        Vec3 craftVel = Sable.HELPER.getVelocity(level, wheel.getBlockPos().getCenter());
        double speedMS = Math.hypot(craftVel.x, craftVel.z);
        detectCollision(craftVel, subLevel, rightDir, timeStep);

        scratch.clear();
        double maxCompressionRate = 0;
        double maxCompressionSide = 0;
        dbgSteered = 0;
        dbgMaxAbsVSide = 0;
        dbgMaxAbsLateral = 0;
        dbgMaxStrengthMul = 0;
        dbgMinMu = Double.NaN;
        dbgAirborne = 0;

        for (WheelMountBlockEntity mount : mountScratch) {
            TireLike tire = mount.getHeldItem().get(OffroadDataComponents.TIRE);
            BlockPos pos = mount.getBlockPos();
            Direction facing = mount.getBlockState().getValue(WheelMountBlock.HORIZONTAL_FACING);
            // Handedness of Offroad's per-axis basis (side × rolling)·ŷ: X-axis
            // facings −1, Z-axis +1 — the kingpin cross-product sign flips with
            // it (GroundTorqueModel class doc).
            double kingpinSign = facing.getAxis() == Direction.Axis.X ? -1 : 1;

            // Side of the craft centerline, projected on the driver's right.
            double sideCoord = (pos.getX() - centroidX) * right.getX()
                    + (pos.getZ() - centroidZ) * right.getZ();
            double sideSign = Math.abs(sideCoord) < CENTERLINE_DEADBAND ? 0 : Math.signum(sideCoord);

            // Steering signal, recomputed from the same public reads the mount uses.
            Direction cw = facing.getClockWise();
            Direction ccw = facing.getCounterClockWise();
            int signal = level.getSignal(pos.relative(cw), cw) - level.getSignal(pos.relative(ccw), ccw);
            if (signal != 0) {
                lastSteerSignal.put(pos, gameTime);
            }
            boolean steered = !linked.isEmpty()
                    ? linked.contains(pos)
                    : gameTime - lastSteerSignal.getOrDefault(pos, Long.MIN_VALUE) <= STEER_DECAY_TICKS;

            // Suspension: getLerpedExtension(1) is exactly the server-side extension
            // (the raw wheel-center→terrain distance; see AIRBORNE_PARKED).
            double ext = mount.getLerpedExtension(1.0f);
            double prev = prevExtension.getOrDefault(pos, ext);
            prevExtension.put(pos, ext);
            boolean airborne = ext == AIRBORNE_PARKED
                    || ext > AIRBORNE_PARKED + tire.radius() + 0.25;
            double suspVel = airborne ? 0 : (ext - prev) / timeStep;
            if (steered && -suspVel > maxCompressionRate) {
                maxCompressionRate = -suspVel;
                maxCompressionSide = sideSign;
            }

            double rpm = mount.getSpeed();

            if (steered) {
                dbgSteered++;
            }
            if (airborne) {
                dbgAirborne++;
                scratch.add(new MountSample(0, kingpinSign, sideSign, 0, 0, 0, 1.0, rpm, steered));
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
            // Rolling axis (slip denominator): X-axis facings roll along +Z,
            // Z-axis facings along +X — the same basis Offroad builds.
            Vec3i rollBase = facing.getAxis() == Direction.Axis.X
                    ? Direction.SOUTH.getNormal() : Direction.EAST.getNormal();
            Vector3d rolling = new Vector3d(rollBase.getX(), rollBase.getY(), rollBase.getZ())
                    .rotateY(yaw);
            double vForward = localVel.x * rolling.x + localVel.y * rolling.y
                    + localVel.z * rolling.z;

            double strengthMul = strengthMul(mount, subLevel, wheelCenter);
            if (!Double.isFinite(strengthMul)) {
                continue;
            }
            double lateralForce = -vSide * 0.6 * mu * strengthMul;

            dbgMaxAbsVSide = Math.max(dbgMaxAbsVSide, Math.abs(vSide));
            dbgMaxAbsLateral = Math.max(dbgMaxAbsLateral, Math.abs(lateralForce));
            dbgMaxStrengthMul = Math.max(dbgMaxStrengthMul, strengthMul);
            dbgMinMu = Double.isNaN(dbgMinMu) ? mu : Math.min(dbgMinMu, mu);
            dbgYaw = yaw;

            scratch.add(new MountSample(lateralForce, kingpinSign, sideSign, suspVel,
                    vSide, vForward, mu, rpm, steered));
        }

        TelemetryFrame frame = model.frame(scratch, wheel.lockDeg(), speedMS, wheel.ffbTrim());
        lastSampledFrame = frame;
        if (pendingCount == pendingFrames.length) {
            // Overflow (a tick with no flush): drop the oldest sample.
            System.arraycopy(pendingTimes, 1, pendingTimes, 0, pendingCount - 1);
            System.arraycopy(pendingFrames, 1, pendingFrames, 0, pendingCount - 1);
            pendingCount--;
        }
        pendingTimes[pendingCount] = timeS;
        pendingFrames[pendingCount] = frame;
        pendingCount++;
        totalSamples++;

        StrikeDetector.Strike strike = strikes.step(maxCompressionRate, maxCompressionSide, timeStep);
        if (strike != null && pendingEvents.size() < MAX_PENDING_EVENTS) {
            pendingEvents.add(new FfbEventPacket(strike.peakNm(), strike.tauSeconds()));
        }
    }

    /**
     * Craft collision → bipolar impulse: a horizontal Δv spike inside one
     * substep is a hit; the impulse kicks toward the struck side. Hitting a
     * wall should feel like hitting a wall.
     */
    private void detectCollision(Vec3 craftVel, ServerSubLevel subLevel, Direction rightDir,
                                 double timeStep) {
        sinceCollisionS = Math.min(sinceCollisionS + timeStep, Double.MAX_VALUE);
        Vec3 prev = prevCraftVel;
        prevCraftVel = craftVel;
        if (prev == null || sinceCollisionS < COLLISION_MIN_INTERVAL_S) {
            return;
        }
        double dvx = craftVel.x - prev.x;
        double dvz = craftVel.z - prev.z;
        double dv = Math.hypot(dvx, dvz);
        if (!Double.isFinite(dv) || dv < COLLISION_DELTA_V_MS) {
            return;
        }
        sinceCollisionS = 0;
        // Kick toward the struck side: the impact pushes the craft away from
        // it, so the side is opposite the Δv's rightward component.
        Vec3 localDv = subLevel.logicalPose().transformNormalInverse(new Vec3(dvx, 0, dvz));
        Vec3i right = rightDir.getNormal();
        double rightward = localDv.x * right.getX() + localDv.z * right.getZ();
        float sign = rightward > 0 ? -1f : 1f;
        float peak = (float) Math.min(dv * COLLISION_PEAK_NM_PER_MS, COLLISION_MAX_PEAK_NM);
        if (pendingEvents.size() < MAX_PENDING_EVENTS) {
            pendingEvents.add(new FfbEventPacket(sign * peak, (float) COLLISION_TAU_S));
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
        if (pendingCount == 0 && pendingEvents.isEmpty()) {
            return;
        }
        ServerPlayer player = driver != null && level.getPlayerByUUID(driver)
                instanceof ServerPlayer sp ? sp : null;

        if (pendingCount > 0) {
            TelemetryFrame[] frames = new TelemetryFrame[pendingCount];
            System.arraycopy(pendingFrames, 0, frames, 0, pendingCount);
            // Ship the true recorded instants (as offsets from the first), not
            // a uniform-dt assumption — under server lag Sable can pack extra
            // substeps into one tick and dt can vary between ticks.
            float[] offsets = new float[pendingCount];
            float[] torques = new float[pendingCount];
            for (int i = 0; i < pendingCount; i++) {
                offsets[i] = (float) (pendingTimes[i] - pendingTimes[0]);
                torques[i] = frames[i].satNm() + frames[i].textureNm();
            }
            lastFlush = torques;
            if (player != null) {
                PacketDistributor.sendToPlayer(player,
                        new FfbTelemetryPacket(pendingTimes[0], offsets, frames));
            }
            pendingCount = 0;
        }
        FfbEventPacket event;
        while ((event = pendingEvents.poll()) != null) {
            if (player != null) {
                PacketDistributor.sendToPlayer(player, event);
            }
        }
    }

    /** Rig teardown (driver released / timeout / block gone). */
    public void reset() {
        prevExtension.clear();
        lastSteerSignal.clear();
        pendingEvents.clear();
        pendingCount = 0;
        substepInTick = 0;
        lastSampleGameTime = Long.MIN_VALUE;
        lastTimeS = Double.NEGATIVE_INFINITY;
        prevCraftVel = null;
        sinceCollisionS = Double.MAX_VALUE;
        strikes.reset();
    }

    // ------------------------------------------------------------------
    // Introspection (gametests, HUD-side debugging)
    // ------------------------------------------------------------------

    public long totalSamples() {
        return totalSamples;
    }

    /** SAT+texture torque of the most recent flush (empty before the first). */
    public float[] lastFlush() {
        return lastFlush;
    }

    /** The most recently sampled frame (gametests). */
    public TelemetryFrame lastSampledFrame() {
        return lastSampledFrame;
    }

    /** One-line state dump of the most recent substep (gametest diagnostics). */
    public String debugSummary() {
        return String.format(
                "mounts=%d tires=%d steered=%d airborne=%d |vSide|=%.3f |latF|=%.1f "
                        + "maxStrengthMul=%.1f minMu=%.2f yaw=%.4f samples=%d",
                dbgMounts, dbgTires, dbgSteered, dbgAirborne, dbgMaxAbsVSide,
                dbgMaxAbsLateral, dbgMaxStrengthMul,
                Double.isNaN(dbgMinMu) ? 1.0 : dbgMinMu, dbgYaw, totalSamples);
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
