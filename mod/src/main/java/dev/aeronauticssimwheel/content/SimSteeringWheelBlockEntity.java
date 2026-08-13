package dev.aeronauticssimwheel.content;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import dev.aeronauticssimwheel.registry.SimWheelRegistry;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative state of the Sim Steering Wheel (DESIGN.md §5).
 *
 * <p><b>Steering</b> is native: the input packet sets a target angle (float
 * degrees within the configured ±lock), the block chases it under a sanity
 * slew clamp only (1080°/s — direct authority, not the stock wheel's 16 RPM),
 * and the current angle drives the stock-compatible redstone side outputs.
 *
 * <p><b>Everything else</b> (throttle, brake, clutch, buttons) transmits on
 * Create redstone-link frequencies — one {@link IRedstoneLinkable} entry per
 * bound {@link SimChannel}, the proven machinery from the SimControl block
 * (alive-before-add registration order and all).
 *
 * <p><b>Safety</b>: input silence for {@value #INPUT_TIMEOUT_TICKS} ticks
 * neutralizes — steering ramps back to center, every channel zeroes except
 * BRAKE, which latches the configured failsafe level (a dead-man's brake).
 * Control loss is neutral, never latched.
 *
 * <p><b>Occupancy</b>: one driver. The packet handler validates the sender is
 * seated within reach (sub-level pose aware); the first accepted frame latches
 * the driver and their seat until they stop sending or leave the seat. Stored
 * list-shaped nowhere yet — multi-station is a deliberate v2 extension seam.
 */
public final class SimSteeringWheelBlockEntity extends BlockEntity {

    public static final int INPUT_TIMEOUT_TICKS = 30;
    /** Direct authority with a sanity clamp: 1080°/s at 20 tps. */
    public static final float MAX_SLEW_DEG_PER_TICK = 54f;
    public static final float DEFAULT_LOCK_DEG = 450f;
    public static final int[] LOCK_PRESETS = {180, 270, 360, 450, 540, 720, 900, 1080};
    public static final int[] FAILSAFE_PRESETS = {0, 5, 10, 15};

    private final Map<SimChannel, ChannelEntry> channels = new EnumMap<>(SimChannel.class);

    @Nullable
    private UUID user;
    @Nullable
    private BlockPos seatPos;
    private long lastInputGameTime = Long.MIN_VALUE;

    private float targetDeg;
    private float angleDeg;
    private float lockDeg = DEFAULT_LOCK_DEG;
    private int failsafeBrake;

    /** Last values pushed to redstone neighbors / clients, for change detection. */
    private int lastEmittedSteer = Integer.MIN_VALUE;
    private boolean lastEmittedEngaged;

    /** Transient placeholder-UX cursor: link channels, then the two settings. */
    private int configCursor;
    private static final int CURSOR_LOCK = SimChannel.values().length;
    private static final int CURSOR_FAILSAFE = CURSOR_LOCK + 1;
    private static final int CURSOR_COUNT = CURSOR_FAILSAFE + 1;

    public SimSteeringWheelBlockEntity(BlockPos pos, BlockState state) {
        super(SimWheelRegistry.SIM_STEERING_WHEEL_BE.get(), pos, state);
        for (SimChannel ch : SimChannel.values()) {
            channels.put(ch, new ChannelEntry(ch));
        }
    }

    // ------------------------------------------------------------------
    // Input path
    // ------------------------------------------------------------------

    /**
     * Apply one validated input frame (the packet handler and gametests call
     * this; occupancy/range validation happens in the handler). Axes are
     * hardware-space floats: steering −1…1 of the lock, pedals 0…1.
     *
     * @return false when another driver currently owns the wheel
     */
    public boolean applyInput(UUID player, @Nullable BlockPos seat, float steering,
                              float throttle, float brake, float clutch, int buttonMask) {
        if (level == null || level.isClientSide) {
            return false;
        }
        if (user != null && !user.equals(player) && !inputTimedOut()) {
            return false; // seat mutex: first driver wins until they let go
        }
        user = player;
        seatPos = seat;
        lastInputGameTime = level.getGameTime();

        targetDeg = Mth.clamp(steering, -1f, 1f) * lockDeg;

        float s = Mth.clamp(steering, -1f, 1f);
        setLevel(SimChannel.STEER_LEFT, s < 0 ? quantize(-s) : 0);
        setLevel(SimChannel.STEER_RIGHT, s > 0 ? quantize(s) : 0);
        setLevel(SimChannel.THROTTLE, quantize(Mth.clamp(throttle, 0f, 1f)));
        setLevel(SimChannel.BRAKE, quantize(Mth.clamp(brake, 0f, 1f)));
        setLevel(SimChannel.CLUTCH, quantize(Mth.clamp(clutch, 0f, 1f)));
        for (int i = 0; i < SimChannel.BUTTONS.length; i++) {
            setLevel(SimChannel.BUTTONS[i], (buttonMask >> i & 1) != 0 ? 15 : 0);
        }
        return true;
    }

    /**
     * Release control: steering target back to center, all channels zero —
     * except BRAKE, which latches the failsafe level so an unattended craft
     * rolls to a stop instead of coasting (the wheel-mount brake input is the
     * block above each mount; 0 keeps stock behavior).
     */
    public void neutralize() {
        user = null;
        seatPos = null;
        targetDeg = 0f;
        for (SimChannel ch : SimChannel.values()) {
            setLevel(ch, ch == SimChannel.BRAKE ? failsafeBrake : 0);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SimSteeringWheelBlockEntity be) {
        if (be.user != null && be.inputTimedOut()) {
            be.neutralize();
        }

        // Direct angle authority under the sanity slew clamp.
        float delta = Mth.clamp(be.targetDeg - be.angleDeg,
                -MAX_SLEW_DEG_PER_TICK, MAX_SLEW_DEG_PER_TICK);
        be.angleDeg += delta;

        int steer = be.quantizedSteerFlipped(state.getValue(SimSteeringWheelBlock.FACING));
        boolean engaged = be.engaged();
        if (steer != be.lastEmittedSteer || engaged != be.lastEmittedEngaged) {
            be.lastEmittedSteer = steer;
            be.lastEmittedEngaged = engaged;
            level.updateNeighborsAt(pos, state.getBlock());
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    private boolean inputTimedOut() {
        return level != null && level.getGameTime() - lastInputGameTime > INPUT_TIMEOUT_TICKS;
    }

    public boolean engaged() {
        return user != null;
    }

    @Nullable
    public UUID user() {
        return user;
    }

    @Nullable
    public BlockPos boundSeat() {
        return seatPos;
    }

    public float angleDeg() {
        return angleDeg;
    }

    public float lockDeg() {
        return lockDeg;
    }

    public int failsafeBrake() {
        return failsafeBrake;
    }

    /** Dead-man's brake level applied to the BRAKE channel on signal loss (0 = off). */
    public void setFailsafeBrake(int level) {
        failsafeBrake = Mth.clamp(level, 0, 15);
        setChanged();
    }

    // ------------------------------------------------------------------
    // Redstone output (stock steering wheel convention — see the block class)
    // ------------------------------------------------------------------

    /** Signed 0–15 steer quantization, stock-style: any past-deadband angle registers. */
    public int quantizedSteer() {
        if (Math.abs(angleDeg) < 0.99f) {
            return 0;
        }
        float frac = Mth.clamp(angleDeg / lockDeg, -1f, 1f);
        return (int) (frac < 0 ? Math.floor(frac * 15) : Math.ceil(frac * 15));
    }

    /**
     * The stock wheel flips the emitted sign for east/south facings so physical
     * left/right stays consistent regardless of placement — mirrored exactly.
     */
    private int quantizedSteerFlipped(Direction facing) {
        int flip = (facing.getStepX() == 1 || facing.getStepZ() == 1) ? -1 : 1;
        return quantizedSteer() * flip;
    }

    /**
     * Analog output toward the consumer on {@code side} of this block:
     * facing side = engaged 15/0 (the stock "held" output), clockwise side =
     * positive steer 0–15, counterclockwise side = negative steer 0–15.
     */
    public int directionalSignal(Direction facing, Direction side) {
        if (side == facing) {
            return engaged() ? 15 : 0;
        }
        int value = quantizedSteerFlipped(facing);
        if (side == facing.getClockWise() && value > 0) {
            return value;
        }
        if (side == facing.getCounterClockWise() && value < 0) {
            return -value;
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Link channels
    // ------------------------------------------------------------------

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
        return Math.round(Mth.clamp(unipolar, 0f, 1f) * 15f);
    }

    // ------------------------------------------------------------------
    // Placeholder config flow (block interactions; a proper screen replaces this)
    // ------------------------------------------------------------------

    public String configCursorNext() {
        configCursor = (configCursor + 1) % CURSOR_COUNT;
        return cursorName();
    }

    private String cursorName() {
        if (configCursor == CURSOR_LOCK) {
            return "STEERING_LOCK";
        }
        if (configCursor == CURSOR_FAILSAFE) {
            return "FAILSAFE_BRAKE";
        }
        return SimChannel.values()[configCursor].name();
    }

    /** Empty-hand click: cycle the targeted setting, or describe the targeted channel. */
    public String applyOrDescribeCursor() {
        if (configCursor == CURSOR_LOCK) {
            lockDeg = nextPreset(LOCK_PRESETS, (int) lockDeg);
            setChanged();
            return "Steering lock: ±" + (int) lockDeg + "°";
        }
        if (configCursor == CURSOR_FAILSAFE) {
            failsafeBrake = nextPreset(FAILSAFE_PRESETS, failsafeBrake);
            setChanged();
            return "Failsafe brake: " + failsafeBrake + "/15 on signal loss";
        }
        SimChannel ch = SimChannel.values()[configCursor];
        ChannelEntry entry = channels.get(ch);
        return ch + (entry.isBound()
                ? " bound to " + entry.first.getHoverName().getString() + " ×2"
                : " unbound (right-click with an item to bind)");
    }

    /** Item click: bind the targeted channel to (held, held). */
    public String bindCursorChannel(ItemStack held) {
        if (configCursor >= CURSOR_LOCK) {
            return cursorName() + " is a setting — click with an empty hand to cycle it";
        }
        SimChannel ch = SimChannel.values()[configCursor];
        bindChannel(ch, held, held);
        return ch + " bound to " + held.getHoverName().getString() + " ×2";
    }

    private static int nextPreset(int[] presets, int current) {
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == current) {
                return presets[(i + 1) % presets.length];
            }
        }
        return presets[0];
    }

    // ------------------------------------------------------------------
    // Lifecycle & persistence
    // ------------------------------------------------------------------

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
        tag.putFloat("LockDeg", lockDeg);
        tag.putInt("FailsafeBrake", failsafeBrake);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Bindings")) {
            CompoundTag bindings = tag.getCompound("Bindings");
            for (SimChannel ch : SimChannel.values()) {
                if (bindings.contains(ch.name())) {
                    CompoundTag pair = bindings.getCompound(ch.name());
                    bindChannel(ch, stackOf(pair.getString("First")), stackOf(pair.getString("Second")));
                }
            }
        }
        if (tag.contains("LockDeg")) {
            lockDeg = tag.getFloat("LockDeg");
        }
        if (tag.contains("FailsafeBrake")) {
            failsafeBrake = tag.getInt("FailsafeBrake");
        }
        // Client sync payload (update tag only — never persisted)
        if (tag.contains("Angle")) {
            angleDeg = tag.getFloat("Angle");
        }
    }

    /** Client sync: angle + lock + engagement for HUD/renderer. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Angle", angleDeg);
        tag.putFloat("LockDeg", lockDeg);
        tag.putInt("FailsafeBrake", failsafeBrake);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static ItemStack stackOf(String id) {
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id)).orElse(Items.AIR);
        return new ItemStack(item);
    }

    /**
     * One link-network transmitter per channel — ported verbatim from the
     * SimControl block (gametested), including the registration-order fix.
     */
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
            return SimSteeringWheelBlockEntity.this.level;
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
            return inNetwork && !SimSteeringWheelBlockEntity.this.isRemoved();
        }

        @Override
        public Couple<Frequency> getNetworkKey() {
            return key;
        }

        @Override
        public BlockPos getLocation() {
            return SimSteeringWheelBlockEntity.this.getBlockPos();
        }
    }
}
