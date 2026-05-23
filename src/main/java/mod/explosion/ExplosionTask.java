package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.RecursiveTask;

import org.joml.Vector3f;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

public class ExplosionTask extends RecursiveTask<Long2ObjectMap<BitSet>> {
	
	private final ExplosionThread explosionThread;
	private final List<Vector3f> vectors;
	private final ServerLevel level;
	private final Vec3 pos;
	private final int radius;
	private final float randomVectorLength;
	private final int minTaskSize;
	private final RandomSource random;
	
	ExplosionTask(ExplosionThread explosionThread, List<Vector3f> vectors, ServerLevel level, Vec3 pos, int radius, float randomVectorLength, int minTaskSize, RandomSource random) {
		this.explosionThread = explosionThread;
		this.vectors = vectors;
		this.level = level;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.minTaskSize = minTaskSize;
		this.random = random;
		explosionThread.taskCount++;
	}

	@Override
	protected Long2ObjectMap<BitSet> compute() {
		if (vectors.size() > minTaskSize) {
			ExplosionTask task1 = new ExplosionTask(explosionThread, vectors.subList(0, vectors.size() / 2), level, pos, radius, randomVectorLength, minTaskSize, random);
			ExplosionTask task2 = new ExplosionTask(explosionThread, vectors.subList(vectors.size() / 2, vectors.size()), level, pos, radius, randomVectorLength, minTaskSize, random);
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
		int sectionAccessesCache = 0;
		int heapAllocationCache = 0;
		int requiredHeapCache = 0;
		int maxRequiredHeapCache = 0;
		
		Long2ObjectMap<BitSet> editedSections = new Long2ObjectOpenHashMap<BitSet>();
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		for (Vector3f ray : vectors) {
			float stepX = ray.x / (radius + 0.5f) * 0.3f;
			float stepY = ray.y / (radius + 0.5f) * 0.3f;
			float stepZ = ray.z / (radius + 0.5f) * 0.3f;

			double blockX = pos.x;
			double blockY = pos.y;
			double blockZ = pos.z;
			
			int lastPosX = Integer.MAX_VALUE;
			int lastPosY = Integer.MAX_VALUE;
			int lastPosZ = Integer.MAX_VALUE;
			
			long lastSectionPos = Long.MAX_VALUE;
			boolean currentSectionEmpty = false;
			boolean currentSectionFull = false;
			float[] currentExplosionResistances = null;
			BitSet currentEditedBlocks = null;
			
			float remainingLength = radius * (0.7f + random.nextFloat() * randomVectorLength);
			
			for (float step = 0f; step < remainingLength; step += 0.225f) {
				int posX = Mth.floor(blockX);
				int posY = Mth.floor(blockY);
				int posZ = Mth.floor(blockZ);
				if (posX == lastPosX && posY == lastPosY && posZ == lastPosZ) {
					continue;
				}
				lastPosX = posX;
				lastPosY = posY;
				lastPosZ = posZ;
				if (posY < minBuildHeight || posY >= maxBuildHeight || Math.abs(posX) >= 30000000 || Math.abs(posZ) >= 30000000) {
					break;
				}
				long sectionPos = SectionPos.asLong(posX, posY, posZ);
				int blockIndex = ((posX & 15) << 8) | ((posY & 15) << 4) | (posZ & 15);
				
				if ((sectionPos == lastSectionPos && currentSectionEmpty) || explosionThread.emptySections.contains(sectionPos)) {
					currentSectionEmpty = true;
					continue;
				}
				currentSectionEmpty = false;
				if ((sectionPos == lastSectionPos && currentSectionFull)) {
					remainingLength -= currentExplosionResistances[blockIndex];
					continue;
				}
				currentSectionFull = false;
				
				if (sectionPos == lastSectionPos) {
					if (currentSectionEmpty || explosionThread.emptySections.contains(sectionPos)) {
						currentSectionEmpty = true;
						continue;
					}
					if (currentSectionFull) {
						remainingLength -= currentExplosionResistances[blockIndex];
						continue;
					}
				} else {
					currentSectionEmpty = false;
					currentSectionFull = false;
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
						LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(chunkY - level.getMinSection());
						sectionAccessesCache++;
						if (section.hasOnlyAir()) {
							explosionThread.emptySections.add(sectionPos);
							currentSectionEmpty = true;
							continue;
						}
						explosionThread.sectionExplosionResistances.computeIfAbsent(sectionPos, sp -> {
							float[] explosionResistances = new float[4096];
							for (int i = 0; i < 4096; ++i) {
								BlockState state = section.getBlockState(i & 3840, i & 240, i & 15);
								explosionResistances[i] = Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance());
							}
							return explosionResistances;
						});
					}
					currentEditedBlocks = editedSections.get(sectionPos);
					if (currentEditedBlocks == null) {
						editedSections.put(sectionPos, currentEditedBlocks = new BitSet(4096));
						heapAllocationCache += 560;
						requiredHeapCache += 560;
						maxRequiredHeapCache = Math.max(requiredHeapCache, maxRequiredHeapCache);
					}
				}
				
				remainingLength -= currentExplosionResistances[blockIndex];
				if (remainingLength <= 0f) {
					break;
				}
				currentEditedBlocks.set(blockIndex);
				if (currentEditedBlocks.nextClearBit(blockIndex) >= 4096) {
					explosionThread.fullSections.add(sectionPos);
					editedSections.remove(sectionPos);
					//Required heap actually reduces here
					requiredHeapCache -= 560;
				}
				
				lastSectionPos = sectionPos;
				blockX += stepX;
				blockY += stepY;
				blockZ += stepZ;
			}
		}
		explosionThread.sectionAccessesCache.addAndGet(sectionAccessesCache);
		explosionThread.heapAllocationCache.addAndGet(heapAllocationCache);
		explosionThread.maxRequiredHeapCache.accumulateAndGet(maxRequiredHeapCache, Math::max);
		return editedSections;
	}
}
