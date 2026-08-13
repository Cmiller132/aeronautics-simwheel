package dev.aeronauticssimwheel.content;

import com.mojang.serialization.MapCodec;
import dev.aeronauticssimwheel.registry.SimWheelRegistry;
import dev.simulated_team.simulated.api.IDirectionalAnalogOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
import org.jetbrains.annotations.Nullable;

/**
 * The Sim Steering Wheel — the ONLY block the sim rig talks to (DESIGN.md §5).
 * One block, three roles:
 *
 * <ul>
 *   <li><b>Steering column</b>: direct float angle authority from the hardware
 *       wheel (no 16 RPM chase), emitted as stock-steering-wheel-compatible
 *       analog redstone on the side faces (clockwise face = positive steer,
 *       counterclockwise = negative, facing face = engaged 15/0) so Offroad
 *       wheel mounts and existing redstone wiring work as a drop-in swap.</li>
 *   <li><b>Link controller</b>: the typewriter replacement — throttle, brake,
 *       clutch and eight buttons transmit on Create redstone-link frequencies
 *       ({@link SimChannel}).</li>
 *   <li><b>FFB rig root</b>: the block entity is the telemetry attach point
 *       (Phase 2a).</li>
 * </ul>
 *
 * <p>Placeholder binding UX until the config screen lands: sneak-right-click
 * (empty hand) cycles the configuration target (each link channel, then
 * steering lock, then failsafe brake); right-click with an item binds the
 * targeted channel's frequency pair to (held, held) — the community's
 * "same item twice" typewriter idiom; right-click empty-handed applies the
 * targeted non-channel setting (cycles lock presets / failsafe level) or
 * shows the current binding. Visual is a placeholder cube until the art pass.
 */
public final class SimSteeringWheelBlock extends HorizontalDirectionalBlock
        implements EntityBlock, IDirectionalAnalogOutput {

    public static final MapCodec<SimSteeringWheelBlock> CODEC = simpleCodec(SimSteeringWheelBlock::new);

    public SimSteeringWheelBlock(Properties properties) {
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
        return new SimSteeringWheelBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || type != SimWheelRegistry.SIM_STEERING_WHEEL_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) ->
                SimSteeringWheelBlockEntity.serverTick(lvl, pos, st, (SimSteeringWheelBlockEntity) be);
    }

    // ------------------------------------------------------------------
    // Redstone output — the stock steering wheel's exact side convention
    // (verified against Simulated SteeringWheelBlock.getAnalogOutputSignalFrom)
    // so wheel mounts and wiring behave identically after a drop-in swap.
    // ------------------------------------------------------------------

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * Vanilla weak power. {@code dir} is the query direction (from the asking
     * neighbor toward this block), so the consumer sits on {@code
     * dir.getOpposite()} — see DiodeBlock for the vanilla convention.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        if (level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            return be.directionalSignal(state.getValue(FACING), dir.getOpposite());
        }
        return 0;
    }

    /**
     * Simulated's directional analog API (comparator mixin et al.). Its callers
     * pass the side of this block they sit on, matching the stock wheel's use.
     */
    @Override
    public int getAnalogOutputSignalFrom(BlockState state, Level level, BlockPos pos, Direction dir) {
        if (level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            return be.directionalSignal(state.getValue(FACING), dir);
        }
        return 0;
    }

    /** Vanilla comparator: absolute steering magnitude 0–15. */
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            return Math.abs(be.quantizedSteer());
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Placeholder configuration flow (chat-feedback, replaced by a screen later)
    // ------------------------------------------------------------------

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            if (player.isShiftKeyDown()) {
                player.displayClientMessage(Component.literal(
                        "SimWheel config target: " + be.configCursorNext()), true);
            } else {
                player.displayClientMessage(Component.literal(be.applyOrDescribeCursor()), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty() || player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            player.displayClientMessage(Component.literal(be.bindCursorChannel(stack)), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof SimSteeringWheelBlockEntity be) {
            be.neutralize();
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
