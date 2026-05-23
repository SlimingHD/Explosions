package mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import mod.explosion.ExplosionBase;
import mod.explosion.SimpleExplosion;
import mod.explosion.SimpleExplosion.Shape;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.server.command.EnumArgument;

public class ExplodeCommand {

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
		Commands.literal("explode")
			.then(Commands.argument("pos", BlockPosArgument.blockPos()))
				.then(Commands.argument("type", EnumArgument.enumArgument(ExplosionType.class)))
					.then(Commands.argument("radius", IntegerArgumentType.integer(0, 800)))
						.then(Commands.argument("randomVectorLength", FloatArgumentType.floatArg(0f, 1f)))
							.executes(ExplodeCommand::explode)
		);
	}
	
	private static int explode(CommandContext<CommandSourceStack> ctx) {
		BlockPos pos = ctx.getArgument("pos", BlockPos.class);
		ExplosionType type = ctx.getArgument("type", ExplosionType.class);
		int radius = ctx.getArgument("radius", Integer.class);
		float randomVectorLength = ctx.getArgument("randomVectorLength", Float.class);
		ExplosionBase explosion = type.createExplosion(ctx.getSource().getLevel(), pos, radius, randomVectorLength);
		explosion.explode();
		explosion.setFinishWork(() -> {
			explosion.profiler.printResults();
		});
		return 1;
	}
	
	private static enum ExplosionType {
		SIMPLE_HALF_CUBE((level, pos, radius, randomVectorLength) -> new SimpleExplosion(level, Vec3.atCenterOf(pos), radius, randomVectorLength, Shape.HALF_CUBE)),
		SIMPLE_CUBE((level, pos, radius, randomVectorLength) -> new SimpleExplosion(level, Vec3.atCenterOf(pos), radius, randomVectorLength, Shape.CUBE)),
		SIMPLE_SPHERE((level, pos, radius, randomVectorLength) -> new SimpleExplosion(level, Vec3.atCenterOf(pos), radius, randomVectorLength, Shape.SPHERE)),
		IMPROVED((level, pos, radius, randomVectorLength) -> null),
		IMPROVED_DDA((level, pos, radius, randomVectorLength) -> null),
		IMPROVED_MULTITHREADED((level, pos, radius, randomVectorLength) -> null),
		IMPROVED_MULTITHREADED_DDA((level, pos, radius, randomVectorLength) -> null);
		
		private final ExplosionFunction explosionFunction;
		
		private ExplosionType(ExplosionFunction explosionFunction) {
			this.explosionFunction = explosionFunction;
		}
		
		public ExplosionBase createExplosion(ServerLevel level, BlockPos pos, int radius, float randomVectorLength) {
			return explosionFunction.createExplosion(level, pos, radius, randomVectorLength);
		}
		
		private static interface ExplosionFunction {
			public ExplosionBase createExplosion(ServerLevel level, BlockPos pos, int radius, float randomVectorLength);
		}
	}
}
