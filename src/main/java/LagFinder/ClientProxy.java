package LagFinder;

import net.minecraftforge.common.MinecraftForge;

import LagFinder.client.ClientScanner;
import LagFinder.client.Highlighter;
import LagFinder.client.HudOverlay;
import LagFinder.client.KeyHandler;
import LagFinder.network.Net;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        KeyHandler.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new KeyHandler());
        FMLCommonHandler.instance()
            .bus()
            .register(ClientScanner.INSTANCE);
        MinecraftForge.EVENT_BUS.register(Highlighter.INSTANCE);
        MinecraftForge.EVENT_BUS.register(HudOverlay.INSTANCE);
    }

    @Override
    public void onStatus(Net.Status status) {
        ClientScanner.INSTANCE.onStatus(status);
    }

    @Override
    public void onReportPart(Net.ReportPart part) {
        ClientScanner.INSTANCE.onReportPart(part);
    }
}
