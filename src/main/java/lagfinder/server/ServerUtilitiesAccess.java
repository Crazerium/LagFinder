package lagfinder.server;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.Loader;
import lagfinder.LagFinder;

public final class ServerUtilitiesAccess {

    private static final String RANK_PREFIX = "lagfinder_";
    private static boolean warned;

    private ServerUtilitiesAccess() {}

    public static boolean allowed(EntityPlayerMP player) {
        if (player == null || !Loader.isModLoaded("serverutilities")) {
            return false;
        }
        try {
            Class<?> ranks = Class.forName("serverutils.ranks.Ranks");
            if (!Boolean.TRUE.equals(
                ranks.getMethod("isActive")
                    .invoke(null))) {
                return false;
            }
            Object instance = ranks.getField("INSTANCE")
                .get(null);
            if (instance == null) {
                return false;
            }
            Object playerRank = ranks.getMethod("getPlayerRank", net.minecraft.entity.player.EntityPlayer.class)
                .invoke(instance, player);
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            return matches(playerRank, seen, 0);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ex) {
            if (!warned) {
                warned = true;
                LagFinder.LOG.warn("Could not query ServerUtilities ranks for LagFinder access", ex);
            }
            return false;
        }
    }

    private static boolean matches(Object rank, Set<Object> seen, int depth) throws ReflectiveOperationException {
        if (rank == null || depth > 16 || !seen.add(rank)) {
            return false;
        }
        Method getId = rank.getClass()
            .getMethod("getId");
        Object id = getId.invoke(rank);
        if (id instanceof String && ((String) id).startsWith(RANK_PREFIX)) {
            return true;
        }
        Object parents = rank.getClass()
            .getMethod("getActualParents")
            .invoke(rank);
        if (parents instanceof Iterable<?>) {
            for (Object parent : (Iterable<?>) parents) {
                if (matches(parent, seen, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
