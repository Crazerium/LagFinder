package lagfinder.compat;

import net.minecraft.world.World;

import lagfinder.core.Describer;
import lagfinder.core.LocationKind;
import lagfinder.core.Node;
import li.cil.oc.api.network.EnvironmentHost;

public final class OpenComputersHosts {

    private OpenComputersHosts() {}

    public static void describe(Node leaf) {
        Describer.fallback(leaf);
        leaf.mod = "OpenComputers";
        if (!(leaf.key instanceof EnvironmentHost)) {
            return;
        }
        EnvironmentHost host = (EnvironmentHost) leaf.key;
        World world = host.world();
        if (world == null) {
            return;
        }
        leaf.kind = LocationKind.BLOCK;
        leaf.dim = world.provider.dimensionId;
        leaf.x = (int) Math.floor(host.xPosition());
        leaf.y = (int) Math.floor(host.yPosition());
        leaf.z = (int) Math.floor(host.zPosition());
    }
}
