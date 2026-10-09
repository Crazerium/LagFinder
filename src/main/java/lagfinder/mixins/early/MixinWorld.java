package lagfinder.mixins.early;

import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;

/** Times each tile entity and entity update of a world's tick, on the server and in the client's own world. */
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
        long start = System.nanoTime();
        try {
            original.call(tile);
        } finally {
            session.record(Category.TILE_ENTITIES, tile, System.nanoTime() - start);
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
        long start = System.nanoTime();
        try {
            original.call(world, entity);
        } finally {
            session.record(Category.ENTITIES, entity, System.nanoTime() - start);
        }
    }
}
