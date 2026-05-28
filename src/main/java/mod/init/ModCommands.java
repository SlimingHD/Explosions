package mod.init;

import mod.command.ExplodeCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@Mod.EventBusSubscriber(bus = Bus.FORGE)
public class ModCommands {

	@SubscribeEvent
	public static void registerCommands(RegisterCommandsEvent event) {
		ExplodeCommand.register(event.getDispatcher());
	}
}
