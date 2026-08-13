package dev.aeronauticssimwheel.registry;

import dev.aeronauticssimwheel.AeronauticsSimwheel;
import dev.aeronauticssimwheel.content.SimControlBlock;
import dev.aeronauticssimwheel.content.SimControlBlockEntity;
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

/** First-party content registration. */
public final class SimWheelRegistry {

    private static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(AeronauticsSimwheel.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(AeronauticsSimwheel.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AeronauticsSimwheel.MOD_ID);

    public static final DeferredBlock<SimControlBlock> SIM_CONTROL =
            BLOCKS.register("sim_control", () -> new SimControlBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_ORANGE)
                            .strength(1.5f)
                            .sound(SoundType.WOOD)));

    public static final DeferredItem<BlockItem> SIM_CONTROL_ITEM =
            ITEMS.register("sim_control", () -> new BlockItem(SIM_CONTROL.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SimControlBlockEntity>> SIM_CONTROL_BE =
            BLOCK_ENTITIES.register("sim_control", () -> BlockEntityType.Builder
                    .of(SimControlBlockEntity::new, SIM_CONTROL.get())
                    .build(null));

    private SimWheelRegistry() {
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
