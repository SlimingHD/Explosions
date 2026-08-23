package mod.command;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import mod.explosion.ExplosionBase;
import mod.explosion.ImprovedExplosion;
import mod.explosion.ImprovedExplosionDDA;
import mod.explosion.ImprovedExplosionRayReduced;
import mod.explosion.ImprovedExplosionRayReduced.RayReduction;
import mod.explosion.MapUnwrappedExplosion;
import mod.explosion.MemoryReducedExplosion;
import mod.explosion.MortonExplosion;
import mod.explosion.SemiUnwrappedExplosion;
import mod.explosion.SimpleExplosion;
import mod.explosion.SimpleExplosion.Shape;
import net.minecraft.FileUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
		dispatcher.register(
		Commands.literal("explode-experiment")
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.then(Commands.argument("type", EnumArgument.enumArgument(ExplosionType.class))
					.then(Commands.argument("initCount", IntegerArgumentType.integer(1, 5))
						.then(Commands.argument("testCount", IntegerArgumentType.integer(5, 20))
							.then(Commands.argument("waitTime", IntegerArgumentType.integer(0))
								.then(Commands.argument("sizeList", EnumArgument.enumArgument(SizeList.class))
									.executes(ExplodeCommand::performExperiment)
								)
							)
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
			explode(ctx.getSource().getLevel(), pos, type, radius, randomVectorLength);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 1;
	}
	
	private static void explode(ServerLevel level, BlockPos pos, ExplosionType type, int radius, float randomVectorLength) {
		ExplosionBase explosion = type.createExplosion(level, pos, radius, randomVectorLength);
		explosion.setFinishWork(() -> {
			String result = explosion.profiler.printResults();
			tryInjectExperimentResult(result);
		});
		explosion.explode();
	}
	
	private static String EXPERIMENT_RESULT = "Experiment Result:\n";
	private static volatile boolean ACCEPTING_RESULTS = false;
	private static volatile boolean AWAITING_RESULT = false;
	private static volatile boolean SKIP_RESULT = false;
	
	private static int performExperiment(CommandContext<CommandSourceStack> ctx) {
		CompletableFuture.runAsync(() -> {
			ACCEPTING_RESULTS = true;
			ServerLevel level = ctx.getSource().getLevel();
			MinecraftServer server = level.getServer();
			ServerPlayer player = ctx.getSource().getPlayer();
			BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
			ExplosionType type = ctx.getArgument("type", ExplosionType.class);
			int initCount = ctx.getArgument("initCount", Integer.class);
			int testCount = ctx.getArgument("testCount", Integer.class);
			int waitTime = ctx.getArgument("waitTime", Integer.class);
			SizeList sizes = ctx.getArgument("sizeList", SizeList.class);
			EXPERIMENT_RESULT = "Experiment Result: \n";
			EXPERIMENT_RESULT += "Testing Type: " + type.name() + "\n";
			for (int size : sizes.getSizes()) {
				EXPERIMENT_RESULT += "Testing Size: " + size + "\n";
				SKIP_RESULT = true;
				for (int i = 0; i < initCount; i++) {
					AWAITING_RESULT = true;
					BlockPos currentPos = pos;
					server.execute(() -> {
						explode(level, currentPos, type, size, 0.05f);
					});
					try {
						while (AWAITING_RESULT) {
							Thread.sleep(500);
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				try {
					BlockPos startPos = new BlockPos(pos.getX() + (int)(size * 2.2f), pos.getY(), pos.getZ());
					player.teleportTo(startPos.getX(), startPos.getY(), startPos.getZ());
					pos = startPos;
					Thread.sleep(30000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				SKIP_RESULT = false;
				for (int i = 0; i < testCount; i++) {
					AWAITING_RESULT = true;
					BlockPos currentPos = pos;
					BlockPos newPos = new BlockPos(pos.getX() + (int)(size * 2.2f), pos.getY(), pos.getZ());
					pos = newPos;
					server.execute(() -> {
						explode(level, currentPos, type, size, 0.05f);
					});
					try {
						while (AWAITING_RESULT) {
							Thread.sleep(500);
						}
						player.teleportTo(newPos.getX(), newPos.getY(), newPos.getZ());
						Thread.sleep(waitTime * 1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			ACCEPTING_RESULTS = false;
			System.out.println(EXPERIMENT_RESULT);
			try {
				FileUtil.createDirectoriesSafe(server.getServerDirectory().toPath().resolve("explosion"));
				File output = new File(server.getServerDirectory().toString() + "/explosion/result_" + type.name() + ".txt");
				FileWriter writer = new FileWriter(output);
				writer.write(EXPERIMENT_RESULT);
				writer.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		
		return 1;
	}
	
	public static void tryInjectExperimentResult(String result) {
		if (!ACCEPTING_RESULTS) {
			return;
		}
		if (!SKIP_RESULT) {
			EXPERIMENT_RESULT += result + "\n";
		}
		AWAITING_RESULT = false;
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
		MORTON_MULTITHREADED((level, pos, radius, randomVectorLength) -> new MortonExplosion(level, pos, radius, randomVectorLength, true)),
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
	
	private static enum SizeList {
		REDUCED_TEST_SIZES(List.of(50, 100, 200, 400)),
		TEST_SIZES(List.of(50, 100, 200, 400, 800));
		
		private final List<Integer> sizes;
		
		private SizeList(List<Integer> sizes) {
			this.sizes = sizes;
		}
		
		public List<Integer> getSizes() {
			return sizes;
		}
	}
}
