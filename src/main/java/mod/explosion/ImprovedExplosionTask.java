package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.RecursiveTask;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class ImprovedExplosionTask extends RecursiveTask<Long2ObjectMap<BitSet>> {
	
	private final ImprovedExplosionThread explosionThread;
	private final List<Vec3i> rays;
	private final ServerLevel level;
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final boolean reducedRays;
	private final int minTaskSize;
	private final RandomSource random;
	
	ImprovedExplosionTask(ImprovedExplosionThread explosionThread, List<Vec3i> rays, ServerLevel level, BlockPos pos, int radius, float randomVectorLength, boolean reducedRays, int minTaskSize) {
		this.explosionThread = explosionThread;
		this.rays = rays;
		this.level = level;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.reducedRays = reducedRays;
		this.minTaskSize = minTaskSize;
		this.random = RandomSource.create(0);
		explosionThread.taskCount.getAndIncrement();
	}

	@Override
	protected Long2ObjectMap<BitSet> compute() {
		if (rays.size() > minTaskSize * 2) {
			ImprovedExplosionTask task1 = new ImprovedExplosionTask(explosionThread, rays.subList(0, rays.size() / 2), level, pos, radius, randomVectorLength, reducedRays, minTaskSize);
			ImprovedExplosionTask task2 = new ImprovedExplosionTask(explosionThread, rays.subList(rays.size() / 2, rays.size()), level, pos, radius, randomVectorLength, reducedRays, minTaskSize);
			task2.fork();
			Long2ObjectMap<BitSet> toMergeBitSets = task1.compute();
			Long2ObjectMap<BitSet> resultBitSets = task2.join();
			for (long key : toMergeBitSets.keySet()) {
				if (resultBitSets.containsKey(key)) {
					resultBitSets.get(key).or(toMergeBitSets.get(key));
					if (resultBitSets.get(key).nextClearBit(0) >= 4096) {
						resultBitSets.remove(key);
						explosionThread.fullSections.add(key);
					}
				} else {
					resultBitSets.put(key, toMergeBitSets.get(key));
				}
			}
			return resultBitSets;
		}
		return calculate();
	}

	@SuppressWarnings("deprecation")
	private Long2ObjectMap<BitSet> calculate() {
		long heapAllocationCache = 0;
		
		Long2ObjectMap<BitSet> editedSections = new Long2ObjectOpenHashMap<BitSet>();
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight() - 1;
		for (Vec3i ray : rays) {
			float length = Mth.sqrt(ray.getX() * ray.getX() + ray.getY() * ray.getY() + ray.getZ() * ray.getZ());
			float stepX = ray.getX() / length * 0.3f;
			float stepY = ray.getY() / length * 0.3f;
			float stepZ = ray.getZ() / length * 0.3f;

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
			
			float remainingLength = radius * ((reducedRays ? 1.175f : 1f) + random.nextFloat() * randomVectorLength);
			
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
				
				if ((sectionPos == lastSectionPos && currentSectionEmpty) || explosionThread.emptySections.contains(sectionPos)) {
					currentSectionEmpty = true;
					continue;
				}
				currentSectionEmpty = false;

				int blockIndex = ((posX & 15) << 8) | ((posY & 15) << 4) | (posZ & 15);
				if (posX == lastPosX && posY == lastPosY && posZ == lastPosZ) {
					remainingLength -= currentExplosionResistances[blockIndex];
					continue;
				}
				lastPosX = posX;
				lastPosY = posY;
				lastPosZ = posZ;
				
				if (sectionPos == lastSectionPos && currentSectionFull) {
					remainingLength -= currentExplosionResistances[blockIndex];
					continue;
				}
				currentSectionFull = false;
				
				if (sectionPos != lastSectionPos) {
					currentExplosionResistances = explosionThread.sectionExplosionResistances.get(sectionPos);
					if (explosionThread.fullSections.contains(sectionPos)) {
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
							explosionThread.emptySections.add(sectionPos);
							currentSectionEmpty = true;
							continue;
						}
						currentExplosionResistances = explosionThread.sectionExplosionResistances.computeIfAbsent(sectionPos, sp -> {
							float[] explosionResistances = new float[4096];
							for (int i = 0; i < 4096; ++i) {
								BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
								explosionResistances[i] = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f * 0.3f;
							}
							return explosionResistances;
						});
					}
					currentEditedBlocks = editedSections.get(sectionPos);
					if (currentEditedBlocks == null) {
						editedSections.put(sectionPos, currentEditedBlocks = new BitSet(4096));
						heapAllocationCache += 560;
						long currentRequired = explosionThread.requiredHeapCache.addAndGet(560);
						explosionThread.maxRequiredHeapCache.accumulateAndGet(currentRequired, Math::max);
					}
				}
				
				remainingLength -= currentExplosionResistances[blockIndex];
				if (step >= remainingLength) {
					break;
				}
				currentEditedBlocks.set(blockIndex);
				if (currentEditedBlocks.nextClearBit(0) >= 4096) {
					explosionThread.fullSections.add(sectionPos);
					editedSections.remove(sectionPos);
					currentSectionFull = true;
					explosionThread.requiredHeapCache.addAndGet(-560);
				}
				
				lastSectionPos = sectionPos;
			}
		}
		explosionThread.heapAllocationCache.addAndGet(heapAllocationCache);
		return editedSections;
	}
}
