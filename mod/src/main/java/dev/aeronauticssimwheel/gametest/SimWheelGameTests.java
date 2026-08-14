package dev.aeronauticssimwheel.gametest;

import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import dev.aeronauticssimwheel.content.GroundTelemetrySampler;
import dev.aeronauticssimwheel.content.SimChannel;
import dev.aeronauticssimwheel.content.SimSteeringWheelBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless in-game verification on a real server with Create + Simulated +
 * Offroad + Sable loaded ({@code ./gradlew :mod:runGameTest}).
 *
 * <p>Covers the DESIGN.md §5 decision: the Sim Steering Wheel is the only
 * control surface — native direct-authority steering on the stock wheel's
 * redstone side convention, plus link-channel transmission (the typewriter
 * replacement) with the failsafe brake. The race car assembly test keeps the
 * primary test vehicle exercising physics.
 */
@GameTestHolder(dev.aeronauticssimwheel.AeronauticsSimwheel.MOD_ID)
public class SimWheelGameTests {

    /**
     * Steering the way a wheel mount reads it: the block must emit the stock
     * steering wheel's directional analog convention — positive steer on the
     * clockwise face, negative on the counterclockwise face, engagement 15/0
     * on the facing face — with direct (slew-clamped) angle authority, and
     * input silence must release everything.
     *
     * <p>The template wheel at (1,1,1) faces north: clockwise face = east
     * (queried as {@code getSignal(pos, WEST)} per the vanilla convention —
     * the query direction points from the asking neighbor to the emitter),
     * counterclockwise = west, facing face = north (queried with SOUTH).
     */
    @GameTest(template = "control_rig", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public static void sim_wheel_emits_stock_steering_signals(GameTestHelper helper) {
        BlockPos rel = findBlockEntityPos(helper, SimSteeringWheelBlockEntity.class, 5, 5, 5);
        SimSteeringWheelBlockEntity wheel = (SimSteeringWheelBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        BlockPos abs = helper.absolutePos(rel);
        UUID driver = UUID.randomUUID();

        // Full right steer: 450° at the 54°/tick sanity clamp arrives in 9 ticks
        // (the stock wheel's 16 RPM chase would need ~94).
        helper.runAtTickTime(5, () -> wheel.applyInput(driver, null, 1.0f, 0f, 0f, 0f, 0));
        helper.runAtTickTime(20, () -> {
            assertSignal(helper, abs, Direction.WEST, 15, "full right steer on clockwise face");
            assertSignal(helper, abs, Direction.EAST, 0, "counterclockwise face silent on right steer");
            assertSignal(helper, abs, Direction.SOUTH, 15, "engaged marker on facing face");
        });

        // Half left steer: floor(-0.5*15) = -8 on the counterclockwise face.
        helper.runAtTickTime(22, () -> wheel.applyInput(driver, null, -0.5f, 0f, 0f, 0f, 0));
        helper.runAtTickTime(40, () -> {
            assertSignal(helper, abs, Direction.EAST, 8, "half left steer on counterclockwise face");
            assertSignal(helper, abs, Direction.WEST, 0, "clockwise face silent on left steer");
        });

        // No further frames: the 30-tick timeout must recenter and disengage.
        helper.runAtTickTime(90, () -> {
            assertSignal(helper, abs, Direction.EAST, 0, "timeout must recenter steering");
            assertSignal(helper, abs, Direction.WEST, 0, "timeout must recenter steering");
            assertSignal(helper, abs, Direction.SOUTH, 0, "timeout must drop the engaged marker");
            helper.succeed();
        });
    }

    /**
     * The typewriter-replacement half: a bound analog channel must reach a real
     * redstone-link receiver on the same frequency at proportional 0–15 levels.
     */
    @GameTest(template = "control_rig", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public static void sim_wheel_transmits_link_channels(GameTestHelper helper) {
        SimSteeringWheelBlockEntity wheel =
                findBlockEntity(helper, SimSteeringWheelBlockEntity.class, 5, 5, 5);
        BlockPos linkRel = findBlockEntityPos(helper, RedstoneLinkBlockEntity.class, 5, 5, 5);
        RedstoneLinkBlockEntity link = (RedstoneLinkBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(linkRel));

        // Bind both ends to the same blue-wool pair. The receiver side goes through
        // Create's own public API (self-registers in the network); the template's
        // frequency NBT is not relied on. Blue, not red: gametests in a batch run
        // concurrently in one shared level and Create's link network combines all
        // transmitters on a frequency by max, so each test needs its own frequency
        // (the failsafe test's latched brake would otherwise mask lower levels here).
        wheel.bindChannel(SimChannel.THROTTLE,
                new ItemStack(Items.BLUE_WOOL), new ItemStack(Items.BLUE_WOOL));
        UUID driver = UUID.randomUUID();

        helper.runAtTickTime(2, () -> {
            LinkBehaviour receiver = link.getBehaviour(LinkBehaviour.TYPE);
            helper.assertTrue(receiver != null, "template link must have a LinkBehaviour");
            receiver.setFrequency(true, new ItemStack(Items.BLUE_WOOL));
            receiver.setFrequency(false, new ItemStack(Items.BLUE_WOOL));
        });

        helper.runAtTickTime(5, () -> wheel.applyInput(driver, null, 0f, 1.0f, 0f, 0f, 0));
        helper.runAtTickTime(8, () -> helper.assertTrue(link.getReceivedSignal() == 15,
                "full throttle must reach the receiver as 15, got " + link.getReceivedSignal()));

        helper.runAtTickTime(10, () -> wheel.applyInput(driver, null, 0f, 0.53f, 0f, 0f, 0));
        helper.runAtTickTime(13, () -> helper.assertTrue(link.getReceivedSignal() == 8,
                "partial throttle must arrive analog (round(0.53*15)=8), got "
                        + link.getReceivedSignal()));

        helper.runAtTickTime(15, () -> wheel.applyInput(driver, null, 0f, 0f, 0f, 0f, 0));
        helper.runAtTickTime(18, () -> helper.assertTrue(link.getReceivedSignal() == 0,
                "released throttle must read 0, got " + link.getReceivedSignal()));

        // No further input frames: the 30-tick timeout must neutralize the channel
        helper.runAtTickTime(20, () -> wheel.applyInput(driver, null, 0f, 1.0f, 0f, 0f, 0));
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(link.getReceivedSignal() == 0,
                    "input silence must neutralize (timeout), got " + link.getReceivedSignal());
            helper.succeed();
        });
    }

    /**
     * The dead-man's brake: with a failsafe level configured, input silence must
     * latch the BRAKE channel at that level instead of zero — an unattended
     * craft brakes to a stop (the wheel-mount brake input is the block above
     * each mount, reading 0–15).
     */
    @GameTest(template = "control_rig", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public static void sim_wheel_failsafe_brake_latches_on_timeout(GameTestHelper helper) {
        SimSteeringWheelBlockEntity wheel =
                findBlockEntity(helper, SimSteeringWheelBlockEntity.class, 5, 5, 5);
        BlockPos linkRel = findBlockEntityPos(helper, RedstoneLinkBlockEntity.class, 5, 5, 5);
        RedstoneLinkBlockEntity link = (RedstoneLinkBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(linkRel));

        wheel.bindChannel(SimChannel.BRAKE,
                new ItemStack(Items.RED_WOOL), new ItemStack(Items.RED_WOOL));
        wheel.setFailsafeBrake(10);
        UUID driver = UUID.randomUUID();

        helper.runAtTickTime(2, () -> {
            LinkBehaviour receiver = link.getBehaviour(LinkBehaviour.TYPE);
            helper.assertTrue(receiver != null, "template link must have a LinkBehaviour");
            receiver.setFrequency(true, new ItemStack(Items.RED_WOOL));
            receiver.setFrequency(false, new ItemStack(Items.RED_WOOL));
        });

        helper.runAtTickTime(5, () -> wheel.applyInput(driver, null, 0f, 0f, 1.0f, 0f, 0));
        helper.runAtTickTime(8, () -> helper.assertTrue(link.getReceivedSignal() == 15,
                "full brake must reach the receiver as 15, got " + link.getReceivedSignal()));

        // Silence: timeout must latch the failsafe level, not zero.
        helper.runAtTickTime(50, () -> {
            helper.assertTrue(link.getReceivedSignal() == 10,
                    "signal loss must latch the failsafe brake at 10, got "
                            + link.getReceivedSignal());
            helper.succeed();
        });
    }

    /**
     * The primary test vehicle: tones_template_race_car.nbt (dndecor cog swapped
     * for create:large_cogwheel). Triggering the physics assembler must move the
     * whole car into a Sable sub-level (original blocks vanish from the world)
     * and the physics craft must then survive 100 ticks of simulation.
     */
    @GameTest(template = "race_car", timeoutTicks = 400)
    @PrefixGameTestTemplate(false)
    public static void race_car_assembles_into_physics_craft(GameTestHelper helper) {
        BlockPos assemblerPos = findBlockEntityPos(helper, PhysicsAssemblerBlockEntity.class, 9, 4, 5);
        PhysicsAssemblerBlockEntity assembler = (PhysicsAssemblerBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(assemblerPos));

        // A few ticks for kinetic networks to settle, then pull the lever.
        helper.runAtTickTime(10, assembler::assembleOrDisassemble);

        helper.runAtTickTime(15, () -> helper.assertTrue(
                !(helper.getLevel().getBlockEntity(helper.absolutePos(assemblerPos))
                        instanceof PhysicsAssemblerBlockEntity),
                "race car must leave the static world on assembly (assembly exception?)"));

        // Survive real physics: rapier natives, suspension raycasts, drivetrain.
        helper.runAtTickTime(115, helper::succeed);
    }

    /**
     * Phase 2a exit gate: the assembled race car must emit plausible ground
     * telemetry. The template carries a sim wheel on the chassis; the test
     * binds its STEER link channels to the car's own steering frequencies
     * (this is a legacy link-steered car — receivers beside the front mounts
     * feed their side faces), latches a driver, steers, shoves the craft so
     * the tires develop side-slip, and asserts the sampler's per-substep
     * column torque: present, finite, bounded, nonzero under slip — and that
     * input silence stops sampling (the rig dies with the driver latch).
     *
     * <p>Also pins the §2 reflection surface: {@code fullFidelity()} fails
     * this test loudly if an Offroad update renames the two reflected fields.
     */
    @GameTest(template = "race_car", timeoutTicks = 400)
    @PrefixGameTestTemplate(false)
    public static void race_car_emits_ground_telemetry(GameTestHelper helper) {
        BlockPos assemblerPos = findBlockEntityPos(helper, PhysicsAssemblerBlockEntity.class, 9, 4, 5);
        PhysicsAssemblerBlockEntity assembler = (PhysicsAssemblerBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(assemblerPos));
        UUID driver = UUID.randomUUID();
        AtomicReference<SimSteeringWheelBlockEntity> wheelRef = new AtomicReference<>();
        AtomicReference<ServerSubLevel> subLevelRef = new AtomicReference<>();
        AtomicReference<Vector3d> rigPositionRef = new AtomicReference<>();
        AtomicReference<PhysicsConstraintHandle> rigConstraintRef = new AtomicReference<>();
        AtomicReference<Float> maxAbsNm = new AtomicReference<>(0f);
        AtomicLong samplesAtSilence = new AtomicLong(-1);

        helper.runAtTickTime(10, assembler::assembleOrDisassemble);

        // The craft is a sub-level now; our wheel BE rode along as a plot actor.
        // Gametests share one level, and the assembly test runs the same
        // template concurrently — so pick the wheel CLOSEST to this test's own
        // template site, and require it genuinely near, or a broken assembly
        // here could silently borrow the other test's craft.
        helper.runAtTickTime(15, () -> {
            helper.assertTrue(!(helper.getLevel().getBlockEntity(helper.absolutePos(assemblerPos))
                            instanceof PhysicsAssemblerBlockEntity),
                    "this test's own race car must have assembled");

            net.minecraft.world.phys.Vec3 home = helper.absolutePos(new BlockPos(4, 2, 2)).getCenter();
            double bestSq = Double.MAX_VALUE;
            ServerSubLevelContainer container = (ServerSubLevelContainer)
                    ((SubLevelContainerHolder) helper.getLevel()).sable$getPlotContainer();
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                for (BlockEntitySubLevelActor actor : sub.getPlot().getBlockEntityActors()) {
                    if (actor instanceof SimSteeringWheelBlockEntity wheel) {
                        double dSq = sub.logicalPose()
                                .transformPosition(wheel.getBlockPos().getCenter())
                                .distanceToSqr(home);
                        if (dSq < bestSq) {
                            bestSq = dSq;
                            wheelRef.set(wheel);
                            subLevelRef.set(sub);
                        }
                    }
                }
            }
            helper.assertTrue(wheelRef.get() != null && bestSq < 32 * 32,
                    "this test's craft must carry the sim wheel as a nearby sub-level actor"
                            + " (closest was " + Math.sqrt(bestSq) + " blocks away)");
            helper.assertTrue(GroundTelemetrySampler.fullFidelity(),
                    "WheelMountBlockEntity reflection surface broke (Offroad update?)");

            // Rebind, not rewire: the car's own steering frequencies.
            wheelRef.get().bindChannel(SimChannel.STEER_RIGHT,
                    new ItemStack(Items.LIME_GLAZED_TERRACOTTA), new ItemStack(Items.LIME_WOOL));
            wheelRef.get().bindChannel(SimChannel.STEER_LEFT,
                    new ItemStack(Items.LIME_WOOL), new ItemStack(Items.LIME_GLAZED_TERRACOTTA));
        });

        // Drive: steer hard and keep the latch fed (heartbeats < 30-tick timeout).
        for (int tick = 16; tick <= 70; tick += 5) {
            helper.runAtTickTime(tick, () -> {
                if (wheelRef.get() != null) {
                    wheelRef.get().applyInput(driver, null, 0.6f, 0f, 0f, 0f, 0);
                }
            });
        }
        // Hold the craft as an upright kinematic test rig throughout the
        // measurement window. Without this, the raw assembly can acquire a
        // small random tip after the initial shove and leave three mounts
        // airborne. Contact and tire forces still come from Offroad's own
        // raycasts and tire model; this only fixes the rig pose and slip input.
        // Pinning the first position also prevents the imposed velocity from
        // carrying the craft off the small GameTest ground pad.
        for (int tick = 20; tick <= 75; tick++) {
            helper.runAtTickTime(tick, () -> {
                RigidBodyHandle body = RigidBodyHandle.of(subLevelRef.get());
                // Seat every tire within suspension reach instead of inheriting
                // the body's nondeterministic partial-fall height at tick 20.
                rigPositionRef.compareAndSet(null,
                        new Vector3d(subLevelRef.get().logicalPose().position()).add(0, -0.75, 0));
                subLevelRef.get().logicalPose().position().set(rigPositionRef.get());
                subLevelRef.get().logicalPose().orientation().identity();
                body.teleport(new Vector3d(rigPositionRef.get()), new org.joml.Quaterniond());
                body.addLinearAndAngularVelocity(
                        new Vector3d(0, 0, 2).sub(body.getLinearVelocity()),
                        new Vector3d(body.getAngularVelocity()).negate());
            });
        }

        // A body-to-world angular-only joint enforces the same upright pose at
        // physics-substep resolution. Linear axes stay free so Offroad still
        // observes the imposed side-slip and computes the tire forces itself.
        helper.runAtTickTime(20, () -> {
            ServerSubLevel subLevel = subLevelRef.get();
            Vector3d localAnchor = new Vector3d(
                    wheelRef.get().getBlockPos().getX() + 0.5,
                    wheelRef.get().getBlockPos().getY() + 0.5,
                    wheelRef.get().getBlockPos().getZ() + 0.5);
            Vector3d worldAnchor = subLevel.logicalPose()
                    .transformPosition(localAnchor, new Vector3d());
            PhysicsConstraintHandle constraint = SubLevelPhysicsSystem.require(helper.getLevel())
                    .getPipeline().addConstraint(subLevel, null,
                            new GenericConstraintConfiguration(
                                    localAnchor, worldAnchor,
                                    new org.joml.Quaterniond(), new org.joml.Quaterniond(),
                                    EnumSet.of(ConstraintJointAxis.ANGULAR_X,
                                            ConstraintJointAxis.ANGULAR_Y,
                                            ConstraintJointAxis.ANGULAR_Z)));
            helper.assertTrue(constraint != null && constraint.isValid(),
                    "kinematic test rig angular constraint must be valid");
            rigConstraintRef.set(constraint);
        });

        // Prove the discrete link path directly, independently of the torque
        // model: both front mounts must actually chase a nonzero steering yaw.
        helper.runAtTickTime(35, () -> {
            int steeredMounts = 0;
            for (BlockEntitySubLevelActor actor : subLevelRef.get().getPlot().getBlockEntityActors()) {
                if (actor instanceof dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity mount
                        && Math.abs(readChasingYaw(mount)) > 0.1) {
                    steeredMounts++;
                }
            }
            helper.assertTrue(steeredMounts == 2,
                    "link steering must reach both front mounts, got " + steeredMounts);
        });

        // Scan the flushed telemetry while driving.
        for (int tick = 25; tick <= 70; tick += 5) {
            helper.runAtTickTime(tick, () -> {
                float[] flush = wheelRef.get().telemetry().lastFlush();
                helper.assertTrue(flush.length <= 64, "flush size insane: " + flush.length);
                for (float nm : flush) {
                    helper.assertTrue(Float.isFinite(nm), "telemetry sample must be finite");
                    helper.assertTrue(Math.abs(nm) < 100f,
                            "telemetry sample out of physical bounds: " + nm);
                    maxAbsNm.accumulateAndGet(Math.abs(nm), Math::max);
                }
            });
        }

        // Continuity: sampling must still be advancing late in the driven
        // window (a sampler that died after its first flushes must not pass).
        AtomicLong samplesMidDrive = new AtomicLong(-1);
        helper.runAtTickTime(45, () ->
                samplesMidDrive.set(wheelRef.get().telemetry().totalSamples()));

        helper.runAtTickTime(75, () -> {
            long total = wheelRef.get().telemetry().totalSamples();
            helper.assertTrue(total >= 40,
                    "expected steady substep sampling while driven, got " + total);
            helper.assertTrue(total > samplesMidDrive.get(),
                    "sampling must be continuous through the driven window ("
                            + samplesMidDrive.get() + " → " + total + ")");
            helper.assertTrue(maxAbsNm.get() > 0.02f,
                    "steered slip must produce nonzero column torque, max was " + maxAbsNm.get()
                            + " — " + wheelRef.get().telemetry().debugSummary());
            dev.aeronauticssimwheel.AeronauticsSimwheel.LOGGER.info(
                    "Ground telemetry trace: max |torque| {} Nm over {} samples — {}",
                    maxAbsNm.get(), total, wheelRef.get().telemetry().debugSummary());
        });
        helper.runAtTickTime(76, () -> rigConstraintRef.get().remove());

        // Input silence: the 30-tick timeout must kill the rig with the latch.
        helper.runAtTickTime(115, () ->
                samplesAtSilence.set(wheelRef.get().telemetry().totalSamples()));
        helper.runAtTickTime(135, () -> {
            helper.assertTrue(!wheelRef.get().engaged(),
                    "input silence must release the driver latch");
            helper.assertTrue(
                    wheelRef.get().telemetry().totalSamples() == samplesAtSilence.get(),
                    "sampling must stop after the input timeout");
            helper.succeed();
        });
    }

    /**
     * Phase 3 exit gate (DESIGN.md §5.5): linking a mount to the wheel must
     * upgrade its steering from the quantized ±15 redstone read to the exact
     * float — the A/B is on the same mount, unlinked then linked. Steering 0.5
     * is chosen because no integer signal can produce it: the float target
     * −(0.5)·π/6 ≈ 0.2618 sits exactly between signal 7 (0.2443) and signal 8
     * (0.2793), so a tight tolerance proves the un-quantized path.
     */
    @GameTest(template = "race_car", timeoutTicks = 400)
    @PrefixGameTestTemplate(false)
    public static void mount_linking_gives_float_steering(GameTestHelper helper) {
        SimSteeringWheelBlockEntity wheel =
                findBlockEntity(helper, SimSteeringWheelBlockEntity.class, 9, 4, 5);
        BlockPos mountRel = findBlockEntityPos(helper,
                dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity.class, 9, 4, 5);
        var mount = (dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(mountRel));
        UUID driver = UUID.randomUUID();

        // Keep the driver latch fed at half right steer for the whole test.
        for (int tick = 5; tick <= 90; tick += 5) {
            helper.runAtTickTime(tick, () ->
                    wheel.applyInput(driver, null, 0.5f, 0f, 0f, 0f, 0));
        }

        // A: unlinked, and the wheel's STEER channels unbound — the mount sees
        // no signal at all, so its yaw must stay at zero (stock behavior).
        helper.runAtTickTime(30, () -> helper.assertTrue(
                Math.abs(readChasingYaw(mount)) < 1e-4,
                "unlinked mount must stay stock (yaw 0), got " + readChasingYaw(mount)));

        // B: link it — the mixin now feeds the exact float yaw.
        helper.runAtTickTime(32, () ->
                wheel.toggleMountLink(helper.absolutePos(mountRel)));

        helper.runAtTickTime(85, () -> {
            double yaw = Math.abs(readChasingYaw(mount));
            double expected = 0.5 * Math.PI / 6.0; // 0.26180 — unreachable by any int signal
            helper.assertTrue(Math.abs(yaw - expected) < 0.009,
                    "linked mount must chase the FLOAT yaw " + expected
                            + " (quantized would be 0.24435 or 0.27925), got " + yaw);
            helper.succeed();
        });
    }

    private static double readChasingYaw(Object mount) {
        try {
            var f = dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity.class
                    .getDeclaredField("chasingYaw");
            f.setAccessible(true);
            return f.getDouble(mount);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("chasingYaw not reachable: " + e);
        }
    }

    private static void assertSignal(GameTestHelper helper, BlockPos absPos, Direction queryDir,
                                     int expected, String what) {
        int actual = helper.getLevel().getSignal(absPos, queryDir);
        helper.assertTrue(actual == expected,
                what + ": expected " + expected + ", got " + actual
                        + " (query dir " + queryDir + ")");
    }

    private static <T> T findBlockEntity(GameTestHelper helper, Class<T> type, int sx, int sy, int sz) {
        BlockPos rel = findBlockEntityPos(helper, type, sx, sy, sz);
        return type.cast(helper.getLevel().getBlockEntity(helper.absolutePos(rel)));
    }

    /** Scan the template volume (with a 1-block margin for placement offsets). */
    private static <T> BlockPos findBlockEntityPos(GameTestHelper helper, Class<T> type,
                                                   int sx, int sy, int sz) {
        for (int y = -1; y <= sy; y++) {
            for (int x = -1; x <= sx; x++) {
                for (int z = -1; z <= sz; z++) {
                    BlockPos rel = new BlockPos(x, y, z);
                    if (type.isInstance(helper.getLevel().getBlockEntity(helper.absolutePos(rel)))) {
                        return rel;
                    }
                }
            }
        }
        throw new GameTestAssertException("no " + type.getSimpleName() + " in the template volume");
    }
}
