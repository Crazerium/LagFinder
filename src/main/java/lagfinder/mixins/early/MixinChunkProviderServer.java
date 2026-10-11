package lagfinder.mixins.early;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import lagfinder.core.ProfileSession;
import lagfinder.core.WorldPart;

/**
 * Times what a world's chunks cost when they are loaded, made and filled: reading them from the disk, generating their
 * terrain and decorating them, wherever in the tick it happens (a player walking into new land, a mod asking for a
 * chunk). Forge adds the first two, so they are not remapped. Each is a part of the world's line in the report; what is loaded while another is loaded counts for itself.
 */
@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    @Shadow
    private WorldServer worldObj;

    @Inject(method = "loadChunk(IILjava/lang/Runnable;)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), remap = false)
    private void lagfinder$loadStarts(int x, int z, Runnable then, CallbackInfoReturnable<Chunk> cir) {
        lagfinder$begin();
    }

    @Inject(method = "loadChunk(IILjava/lang/Runnable;)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"), remap = false)
    private void lagfinder$loadEnds(int x, int z, Runnable then, CallbackInfoReturnable<Chunk> cir) {
        lagfinder$end(WorldPart.CHUNK_LOADING);
    }

    @Inject(method = "originalLoadChunk", at = @At("HEAD"), remap = false)
    private void lagfinder$generationStarts(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        lagfinder$begin();
    }

    @Inject(method = "originalLoadChunk", at = @At("RETURN"), remap = false)
    private void lagfinder$generationEnds(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        lagfinder$end(WorldPart.CHUNK_GENERATION);
    }

    @Inject(method = { "populate", "func_73153_a" }, at = @At("HEAD"))
    private void lagfinder$populationStarts(IChunkProvider provider, int x, int z, CallbackInfo ci) {
        lagfinder$begin();
    }

    @Inject(method = { "populate", "func_73153_a" }, at = @At("RETURN"))
    private void lagfinder$populationEnds(IChunkProvider provider, int x, int z, CallbackInfo ci) {
        lagfinder$end(WorldPart.CHUNK_POPULATION);
    }

    private static void lagfinder$begin() {
        ProfileSession session = ProfileSession.current();
        if (session != null && session.timesWorlds()) {
            session.stepBegin();
        }
    }

    private void lagfinder$end(WorldPart part) {
        ProfileSession session = ProfileSession.current();
        if (session != null && session.timesWorlds()) {
            session.stepEnd(worldObj, part);
        }
    }
}
