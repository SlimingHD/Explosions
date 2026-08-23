package mod.explosion;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import mod.explosion.ImprovedExplosionThread.Mode;
import mod.explosion.helper.MultithreadedExplosionHandler;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class ImprovedExplosionRayReduced extends ExplosionBase {

	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final boolean multithreaded;
	private final RayReduction reduction;
	
	public ImprovedExplosionRayReduced(ServerLevel level, BlockPos pos, int radius, float randomVectorLength, boolean multithreaded, RayReduction reduction) {
		super(level);
		this.pos = pos;
		if (reduction.isRadius()) {
			this.radius = (int)(radius * 0.85f);
		} else {
			this.radius = radius;
		}
		this.randomVectorLength = randomVectorLength;
		this.multithreaded = multithreaded;
		this.reduction = reduction;
	}

	@Override
	public void explode() {
		if (multithreaded) {
			profiler.setTitle("explosions.benchmark.improved_explosion_ray_reduced_multithreaded");
			explodeMultithreaded();
		} else {
			profiler.setTitle("explosions.benchmark.improved_explosion_ray_reduced");
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
		int maxBuildHeight = level.getMaxBuildHeight() - 1;
		int minRadiusY = Math.max(-radius, minBuildHeight - pos.getY());
		int maxRadiusY = Math.min(radius, maxBuildHeight - pos.getY());
		boolean clippedBottom = minRadiusY > -radius;
		boolean clippedTop = maxRadiusY < radius;
		List<Vec3i> rays = new ArrayList<Vec3i>(reduction.isYLimit() ? 10 : Mth.ceil(4 * Mth.PI * radius * radius));
		for (int x = -radius; x <= radius; ++x) {
			for (int y = reduction.isYLimit() ? minRadiusY : -radius; y <= (reduction.isYLimit() ? maxRadiusY : radius); ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if ((distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1))
							|| (reduction.isYLimit() && ((clippedBottom && y == minRadiusY) || (clippedTop && y == maxRadiusY)) && distanceSqr <= (radius + 1) * (radius + 1))) {
						rays.add(new Vec3i(x, y, z));
					}
				}
			}
		}
		rayCreationTime.stopBenchmark();
		rayTracingTime.addCounter(rays.size());

		rayTracingTime.startBenchmark();
		ImprovedExplosionThread explosionThread = new ImprovedExplosionThread(this, rays, pos, radius, randomVectorLength, reduction.isRadius() ? Mode.REDUCED_RAYS : Mode.DEFAULT);
		MultithreadedExplosionHandler.enqueue(explosionThread);
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
		
		int maxRequiredHeapCache = 0;
		int requiredHeapCache = 0;
		
		totalTime.startBenchmark();
		rayTracingTime.startBenchmark();
		Map<Long, BitSet> editedSections = new Long2ObjectOpenHashMap<BitSet>();
		Set<Long> fullSections = new LongOpenHashSet();
		Set<Long> emptySections = new LongOpenHashSet();
		Map<Long, float[]> sectionExplosionResistances = new Long2ObjectOpenHashMap<float[]>();
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight() - 1;
		int minRadiusY = Math.max(-radius, minBuildHeight - pos.getY());
		int maxRadiusY = Math.min(radius, maxBuildHeight - pos.getY());
		boolean clippedBottom = minRadiusY > -radius;
		boolean clippedTop = maxRadiusY < radius;
		for (int x = -radius; x <= radius; ++x) {
			for (int y = reduction.isYLimit() ? minRadiusY : -radius; y <= (reduction.isYLimit() ? maxRadiusY : radius); ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if ((distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1))
							|| (reduction.isYLimit() && ((clippedBottom && y == minRadiusY) || (clippedTop && y == maxRadiusY)) && distanceSqr <= (radius + 1) * (radius + 1))) {
						rayCount.increment(1);
						float distance = Mth.sqrt(distanceSqr);
						float stepX = x / distance * 0.3f;
						float stepY = y / distance * 0.3f;
						float stepZ = z / distance * 0.3f;

						double blockX = pos.getX();
						double blockY = pos.getY();
						double blockZ = pos.getZ();
						
						int lastPosX = Integer.MAX_VALUE;
						int lastPosY = Integer.MAX_VALUE;
						int lastPosZ = Integer.MAX_VALUE;
						
						long lastSectionPos = Long.MAX_VALUE;
						boolean currentSectionEmpty = false;
						boolean currentSectionFull = false;
						float[] currentExplosionResistances = null;
						BitSet currentEditedBlocks = null;
						
						float remainingLength = radius * ((reduction.isRadius() ? 1.175f : 1f) + random.nextFloat() * randomVectorLength);
						
						for (float step = 0f; step < remainingLength; step += 0.3f) {
							blockX += stepX;
							blockY += stepY;
							blockZ += stepZ;
							int posX = Mth.floor(blockX);
							int posY = Mth.floor(blockY);
							int posZ = Mth.floor(blockZ);
							if (posY < minBuildHeight || posY > maxBuildHeight || Math.abs(posX) >= 30000000 || Math.abs(posZ) >= 30000000) {
								break;
							}
							long sectionPos = SectionPos.asLong(posX >> 4, posY >> 4, posZ >> 4);
							
							if ((sectionPos == lastSectionPos && currentSectionEmpty) || emptySections.contains(sectionPos)) {
								currentSectionEmpty = true;
								continue;
							}

							int blockIndex = ((posX & 15) << 8) | ((posY & 15) << 4) | (posZ & 15);
							if (posX == lastPosX && posY == lastPosY && posZ == lastPosZ) {
								remainingLength -= currentExplosionResistances[blockIndex];
								continue;
							}
							lastPosX = posX;
							lastPosY = posY;
							lastPosZ = posZ;
							
							currentSectionEmpty = false;
							if (sectionPos == lastSectionPos && currentSectionFull) {
								remainingLength -= currentExplosionResistances[blockIndex];
								continue;
							}
							currentSectionFull = false;
							
							if (sectionPos != lastSectionPos) {
								currentExplosionResistances = sectionExplosionResistances.get(sectionPos);
								if (fullSections.contains(sectionPos)) {
									currentSectionFull = true;
									remainingLength -= currentExplosionResistances[blockIndex];
									continue;
								}
								if (currentExplosionResistances == null) {
									int chunkX = posX >> 4;
									int chunkY = posY >> 4;
									int chunkZ = posZ >> 4;
									LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(level.getSectionIndexFromSectionY(chunkY));
									if (section.hasOnlyAir()) {
										emptySections.add(sectionPos);
										currentSectionEmpty = true;
										continue;
									}
									float[] explosionResistances = new float[4096];
									for (int i = 0; i < 4096; ++i) {
										BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
										explosionResistances[i] = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f * 0.3f;
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
							
							remainingLength -= currentExplosionResistances[blockIndex];
							if (step >= remainingLength) {
								break;
							}
							currentEditedBlocks.set(blockIndex);
							if (currentEditedBlocks.nextClearBit(0) >= 4096) {
								fullSections.add(sectionPos);
								editedSections.remove(sectionPos);
								currentSectionFull = true;
								requiredHeapCache -= 560;
							}
							
							lastSectionPos = sectionPos;
						}
					}
				}
			}
		}
		rayTracingTime.stopBenchmark();
		blockCount.increment(4096 * fullSections.size());
		heapAllocationMB.increment(40 * (fullSections.size() + emptySections.size()) + 16432 * sectionExplosionResistances.size());
		maxRequiredHeapCache += 40 * (fullSections.size() + emptySections.size()) + 16432 * sectionExplosionResistances.size();
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
	
	public static enum RayReduction {
		BY_Y_LIMIT(true, false),
		BY_RADIUS(false, true),
		BOTH(true, true);
		
		private final boolean yLimit;
		private final boolean radius;
		
		private RayReduction(boolean yLimit, boolean radius) {
			this.yLimit = yLimit;
			this.radius = radius;
		}
		
		public boolean isYLimit() {
			return yLimit;
		}
		
		public boolean isRadius() {
			return radius;
		}
	}
}
