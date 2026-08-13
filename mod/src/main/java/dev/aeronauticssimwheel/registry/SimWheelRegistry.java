package dev.aeronauticssimwheel.registry;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.content.SimSteeringWheelBlock;
import dev.aeronauticssimwheel.content.SimSteeringWheelBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** First-party content registration: the Sim Steering Wheel, the only control surface. */
public final class SimWheelRegistry {

    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(AeronauticsSimwheel.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(AeronauticsSimwheel.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AeronauticsSimwheel.MOD_ID);

    public static final DeferredBlock<SimSteeringWheelBlock> SIM_STEERING_WHEEL =
            BLOCKS.register("sim_steering_wheel", () -> new SimSteeringWheelBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_ORANGE)
                            .strength(1.5f)
                            .sound(SoundType.WOOD)));

    public static final DeferredItem<BlockItem> SIM_STEERING_WHEEL_ITEM =
            ITEMS.register("sim_steering_wheel",
                    () -> new BlockItem(SIM_STEERING_WHEEL.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SimSteeringWheelBlockEntity>> SIM_STEERING_WHEEL_BE =
            BLOCK_ENTITIES.register("sim_steering_wheel", () -> BlockEntityType.Builder
                    .of(SimSteeringWheelBlockEntity::new, SIM_STEERING_WHEEL.get())
                    .build(null));

    private SimWheelRegistry() {
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
