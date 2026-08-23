package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.RecursiveTask;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class SemiUnwrappedExplosionTask extends RecursiveTask<Int2ObjectMap<BitSet>> {
	
	private final SemiUnwrappedExplosionThread explosionThread;
	private final List<int[]> rays;
	private final ServerLevel level;
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final int maxReachSection;
	private final int maxReachSectionY;
	private final int maxTaskSize;
	private final RandomSource random;
	
	public SemiUnwrappedExplosionTask(SemiUnwrappedExplosionThread explosionThread, List<int[]> rays, ServerLevel level, BlockPos pos, int radius, float randomVectorLength, int maxReachSection, int maxReachSectionY, int maxTaskSize) {
		this.explosionThread = explosionThread;
		this.rays = rays;
		this.level = level;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.maxReachSection = maxReachSection;
		this.maxReachSectionY = maxReachSectionY;
		this.maxTaskSize = maxTaskSize;
		this.random = RandomSource.create(0);
		explosionThread.taskCount.getAndIncrement();
	}
	
	public Int2ObjectMap<BitSet> compute() {
		if (rays.size() > maxTaskSize) {
			SemiUnwrappedExplosionTask task1 = new SemiUnwrappedExplosionTask(explosionThread, rays.subList(0, rays.size() / 2), level, pos, radius, randomVectorLength, maxReachSection, maxReachSectionY, maxTaskSize);
			SemiUnwrappedExplosionTask task2 = new SemiUnwrappedExplosionTask(explosionThread, rays.subList(rays.size() / 2, rays.size()), level, pos, radius, randomVectorLength, maxReachSection, maxReachSectionY, maxTaskSize);
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
		for (int[] raySet : rays) {
			for (int ray = 0; ray < raySet.length; ray += 3) {
				int rayX = raySet[ray];
				int rayY = raySet[ray + 1];
				int rayZ = raySet[ray + 2];
				float length = Mth.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
				float dirX = rayX / length;
				float dirY = rayY / length;
				float dirZ = rayZ / length;
				
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
						travel = Math.max(0.1f, travel);
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
							int chunkX = posX >> 4;
							int chunkY = posY >> 4;
							int chunkZ = posZ >> 4;
							currentExplosionResistances = explosionThread.sectionExplosionResistances.trySet(sectionIndex, () -> {
								LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(level.getSectionIndexFromSectionY(chunkY));
								if (section.hasOnlyAir()) {
									return null;
								}
								float[] explosionResistances = new float[4096];
								for (int i = 0; i < 4096; ++i) {
									BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
									explosionResistances[i] = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f;
								}
								return explosionResistances;
							});
							if (currentExplosionResistances == null) {
								explosionThread.emptySections.set(sectionIndex);
								explosionThread.sectionExplosionResistances.clear(sectionIndex);
								continue;
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
