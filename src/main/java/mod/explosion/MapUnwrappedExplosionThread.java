package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import mod.explosion.helper.ConcurrentArray;
import mod.explosion.helper.ConcurrentBitSet;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;

public class MapUnwrappedExplosionThread extends Thread {

	private static final ForkJoinPool pool = ImprovedExplosionThread.pool;
	
	private final ExplosionBase explosion;
	private final List<int[]> rays;
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final int maxReachSection;
	private final int maxReachSectionY;
	
	public final Profile totalTime;
	public final Counter blockCount;
	public final Profile rayTracingTime;
	public final Counter sectionAccessCount;
	public final Counter heapAllocationMB;
	public final Counter requiredHeapAllocationMB;
	public final Profile cpu;
	public final Counter cpuTaskCount;

	public final AtomicInteger sectionAccessesCache = new AtomicInteger();
	public final AtomicInteger heapAllocationCache = new AtomicInteger();
	public final AtomicInteger maxRequiredHeapCache = new AtomicInteger();
	
	public final ConcurrentArray<float[]> sectionExplosionResistances;
	public final ConcurrentBitSet emptySections;
	public final ConcurrentBitSet fullSections;
	public final ConcurrentArray<ConcurrentBitSet> editedSections;
	
	int taskCount = 0;
	
	MapUnwrappedExplosionThread(ExplosionBase explosion, List<int[]> rays, BlockPos pos, int radius, float randomVectorLength) {
		this.explosion = explosion;
		this.rays = rays;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		
		totalTime = explosion.profiler.getProfile(0);
		blockCount = totalTime.addCounter(0);
		rayTracingTime = explosion.profiler.getProfile(1);
		Profile sectionAccesses = explosion.profiler.createProfile("explosions.benchmark.section_accesses");
		sectionAccessCount = sectionAccesses.addCounter(0);
		Profile heapAllocations = explosion.profiler.createProfile("explosions.benchmark.heap_allocations");
		heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		for (int[] raySet : rays) {
			heapAllocationMB.increment(36 + raySet.length * 4);
			requiredHeapAllocationMB.increment(36 + raySet.length * 4);
		}
		cpu = explosion.profiler.createProfile("explosions.benchmark.cpu");
		cpuTaskCount = cpu.addCounter(0);
		
		int maxReach = Mth.ceil(radius * (1f + randomVectorLength));
		maxReachSection = Mth.ceil(maxReach / 16f) * 2 + 1;
		int minBuildHeight = explosion.level.getMinBuildHeight();
		int maxBuildHeight = explosion.level.getMaxBuildHeight();
		int maxReachUp = Math.min(maxReach, maxBuildHeight - pos.getY() - 1);
		int maxReachDown = Math.min(maxReach, pos.getY() - minBuildHeight);
		maxReachSectionY = Mth.ceil(Math.max(maxReachUp / 16f, maxReachDown / 16f)) * 2 + 1;
		int arraySize = maxReachSection * maxReachSectionY * maxReachSection;
		
		sectionExplosionResistances = new ConcurrentArray<float[]>(arraySize);
		emptySections = new ConcurrentBitSet(arraySize);
		fullSections = new ConcurrentBitSet(arraySize);
		editedSections = new ConcurrentArray<ConcurrentBitSet>(arraySize);
		
		heapAllocationMB.increment((arraySize) * 24 + (arraySize) / 32);
		requiredHeapAllocationMB.increment((arraySize) * 24 + (arraySize) / 32);
	}
	
	@Override
	public void run() {
		pool.invoke(new MapUnwrappedExplosionTask(this, rays, explosion.level, pos, radius, randomVectorLength, maxReachSection, maxReachSectionY));
		rayTracingTime.stopBenchmark();
		sectionAccessCount.increment(sectionAccessesCache.get());
		int maxRequiredHeapCache = this.maxRequiredHeapCache.get();
		for (int i = 0; i < fullSections.size(); i++) {
			if (fullSections.get(i)) {
				blockCount.increment(4096);
			}
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
			if (sectionExplosionResistances.get(i) != null) {
				heapAllocationMB.increment(16432);
				maxRequiredHeapCache += 16432;
			}
		}
		heapAllocationMB.increment(heapAllocationCache.get());
		for (int i = 0; i < editedSections.size(); i++) {
			ConcurrentBitSet editedBlocks = editedSections.get(i);
			if (editedBlocks != null) {
				blockCount.increment(editedBlocks.toLegacyBitSet());
			}
		}
		cpuTaskCount.increment(taskCount);
		requiredHeapAllocationMB.increment(maxRequiredHeapCache);
	}

	public BitSet[] getEditedSections() {
		BitSet[] convertedEditedSections = new BitSet[editedSections.size()];
		for (int i = 0; i < convertedEditedSections.length; i++) {
			ConcurrentBitSet editedBlocks = editedSections.get(i);
			if (editedBlocks != null) {
				convertedEditedSections[i] = editedSections.get(i).toLegacyBitSet();
			}
		}
		return convertedEditedSections;
	}

	public BitSet getFullSections() {
		return fullSections.toLegacyBitSet();
	}
	
	public int getMaxReachSection() {
		return maxReachSection;
	}
	
	public int getMaxReachSectionY() {
		return maxReachSectionY;
	}
	
	public SectionPos getCenterSection() {
		return SectionPos.of(pos);
	}
	
	public ExplosionBase getExplosion() {
		return explosion;
	}
}
