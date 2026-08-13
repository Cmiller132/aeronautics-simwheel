package dev.aeronauticssimwheel.gametest;

import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import dev.aeronauticssimwheel.content.SimChannel;
import dev.aeronauticssimwheel.content.SimControlBlockEntity;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity;
import net.minecraft.core.BlockPos;
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
 * <p>Covers the §5.3b decision: the SimControl block is the only control path,
 * transmitting analog 0–15 on Create redstone-link frequencies. The race car
 * assembly test keeps the primary test vehicle exercising physics.
 */
@GameTestHolder(dev.aeronauticssimwheel.AeronauticsSimwheel.MOD_ID)
public class SimWheelGameTests {

    /**
     * The Tier-1 analog chain end to end with real Create networking: a bound
     * steering channel must reach a real redstone-link receiver on the same
     * frequency at proportional 0–15 levels, and input timeout must neutralize.
     */
    @GameTest(template = "control_rig", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public static void sim_control_transmits_analog_levels(GameTestHelper helper) {
        SimControlBlockEntity control = findBlockEntity(helper, SimControlBlockEntity.class, 5, 5, 5);
        BlockPos linkRel = findBlockEntityPos(helper, RedstoneLinkBlockEntity.class, 5, 5, 5);
        RedstoneLinkBlockEntity link = (RedstoneLinkBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(linkRel));

        // Bind both ends to the same red-wool pair. The receiver side goes through
        // Create's own public API (self-registers in the network); the template's
        // frequency NBT is not relied on.
        control.bindChannel(SimChannel.STEER_RIGHT,
                new ItemStack(Items.RED_WOOL), new ItemStack(Items.RED_WOOL));
        UUID driver = UUID.randomUUID();

        helper.runAtTickTime(2, () -> {
            LinkBehaviour receiver = link.getBehaviour(LinkBehaviour.TYPE);
            helper.assertTrue(receiver != null, "template link must have a LinkBehaviour");
            receiver.setFrequency(true, new ItemStack(Items.RED_WOOL));
            receiver.setFrequency(false, new ItemStack(Items.RED_WOOL));
        });

        helper.runAtTickTime(5, () -> control.applyInput(driver, 1.0f, 0f, 0f, 0));
        helper.runAtTickTime(8, () -> helper.assertTrue(link.getReceivedSignal() == 15,
                "full right steer must reach the receiver as 15, got " + link.getReceivedSignal()));

        helper.runAtTickTime(10, () -> control.applyInput(driver, 0.5f, 0f, 0f, 0));
        helper.runAtTickTime(13, () -> {
            StringBuilder net = new StringBuilder();
            com.simibubi.create.Create.REDSTONE_LINK_NETWORK_HANDLER
                    .getNetworkOf(helper.getLevel(), link.getBehaviour(LinkBehaviour.TYPE))
                    .forEach(m -> net.append(m.getClass().getSimpleName())
                            .append('=').append(m.getTransmittedStrength()).append(' '));
            helper.assertTrue(link.getReceivedSignal() == 8,
                    "half steer must arrive analog (round(0.5*15)=8), got " + link.getReceivedSignal()
                            + " network[" + net + "]");
        });

        helper.runAtTickTime(15, () -> control.applyInput(driver, -1.0f, 0f, 0f, 0));
        helper.runAtTickTime(18, () -> helper.assertTrue(link.getReceivedSignal() == 0,
                "left steer must zero the right channel, got " + link.getReceivedSignal()));

        // No further input frames: the 30-tick timeout must neutralize the channel
        helper.runAtTickTime(20, () -> control.applyInput(driver, 1.0f, 0f, 0f, 0));
        helper.runAtTickTime(60, () -> {
            helper.assertTrue(link.getReceivedSignal() == 0,
                    "input silence must neutralize (timeout), got " + link.getReceivedSignal());
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
