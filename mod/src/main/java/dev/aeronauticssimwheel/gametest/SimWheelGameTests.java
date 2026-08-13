package dev.aeronauticssimwheel.gametest;

import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import dev.aeronauticssimwheel.content.SimChannel;
import dev.aeronauticssimwheel.content.SimSteeringWheelBlockEntity;
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

import java.util.UUID;

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

        // Bind both ends to the same red-wool pair. The receiver side goes through
        // Create's own public API (self-registers in the network); the template's
        // frequency NBT is not relied on.
        wheel.bindChannel(SimChannel.THROTTLE,
                new ItemStack(Items.RED_WOOL), new ItemStack(Items.RED_WOOL));
        UUID driver = UUID.randomUUID();

        helper.runAtTickTime(2, () -> {
            LinkBehaviour receiver = link.getBehaviour(LinkBehaviour.TYPE);
            helper.assertTrue(receiver != null, "template link must have a LinkBehaviour");
            receiver.setFrequency(true, new ItemStack(Items.RED_WOOL));
            receiver.setFrequency(false, new ItemStack(Items.RED_WOOL));
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
