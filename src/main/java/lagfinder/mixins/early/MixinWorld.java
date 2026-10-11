package lagfinder.mixins.early;

import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;
import lagfinder.core.WorldPart;

/**
 * Times each tile entity and entity update of a world's tick, on the server and in the client's own world: their own
 * time, without what is measured inside them (the events they post, the riders they carry). Weather effects
 * (lightning) too, which are updated apart. And two steps of the server's worlds: the weather and the light recalculated
 * after blocks changed.
 */
@Mixin(World.class)
public abstract class MixinWorld {

    @WrapOperation(
        method = "updateEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/tileentity/TileEntity;updateEntity()V"))
    private void lagfinder$timeTileEntity(TileEntity tile, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(tile);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(tile);
        } finally {
            session.recordTile(tile, start, outer);
        }
    }

    @WrapOperation(
        method = "updateEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;updateEntity(Lnet/minecraft/entity/Entity;)V"))
    private void lagfinder$timeEntity(World world, Entity entity, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world, entity);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world, entity);
        } finally {
            session.record(Category.ENTITIES, entity, start, outer);
        }
    }

    /** Weather effects (lightning) are updated on their own, not through updateEntity. */
    @WrapOperation(
        method = "updateEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;onUpdate()V"))
    private void lagfinder$timeWeather(Entity entity, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(entity);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(entity);
        } finally {
            session.record(Category.ENTITIES, entity, start, outer);
        }
    }

    /**
     * A rider is updated inside its mount's update: timed as itself, and left out of the mount's own time (the time
     * measured inside a thing is its inner thing's).
     */
    @WrapOperation(
        method = "updateEntityWithOptionalForce",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;updateEntity(Lnet/minecraft/entity/Entity;)V"))
    private void lagfinder$timeRider(World world, Entity rider, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(world, rider);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(world, rider);
        } finally {
            session.record(Category.ENTITIES, rider, start, outer);
        }
    }

    /** The rain and thunder clocks, and what the weather does. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;updateWeather()V"))
    private void lagfinder$timeRainAndThunder(World world, Operation<Void> original) {
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
            session.recordStep(world, WorldPart.WEATHER, start, outer);
        }
    }

    /** Light recalculated for a block that changed: called many times in a tick, so the one place to time it. */
    @Inject(method = { "updateLightByType", "func_147463_c" }, at = @At("HEAD"))
    private void lagfinder$lightStarts(EnumSkyBlock type, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null && session.timesWorlds()) {
            session.stepBegin();
        }
    }

    @Inject(method = { "updateLightByType", "func_147463_c" }, at = @At("RETURN"))
    private void lagfinder$lightEnds(EnumSkyBlock type, int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        ProfileSession session = ProfileSession.current();
        if (session != null && session.timesWorlds()) {
            session.stepEnd((World) (Object) this, WorldPart.LIGHTING);
        }
    }
}
