package LagFinder.core;

import java.io.File;
import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Tells which mod a class, a block or a mod id belongs to. Classes go by the package of each mod's main class (the
 * most precise: one jar may hold several mods), then by the jar they were loaded from.
 */
public final class ModResolver {

    public static final String MINECRAFT = "Minecraft";

    private static final Map<Class<?>, String> CLASSES = new ConcurrentHashMap<>();
    private static volatile List<String[]> packages;
    private static volatile Map<String, String> sources;

    private ModResolver() {}

    /** The mod's name for a mod id ("gregtech" is "GregTech"). */
    public static String nameOfModId(String modId) {
        if (modId == null || modId.isEmpty() || "minecraft".equals(modId)) {
            return MINECRAFT;
        }
        ModContainer container = Loader.instance()
            .getIndexedModList()
            .get(modId);
        return container != null ? container.getName() : modId;
    }

    /** The mod that registered the block, or null if it isn't known. */
    public static String modOfBlock(Block block) {
        if (block == null) {
            return null;
        }
        try {
            GameRegistry.UniqueIdentifier id = GameRegistry.findUniqueIdentifierFor(block);
            return id == null ? null : nameOfModId(id.modId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The mod the class comes from, {@link Describer#UNKNOWN_MOD} if no mod owns it. */
    public static String modOf(Class<?> type) {
        String mod = CLASSES.get(type);
        if (mod == null) {
            mod = find(type);
            CLASSES.put(type, mod);
        }
        return mod;
    }

    private static String find(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("net.minecraft.")) {
            return MINECRAFT;
        }
        if (name.startsWith("net.minecraftforge.") || name.startsWith("cpw.mods.fml.")) {
            return "Forge";
        }
        String best = null;
        int bestLength = -1;
        for (String[] entry : packages()) {
            String prefix = entry[0];
            if (name.startsWith(prefix) && prefix.length() > bestLength) {
                best = entry[1];
                bestLength = prefix.length();
            }
        }
        if (best != null) {
            return best;
        }
        String source = sourceOf(type);
        if (source != null) {
            String mod = sources().get(source);
            if (mod != null) {
                return mod;
            }
        }
        return Describer.UNKNOWN_MOD;
    }

    /** Package prefixes ("gregtech.") of each mod's main class, and its parent package when that is precise enough. */
    private static List<String[]> packages() {
        List<String[]> list = packages;
        if (list != null) {
            return list;
        }
        list = new ArrayList<>();
        for (ModContainer container : Loader.instance()
            .getActiveModList()) {
            Object mod = container.getMod();
            if (mod == null || mod.getClass()
                .getPackage() == null) {
                continue;
            }
            String pkg = mod.getClass()
                .getPackage()
                .getName();
            if (pkg.startsWith("net.minecraft") || pkg.startsWith("cpw.mods.fml")) {
                continue;
            }
            list.add(new String[] { pkg + ".", container.getName() });
            int dot = pkg.lastIndexOf('.');
            if (dot > 0) {
                String parent = pkg.substring(0, dot);
                if (count(parent, '.') >= 2) {
                    list.add(new String[] { parent + ".", container.getName() });
                }
            }
        }
        packages = list;
        return list;
    }

    private static Map<String, String> sources() {
        Map<String, String> map = sources;
        if (map != null) {
            return map;
        }
        map = new HashMap<>();
        for (ModContainer container : Loader.instance()
            .getActiveModList()) {
            File source = container.getSource();
            if (source != null && !map.containsKey(source.getAbsolutePath())) {
                map.put(source.getAbsolutePath(), container.getName());
            }
        }
        sources = map;
        return map;
    }

    /** The jar or folder the class was loaded from, or null. */
    private static String sourceOf(Class<?> type) {
        try {
            CodeSource code = type.getProtectionDomain()
                .getCodeSource();
            URL location = code == null ? null : code.getLocation();
            if (location == null) {
                return null;
            }
            String path = location.getPath();
            if (path.startsWith("file:")) {
                path = path.substring(5);
            }
            int bang = path.indexOf("!/");
            if (bang >= 0) {
                path = path.substring(0, bang);
            }
            return new File(URLDecoder.decode(path, "UTF-8")).getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
