package lagfinder.mixins.early;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.village.VillageCollection;
import net.minecraft.village.VillageSiege;
import net.minecraft.world.SpawnerAnimals;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ProfileSession;
import lagfinder.core.WorldPart;

/**
 * Times scheduled block ticks ({@code tickUpdates}) and random ones ({@code func_147456_g}), and the steps of a
 * world's own tick: spawning mobs, unloading chunks, the sweep for random ticks, sending changes to the players,
 * villages and portals. Each is a part of the world's line in the report.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @WrapOperation(
        method = { "tickUpdates", "func_147456_g" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/block/Block;updateTick(Lnet/minecraft/world/World;IIILjava/util/Random;)V"))
    private void lagfinder$timeBlockTick(Block block, World world, int x, int y, int z, Random random,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(block, world, x, y, z, random);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(block, world, x, y, z, random);
        } finally {
            session.recordBlockTick(world, block, x, y, z, start, outer);
        }
    }

    /** Mobs spawning around the players. */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/SpawnerAnimals;findChunksForSpawning(Lnet/minecraft/world/WorldServer;ZZZ)I"))
    private int lagfinder$timeSpawning(WorldServer world, boolean hostile, boolean peaceful, boolean animals,
        Operation<Integer> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || !session.timesWorlds()) {
            return original.call(world, hostile, peaceful, animals);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(world, hostile, peaceful, animals);
        } finally {
            session.recordStep(world, WorldPart.MOB_SPAWNING, start, outer);
        }
    }

    /** Chunks no player needs unloaded. */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/IChunkProvider;unloadQueuedChunks()Z"))
    private boolean lagfinder$timeChunkUnloading(IChunkProvider provider, Operation<Boolean> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || !session.timesWorlds()) {
            return original.call(provider);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(provider);
        } finally {
            session.recordStep((World) (Object) this, WorldPart.CHUNK_UNLOADING, start, outer);
        }
    }

    /** The sweep of the loaded chunks for random ticks, lightning, snow and ice (the blocks' own ticks are apart). */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;func_147456_g()V"))
    private void lagfinder$timeRandomTicks(WorldServer world, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || !session.timesWorlds()) {
            original.call(world);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world);
        } finally {
            session.recordStep(world, WorldPart.RANDOM_TICKS, start, outer);
        }
    }

    /** Block changes and chunks sent to the players. */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/management/PlayerManager;updatePlayerInstances()V"))
    private void lagfinder$timePlayerChunks(PlayerManager manager, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || !session.timesWorlds()) {
            original.call(manager);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(manager);
        } finally {
            session.recordStep((World) (Object) this, WorldPart.PLAYER_CHUNKS, start, outer);
        }
    }

    /** Villages... */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/village/VillageCollection;tick()V"))
    private void lagfinder$timeVillages(VillageCollection villages, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || !session.timesWorlds()) {
            original.call(villages);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(villages);
        } finally {
            session.recordStep((World) (Object) this, WorldPart.VILLAGES, start, outer);
        }
    }

    /** ...and the sieges of zombies on them. */
    @WrapOperation(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/village/VillageSiege;tick()V"))
    private void lagfinder$timeSieges(VillageSiege siege, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || !session.timesWorlds()) {
            original.call(siege);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(siege);
        } finally {
            session.recordStep((World) (Object) this, WorldPart.VILLAGES, start, outer);
        }
    }

    /** Portal exits remembered expiring. */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/Teleporter;removeStaleTeleportLocations()V"))
    private void lagfinder$timePortals(Teleporter teleporter, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null || !session.timesWorlds()) {
            original.call(teleporter);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(teleporter);
        } finally {
            session.recordStep((World) (Object) this, WorldPart.PORTALS, start, outer);
        }
    }
}
