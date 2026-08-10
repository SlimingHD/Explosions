package mod.explosion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import mod.explosion.helper.MultithreadedExplosionHandler;
import mod.explosion.helper.PalettedSectionResistances;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class PrecomputedExplosion extends ExplosionBase {

	private static final PalettedSectionResistances EMPTY_SECTION = new PalettedSectionResistances(FloatList.of(), new short[0]);
	
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final boolean multithreaded;
	
	public PrecomputedExplosion(ServerLevel level, BlockPos pos, int radius, float randomVectorLength, boolean multithreaded) {
		super(level);
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.multithreaded = multithreaded;
	}

	@Override
	public void explode() {
		if (multithreaded) {
			profiler.setTitle("explosions.benchmark.precomputed_explosion_multithreaded");
			explodeMultithreaded();
		} else {
			profiler.setTitle("explosions.benchmark.precomputed_explosion");
			explodeSinglethreaded();
		}
	}
	
	@SuppressWarnings("deprecation")
	private void explodeMultithreaded() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Counter rayCount = rayTracingTime.addCounter(0);
		Profile preComputationTime = profiler.createProfile("explosions.benchmark.precomputation_time");
		Counter sectionCount = preComputationTime.addCounter(0);
		Profile rayCreationTime = profiler.createProfile("explosions.benchmark.ray_creation_time");
		Profile heapAllocations = profiler.createProfile("explosions.benchmark.heap_allocations");
		Counter heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		Counter requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		
		totalTime.startBenchmark();
		preComputationTime.startBenchmark();
		
		int maxReach = Mth.ceil(radius * (1f + randomVectorLength));
		int maxReachSection = Mth.ceil(maxReach / 16f) * 2 + 1;
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		int maxReachUp = Math.min(maxReach, maxBuildHeight - pos.getY() - 1);
		int maxReachDown = Math.min(maxReach, pos.getY() - minBuildHeight);
		int maxReachSectionY = Mth.ceil(Math.max(maxReachUp / 16f, maxReachDown / 16f)) * 2 + 1;
		int arraySize = maxReachSection * maxReachSectionY * maxReachSection;
		
		PalettedSectionResistances[] sectionExplosionResistances = new PalettedSectionResistances[arraySize];
		
		heapAllocationMB.increment(arraySize * 24 + arraySize / 8);
		requiredHeapAllocationMB.increment(arraySize * 24 + arraySize / 8);
		
		int centerX = pos.getX() >> 4;
		int centerY = pos.getY() >> 4;
		int centerZ = pos.getZ() >> 4;
		
		for (int x = -maxReachSection / 2; x <= maxReachSection / 2; ++x) {
			for (int z = -maxReachSection / 2; z <= maxReachSection / 2; ++z) {
				int realX = x << 4;
				int realZ = z << 4;
				int minX = realX > 0 ? realX : realX + 15 < 0 ? realX + 15 : 0;
				int minZ = realZ > 0 ? realZ : realZ + 15 < 0 ? realZ + 15 : 0;
				int minDistanceSqr = minX * minX + minZ * minZ;
				if (minDistanceSqr > maxReach * maxReach) {
					continue;
				}
				LevelChunk chunk = level.getChunk(x + centerX, z + centerZ);
				for (int y = -maxReachSectionY / 2; y <= maxReachSectionY / 2; ++y) {
					int realY = y << 4;
					int minY = realY > 0 ? realY : realY + 15 < 0 ? realY + 15 : 0;
					minDistanceSqr = minX * minX + minY * minY + minZ * minZ;
					if (minDistanceSqr > maxReach * maxReach || y + centerY < level.getMinSection() || y + centerY >= level.getMaxSection()) {
						continue;
					}
					LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(y + centerY));
					int sectionIndex = (x + maxReachSection / 2) * maxReachSection * maxReachSectionY + (y + maxReachSectionY / 2) * maxReachSection + z + maxReachSection / 2;
					if (section.hasOnlyAir()) {
						sectionExplosionResistances[sectionIndex] = EMPTY_SECTION;
					} else {
						FloatList resistances = new FloatArrayList();
						short[] indices = new short[4096];
						//Two 12 byte headers are included here
						heapAllocationMB.increment(8216);
						for (int i = 0; i < 4096; ++i) {
							BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
							float resistance = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f;
							int resistanceIndex = resistances.indexOf(resistance);
							if (resistanceIndex != -1) {
								indices[i] = (short)resistanceIndex;
							} else {
								indices[i] = (short)resistances.size();
								resistances.add(resistance);
							}
						}
						sectionExplosionResistances[sectionIndex] = new PalettedSectionResistances(resistances, indices);
					}
					sectionCount.increment(1);
				}
			}
		}
		
		preComputationTime.stopBenchmark();
		rayCreationTime.startBenchmark();

		int maxRaysPerTask = 25000;
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
		
		rayCreationTime.stopBenchmark();
		
		rayTracingTime.startBenchmark();
		PrecomputedExplosionThread thread = new PrecomputedExplosionThread(this, rays, sectionExplosionResistances, pos, radius, randomVectorLength);
		MultithreadedExplosionHandler.enqueuePrecomputed(thread);
	}
	
	@SuppressWarnings("deprecation")
	private void explodeSinglethreaded() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Counter blockCount = totalTime.addCounter(0);
		Profile preComputationTime = profiler.createProfile("explosions.benchmark.precomputation_time");
		Counter sectionCount = preComputationTime.addCounter(0);
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Counter rayCount = rayTracingTime.addCounter(0);
		Profile heapAllocations = profiler.createProfile("explosions.benchmark.heap_allocations");
		Counter heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		Counter requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		
		long maxRequiredHeapCache = 0;
		long requiredHeapCache = 0;
		
		totalTime.startBenchmark();
		preComputationTime.startBenchmark();
		
		int maxReach = Mth.ceil(radius * (1f + randomVectorLength));
		int maxReachSection = Mth.ceil(maxReach / 16f) * 2 + 1;
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		int maxReachUp = Math.min(maxReach, maxBuildHeight - pos.getY() - 1);
		int maxReachDown = Math.min(maxReach, pos.getY() - minBuildHeight);
		int maxReachSectionY = Mth.ceil(Math.max(maxReachUp / 16f, maxReachDown / 16f)) * 2 + 1;
		int arraySize = maxReachSection * maxReachSectionY * maxReachSection;
		
		PalettedSectionResistances[] sectionExplosionResistances = new PalettedSectionResistances[arraySize];
		BitSet fullSections = new BitSet(arraySize);
		BitSet[] editedSections = new BitSet[arraySize];
		
		heapAllocationMB.increment(arraySize * 24 + arraySize / 8);
		requiredHeapAllocationMB.increment(arraySize * 24 + arraySize / 8);
		
		int centerX = pos.getX() >> 4;
		int centerY = pos.getY() >> 4;
		int centerZ = pos.getZ() >> 4;
		
		for (int x = -maxReachSection / 2; x <= maxReachSection / 2; ++x) {
			for (int z = -maxReachSection / 2; z <= maxReachSection / 2; ++z) {
				int realX = x << 4;
				int realZ = z << 4;
				int minX = realX > 0 ? realX : realX + 15 < 0 ? realX + 15 : 0;
				int minZ = realZ > 0 ? realZ : realZ + 15 < 0 ? realZ + 15 : 0;
				int minDistanceSqr = minX * minX + minZ * minZ;
				if (minDistanceSqr > maxReach * maxReach) {
					continue;
				}
				LevelChunk chunk = level.getChunk(x + centerX, z + centerZ);
				for (int y = -maxReachSectionY / 2; y <= maxReachSectionY / 2; ++y) {
					int realY = y << 4;
					int minY = realY > 0 ? realY : realY + 15 < 0 ? realY + 15 : 0;
					minDistanceSqr = minX * minX + minY * minY + minZ * minZ;
					if (minDistanceSqr > maxReach * maxReach || y + centerY < level.getMinSection() || y + centerY >= level.getMaxSection()) {
						continue;
					}
					LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(y + centerY));
					int sectionIndex = (x + maxReachSection / 2) * maxReachSection * maxReachSectionY + (y + maxReachSectionY / 2) * maxReachSection + z + maxReachSection / 2;
					if (section.hasOnlyAir()) {
						sectionExplosionResistances[sectionIndex] = EMPTY_SECTION;
					} else {
						FloatList resistances = new FloatArrayList();
						short[] indices = new short[4096];
						//Two 12 byte headers are included here
						heapAllocationMB.increment(8216);
						for (int i = 0; i < 4096; ++i) {
							BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
							float resistance = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f;
							int resistanceIndex = resistances.indexOf(resistance);
							if (resistanceIndex != -1) {
								indices[i] = (short)resistanceIndex;
							} else {
								indices[i] = (short)resistances.size();
								resistances.add(resistance);
							}
						}
						sectionExplosionResistances[sectionIndex] = new PalettedSectionResistances(resistances, indices);
					}
					sectionCount.increment(1);
				}
			}
		}
		
		preComputationTime.stopBenchmark();
		rayTracingTime.startBenchmark();
		
		int minRadiusY = Math.max(-radius, minBuildHeight - pos.getY());
		int maxRadiusY = Math.min(radius, maxBuildHeight - pos.getY());
		boolean clippedBottom = minRadiusY > -radius;
		boolean clippedTop = maxRadiusY < radius;
		
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
						PalettedSectionResistances currentExplosionResistances = null;
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
								travel = Math.max(0.3f, travel);
							}
							firstIteration = false;
							
							if (posY < minBuildHeight || posY >= maxBuildHeight || Math.abs(posX) >= 30000000 || Math.abs(posZ) >= 30000000) {
								break;
							}

							int sectionIndex = ((posX >> 4) - centerX + maxReachSection / 2) * maxReachSection * maxReachSectionY + ((posY >> 4) - centerY + maxReachSectionY / 2) * maxReachSection + (posZ >> 4) - centerZ + maxReachSection / 2;
							int blockIndex = ((posX & 15) << 8) | ((posY & 15) << 4) | (posZ & 15);
							if (sectionIndex == lastSectionIndex && currentExplosionResistances != null && currentExplosionResistances.isEmpty()) {
								continue;
							}
							if (fullSections.get(sectionIndex)) {
								currentExplosionResistances = sectionExplosionResistances[sectionIndex];
								remainingLength -= currentExplosionResistances.get(blockIndex) * travel;
								continue;
							}
							
							if (sectionIndex != lastSectionIndex) {
								currentExplosionResistances = sectionExplosionResistances[sectionIndex];
								if (currentExplosionResistances == null) {
									break;
								}
								if (currentExplosionResistances.isEmpty()) {
									continue;
								}
								currentEditedBlocks = editedSections[sectionIndex];
								if (currentEditedBlocks == null) {
									currentEditedBlocks = editedSections[sectionIndex] = new BitSet(4096);
									heapAllocationMB.increment(560);
									requiredHeapCache += 560;
									maxRequiredHeapCache = Math.max(requiredHeapCache, maxRequiredHeapCache);
								}
							}
							
							remainingLength -= currentExplosionResistances.get(blockIndex) * travel;
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
				heapAllocationMB.increment(sectionExplosionResistances[i].getByteSize());
				maxRequiredHeapCache += sectionExplosionResistances[i].getByteSize();
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
