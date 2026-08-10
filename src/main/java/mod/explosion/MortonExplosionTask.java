package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.RecursiveTask;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class MortonExplosionTask extends RecursiveTask<Int2ObjectMap<BitSet>> {
	
	private final MortonExplosionThread explosionThread;
	private final Long2LongMap rays;
	private final List<long[]> rayOrder;
	private final int bitCount;
	private final ServerLevel level;
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final int maxReachSection;
	private final int maxReachSectionY;
	private final int maxTaskSize;
	private final RandomSource random;
	
	public MortonExplosionTask(MortonExplosionThread explosionThread, Long2LongMap rays, List<long[]> rayOrder, int bitCount, ServerLevel level, BlockPos pos, int radius, float randomVectorLength, int maxReachSection, int maxReachSectionY, int maxSizeTask) {
		this.explosionThread = explosionThread;
		this.rays = rays;
		this.rayOrder = rayOrder;
		this.bitCount = bitCount;
		this.level = level;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.maxReachSection = maxReachSection;
		this.maxReachSectionY = maxReachSectionY;
		this.maxTaskSize = maxSizeTask;
		this.random = RandomSource.create(0);
		explosionThread.taskCount++;
	}
	
	public Int2ObjectMap<BitSet> compute() {
		if (rayOrder.size() > maxTaskSize) {
			MortonExplosionTask task1 = new MortonExplosionTask(explosionThread, rays, rayOrder.subList(0, rayOrder.size() / 2), bitCount, level, pos, radius, randomVectorLength, maxReachSection, maxReachSectionY, maxTaskSize);
			MortonExplosionTask task2 = new MortonExplosionTask(explosionThread, rays, rayOrder.subList(rayOrder.size() / 2, rayOrder.size()), bitCount, level, pos, radius, randomVectorLength, maxReachSection, maxReachSectionY, maxTaskSize);
			task2.fork();
			Int2ObjectMap<BitSet> toMergeBitSets = task1.compute();
			Int2ObjectMap<BitSet> resultBitSets = task2.join();
			for (int sectionIndex : toMergeBitSets.keySet()) {
				if (resultBitSets.containsKey(sectionIndex)) {
					resultBitSets.get(sectionIndex).or(toMergeBitSets.get(sectionIndex));
					if (resultBitSets.get(sectionIndex).nextClearBit(0) >= 4096) {
						resultBitSets.remove(sectionIndex);
						explosionThread.fullSections.set(sectionIndex);
					}
				} else {
					resultBitSets.put(sectionIndex, toMergeBitSets.get(sectionIndex));
				}
			}
			return resultBitSets;
		}
		return calculate();
	}
	
	@SuppressWarnings("deprecation")
	private Int2ObjectMap<BitSet> calculate() {
		int heapAllocationCache = 0;
		
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		
		int centerX = pos.getX() >> 4;
		int centerY = pos.getY() >> 4;
		int centerZ = pos.getZ() >> 4;

		Int2ObjectMap<BitSet> editedSections = new Int2ObjectOpenHashMap<BitSet>();
		int mask = 0;
		for (int i = 0; i < bitCount; ++i) {
			mask |= 1 << i;
		}
		for (long[] rayOrderSet : rayOrder) {
			for (long rayIndex : rayOrderSet) {
				long rawRay = rays.get(rayIndex);
				int x = (int)(rawRay >> (bitCount << 1)) - radius;
				int y = (int)((rawRay >> bitCount) & mask) - radius;
				int z = (int)(rawRay & mask) - radius;
				float length = Mth.sqrt(x * x + y * y + z * z);
				float dirX = x / length;
				float dirY = y / length;
				float dirZ = z / length;
				
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
				boolean currentSectionFull = false;
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
						travel = Math.max(0.3f, travel);
					}
					firstIteration = false;
					
					if (posY < minBuildHeight || posY >= maxBuildHeight || Math.abs(posX) >= 30000000 || Math.abs(posZ) >= 30000000) {
						break;
					}
	
					int sectionIndex = ((posX >> 4) - centerX + maxReachSection / 2) * maxReachSection * maxReachSectionY + ((posY >> 4) - centerY + maxReachSectionY / 2) * maxReachSection + (posZ >> 4) - centerZ + maxReachSection / 2;
					int blockIndex = ((posX & 15) << 8) | ((posY & 15) << 4) | (posZ & 15);
					
					if (explosionThread.emptySections.get(sectionIndex)) {
						continue;
					}
					if (sectionIndex == lastSectionIndex && currentSectionFull) {
						remainingLength -= currentExplosionResistances[blockIndex] * travel;
						continue;
					}
					currentSectionFull = false;
					
					if (sectionIndex != lastSectionIndex) {
						currentExplosionResistances = explosionThread.sectionExplosionResistances.get(sectionIndex);
						if (explosionThread.fullSections.get(sectionIndex)) {
							currentSectionFull = true;
							remainingLength -= currentExplosionResistances[blockIndex] * travel;
							continue;
						}
						if (currentExplosionResistances == null) {
							if (explosionThread.sectionExplosionResistances.enterWrite(sectionIndex)) {
								int chunkX = posX >> 4;
								int chunkY = posY >> 4;
								int chunkZ = posZ >> 4;
								LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(level.getSectionIndexFromSectionY(chunkY));
								if (section.hasOnlyAir()) {
									explosionThread.emptySections.set(sectionIndex);
									explosionThread.sectionExplosionResistances.clear(sectionIndex);
									continue;
								}
								float[] explosionResistances = new float[4096];
								for (int i = 0; i < 4096; ++i) {
									BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
									explosionResistances[i] = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f;
								}
								currentExplosionResistances = explosionThread.sectionExplosionResistances.set(sectionIndex, explosionResistances);
							} else {
								currentExplosionResistances = explosionThread.sectionExplosionResistances.get(sectionIndex);
								if (currentExplosionResistances == null) {
									continue;
								}
							}
						}
						currentEditedBlocks = editedSections.get(sectionIndex);
						if (currentEditedBlocks == null) {
							editedSections.put(sectionIndex, currentEditedBlocks = new BitSet(4096));
							heapAllocationCache += 560;
							long currentRequired = explosionThread.requiredHeapCache.addAndGet(560);
							explosionThread.maxRequiredHeapCache.accumulateAndGet(currentRequired, Math::max);
						}
					}
					
					remainingLength -= currentExplosionResistances[blockIndex] * travel;
					if (remainingLength <= 0f) {
						break;
					}
					currentEditedBlocks.set(blockIndex);
					if (currentEditedBlocks.cardinality() == 4096) {
						explosionThread.fullSections.set(sectionIndex);
						editedSections.remove(sectionIndex);
						currentSectionFull = true;
						explosionThread.requiredHeapCache.addAndGet(-560);
					}
					
					lastSectionIndex = sectionIndex;
				}
			}
			explosionThread.heapAllocationCache.addAndGet(heapAllocationCache);
		}
		return editedSections;
	}
}
