package dev.aeronauticssimwheel.content;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import dev.aeronauticssimwheel.registry.SimWheelRegistry;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * The SimWheel Control Block (DESIGN.md §5.3b): the analog controller for
 * link-wired craft. Sim hardware input arrives as a float packet; each bound
 * channel maintains an {@link IRedstoneLinkable} entry in Create's redstone
 * link network, transmitting quantized 0–15 levels the same way a linked
 * typewriter transmits its binary presses — but proportional.
 *
 * <p>Safety: input silence for {@value #INPUT_TIMEOUT_TICKS} ticks (the
 * linked-controller convention) neutralizes every channel — control loss is
 * neutral, never latched.
 */
public final class SimControlBlockEntity extends BlockEntity {

    public static final int INPUT_TIMEOUT_TICKS = 30;

    private final Map<SimChannel, ChannelEntry> channels = new EnumMap<>(SimChannel.class);

    private UUID user;
    private long lastInputGameTime = Long.MIN_VALUE;
    /** Transient UI state for the placeholder binding flow (sneak-click cycles). */
    private int bindCursor;

    public SimControlBlockEntity(BlockPos pos, BlockState state) {
        super(SimWheelRegistry.SIM_CONTROL_BE.get(), pos, state);
        for (SimChannel ch : SimChannel.values()) {
            channels.put(ch, new ChannelEntry(ch));
        }
    }

    /** Bind a channel to a frequency item pair (server side). */
    public void bindChannel(SimChannel channel, ItemStack first, ItemStack second) {
        ChannelEntry entry = channels.get(channel);
        entry.leaveNetwork();
        entry.first = first.copyWithCount(1);
        entry.second = second.copyWithCount(1);
        entry.key = Couple.create(Frequency.of(entry.first), Frequency.of(entry.second));
        setChanged();
    }

    public boolean isBound(SimChannel channel) {
        return channels.get(channel).isBound();
    }

    /**
     * Apply one input frame (the packet handler and gametests call this).
     * Axes are hardware-space floats; quantization to the link medium's 0–15
     * happens here, once, server-side.
     */
    public void applyInput(UUID player, float steering, float throttle, float brake, int buttonMask) {
        if (level == null || level.isClientSide) {
            return;
        }
        user = player;
        lastInputGameTime = level.getGameTime();

        float s = clamp(steering, -1f, 1f);
        setLevel(SimChannel.STEER_LEFT, s < 0 ? quantize(-s) : 0);
        setLevel(SimChannel.STEER_RIGHT, s > 0 ? quantize(s) : 0);
        setLevel(SimChannel.THROTTLE, quantize(clamp(throttle, 0f, 1f)));
        setLevel(SimChannel.BRAKE, quantize(clamp(brake, 0f, 1f)));
        for (int i = 0; i < SimChannel.BUTTONS.length; i++) {
            setLevel(SimChannel.BUTTONS[i], (buttonMask >> i & 1) != 0 ? 15 : 0);
        }
    }

    /** All channels to zero; entries stay registered until removal. */
    public void neutralize() {
        for (SimChannel ch : SimChannel.values()) {
            setLevel(ch, 0);
        }
        user = null;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SimControlBlockEntity be) {
        if (be.user != null && level.getGameTime() - be.lastInputGameTime > INPUT_TIMEOUT_TICKS) {
            be.neutralize();
        }
    }

    public UUID user() {
        return user;
    }

    public int bindCursorNext() {
        bindCursor = (bindCursor + 1) % SimChannel.values().length;
        return bindCursor;
    }

    public SimChannel bindCursorChannel() {
        return SimChannel.values()[bindCursor];
    }

    private void setLevel(SimChannel channel, int newLevel) {
        ChannelEntry entry = channels.get(channel);
        if (!entry.isBound()) {
            return;
        }
        entry.joinNetwork();
        if (entry.strength != newLevel) {
            entry.strength = newLevel;
            Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, entry);
        }
    }

    private static int quantize(float unipolar) {
        return Math.round(clamp(unipolar, 0f, 1f) * 15f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void setRemoved() {
        releaseNetwork();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        releaseNetwork();
        super.onChunkUnloaded();
    }

    private void releaseNetwork() {
        channels.values().forEach(ChannelEntry::leaveNetwork);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag bindings = new CompoundTag();
        for (var e : channels.entrySet()) {
            if (e.getValue().isBound()) {
                CompoundTag pair = new CompoundTag();
                pair.putString("First", itemId(e.getValue().first));
                pair.putString("Second", itemId(e.getValue().second));
                bindings.put(e.getKey().name(), pair);
            }
        }
        tag.put("Bindings", bindings);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        CompoundTag bindings = tag.getCompound("Bindings");
        for (SimChannel ch : SimChannel.values()) {
            if (bindings.contains(ch.name())) {
                CompoundTag pair = bindings.getCompound(ch.name());
                bindChannel(ch, stackOf(pair.getString("First")), stackOf(pair.getString("Second")));
            }
        }
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static ItemStack stackOf(String id) {
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).orElse(Items.AIR);
        return new ItemStack(item);
    }

    /** One link-network transmitter per channel. */
    private final class ChannelEntry implements IRedstoneLinkable {

        private final SimChannel channel;
        private ItemStack first = ItemStack.EMPTY;
        private ItemStack second = ItemStack.EMPTY;
        private Couple<Frequency> key;
        private int strength;
        private boolean inNetwork;

        ChannelEntry(SimChannel channel) {
            this.channel = channel;
        }

        boolean isBound() {
            return key != null && !(first.isEmpty() && second.isEmpty());
        }

        void joinNetwork() {
            if (!inNetwork && level() != null && !level().isClientSide) {
                // isAlive() reads inNetwork, and addToNetwork immediately runs an
                // updateNetworkOf pass that prunes dead entries — so the flag must
                // be set before the add or we prune ourselves during registration.
                inNetwork = true;
                Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level(), this);
            }
        }

        void leaveNetwork() {
            if (inNetwork && level() != null && !level().isClientSide) {
                strength = 0;
                Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level(), this);
                inNetwork = false;
            }
        }

        private Level level() {
            return SimControlBlockEntity.this.level;
        }

        @Override
        public int getTransmittedStrength() {
            return strength;
        }

        @Override
        public void setReceivedStrength(int power) {
        }

        @Override
        public boolean isListening() {
            return false;
        }

        @Override
        public boolean isAlive() {
            return inNetwork && !SimControlBlockEntity.this.isRemoved();
        }

        @Override
        public Couple<Frequency> getNetworkKey() {
            return key;
        }

        @Override
        public BlockPos getLocation() {
            return SimControlBlockEntity.this.getBlockPos();
        }
    }
}
