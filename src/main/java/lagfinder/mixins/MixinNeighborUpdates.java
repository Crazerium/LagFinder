package lagfinder.mixins;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.ProfileSession;

/**
 * Times each block's reaction to a neighbor changing (redstone, pipes, rails...), per block and chunk: wherever the
 * game hands a neighbor update to a block, at once or from its queue of updates.
 */
@Mixin(
    targets = { "net.minecraft.world.level.redstone.CollectingNeighborUpdater$SimpleNeighborUpdate",
        "net.minecraft.world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate",
        "net.minecraft.world.level.redstone.CollectingNeighborUpdater$FullNeighborUpdate",
        "net.minecraft.world.level.redstone.InstantNeighborUpdater" })
public abstract class MixinNeighborUpdates {

    @WrapOperation(
        method = { "runNext", "neighborChanged" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;executeUpdate(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos;Z)V"))
    private void lagfinder$timeNeighborUpdate(Level level, BlockState state, BlockPos pos, Block neighbor,
        BlockPos from, boolean moving, Operation<Void> original) {
        ProfileSession session = level.isClientSide() ? null : ProfileSession.current();
        if (session == null) {
            original.call(level, state, pos, neighbor, from, moving);
            return;
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            original.call(level, state, pos, neighbor, from, moving);
        } finally {
            session.recordNeighborUpdate(level, state.getBlock(), pos.getX(), pos.getY(), pos.getZ(), start, outer);
        }
    }
}
