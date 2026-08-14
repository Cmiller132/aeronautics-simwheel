package dev.aeronauticssimwheel.mixin;

import dev.aeronauticssimwheel.content.MountLinks;
import dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalDouble;

/**
 * The mod's FIRST and only mixin (DESIGN.md §5.5 Phase 3): linked wheel mounts
 * take their steering yaw and brake strength as floats from their Sim Steering
 * Wheel instead of the quantized redstone reads — a resolution upgrade of the
 * exact same native formulas, zero new physics. Unlinked mounts return through
 * the {@code MountLinks} emptiness fast path and stay 100% stock.
 *
 * <p>Both injections use {@code require = 0} (via the config's defaultRequire):
 * if an Offroad update moves the targets, the mixin silently doesn't apply and
 * every mount behaves stock — the §10.4 health check reports the drift.
 *
 * <p>Targets verified against offroad-neoforge-1.21.1-1.3.0 bytecode
 * (debug LVT present — {@code brakeStrength} resolves by name):
 * {@code computeYaw()D} is the narrowest un-quantized steering point (bypasses
 * the ±15 int entirely, keeps the 0.4/tick chase lerp and the physics/renderer
 * agreement); the brake read is inlined in {@code sable$physicsTick}, where
 * one {@code @ModifyVariable} at the store covers both the drag term and the
 * {@code (1−brake)} drive cut.
 */
@Mixin(value = WheelMountBlockEntity.class, remap = false)
abstract class WheelMountBlockEntityMixin {

    @Inject(method = "computeYaw()D", at = @At("HEAD"), cancellable = true)
    private void aeronautics_simwheel$linkedSteering(CallbackInfoReturnable<Double> cir) {
        OptionalDouble yaw = MountLinks.steeringYawRad((WheelMountBlockEntity) (Object) this);
        if (yaw.isPresent()) {
            cir.setReturnValue(yaw.getAsDouble());
        }
    }

    @ModifyVariable(
            method = "sable$physicsTick(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"
                    + "Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V",
            at = @At("STORE"),
            name = "brakeStrength")
    private double aeronautics_simwheel$linkedBrake(double original) {
        OptionalDouble brake = MountLinks.brake01((WheelMountBlockEntity) (Object) this);
        return brake.isPresent() ? brake.getAsDouble() : original;
    }
}
