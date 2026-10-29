package lagfinder.mixins;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import lagfinder.core.Category;
import lagfinder.core.ProfileSession;

/** Times each mob's search for a path, apart from the rest of its tick. */
@Mixin(PathNavigation.class)
public abstract class MixinPathNavigation {

    @Shadow
    @Final
    protected Mob mob;

    @WrapOperation(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path lagfinder$timePath(PathFinder finder, PathNavigationRegion region, Mob searcher, Set<BlockPos> targets,
        float range, int accuracy, float depth, Operation<Path> original) {
        ProfileSession session = mob.level()
            .isClientSide() ? null : ProfileSession.current();
        if (session == null) {
            return original.call(finder, region, searcher, targets, range, accuracy, depth);
        }
        long outer = session.enter();
        long start = System.nanoTime();
        try {
            return original.call(finder, region, searcher, targets, range, accuracy, depth);
        } finally {
            session.record(Category.PATHFINDING, mob, start, outer);
        }
    }
}
