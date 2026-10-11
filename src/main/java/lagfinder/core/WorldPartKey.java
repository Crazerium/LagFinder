package lagfinder.core;

import net.minecraft.world.World;

/** What a part of a world is measured by: the part and the world, which is let go when it is unloaded. */
public final class WorldPartKey {

    public final World world;
    public final WorldPart part;

    public WorldPartKey(World world, WorldPart part) {
        this.world = world;
        this.part = part;
    }
}
