package mod.explosion;

import java.util.Set;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class SimpleExplosion extends ExplosionBase {

	private final Vec3 pos;
	private final int radius;
	private final float randomVectorLength;
	private final Shape rayTarget;
	
	public SimpleExplosion(ServerLevel level, Vec3 pos, int radius, float randomVectorLength, Shape rayTarget) {
		super(level);
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.rayTarget = rayTarget;
	}

	@Override
	public void explode() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Counter blockCount = totalTime.addCounter(0);
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Counter rayCount = rayTracingTime.addCounter(0);
		Profile sectionAccesses = profiler.createProfile("explosions.benchmark.section_accesses");
		Counter sectionAccessCount = sectionAccesses.addCounter(0);
		Profile heapAllocations = profiler.createProfile("explosions.benchmark.heap_allocations");
		Counter heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		Counter requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		
		totalTime.startBenchmark();
		switch(rayTarget) {
			case HALF_CUBE: explodeHalfCube(rayTracingTime, blockCount, rayCount, sectionAccessCount, heapAllocationMB, requiredHeapAllocationMB); break;
			case CUBE: explodeCube(rayTracingTime, blockCount, rayCount, sectionAccessCount, heapAllocationMB, requiredHeapAllocationMB); break;
			case SPHERE: explodeSphere(rayTracingTime, blockCount, rayCount, sectionAccessCount, heapAllocationMB, requiredHeapAllocationMB); break;
		}
		totalTime.stopBenchmark();
		if (finishWork != null) {
			finishWork.run();
		}
	}
	
	private void explodeHalfCube(Profile rayTracingTime, Counter blockCount, Counter rayCount, Counter sectionAccessCount, Counter heapAllocationMB, Counter requiredHeapAllocationMBs) {
		profiler.setTitle("explosions.benchmark.half_cube");
		rayTracingTime.startBenchmark();
		int halfRadius = radius / 2;
		Set<BlockPos> toExplode = new ObjectOpenHashSet<BlockPos>();
		for (int x = -halfRadius; x <= halfRadius; x++) {
			for (int y = -halfRadius; y <= halfRadius; y++) {
				for (int z = -halfRadius; z <= halfRadius; z++) {
					if (Math.abs(x) == halfRadius || Math.abs(y) == halfRadius || Math.abs(z) == halfRadius) {
						traceRay(toExplode, x, y, z, Mth.sqrt(x * x + y * y + z * z), sectionAccessCount, heapAllocationMB);
						rayCount.increment(1);
					}
				}
			}
		}
		rayTracingTime.stopBenchmark();
		requiredHeapAllocationMBs.increment(toExplode.size() * 56);
		blockCount.increment(toExplode.size());
		finishExplosion(toExplode);
	}
	
	private void explodeCube(Profile rayTracingTime, Counter blockCount, Counter rayCount, Counter sectionAccessCount, Counter heapAllocationMB, Counter requiredHeapAllocationMBs) {
		profiler.setTitle("explosions.benchmark.cube");
		rayTracingTime.startBenchmark();
		Set<BlockPos> toExplode = new ObjectOpenHashSet<BlockPos>();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					if (Math.abs(x) == radius || Math.abs(y) == radius || Math.abs(z) == radius) {
						traceRay(toExplode, x, y, z, Mth.sqrt(x * x + y * y + z * z), sectionAccessCount, heapAllocationMB);
						rayCount.increment(1);
					}
				}
			}
		}
		rayTracingTime.stopBenchmark();
		requiredHeapAllocationMBs.increment(toExplode.size() * 56);
		blockCount.increment(toExplode.size());
		finishExplosion(toExplode);
	}
	
	private void explodeSphere(Profile rayTracingTime, Counter blockCount, Counter rayCount, Counter sectionAccessCount, Counter heapAllocationMB, Counter requiredHeapAllocationMBs) {
		profiler.setTitle("explosions.benchmark.sphere");
		rayTracingTime.startBenchmark();
		Set<BlockPos> toExplode = new ObjectOpenHashSet<BlockPos>();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					int distanceSqr = x * x + y * y + z * z;
					if (distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1)) {
						traceRay(toExplode, x, y, z, radius + 0.5f, sectionAccessCount, heapAllocationMB);
						rayCount.increment(1);
					}
				}
			}
		}
		rayTracingTime.stopBenchmark();
		requiredHeapAllocationMBs.increment(toExplode.size() * 56);
		blockCount.increment(toExplode.size());
		finishExplosion(toExplode);
	}
	
	@SuppressWarnings("deprecation")
	private void traceRay(Set<BlockPos> toExplode, int x, int y, int z, float distance, Counter sectionAccessCount, Counter heapAllocationMB) {
		double blockX = pos.x;
		double blockY = pos.y;
		double blockZ = pos.z;
		float stepX = x / distance * 0.3f;
		float stepY = y / distance * 0.3f;
		float stepZ = z / distance * 0.3f;
		float remainingLength = radius * (0.7f + random.nextFloat() * randomVectorLength);
		BlockPos lastPos = null;
		long lastSectionPos = Long.MAX_VALUE;
		for (float step = 0f; step < remainingLength; step += 0.225f) {
			BlockPos pos = BlockPos.containing(blockX, blockY, blockZ);
			heapAllocationMB.increment(24);
			if (!level.isInWorldBounds(pos)) {
				break;
			}
			if (pos.equals(lastPos)) {
				blockX += stepX;
				blockY += stepY;
				blockZ += stepZ;
				continue;
			}
			lastPos = pos;
			BlockState state = level.getBlockState(pos);
			
			long sectionPos = SectionPos.asLong(pos);
			if (sectionPos != lastSectionPos) {
				sectionAccessCount.increment(1);
			}
			lastSectionPos = sectionPos;
			
			if (!state.isAir()) {
				float explosionResistance = Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance());
				//Explosion Resistance is made weaker on purpose to ensure large explosions are actually large
				remainingLength -= explosionResistance * 0.167f;
				if (step >= remainingLength) {
					break;
				}
				if (toExplode.add(pos)) {
					heapAllocationMB.increment(32);
				}
			}
			blockX += stepX;
			blockY += stepY;
			blockZ += stepZ;
		}
	}
	
	public static enum Shape {
		HALF_CUBE,
		CUBE,
		SPHERE
	}
}