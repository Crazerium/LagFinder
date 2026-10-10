package lagfinder.mixins.early;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import lagfinder.server.ServerScanner;

/**
 * Marks where the server's tick starts and ends around all of it: the tick events a mod listens to on the highest
 * priority at the start (or the lowest at the end) may run before (after) LagFinder's own and be left out otherwise.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Inject(method = "tick", at = @At("HEAD"))
    private void lagfinder$tickStarts(CallbackInfo ci) {
        ServerScanner.INSTANCE.tickStarts();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lagfinder$tickEnds(CallbackInfo ci) {
        ServerScanner.INSTANCE.tickEnds();
    }
}
