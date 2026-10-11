package lagfinder.mixins.late.wayfarmap;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import lagfinder.client.gui.GuiHeatmapLogs;
import lagfinder.client.gui.ui.Icons;
import lagfinder.client.heatmap.Heatmap;
import lagfinder.client.heatmap.MapBridge;

/**
 * Adds the chunk lag heatmap to the world map of Wayfarer's Map as one more mode of its modes menu: choosing it asks
 * for a tick scan log (only those are listed, see {@link GuiHeatmapLogs}) and paints each chunk of that scan over the
 * flat map from green to red by what it cost. Everything it does is in {@link Heatmap}; this only hooks the map.
 */
@Pseudo
@Mixin(targets = "WayFarMap.client.gui.GuiWorldMap", remap = false)
public abstract class MixinGuiWorldMap extends GuiScreen {

    /** The color of the mode's icon on the modes menu and button. */
    private static final int MODE_COLOR = 0xFFF2994A;
    private static final int MODE_FLAT = 0;

    @Shadow
    private double centerX;
    @Shadow
    private double centerZ;
    @Shadow
    private double scale;
    @Shadow
    private List menu;
    @Shadow
    private int menuWidth;

    @Shadow
    abstract void setMode(int mode);

    @Shadow
    abstract int menuWidthFor(List entries, int minimum);

    /** The modes menu has just been made: the heatmap is its last line. */
    @Inject(method = "openModesMenu", at = @At("TAIL"), require = 0, remap = false)
    private void lagfinder$addMode(CallbackInfo ci) {
        boolean added = MapBridge.addModeEntry(
            menu,
            I18n.format("lagfinder.heatmap.mode"),
            Icons.HEATMAP,
            MODE_COLOR,
            Heatmap.isActive(),
            () -> {
                // On the flat map, which the heat is painted on; shown once a log is picked.
                setMode(MODE_FLAT);
                Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiHeatmapLogs(this));
            });
        if (added) {
            menuWidth = menuWidthFor(menu, menuWidth);
        }
    }

    /** Any other mode picked: the heat goes. */
    @Inject(method = "setMode", at = @At("HEAD"), require = 0, remap = false)
    private void lagfinder$leaveHeatmap(int mode, CallbackInfo ci) {
        Heatmap.deactivate();
    }

    /** The modes button shows the heatmap while it is on. */
    @Inject(method = "updateLightButtons", at = @At("TAIL"), require = 0, remap = false)
    private void lagfinder$markButton(CallbackInfo ci) {
        if (Heatmap.isActive()) {
            MapBridge.showOnModesButton(
                this,
                Icons.HEATMAP,
                MODE_COLOR,
                I18n.format("wayfarmap.gui.modes") + ": " + I18n.format("lagfinder.heatmap.mode"));
        }
    }

    /** Right after the flat map itself is drawn, so everything else (grid, markers, players) stays over the heat. */
    @Inject(
        method = "drawFlatLayers",
        at = @At(
            value = "INVOKE",
            target = "LWayFarMap/client/map/MapDrawer;drawMap(LWayFarMap/client/map/MapDimension;DDDIIII)V",
            shift = At.Shift.AFTER,
            remap = false),
        require = 0,
        remap = false)
    private void lagfinder$paintHeat(@Coerce Object dimension, int dimensionId, boolean otherDimension, int mouseX,
        int mouseY, float partialTicks, CallbackInfo ci) {
        Heatmap.draw(dimensionId, centerX, centerZ, scale, width, height);
    }

    /** The legend, the chunk under the mouse and its tooltip, over everything. */
    @Inject(method = "drawOverlay", at = @At("TAIL"), require = 0, remap = false)
    private void lagfinder$heatmapOverlay(int mouseX, int mouseY, float partialTicks, @Coerce Object dimension,
        int dimensionId, boolean otherDimension, boolean iso, CallbackInfo ci) {
        if (iso || !Heatmap.isActive()) {
            return;
        }
        Heatmap.drawLegend(fontRendererObj, dimensionId, height);
        // Not over the header and footer, nor while a menu is open.
        if (menu != null || mouseY <= 24 || mouseY >= height - 14) {
            return;
        }
        Heatmap.drawHovered(dimensionId, centerX, centerZ, scale, width, height, mouseX, mouseY);
        List<String> tooltip = Heatmap.tooltip(dimensionId, centerX, centerZ, scale, width, height, mouseX, mouseY);
        if (tooltip != null) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }
}
