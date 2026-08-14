package dev.aeronauticssimwheel.content;

import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Phase 3 mount linking (DESIGN.md §5.5): the mount→wheel index the
 * {@code WheelMountBlockEntityMixin} consults. A linked mount takes its
 * steering yaw and brake strength as <b>floats</b> from its Sim Steering Wheel
 * — the same native Offroad formulas at un-quantized resolution; unlinked
 * mounts never reach past the {@code isEmpty} fast path and stay 100% stock.
 *
 * <p>Maintained by {@link SimSteeringWheelBlockEntity} on load/unload/link
 * change, on both sides (the client index drives the mount's visual yaw).
 * Lookups happen per mount tick (steering) and per physics substep (brake) —
 * two map hits and a BE fetch, guarded by the emptiness check.
 */
public final class MountLinks {

    /** level → (mount pos → wheel pos). Weak on the level: worlds unload. */
    private static final Map<Level, Map<BlockPos, BlockPos>> BY_LEVEL = new WeakHashMap<>();
    private static volatile boolean empty = true;

    /** Transient linker-tool sessions: player → armed wheel pos (server only). */
    private static final Map<UUID, BlockPos> LINKER_SESSIONS = new HashMap<>();

    private MountLinks() {
    }

    static synchronized void register(Level level, BlockPos mountPos, BlockPos wheelPos) {
        BY_LEVEL.computeIfAbsent(level, l -> new HashMap<>()).put(mountPos, wheelPos);
        empty = false;
    }

    static synchronized void unregister(Level level, BlockPos mountPos, BlockPos wheelPos) {
        Map<BlockPos, BlockPos> map = BY_LEVEL.get(level);
        if (map != null) {
            map.remove(mountPos, wheelPos);
            if (map.isEmpty()) {
                BY_LEVEL.remove(level);
            }
        }
        empty = BY_LEVEL.values().stream().allMatch(Map::isEmpty);
    }

    private static synchronized BlockPos wheelFor(Level level, BlockPos mountPos) {
        Map<BlockPos, BlockPos> map = BY_LEVEL.get(level);
        return map == null ? null : map.get(mountPos);
    }

    /**
     * Mixin entry: the linked steering yaw for this mount, radians in the
     * mount's ±30° convention — or empty to run the stock redstone path.
     */
    public static OptionalDouble steeringYawRad(WheelMountBlockEntity mount) {
        SimSteeringWheelBlockEntity wheel = linkedWheel(mount);
        return wheel == null ? OptionalDouble.empty()
                : OptionalDouble.of(wheel.linkedSteerYawRad());
    }

    /** Mixin entry: the linked brake strength 0..1 — or empty for stock. */
    public static OptionalDouble brake01(WheelMountBlockEntity mount) {
        SimSteeringWheelBlockEntity wheel = linkedWheel(mount);
        return wheel == null ? OptionalDouble.empty()
                : OptionalDouble.of(wheel.linkedBrake01());
    }

    private static SimSteeringWheelBlockEntity linkedWheel(WheelMountBlockEntity mount) {
        if (empty) {
            return null; // the no-links fast path — stock behavior costs one volatile read
        }
        Level level = mount.getLevel();
        if (level == null) {
            return null;
        }
        BlockPos wheelPos = wheelFor(level, mount.getBlockPos());
        if (wheelPos == null) {
            return null;
        }
        // A stale entry (wheel gone, chunk edge) degrades to stock silently.
        return level.getBlockEntity(wheelPos) instanceof SimSteeringWheelBlockEntity be
                ? be : null;
    }

    // ------------------------------------------------------------------
    // Linker-tool sessions (server side; the stick is the linker)
    // ------------------------------------------------------------------

    static synchronized void armLinker(UUID player, BlockPos wheelPos) {
        LINKER_SESSIONS.put(player, wheelPos);
    }

    static synchronized BlockPos disarmLinker(UUID player) {
        return LINKER_SESSIONS.remove(player);
    }

    public static synchronized BlockPos armedWheel(UUID player) {
        return LINKER_SESSIONS.get(player);
    }

    /** Test/debug: number of registered mount links in a level. */
    static synchronized int count(Level level) {
        Map<BlockPos, BlockPos> map = BY_LEVEL.get(level);
        return map == null ? 0 : map.size();
    }

    /** Bulk (re)registration for a wheel's mount set — see the BE lifecycle. */
    static synchronized void replaceAll(Level level, BlockPos wheelPos,
                                        Set<BlockPos> oldMounts, Set<BlockPos> newMounts) {
        for (BlockPos m : oldMounts) {
            unregister(level, m, wheelPos);
        }
        for (BlockPos m : newMounts) {
            register(level, m, wheelPos);
        }
    }
}
