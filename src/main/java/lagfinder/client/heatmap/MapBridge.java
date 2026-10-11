package lagfinder.client.heatmap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import lagfinder.LagFinder;

/**
 * Wayfarer's Map is not something this mod compiles against, so the few of its classes the heatmap has to touch (a
 * line of the modes menu, the modes button) are reached by name. If one is not as expected (another version of the
 * map) the heatmap mode is simply not offered, and the game goes on.
 */
public final class MapBridge {

    private static final String MAP = "WayFarMap.client.gui.GuiWorldMap";

    private static boolean resolved, ok;
    private static Constructor<?> entryConstructor;
    private static Method entryIcon, entryIconColor, entrySelected;
    private static Field entrySelectedField;

    private MapBridge() {}

    private static synchronized boolean resolve() {
        if (resolved) {
            return ok;
        }
        resolved = true;
        try {
            ClassLoader loader = MapBridge.class.getClassLoader();
            Class<?> entry = Class.forName(MAP + "$MenuEntry", false, loader);
            entryConstructor = entry.getDeclaredConstructor(String.class, boolean.class, Runnable.class);
            entryConstructor.setAccessible(true);
            entryIcon = entry.getDeclaredMethod("icon", String[].class);
            entryIcon.setAccessible(true);
            entryIconColor = entry.getDeclaredMethod("iconColor", int.class);
            entryIconColor.setAccessible(true);
            entrySelected = entry.getDeclaredMethod("selected", boolean.class);
            entrySelected.setAccessible(true);
            entrySelectedField = entry.getDeclaredField("selected");
            entrySelectedField.setAccessible(true);
            ok = true;
        } catch (Throwable t) {
            LagFinder.LOG
                .warn("Wayfarer's Map is not the version the chunk lag heatmap was made for; the mode is left out", t);
        }
        return ok;
    }

    /**
     * Adds a line to the map's modes menu and, while the heatmap is shown, takes the mark off the others.
     *
     * @param menu the menu's entries
     * @return whether the line was added
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean addModeEntry(List menu, String label, String[] icon, int iconColor, boolean selected,
        Runnable action) {
        if (menu == null || !resolve()) {
            return false;
        }
        try {
            if (selected) {
                for (Object other : menu) {
                    entrySelectedField.setBoolean(other, false);
                }
            }
            Object entry = entryConstructor.newInstance(label, true, action);
            entryIcon.invoke(entry, (Object) icon);
            entryIconColor.invoke(entry, iconColor);
            entrySelected.invoke(entry, selected);
            menu.add(entry);
            return true;
        } catch (Throwable t) {
            LagFinder.LOG.warn("Could not add the chunk lag heatmap to the map's modes menu", t);
            return false;
        }
    }

    /** Sets what the map's modes button shows (the mode's icon, color and name); the button is read by name. */
    public static void showOnModesButton(Object map, String[] icon, int iconColor, String tooltip) {
        if (!resolve()) {
            return;
        }
        try {
            Field field = map.getClass()
                .getDeclaredField("modesButton");
            field.setAccessible(true);
            Object button = field.get(map);
            if (button == null) {
                return;
            }
            Class<?> type = button.getClass();
            set(type, button, "icon", icon);
            set(type, button, "iconColor", iconColor);
            set(type, button, "active", true);
            set(type, button, "tooltip", tooltip);
        } catch (Throwable t) {
            LagFinder.LOG.warn("Could not mark the heatmap on the map's modes button", t);
        }
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException next) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
