package dev.aeronauticssimwheel.content;

import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * The Phase 3 linker flow (DESIGN.md §5.5): stick-click the Sim Steering Wheel
 * to start a session, stick-click wheel mounts to toggle their links, stick-
 * click the wheel again to finish. The wheel side lives in the block's
 * {@code useItemOn}; the mount side hooks {@code RightClickBlock} because the
 * mount is Offroad's block, not ours. Server-side only; sessions are
 * transient (stored in {@link MountLinks}).
 */
public final class MountLinkerInteraction {

    /** A mount farther than this from the wheel is almost certainly a mistake. */
    private static final double MAX_LINK_RANGE = 48.0;

    private MountLinkerInteraction() {
    }

    /** Wheel stick-click: arm or finish this player's linking session. */
    static String toggleSession(Player player, BlockPos wheelPos, SimSteeringWheelBlockEntity be) {
        BlockPos armed = MountLinks.armedWheel(player.getUUID());
        if (armed == null) {
            MountLinks.armLinker(player.getUUID(), wheelPos);
            return "Mount linking ON (" + be.linkedMountCount() + " linked) — "
                    + "stick-click wheel mounts to toggle, stick-click the wheel to finish";
        }
        MountLinks.disarmLinker(player.getUUID());
        return "Mount linking finished: " + be.linkedMountCount() + " mount(s) linked";
    }

    /** NeoForge event: stick-click on a wheel mount while a session is armed. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        Player player = event.getEntity();
        if (level.isClientSide || !event.getItemStack().is(Items.STICK)) {
            return;
        }
        BlockPos wheelPos = MountLinks.armedWheel(player.getUUID());
        if (wheelPos == null) {
            return;
        }
        if (!(level.getBlockEntity(event.getPos()) instanceof WheelMountBlockEntity)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(level.getBlockEntity(wheelPos) instanceof SimSteeringWheelBlockEntity wheel)) {
            MountLinks.disarmLinker(player.getUUID());
            player.displayClientMessage(
                    Component.literal("Linked wheel is gone — session ended"), true);
            return;
        }
        if (!event.getPos().closerThan(wheelPos, MAX_LINK_RANGE)) {
            player.displayClientMessage(Component.literal(
                    "Mount too far from the wheel (max " + (int) MAX_LINK_RANGE + " blocks)"), true);
            return;
        }
        boolean linked = wheel.toggleMountLink(event.getPos());
        player.displayClientMessage(Component.literal(
                (linked ? "Mount LINKED — float steering/brake active ("
                        : "Mount unlinked — back to stock redstone (")
                        + wheel.linkedMountCount() + " total)"), true);
    }
}
