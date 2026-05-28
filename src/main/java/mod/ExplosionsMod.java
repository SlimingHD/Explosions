package mod;

import mod.network.PacketHandler;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ExplosionsMod.MODID)
public class ExplosionsMod {
	
	public static final String MODID = "explosions";

    public ExplosionsMod() {
    	IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
    	bus.addListener(ExplosionsMod::commonSetup);
    }
    
    public static void commonSetup(FMLCommonSetupEvent event) {
    	PacketHandler.register();
    }
}
