package lagfinder.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import lagfinder.core.Describer;
import lagfinder.core.LocationKind;
import lagfinder.core.ModResolver;
import lagfinder.core.Node;
import lagfinder.core.ProfileSession;

public final class EnderIoConduits {

    private static final Map<String, String> FAMILY = new HashMap<>();
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Method MISSING;

    static {
        FAMILY.put("PowerConduitNetwork", "EnderIO energy conduits");
        FAMILY.put("ItemConduitNetwork", "EnderIO item conduits");
        FAMILY.put("LiquidConduitNetwork", "EnderIO fluid conduits");
        FAMILY.put("AdvancedLiquidConduitNetwork", "EnderIO fluid conduits");
        FAMILY.put("EnderLiquidConduitNetwork", "EnderIO ender fluid conduits");
        FAMILY.put("RedstoneConduitNetwork", "EnderIO redstone conduits");
        FAMILY.put("MEConduitNetwork", "EnderIO ME conduits");
        FAMILY.put("GasConduitNetwork", "EnderIO gas conduits");
        FAMILY.put("OCConduitNetwork", "EnderIO OC conduits");
        Method missing = null;
        try {
            missing = Object.class.getMethod("toString");
        } catch (Throwable ignored) {}
        MISSING = missing;
    }

    private EnderIoConduits() {}

    public static void describe(Node leaf, Describer describer) {
        if (leaf.pipePart == ProfileSession.PIPE_EIO_NETWORK) {
            describeNetwork(leaf, leaf.key, describer.pipeNetworkNames);
        } else {
            describeConduit(leaf, leaf.key, describer.pipeNetworkNames);
        }
    }

    private static void describeNetwork(Node leaf, Object network, Map<Object, String> names) {
        Class<?> type = network.getClass();
        List<TileEntity> tiles = tilesOf(network);
        TileEntity at = lowest(tiles);
        leaf.mod = networkName(network, tiles, at, names);
        leaf.typeKey = "lagfinder:eio_network|" + type.getName();
        leaf.name = "Network tick";
        leaf.translationKey = "lagfinder.pipes.network_tick";
        leaf.detail = type.getName() + ".doNetworkTick";
        if (at != null) {
            place(leaf, at);
        }
    }

    private static void describeConduit(Node leaf, Object conduit, Map<Object, String> names) {
        Class<?> type = conduit.getClass();
        leaf.detail = type.getName();
        if (leaf.live instanceof TileEntity) {
            place(leaf, (TileEntity) leaf.live);
        }
        Object made = call(conduit, "createItem");
        ItemStack stack = made instanceof ItemStack ? (ItemStack) made : null;
        if (stack != null && stack.getItem() != null) {
            leaf.itemId = Item.getIdFromItem(stack.getItem());
            leaf.itemDamage = stack.getItemDamage();
            try {
                leaf.name = stack.getDisplayName();
            } catch (Throwable ignored) {}
            leaf.typeKey = type.getName() + "|" + leaf.itemId + ":" + leaf.itemDamage;
        } else {
            leaf.typeKey = type.getName();
        }
        if (leaf.name == null || leaf.name.isEmpty()) {
            leaf.name = type.getSimpleName();
        }
        Object network = call(conduit, "getNetwork");
        if (network != null) {
            List<TileEntity> tiles = tilesOf(network);
            leaf.mod = networkName(network, tiles, lowest(tiles), names);
        } else {
            leaf.mod = "EnderIO conduits · not in a network";
        }
    }

    private static void place(Node leaf, TileEntity tile) {
        World world = tile.getWorldObj();
        leaf.kind = LocationKind.BLOCK;
        leaf.x = tile.xCoord;
        leaf.y = tile.yCoord;
        leaf.z = tile.zCoord;
        leaf.dim = world != null ? world.provider.dimensionId : 0;
    }

    private static String networkName(Object network, List<TileEntity> tiles, TileEntity at,
        Map<Object, String> names) {
        String known = names.get(network);
        if (known != null) {
            return known;
        }
        String family = FAMILY.get(
            network.getClass()
                .getSimpleName());
        if (family == null) {
            family = "EnderIO conduits (" + ModResolver.modOf(network.getClass()) + ")";
        }
        StringBuilder text = new StringBuilder(family);
        if (at != null) {
            World world = at.getWorldObj();
            text.append(" · ")
                .append(world != null ? Describer.dimensionNameOf(world) : "DIM")
                .append(' ')
                .append(at.xCoord)
                .append(", ")
                .append(at.yCoord)
                .append(", ")
                .append(at.zCoord);
        }
        int count = tiles.size();
        text.append(" · ")
            .append(count)
            .append(count == 1 ? " conduit" : " conduits");
        String name = text.toString();
        names.put(network, name);
        return name;
    }

    private static List<TileEntity> tilesOf(Object network) {
        List<TileEntity> tiles = new ArrayList<>();
        Object conduits = call(network, "getConduits");
        if (!(conduits instanceof List)) {
            return tiles;
        }
        for (Object conduit : new ArrayList<>((List<?>) conduits)) {
            Object bundle = conduit != null ? call(conduit, "getBundle") : null;
            Object entity = bundle != null ? call(bundle, "getEntity") : null;
            if (entity instanceof TileEntity) {
                tiles.add((TileEntity) entity);
            }
        }
        return tiles;
    }

    private static TileEntity lowest(List<TileEntity> tiles) {
        TileEntity lowest = null;
        for (TileEntity tile : tiles) {
            if (lowest == null || tile.xCoord < lowest.xCoord
                || tile.xCoord == lowest.xCoord && (tile.yCoord < lowest.yCoord
                    || tile.yCoord == lowest.yCoord && tile.zCoord < lowest.zCoord)) {
                lowest = tile;
            }
        }
        return lowest;
    }

    private static Object call(Object target, String name) {
        Class<?> type = target.getClass();
        String key = type.getName() + "#" + name;
        Method method = METHODS.get(key);
        if (method == null) {
            try {
                method = type.getMethod(name);
                method.setAccessible(true);
            } catch (Throwable t) {
                method = MISSING;
            }
            METHODS.put(key, method);
        }
        if (method == MISSING) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
