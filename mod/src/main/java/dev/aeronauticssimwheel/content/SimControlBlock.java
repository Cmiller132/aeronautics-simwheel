package dev.aeronauticssimwheel.content;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionHand;
import dev.aeronauticssimwheel.registry.SimWheelRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * The SimWheel Control Block (DESIGN.md §5.3b). Placeholder binding UX until
 * the proper screen lands: sneak-right-click (empty hand) cycles the target
 * channel; right-click holding an item binds that channel's frequency pair to
 * (held, held) — the same "same item twice" idiom the community's typewriter
 * cars use. Visual is a placeholder cube until the look-alike wheel art pass.
 */
public final class SimControlBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<SimControlBlock> CODEC = simpleCodec(SimControlBlock::new);

    public SimControlBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SimControlBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != SimWheelRegistry.SIM_CONTROL_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> SimControlBlockEntity.serverTick(lvl, pos, st, (SimControlBlockEntity) be);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SimControlBlockEntity be) {
            be.bindCursorNext();
            player.displayClientMessage(Component.literal(
                    "SimControl bind target: " + be.bindCursorChannel()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SimControlBlockEntity be) {
            SimChannel channel = be.bindCursorChannel();
            be.bindChannel(channel, stack, stack);
            player.displayClientMessage(Component.literal(
                    "SimControl " + channel + " bound to " + stack.getHoverName().getString()
                            + " × " + stack.getHoverName().getString()), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SimControlBlockEntity be) {
            be.neutralize();
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
