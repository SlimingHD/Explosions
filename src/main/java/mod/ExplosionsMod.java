package mod;

import mod.network.PacketHandler;
import net.minecraftforge.fml.common.Mod;

@Mod(ExplosionsMod.MODID)
public class ExplosionsMod {
	
	public static final String MODID = "explosions";

    public ExplosionsMod() {
    	PacketHandler.register();
    }
}
