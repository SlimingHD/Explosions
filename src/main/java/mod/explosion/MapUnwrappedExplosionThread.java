package mod.explosion;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

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
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final int maxReachSection;
	private final int maxReachSectionY;
	
	private final List<Future<Void>> tasks = new ArrayList<Future<Void>>();
	
	public final Profile totalTime;
	public final Counter blockCount;
	public final Profile rayTracingTime;
	public final Counter heapAllocationMB;
	public final Counter requiredHeapAllocationMB;
	public final Profile cpu;
	public final Counter cpuTaskCount;

	public final AtomicLong heapAllocationCache = new AtomicLong();
	public final AtomicLong requiredHeapCache = new AtomicLong();
	public final AtomicLong maxRequiredHeapCache = new AtomicLong();
	
	public final ConcurrentArray<float[]> sectionExplosionResistances;
	public final ConcurrentBitSet emptySections;
	public final ConcurrentBitSet fullSections;
	public final ConcurrentArray<ConcurrentBitSet> editedSections;
	
	int taskCount = 0;
	
	MapUnwrappedExplosionThread(ExplosionBase explosion, BlockPos pos, int radius, float randomVectorLength) {
		this.explosion = explosion;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		
		totalTime = explosion.profiler.getProfile(0);
		blockCount = totalTime.addCounter(0);
		rayTracingTime = explosion.profiler.getProfile(1);
		Profile heapAllocations = explosion.profiler.createProfile("explosions.benchmark.heap_allocations");
		heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
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
		
		heapAllocationMB.increment(arraySize * 24 + arraySize / 4);
		requiredHeapAllocationMB.increment(arraySize * 24 + arraySize / 4);
	}
	
	public void submitRays(int[] rays) {
		tasks.add(pool.submit(new MapUnwrappedExplosionTask(this, rays, explosion.level, pos, radius, randomVectorLength, maxReachSection, maxReachSectionY)));
		heapAllocationMB.increment(36 + rays.length * 4);
		requiredHeapAllocationMB.increment(36 + rays.length * 4);
	}
	
	@Override
	public void run() {
		for (Future<Void> task : tasks) {
			try {
				task.get();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}
		}
		rayTracingTime.stopBenchmark();
		long maxRequiredHeapCache = this.maxRequiredHeapCache.get();
		for (int i = 0; i < fullSections.size(); i++) {
			if (fullSections.get(i)) {
				blockCount.increment(4096);
			}
		}
		for (int i = 0; i < editedSections.size(); i++) {
			if (editedSections.get(i) != null) {
				blockCount.increment(editedSections.get(i).toLegacyBitSet());
			}
		}
		heapAllocationMB.increment(fullSections.size() + emptySections.size());
		maxRequiredHeapCache += fullSections.size() + emptySections.size();
		for (int i = 0; i < sectionExplosionResistances.size(); i++) {
			if (sectionExplosionResistances.get(i) != null) {
				heapAllocationMB.increment(16432);
				maxRequiredHeapCache += 16432;
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
