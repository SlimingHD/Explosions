package mod.explosion;

import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import mod.explosion.helper.ConcurrentBitSet;
import mod.explosion.helper.PalettedSectionResistances;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public class MemoryReducedExplosionTask implements Runnable {

	private final MapUnwrappedExplosionThread explosionThread;
	private final int[] rays;
	private final ServerLevel level;
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final int maxReachSection;
	private final int maxReachSectionY;
	private final RandomSource random;
	
	private final short[] indicesCache = new short[4096];
	
	public MemoryReducedExplosionTask(MapUnwrappedExplosionThread explosionThread, int[] rays, ServerLevel level, BlockPos pos, int radius, float randomVectorLength, int maxReachSection, int maxReachSectionY) {
		this.explosionThread = explosionThread;
		this.rays = rays;
		this.level = level;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.maxReachSection = maxReachSection;
		this.maxReachSectionY = maxReachSectionY;
		this.random = RandomSource.create(0);
		explosionThread.taskCount.getAndIncrement();
	}
	
	@SuppressWarnings("deprecation")
	@Override
	public void run() {
		AtomicLong heapAllocationCache = new AtomicLong();
		
		int minBuildHeight = level.getMinBuildHeight();
		int maxBuildHeight = level.getMaxBuildHeight();
		
		int centerX = pos.getX() >> 4;
		int centerY = pos.getY() >> 4;
		int centerZ = pos.getZ() >> 4;
		
		for (int ray = 0; ray < rays.length; ray += 3) {
			int rayX = rays[ray];
			int rayY = rays[ray + 1];
			int rayZ = rays[ray + 2];
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
			PalettedSectionResistances currentExplosionResistances = null;
			ConcurrentBitSet currentEditedBlocks = null;
			
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
					remainingLength -= currentExplosionResistances.get(blockIndex) * travel;
					continue;
				}
				currentSectionFull = false;
				
				if (sectionIndex != lastSectionIndex) {
					currentExplosionResistances = explosionThread.memoryReducedSectionExplosionResistances.get(sectionIndex);
					if (explosionThread.fullSections.get(sectionIndex)) {
						currentSectionFull = true;
						remainingLength -= currentExplosionResistances.get(blockIndex) * travel;
						continue;
					}

					if (currentExplosionResistances == null) {
						int chunkX = posX >> 4;
						int chunkY = posY >> 4;
						int chunkZ = posZ >> 4;
						currentExplosionResistances = explosionThread.memoryReducedSectionExplosionResistances.trySet(sectionIndex, () -> {
							LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(level.getSectionIndexFromSectionY(chunkY));
							if (section.hasOnlyAir()) {
								return null;
							}
							FloatList resistances = new FloatArrayList();
							for (int i = 0; i < 4096; ++i) {
								BlockState state = section.getBlockState(i >> 8, (i >> 4) & 15, i & 15);
								float resistance = state.isAir() ? 0f : (Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance()) + 0.3f) * 0.3f;
								int resistanceIndex = resistances.indexOf(resistance);
								if (resistanceIndex != -1) {
									indicesCache[i] = (short)resistanceIndex;
								} else {
									indicesCache[i] = (short)resistances.size();
									resistances.add(resistance);
								}
							}
							return new PalettedSectionResistances(resistances, indicesCache);
						});
						if (currentExplosionResistances == null) {
							explosionThread.memoryReducedSectionExplosionResistances.clear(sectionIndex);
							explosionThread.emptySections.set(sectionIndex);
							continue;
						}
					}
					currentEditedBlocks = explosionThread.editedSections.get(sectionIndex);
					if (currentEditedBlocks == null) {
						currentEditedBlocks = explosionThread.editedSections.trySet(sectionIndex, () -> {
							heapAllocationCache.getAndAdd(610);
							long currentRequired = explosionThread.requiredHeapCache.addAndGet(610);
							explosionThread.maxRequiredHeapCache.accumulateAndGet(currentRequired, Math::max);
							return new ConcurrentBitSet(4096);
						});
					}
				}
				
				remainingLength -= currentExplosionResistances.get(blockIndex) * travel;
				if (remainingLength <= 0f) {
					break;
				}
				if (!currentEditedBlocks.get(blockIndex)) {
					currentEditedBlocks.set(blockIndex);
					if (currentEditedBlocks.cardinality() == 4096) {
						explosionThread.fullSections.set(sectionIndex);
						explosionThread.editedSections.clear(sectionIndex);
						currentSectionFull = true;
						explosionThread.requiredHeapCache.addAndGet(-610);
					}
				}
				
				lastSectionIndex = sectionIndex;
			}
		}
		explosionThread.heapAllocationCache.addAndGet(heapAllocationCache.get());
	}
}
