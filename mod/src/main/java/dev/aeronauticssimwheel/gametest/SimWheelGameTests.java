package dev.aeronauticssimwheel.gametest;

import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Headless in-game verification of the MVP contract, run on a real server with
 * Create + Simulated + Offroad + Sable loaded ({@code ./gradlew :mod:runGameTest}).
 *
 * <p>These tests exercise the exact server-side effect our client injector's
 * {@code SteeringWheelPacket(false, angle, pos)} has — the packet handler does
 * nothing but {@code be.targetAngleToUpdate = angle; be.startHolding()} — and
 * the primary test vehicle (testdata race car) assembling into a physics craft.
 */
@GameTestHolder(dev.aeronauticssimwheel.AeronauticsSimwheel.MOD_ID)
public class SimWheelGameTests {


    /**
     * Injecting a target angle must slew the kinetic wheel at the fixed 16 RPM
     * (4.8°/tick), not snap — this is the latency model the whole FFB sync-spring
     * design (DESIGN.md §6.5) is built around.
     */
    @GameTest(template = "steering_rig", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public static void steering_wheel_follows_injected_target(GameTestHelper helper) {
        SteeringWheelBlockEntity wheel = findBlockEntity(helper, SteeringWheelBlockEntity.class, 5, 5, 5);
        helper.assertTrue(Math.abs(wheel.getAngle()) < 0.01f, "wheel must start centered");

        // Exactly what SteeringWheelPacket.handle() does server-side:
        wheel.targetAngleToUpdate = 90f;
        wheel.startHolding();

        // Mid-slew (tick 10): ~4.8°/tick → ~48°, definitely neither 0 nor 90.
        helper.runAtTickTime(10, () -> {
            float a = Math.abs(wheel.getAngle());
            helper.assertTrue(a > 20f && a < 80f,
                    "wheel must slew at 16 RPM, was at " + wheel.getAngle() + "° after 10 ticks");
        });

        // Converged: |angle| = 90 within tolerance and holds there.
        helper.runAtTickTime(40, () -> {
            float a = Math.abs(wheel.getAngle());
            helper.assertTrue(Math.abs(a - 90f) < 2f,
                    "wheel must reach the 90° target, was at " + wheel.getAngle() + "°");
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
        BlockPos assemblerPos = findAssembler(helper);
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

    private static BlockPos findAssembler(GameTestHelper helper) {
        return findBlockEntityPos(helper, PhysicsAssemblerBlockEntity.class, 9, 4, 5);
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
