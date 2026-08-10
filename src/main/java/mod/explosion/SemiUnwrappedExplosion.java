package mod.explosion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import mod.explosion.helper.MultithreadedExplosionHandler;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public class SemiUnwrappedExplosion extends ExplosionBase {
	
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	
	public SemiUnwrappedExplosion(ServerLevel level, BlockPos pos, int radius, float randomVectorLength) {
		super(level);
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
	}

	@Override
	public void explode() {
		profiler.setTitle("explosions.benchmark.semi_unwrapped_explosion_multithreaded");
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Counter rayCount = rayTracingTime.addCounter(0);
		Profile rayCreationTime = profiler.createProfile("explosions.benchmark.ray_creation_time");
		
		totalTime.startBenchmark();
		rayCreationTime.startBenchmark();
		int maxRaysPerTask = 25000;
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		int minRadiusY = Math.max(-radius, minBuildHeight - pos.getY());
		int maxRadiusY = Math.min(radius, maxBuildHeight - pos.getY());
		boolean clippedBottom = minRadiusY > -radius;
		boolean clippedTop = maxRadiusY < radius;
		List<int[]> rays = new ArrayList<int[]>();
		int rayIndex = 0;
		int[] currentRays = new int[maxRaysPerTask * 3];
		for (int x = -radius; x <= radius; ++x) {
			for (int y = minRadiusY; y <= maxRadiusY; ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if ((distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1))
							|| (((clippedBottom && y == minRadiusY) || (clippedTop && y == maxRadiusY)) && distanceSqr <= (radius + 1) * (radius + 1))) {
						if (rayIndex == maxRaysPerTask * 3) {
							rays.add(currentRays);
							currentRays = new int[maxRaysPerTask * 3];
							rayIndex = 0;
						}
						currentRays[rayIndex] = x;
						currentRays[rayIndex + 1] = y;
						currentRays[rayIndex + 2] = z;
						rayIndex += 3;
					}
				}
			}
		}
		int[] repairedRays = Arrays.copyOf(currentRays, rayIndex);
		rays.add(repairedRays);
		rayCreationTime.stopBenchmark();
		for (int[] raySet : rays) {
			rayCount.increment(raySet.length / 3);
		}

		rayTracingTime.startBenchmark();
		SemiUnwrappedExplosionThread thread = new SemiUnwrappedExplosionThread(this, rays, pos, radius, randomVectorLength);
		MultithreadedExplosionHandler.enqueueSemiUnwrapped(thread);
	}
}
