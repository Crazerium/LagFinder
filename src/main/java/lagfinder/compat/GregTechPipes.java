package lagfinder.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import lagfinder.core.Describer;
import lagfinder.core.Node;
import lagfinder.core.ProfileSession;

public final class GregTechPipes {

    public static final int CABLE = 0, ITEM = 1, FLUID = 2;
    private static final int LIMIT = 50000;
    private static final String[] FAMILY = { "GT cable network", "GT item pipe network", "GT fluid pipe network" };
    private static final Class<?> PIPE_TILE = find("gregtech.api.metatileentity.BaseMetaPipeEntity");
    private static final Class<?> META_PIPE = find("gregtech.api.metatileentity.MetaPipeEntity");
    private static final Class<?> CABLE_META = find("gregtech.api.metatileentity.implementations.MTECable");
    private static final Class<?> ITEM_META = find("gregtech.api.interfaces.metatileentity.IMetaTileEntityItemPipe");
    private static final Class<?> FLUID_META = find("gregtech.api.metatileentity.implementations.MTEFluidPipe");
    private static final Method META = method(PIPE_TILE, "getMetaTileEntity");
    private static final Field CONNECTIONS = field(META_PIPE, "mConnections");

    private static TileEntity ticking;

    private GregTechPipes() {}

    public static int family(Object meta) {
        if (meta == null) {
            return -1;
        }
        if (CABLE_META != null && CABLE_META.isInstance(meta)) {
            return CABLE;
        }
        if (ITEM_META != null && ITEM_META.isInstance(meta)) {
            return ITEM;
        }
        if (FLUID_META != null && FLUID_META.isInstance(meta)) {
            return FLUID;
        }
        return -1;
    }

    public static TileEntity beginTick(TileEntity pipe) {
        TileEntity previous = ticking;
        ticking = pipe;
        return previous;
    }

    public static void endTick(TileEntity previous) {
        ticking = previous;
    }

    public static TileEntity ticking() {
        return ticking;
    }

    public static void describe(Node leaf, Describer describer) {
        TileEntity pipe = (TileEntity) leaf.key;
        switch (leaf.pipePart) {
            case ProfileSession.PIPE_GT_ROUTE:
                describer.describeBlock(leaf, pipe);
                leaf.label = leaf.name;
                leaf.typeKey = "lagfinder:gt_route_search";
                leaf.name = "Item route search";
                leaf.translationKey = "lagfinder.pipes.route_search";
                leaf.detail = "IMetaTileEntityItemPipe.Util.scanPipes";
                leaf.itemId = -1;
                break;
            case ProfileSession.PIPE_GT_ENERGY:
                describeSource(leaf, pipe, describer);
                break;
            default:
                describer.describeBlock(leaf, pipe);
        }
        leaf.mod = networkName(pipe, describer.pipeNetworkNames, describer.pipeNetworkParts);
        if (leaf.pipePart == ProfileSession.PIPE_GT_ROUTE || leaf.pipePart == ProfileSession.PIPE_GT_ENERGY) {
            leaf.parts = describer.pipeNetworkParts.get(pipe);
        }
    }

    private static void describeSource(Node leaf, TileEntity cable, Describer describer) {
        ForgeDirection side = ForgeDirection.getOrientation(leaf.coverSide);
        TileEntity source = neighbor(cable, side);
        if (source != null && source != cable) {
            describer.describeBlock(leaf, source);
            leaf.typeKey = "lagfinder:gt_energy|" + leaf.typeKey;
            leaf.detail = "Sends energy into the network: " + leaf.detail;
        } else {
            describer.describeBlock(leaf, cable);
            leaf.typeKey = "lagfinder:gt_energy_in";
            leaf.name = "Energy put in from outside";
            leaf.translationKey = "lagfinder.pipes.energy_in";
            leaf.detail = "BaseMetaPipeEntity.injectEnergyUnits";
            leaf.itemId = -1;
        }
        leaf.label = side.name()
            .toLowerCase();
    }

    private static String networkName(TileEntity pipe, Map<Object, String> names, Map<Object, int[]> parts) {
        String known = names.get(pipe);
        if (known != null) {
            return known;
        }
        int family = family(metaOf(pipe));
        World world = pipe.getWorldObj();
        List<TileEntity> members = new ArrayList<>();
        Set<TileEntity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<TileEntity> queue = new ArrayDeque<>();
        seen.add(pipe);
        queue.add(pipe);
        TileEntity lowest = pipe;
        while (!queue.isEmpty()) {
            TileEntity tile = queue.poll();
            members.add(tile);
            if (before(tile, lowest)) {
                lowest = tile;
            }
            if (world == null || seen.size() >= LIMIT) {
                continue;
            }
            int connections = connections(metaOf(tile));
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                if ((connections & side.flag) == 0) {
                    continue;
                }
                TileEntity next = neighbor(tile, side);
                if (next == null || seen.contains(next)
                    || !PIPE_TILE.isInstance(next)
                    || family(metaOf(next)) != family) {
                    continue;
                }
                seen.add(next);
                queue.add(next);
            }
        }
        StringBuilder text = new StringBuilder(family >= 0 ? FAMILY[family] : "GT pipe network");
        text.append(" · ")
            .append(world != null ? Describer.dimensionNameOf(world) : "DIM")
            .append(' ')
            .append(lowest.xCoord)
            .append(", ")
            .append(lowest.yCoord)
            .append(", ")
            .append(lowest.zCoord)
            .append(" · ")
            .append(members.size());
        if (seen.size() >= LIMIT) {
            text.append('+');
        }
        text.append(members.size() == 1 ? " segment" : " segments");
        String name = text.toString();
        int[] places = Describer.partsOf(members);
        for (TileEntity member : members) {
            names.put(member, name);
            parts.put(member, places);
        }
        return name;
    }

    private static TileEntity neighbor(TileEntity tile, ForgeDirection side) {
        World world = tile.getWorldObj();
        if (world == null || side == ForgeDirection.UNKNOWN) {
            return null;
        }
        int x = tile.xCoord + side.offsetX, y = tile.yCoord + side.offsetY, z = tile.zCoord + side.offsetZ;
        if (y < 0 || y >= 256 || !world.blockExists(x, y, z)) {
            return null;
        }
        return world.getTileEntity(x, y, z);
    }

    private static boolean before(TileEntity a, TileEntity b) {
        if (a.xCoord != b.xCoord) {
            return a.xCoord < b.xCoord;
        }
        return a.yCoord != b.yCoord ? a.yCoord < b.yCoord : a.zCoord < b.zCoord;
    }

    private static Object metaOf(TileEntity tile) {
        if (META == null || tile == null || !PIPE_TILE.isInstance(tile)) {
            return null;
        }
        try {
            return META.invoke(tile);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int connections(Object meta) {
        if (CONNECTIONS == null || meta == null || !META_PIPE.isInstance(meta)) {
            return 0;
        }
        try {
            return CONNECTIONS.getByte(meta) & 0x3F;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, GregTechPipes.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getField(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
