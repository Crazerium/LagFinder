package lagfinder.client;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.world.World;

import lagfinder.core.Category;
import lagfinder.core.Describer;
import lagfinder.core.LocationKind;
import lagfinder.core.ModResolver;
import lagfinder.core.Node;

/** Describes what a frame scan measures, chunk sections being rebuilt included. */
public class ClientDescriber extends Describer {

    @Override
    public void describe(Node leaf) {
        if (leaf.key instanceof WorldRenderer) {
            WorldRenderer renderer = (WorldRenderer) leaf.key;
            leaf.kind = LocationKind.SECTION;
            leaf.mod = ModResolver.MINECRAFT;
            leaf.typeKey = WorldRenderer.class;
            leaf.name = "Chunk section";
            leaf.translationKey = "lagfinder.type.chunk_section";
            leaf.detail = renderer.getClass()
                .getName();
            leaf.x = renderer.posX;
            leaf.y = renderer.posY;
            leaf.z = renderer.posZ;
            Minecraft mc = Minecraft.getMinecraft();
            leaf.dim = mc.theWorld != null ? mc.theWorld.provider.dimensionId : 0;
            leaf.label = (renderer.posX >> 4) + ", " + (renderer.posY >> 4) + ", " + (renderer.posZ >> 4);
            return;
        }
        super.describe(leaf);
    }

    @Override
    public boolean isGone(Object thing) {
        if (thing instanceof World) {
            // The client has one world: any other is left behind.
            return Minecraft.getMinecraft().theWorld != thing;
        }
        return super.isGone(thing);
    }

    @Override
    public boolean expects(Category category) {
        World world = Minecraft.getMinecraft().theWorld;
        switch (category) {
            case EVENTS:
                return true;
            case TILE_ENTITIES:
            case ENTITIES:
                return world != null && hasSome(world, category);
            default:
                // Nothing to draw or rebuild may well be all there was.
                return false;
        }
    }

    @Override
    public Map<Integer, String> dimensionNames() {
        Map<Integer, String> names = new HashMap<>();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != null) {
            names.put(mc.theWorld.provider.dimensionId, dimensionName(mc.theWorld));
        }
        return names;
    }
}
