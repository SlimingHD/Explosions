package mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import mod.explosion.*;
import mod.explosion.ImprovedExplosionRayReduced.RayReduction;
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
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.then(Commands.argument("type", EnumArgument.enumArgument(ExplosionType.class))
					.then(Commands.argument("radius", IntegerArgumentType.integer(0, 1000))
						.then(Commands.argument("randomVectorLength", FloatArgumentType.floatArg(0f, 1f))
							.executes(ExplodeCommand::explode)
						)
					)
				)
			)
		);
	}
	
	private static int explode(CommandContext<CommandSourceStack> ctx) {
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		ExplosionType type = ctx.getArgument("type", ExplosionType.class);
		int radius = ctx.getArgument("radius", Integer.class);
		float randomVectorLength = ctx.getArgument("randomVectorLength", Float.class);
		try {
			ExplosionBase explosion = type.createExplosion(ctx.getSource().getLevel(), pos, radius, randomVectorLength);
			explosion.setFinishWork(() -> {
				explosion.profiler.printResults();
			});
			explosion.explode();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 1;
	}
	
	private static enum ExplosionType {
		SIMPLE_HALF_CUBE((level, pos, radius, randomVectorLength) -> new SimpleExplosion(level, Vec3.atCenterOf(pos), radius, randomVectorLength, Shape.HALF_CUBE)),
		SIMPLE_CUBE((level, pos, radius, randomVectorLength) -> new SimpleExplosion(level, Vec3.atCenterOf(pos), radius, randomVectorLength, Shape.CUBE)),
		SIMPLE_SPHERE((level, pos, radius, randomVectorLength) -> new SimpleExplosion(level, Vec3.atCenterOf(pos), radius, randomVectorLength, Shape.SPHERE)),
		IMPROVED((level, pos, radius, randomVectorLength) -> new ImprovedExplosion(level, pos, radius, randomVectorLength, false)),
		IMPROVED_RADIUS_REDUCED((level, pos, radius, randomVectorLength) ->  new ImprovedExplosionRayReduced(level, pos, radius, randomVectorLength, false, RayReduction.BY_RADIUS)),
		IMPROVED_Y_LIMIT_REDUCED((level, pos, radius, randomVectorLength) ->  new ImprovedExplosionRayReduced(level, pos, radius, randomVectorLength, false, RayReduction.BY_Y_LIMIT)),
		IMPROVED_FULLY_REDUCED((level, pos, radius, randomVectorLength) ->  new ImprovedExplosionRayReduced(level, pos, radius, randomVectorLength, false, RayReduction.BOTH)),
		IMPROVED_MULTITHREADED((level, pos, radius, randomVectorLength) ->  new ImprovedExplosion(level, pos, radius, randomVectorLength, true)),
		IMPROVED_RADIUS_REDUCED_MULTITHREADED((level, pos, radius, randomVectorLength) ->  new ImprovedExplosionRayReduced(level, pos, radius, randomVectorLength, true, RayReduction.BY_RADIUS)),
		IMPROVED_Y_LIMIT_REDUCED_MULTITHREADED((level, pos, radius, randomVectorLength) ->  new ImprovedExplosionRayReduced(level, pos, radius, randomVectorLength, true, RayReduction.BY_Y_LIMIT)),
		IMPROVED_FULLY_REDUCED_MULTITHREADED((level, pos, radius, randomVectorLength) ->  new ImprovedExplosionRayReduced(level, pos, radius, randomVectorLength, true, RayReduction.BOTH)),
		DDA((level, pos, radius, randomVectorLength) -> new ImprovedExplosionDDA(level, pos, radius, randomVectorLength, false)),
		DDA_MULTITHREADED((level, pos, radius, randomVectorLength) -> new ImprovedExplosionDDA(level, pos, radius, randomVectorLength, true)),
		MAP_UNWRAPPED((level, pos, radius, randomVectorLength) -> new MapUnwrappedExplosion(level, pos, radius, randomVectorLength, false)),
		MAP_UNWRAPPED_MULTITHREADED((level, pos, radius, randomVectorLength) -> new MapUnwrappedExplosion(level, pos, radius, randomVectorLength, true)),
		SEMI_UNWRAPPED_MULTITHREADED((level, pos, radius, randomVectorLength) -> new SemiUnwrappedExplosion(level, pos, radius, randomVectorLength)),
		MORTON((level, pos, radius, randomVectorLength) -> new MortonExplosion(level, pos, radius, randomVectorLength, false)),
		MORTON_SEMI_UNWRAPPED_MULTITHREADED((level, pos, radius, randomVectorLength) -> new MortonExplosion(level, pos, radius, randomVectorLength, true)),
		PRECOMPUTED((level, pos, radius, randomVectorLength) -> new PrecomputedExplosion(level, pos, radius, randomVectorLength, false)),
		PRECOMPUTED_MULTITHREADED((level, pos, radius, randomVectorLength) -> new PrecomputedExplosion(level, pos, radius, randomVectorLength, true)),
		MEMORY_REDUCED((level, pos, radius, randomVectorLength) -> new MemoryReducedExplosion(level, pos, radius, randomVectorLength, false)),
		MEMORY_REDUCED_MULTITHREADED((level, pos, radius, randomVectorLength) -> new MemoryReducedExplosion(level, pos, radius, randomVectorLength, true));
		
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
