package lagfinder.mixins.early;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import cpw.mods.fml.common.FMLCommonHandler;
import lagfinder.server.ServerScanner;

/**
 * Marks where the server's tick, and each world's, starts and ends around all of it: the tick events a mod listens to
 * on the highest priority at the start (or the lowest at the end) may run before (after) LagFinder's own and be left
 * out otherwise.
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

    /** A world's tick starts before FML tells the mods, so all their handlers of it are in the world's time. */
    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/FMLCommonHandler;onPreWorldTick(Lnet/minecraft/world/World;)V",
            remap = false))
    private void lagfinder$worldStarts(FMLCommonHandler fml, World world, Operation<Void> original) {
        ServerScanner.INSTANCE.worldStarts(world);
        original.call(fml, world);
    }

    /** ...and ends after FML told them it ended. */
    @WrapOperation(
        method = "updateTimeLightAndEntities",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/FMLCommonHandler;onPostWorldTick(Lnet/minecraft/world/World;)V",
            remap = false))
    private void lagfinder$worldEnds(FMLCommonHandler fml, World world, Operation<Void> original) {
        original.call(fml, world);
        ServerScanner.INSTANCE.worldEnds(world);
    }
}
