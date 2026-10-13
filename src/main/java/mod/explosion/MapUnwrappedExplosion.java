package mod.explosion;

import java.util.Arrays;
import java.util.BitSet;

import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import mod.explosion.helper.MultithreadedExplosionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class MapUnwrappedExplosion extends ExplosionBase {
	
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final boolean multithreaded;
	
	public MapUnwrappedExplosion(ServerLevel level, BlockPos pos, int radius, float randomVectorLength, boolean multithreaded) {
		super(level);
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.multithreaded = multithreaded;
	}

	@Override
	public void explode() {
		if (multithreaded) {
			profiler.setTitle("explosions.benchmark.map_unwrapped_explosion_multithreaded");
			explodeMultithreaded();
		} else {
			profiler.setTitle("explosions.benchmark.map_unwrapped_explosion");
			explodeSinglethreaded();
		}
	}
	
	private void explodeMultithreaded() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		
		totalTime.startBenchmark();
		rayTracingTime.startBenchmark();
		int maxRaysPerTask = 25000;
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		int minRadiusY = Math.max(-radius, minBuildHeight - pos.getY());
		int maxRadiusY = Math.min(radius, maxBuildHeight - pos.getY());
		boolean clippedBottom = minRadiusY > -radius;
		boolean clippedTop = maxRadiusY < radius;
		int rayIndex = 0;
		int[] currentRays = new int[maxRaysPerTask * 3];
		Counter rayCounter = rayTracingTime.addCounter(0);
		MapUnwrappedExplosionThread thread = new MapUnwrappedExplosionThread(this, pos, radius, randomVectorLength, false);
		
		for (int x = -radius; x <= radius; ++x) {
			for (int y = minRadiusY; y <= maxRadiusY; ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if ((distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1))
							|| (((clippedBottom && y == minRadiusY) || (clippedTop && y == maxRadiusY)) && distanceSqr <= (radius + 1) * (radius + 1))) {
						if (rayIndex == maxRaysPerTask * 3) {
							thread.submitRays(currentRays);
							rayCounter.increment(currentRays.length / 3);
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
		thread.submitRays(repairedRays);
		rayCounter.increment(repairedRays.length / 3);

		MultithreadedExplosionHandler.enqueueMapUnwrapped(thread);
	}
	
	@SuppressWarnings("deprecation")
	private void explodeSinglethreaded() {	
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Counter blockCount = totalTime.addCounter(0);
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Counter rayCount = rayTracingTime.addCounter(0);
		Profile heapAllocations = profiler.createProfile("explosions.benchmark.heap_allocations");
		Counter heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		Counter requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		
		long maxRequiredHeapCache = 0;
		long requiredHeapCache = 0;
		
		totalTime.startBenchmark();
		rayTracingTime.startBenchmark();
		
		int maxReach = Mth.ceil(radius * (1f + randomVectorLength));
		int maxReachSection = Mth.ceil(maxReach / 16f) * 2 + 1;
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		int maxReachUp = Math.min(maxReach, maxBuildHeight - pos.getY() - 1);
		int maxReachDown = Math.min(maxReach, pos.getY() - minBuildHeight);
		int maxReachSectionY = Mth.ceil(Math.max(maxReachUp / 16f, maxReachDown / 16f)) * 2 + 1;
		int arraySize = maxReachSection * maxReachSectionY * maxReachSection;
		
		float[][] sectionExplosionResistances = new float[arraySize][];
		BitSet emptySections = new BitSet(arraySize);
		BitSet fullSections = new BitSet(arraySize);
		BitSet[] editedSections = new BitSet[arraySize];
		heapAllocationMB.increment(arraySize * 8 + arraySize / 4);
		requiredHeapAllocationMB.increment(arraySize * 8 + arraySize / 4);
		int minRadiusY = Math.max(-radius, minBuildHeight - pos.getY());
		int maxRadiusY = Math.min(radius, maxBuildHeight - pos.getY());
		boolean clippedBottom = minRadiusY > -radius;
		boolean clippedTop = maxRadiusY < radius;
		
		int centerX = pos.getX() >> 4;
		int centerY = pos.getY() >> 4;
		int centerZ = pos.getZ() >> 4;
		
		for (int x = -radius; x <= radius; ++x) {
			for (int y = minRadiusY; y <= maxRadiusY; ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if ((distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1))
							|| (((clippedBottom && y == minRadiusY) || (clippedTop && y == maxRadiusY)) && distanceSqr <= (radius + 1) * (radius + 1))) {
						rayCount.increment(1);
						float distance = Mth.sqrt(distanceSqr);
						float dirX = x / distance;
						float dirY = y / distance;
						float dirZ = z / distance;
						
						int stepX = dirX > 0 ? 1 : -1;
						int stepY = dirY > 0 ? 1 : -1;
						int stepZ = dirZ > 0 ? 1 : -1;

						float deltaX = dirX == 0f ? Float.POSITIVE_INFINITY : Math.abs(1f / dirX);
						float deltaY = dirY == 0f ? Float.POSITIVE_INFINITY : Math.abs(1f / dirY);
						float deltaZ = dirZ == 0f ? Float.POSITIVE_INFINITY : Math.abs(1f / dirZ);

						int posX = pos.getX();
						int posY = pos.getY();
						int posZ = pos.getZ();

						float nextVoxelX = stepX > 0 ? posX + 1 : posX - 1;
						float nextVoxelY = stepY > 0 ? posY + 1 : posY - 1;
						float nextVoxelZ = stepZ > 0 ? posZ + 1 : posZ - 1;
						
						float tMaxX = dirX == 0f ? Float.POSITIVE_INFINITY : (nextVoxelX - posX) / dirX;
						float tMaxY = dirY == 0f ? Float.POSITIVE_INFINITY : (nextVoxelY - posY) / dirY;
						float tMaxZ = dirZ == 0f ? Float.POSITIVE_INFINITY : (nextVoxelZ - posZ) / dirZ;	
						float t = 0f;
						
						int lastSectionIndex = Integer.MAX_VALUE;
						float[] currentExplosionResistances = null;
						BitSet currentEditedBlocks = null;
						
						float remainingLength = radius * (1f + random.nextFloat() * randomVectorLength);
						boolean firstIteration = true;
						
						while (remainingLength > 0f) {
							float travel = 0.3f;
							if (!firstIteration) {
								float nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));

								if (tMaxX == nextT) {
								    posX += stepX;
								    tMaxX += deltaX;
								} else if (tMaxY == nextT) {
								    posY += stepY;
								    tMaxY += deltaY;
								} else if (tMaxZ == nextT) {
								    posZ += stepZ;
								    tMaxZ += deltaZ;
								}

								travel = nextT - t;
								t = nextT;
								remainingLength -= travel;
								travel = Math.max(0.1f, travel);
							}
							firstIteration = false;
							
							if (posY < minBuildHeight || posY >= maxBuildHeight || Math.abs(posX) >= 30000000 || Math.abs(posZ) >= 30000000) {
								break;
							}

							int sectionIndex = ((posX >> 4) - centerX + maxReachSection / 2) * maxReachSection * maxReachSectionY + ((posY >> 4) - centerY + maxReachSectionY / 2) * maxReachSection + (posZ >> 4) - centerZ + maxReachSection / 2;
							int blockIndex = ((posX & 15) << 8) | ((posY & 15) << 4) | (posZ & 15);
							if (emptySections.get(sectionIndex)) {
								continue;
							}
							if (fullSections.get(sectionIndex)) {
								currentExplosionResistances = sectionExplosionResistances[sectionIndex];
								remainingLength -= currentExplosionResistances[blockIndex] * travel;
								continue;
							}
							
							if (sectionIndex != lastSectionIndex) {
								currentExplosionResistances = sectionExplosionResistances[sectionIndex];
								if (currentExplosionResistances == null) {
									int chunkX = posX >> 4;
									int chunkY = posY >> 4;
									int chunkZ = posZ >> 4;
									LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(level.getSectionIndexFromSectionY(chunkY));
									if (section.hasOnlyAir()) {
										emptySections.set(sectionIndex);
										continue;
									}
									float[] explosionResistances = new float[4096];
									for (int i = 0; i < 4096; ++i) {
										BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
										explosionResistances[i] = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f;
									}
									currentExplosionResistances = sectionExplosionResistances[sectionIndex] = explosionResistances;
								}
								currentEditedBlocks = editedSections[sectionIndex];
								if (currentEditedBlocks == null) {
									currentEditedBlocks = editedSections[sectionIndex] = new BitSet(4096);
									heapAllocationMB.increment(560);
									requiredHeapCache += 560;
									maxRequiredHeapCache = Math.max(requiredHeapCache, maxRequiredHeapCache);
								}
							}
							
							remainingLength -= currentExplosionResistances[blockIndex] * travel;
							if (remainingLength <= 0f) {
								break;
							}
							currentEditedBlocks.set(blockIndex);
							if (currentEditedBlocks.nextClearBit(0) >= 4096) {
								fullSections.set(sectionIndex);
								editedSections[sectionIndex] = null;
								requiredHeapCache -= 560;
							}
							
							lastSectionIndex = sectionIndex;
						}
					}
				}
			}
		}
		rayTracingTime.stopBenchmark();
		for (int i = 0; i < fullSections.size(); i++) {
			if (fullSections.get(i)) {
				blockCount.increment(4096);
			}
		}
		for (int i = 0; i < sectionExplosionResistances.length; i++) {
			if (sectionExplosionResistances[i] != null) {
				heapAllocationMB.increment(16432);
				maxRequiredHeapCache += 16432;
			}
		}
		for (BitSet editedBlocks : editedSections) {
			if (editedBlocks != null) {
				blockCount.increment(editedBlocks);
			}
		}
		requiredHeapAllocationMB.increment(maxRequiredHeapCache);
		finishExplosion(editedSections, fullSections, SectionPos.of(pos), maxReachSection, maxReachSectionY);
		totalTime.stopBenchmark();
		if (finishWork != null) {
			finishWork.run();
		}
	}
}
