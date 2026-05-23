package mod.explosion;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Vector3f;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import mod.explosion.helper.MultithreadedExplosionHandler;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

public class ImprovedExplosion extends ExplosionBase {

	private final Vec3 pos;
	private final int radius;
	private final float randomVectorLength;
	private final boolean multithreaded;
	
	public ImprovedExplosion(ServerLevel level, Vec3 pos, int radius, float randomVectorLength, boolean multithreaded) {
		super(level);
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.multithreaded = multithreaded;
	}

	@Override
	public void explode() {
		if (multithreaded) {
			explodeMultithreaded();
		} else {
			explodeSinglethreaded();
		}
	}
	
	private void explodeMultithreaded() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		
		totalTime.startBenchmark();
		List<Vector3f> rays = new ArrayList<Vector3f>(Mth.ceil(4 * Mth.PI * radius * radius));
		for (int x = -radius; x <= radius; ++x) {
			for (int y = -radius; y <= radius; ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if (distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1)) {
						rays.add(new Vector3f(x, y, z).mul(0.7f + random.nextFloat() * randomVectorLength));
					}
				}
			}
		}
		rayTracingTime.addCounter("%1$i", rays.size());
		
		ExplosionThread explosionThread = new ExplosionThread(this, rays, pos, radius, randomVectorLength);
		MultithreadedExplosionHandler.enqueue(explosionThread);
	}
	
	@SuppressWarnings("deprecation")
	private void explodeSinglethreaded() {
		Profile totalTime = profiler.createProfile("explosions.benchmark.total_time");
		Counter blockCount = totalTime.addCounter("%1$i", 0);
		Profile rayTracingTime = profiler.createProfile("explosions.benchmark.ray_tracing_time");
		Counter rayCount = rayTracingTime.addCounter("%1$i", 0);
		Profile sectionAccesses = profiler.createProfile("explosions.benchmark.section_accesses");
		Counter sectionAccessCount = sectionAccesses.addCounter("%1$i", 0);
		Profile heapAllocations = profiler.createProfile("explosions.benchmark.heap_allocations");
		Counter heapAllocationMB = heapAllocations.addCounter("%1$i", 0, v -> v / 1048576);
		Counter requiredHeapAllocationMB = heapAllocations.addCounter("%2$i", 0, v -> v / 1048576);
		
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
		for (int x = -radius; x <= radius; ++x) {
			for (int y = -radius; y <= radius; ++y) {
				for (int z = -radius; z <= radius; ++z) {
					float distanceSqr = x * x + y * y + z * z;
					if (distanceSqr >= radius * radius && distanceSqr <= (radius + 1) * (radius + 1)) {
						rayCount.increment(1);
						float stepX = x / (radius + 0.5f) * 0.3f;
						float stepY = y / (radius + 0.5f) * 0.3f;
						float stepZ = z / (radius + 0.5f) * 0.3f;

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
							
							if ((sectionPos == lastSectionPos && currentSectionEmpty) || emptySections.contains(sectionPos)) {
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
								if (currentSectionEmpty || emptySections.contains(sectionPos)) {
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
									LevelChunkSection section = level.getChunk(chunkX, chunkZ).getSection(chunkY - level.getMinSection());
									sectionAccessCount.increment(1);
									if (section.hasOnlyAir()) {
										emptySections.add(sectionPos);
										currentSectionEmpty = true;
										continue;
									}
									float[] explosionResistances = new float[4096];
									for (int i = 0; i < 4096; ++i) {
										BlockState state = section.getBlockState(i & 3840, i & 240, i & 15);
										explosionResistances[i] = Math.max(state.getBlock().getExplosionResistance(), state.getFluidState().getExplosionResistance());
									}
									sectionExplosionResistances.put(sectionPos, explosionResistances);
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
							if (remainingLength <= 0f) {
								break;
							}
							currentEditedBlocks.set(blockIndex);
							if (currentEditedBlocks.nextClearBit(blockIndex) >= 4096) {
								fullSections.add(sectionPos);
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
	}
}
