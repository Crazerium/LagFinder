package LagFinder.mixins.early.client;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import LagFinder.core.Category;
import LagFinder.core.ProfileSession;

/** Times what a frame draws one by one: special tile entity renderers, entities, and chunk sections rebuilt. */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {

    @WrapOperation(
        method = "renderEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;renderTileEntity(Lnet/minecraft/tileentity/TileEntity;F)V"))
    private void lagfinder$timeTileRender(TileEntityRendererDispatcher dispatcher, TileEntity tile, float partialTicks,
        Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(dispatcher, tile, partialTicks);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(dispatcher, tile, partialTicks);
        } finally {
            session.record(Category.TILE_RENDER, tile, System.nanoTime() - start);
        }
    }

    @WrapOperation(
        method = "renderEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RenderManager;renderEntitySimple(Lnet/minecraft/entity/Entity;F)Z"))
    private boolean lagfinder$timeEntityRender(RenderManager manager, Entity entity, float partialTicks,
        Operation<Boolean> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            return original.call(manager, entity, partialTicks);
        }
        long start = System.nanoTime();
        try {
            return original.call(manager, entity, partialTicks);
        } finally {
            session.record(Category.ENTITY_RENDER, entity, System.nanoTime() - start);
        }
    }

    @WrapOperation(
        method = "updateRenderers",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/WorldRenderer;updateRenderer(Lnet/minecraft/entity/EntityLivingBase;)V"))
    private void lagfinder$timeChunkUpdate(WorldRenderer renderer, EntityLivingBase viewer, Operation<Void> original) {
        ProfileSession session = ProfileSession.current();
        if (session == null) {
            original.call(renderer, viewer);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(renderer, viewer);
        } finally {
            session.record(Category.CHUNK_UPDATES, renderer, System.nanoTime() - start);
        }
    }
}
