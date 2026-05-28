package mod.explosion;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import mod.explosion.helper.MultithreadedExplosionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class ImprovedExplosionDDA extends ExplosionBase {

	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final boolean multithreaded;
	
	public ImprovedExplosionDDA(ServerLevel level, BlockPos pos, int radius, float randomVectorLength, boolean multithreaded) {
		super(level);
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.multithreaded = multithreaded;
	}

	@Override
	public void explode() {
		if (multithreaded) {
			profiler.setTitle("explosions.benchmark.improved_explosion_dda_multithreaded");
			explodeMultithreaded();
		} else {
			profiler.setTitle("explosions.benchmark.improved_explosion_dda");
			explodeSinglethreaded();
		}
	}
	
	private void explodeMultithreaded() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Profile rayCreationTime = profiler.createProfile("explosions.benchmark.ray_creation_time");
		
		totalTime.startBenchmark();
		rayCreationTime.startBenchmark();
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		int minRadiusY = Math.max(-radius, minBuildHeight - pos.getY());
		int maxRadiusY = Math.min(radius, maxBuildHeight - pos.getY());
		boolean clippedBottom = minRadiusY > -radius;
		boolean clippedTop = maxRadiusY < radius;
		List<Vec3i> rays = new ArrayList<Vec3i>();
		for (int x = -radius; x <= radius; ++x) {
			for (int y = minRadiusY; y <= maxRadiusY; ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if ((distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1))
							|| (((clippedBottom && y == minRadiusY) || (clippedTop && y == maxRadiusY)) && distanceSqr <= (radius + 1) * (radius + 1))) {
						rays.add(new Vec3i(x, y, z));
					}
				}
			}
		}
		rayCreationTime.stopBenchmark();
		rayTracingTime.addCounter(rays.size());

		rayTracingTime.startBenchmark();
		ImprovedExplosionThread explosionThread = new ImprovedExplosionThread(this, rays, pos, radius, randomVectorLength, true, true);
		MultithreadedExplosionHandler.enqueue(explosionThread);
	}
	
	@SuppressWarnings("deprecation")
	private void explodeSinglethreaded() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Counter blockCount = totalTime.addCounter(0);
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Counter rayCount = rayTracingTime.addCounter(0);
		Profile sectionAccesses = profiler.createProfile("explosions.benchmark.section_accesses");
		Counter sectionAccessCount = sectionAccesses.addCounter(0);
		Profile heapAllocations = profiler.createProfile("explosions.benchmark.heap_allocations");
		Counter heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		Counter requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		
		int maxRequiredHeapCache = 0;
		int requiredHeapCache = 0;
		
		totalTime.startBenchmark();
		rayTracingTime.startBenchmark();
		Map<Long, BitSet> editedSections = new Long2ObjectOpenHashMap<BitSet>();
		Set<Long> fullSections = new LongOpenHashSet();
		Set<Long> emptySections = new LongOpenHashSet();
		Map<Long, float[]> sectionExplosionResistances = new Long2ObjectOpenHashMap<float[]>();
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
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
						
						float deltaX = Math.abs(1f / dirX);
						float deltaY = Math.abs(1f / dirY);
						float deltaZ = Math.abs(1f / dirZ);

						int posX = pos.getX();
						int posY = pos.getY();
						int posZ = pos.getZ();
						
						float nextVoxelX = stepX > 0 ? posX + 1 : posX;
						float nextVoxelY = stepY > 0 ? posY + 1 : posY;
						float nextVoxelZ = stepZ > 0 ? posZ + 1 : posZ;
						
						float tMaxX = (nextVoxelX - posX) / dirX;
						float tMaxY = (nextVoxelY - posY) / dirY;
						float tMaxZ = (nextVoxelZ - posZ) / dirZ;		
						float t = 0f;
						
						long lastSectionPos = Long.MAX_VALUE;
						boolean currentSectionEmpty = false;
						boolean currentSectionFull = false;
						float[] currentExplosionResistances = null;
						BitSet currentEditedBlocks = null;
						
						float remainingLength = radius * (1f + random.nextFloat() * randomVectorLength);
						boolean firstIteration = true;
						
						while (remainingLength > 0f) {
							float travel = 0f;
							if (!firstIteration) {
								float nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));

								if (tMaxX == nextT) {
								    posX += stepX;
								    tMaxX += deltaX;
								}

								if (tMaxY == nextT) {
								    posY += stepY;
								    tMaxY += deltaY;
								}

								if (tMaxZ == nextT) {
								    posZ += stepZ;
								    tMaxZ += deltaZ;
								}

								travel = nextT - t;
								t = nextT;
								remainingLength -= travel;
								travel = Math.max(0.225f, travel);
								if (t >= radius) {
									break;
								}
							}
							firstIteration = false;
							
							if (posY < minBuildHeight || posY >= maxBuildHeight || Math.abs(posX) >= 30000000 || Math.abs(posZ) >= 30000000) {
								break;
							}
							long sectionPos = SectionPos.asLong(posX >> 4, posY >> 4, posZ >> 4);
							int blockIndex = ((posX & 15) << 8) | ((posY & 15) << 4) | (posZ & 15);
							
							if ((sectionPos == lastSectionPos && currentSectionEmpty) || emptySections.contains(sectionPos)) {
								currentSectionEmpty = true;
								continue;
							}
							currentSectionEmpty = false;
							if ((sectionPos == lastSectionPos && currentSectionFull)) {
								remainingLength -= currentExplosionResistances[blockIndex] * travel;
								continue;
							}
							currentSectionFull = false;
							
							if (sectionPos != lastSectionPos) {
								currentExplosionResistances = sectionExplosionResistances.get(sectionPos);
								if (fullSections.contains(sectionPos)) {
									currentSectionFull = true;
									remainingLength -= currentExplosionResistances[blockIndex] * travel;
									continue;
								}
								if (currentExplosionResistances == null) {
									int chunkX = posX >> 4;
									int chunkY = posY >> 4;
									int chunkZ = posZ >> 4;
									LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(level.getSectionIndexFromSectionY(chunkY));
									sectionAccessCount.increment(1);
									if (section.hasOnlyAir()) {
										emptySections.add(sectionPos);
										currentSectionEmpty = true;
										continue;
									}
									float[] explosionResistances = new float[4096];
									for (int i = 0; i < 4096; ++i) {
										BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
										explosionResistances[i] = Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) * 0.167f;
									}
									sectionExplosionResistances.put(sectionPos, explosionResistances);
									currentExplosionResistances = explosionResistances;
								}
								currentEditedBlocks = editedSections.get(sectionPos);
								if (currentEditedBlocks == null) {
									editedSections.put(sectionPos, currentEditedBlocks = new BitSet(4096));
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
								fullSections.add(sectionPos);
								editedSections.remove(sectionPos);
								//Required heap actually reduces here
								requiredHeapCache -= 560;
							}
							
							lastSectionPos = sectionPos;
						}
					}
				}
			}
		}
		rayTracingTime.stopBenchmark();
		for (int i = 0; i < fullSections.size(); i++) {
			blockCount.increment(4096);
			//We can do this here, since the count in this set never decreases
			heapAllocationMB.increment(40);
			maxRequiredHeapCache += 40;
		}
		for (int i = 0; i < emptySections.size(); i++) {
			//We can do this here, since the count in this set never decreases
			heapAllocationMB.increment(40);
			maxRequiredHeapCache += 40;
		}
		for (int i = 0; i < sectionExplosionResistances.size(); i++) {
			//We can do this here, since the count in this set never decreases
			heapAllocationMB.increment(16432);
			maxRequiredHeapCache += 16432;
		}
		for (BitSet editedBlocks : editedSections.values()) {
			blockCount.increment(editedBlocks);
		}
		requiredHeapAllocationMB.increment(maxRequiredHeapCache);
		finishExplosion(editedSections, fullSections);
		totalTime.stopBenchmark();
		if (finishWork != null) {
			finishWork.run();
		}
	}
}
