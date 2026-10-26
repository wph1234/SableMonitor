package dev.wphuujmhu.sablemonitor.mixin;

import dev.wphuujmhu.sablemonitor.natives.PatchedNatives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Sable's native loader so that SableMonitor's patched {@code sable_rapier} build is the
 * one that ends up in the JVM.
 * <p>
 * Sable's {@code Rapier3D} has a private static {@code void loadLibrary()} that unconditionally
 * deletes and re-extracts its bundled native library, then loads it. We inject at the head of that
 * method: if this mod ships a patched library for the current platform, we extract and load it
 * ourselves and cancel the original, so Sable never overwrites it.
 * <p>
 * If the patched library is not bundled, nothing happens and Sable loads its own natives exactly
 * as before - the only consequence is that the profiler reports itself as unavailable.
 */
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D", remap = false)
public abstract class RapierNativeLoaderMixin {
    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sablemonitor$usePatchedNatives(final CallbackInfo ci) {
        if (PatchedNatives.tryLoad()) {
            ci.cancel();
        }
    }
}
