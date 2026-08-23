package mod.explosion;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongList;
import mod.explosion.helper.ConcurrentArray;
import mod.explosion.helper.ConcurrentBitSet;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;

public class MortonExplosionThread extends Thread {

	private static final int maxRaysPerTask = 25000;
	private static final ExecutorService pool = Executors.newCachedThreadPool();
	
	private final ExplosionBase explosion;
	private final Long2LongMap rays;
	private final List<long[]> rayOrder = new ArrayList<long[]>();
	private final int bitCount;
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final int maxReachSection;
	private final int maxReachSectionY;
	
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
	
	AtomicInteger taskCount = new AtomicInteger();
	
	MortonExplosionThread(ExplosionBase explosion, Long2LongMap rays, LongList rayOrder, int bitCount, BlockPos pos, int radius, float randomVectorLength) {
		this.explosion = explosion;
		this.rays = rays;
		this.bitCount = bitCount;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		for (int i = 0; i < rayOrder.size(); i += maxRaysPerTask) {
			int size = Math.min(rayOrder.size() - 1 - i, maxRaysPerTask);
			this.rayOrder.add(rayOrder.subList(i, i + size).toLongArray());
		}
		
		totalTime = explosion.profiler.getProfile(0);
		blockCount = totalTime.addCounter(0);
		rayTracingTime = explosion.profiler.getProfile(1);
		Profile heapAllocations = explosion.profiler.createProfile("explosions.benchmark.heap_allocations");
		heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		heapAllocationMB.increment(rays.size() * 16 + rayOrder.size() * 8);
		requiredHeapAllocationMB.increment(rays.size() * 16 + rayOrder.size() * 8);
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
		
		heapAllocationMB.increment(arraySize * 8 + arraySize / 4);
		requiredHeapAllocationMB.increment(arraySize * 8 + arraySize / 4);
	}
	
	@Override
	public void run() {
		List<Future<?>> tasks = new ArrayList<Future<?>>();
		for (long[] order : rayOrder) {
			tasks.add(pool.submit(new MortonExplosionTask(this, rays, order, bitCount, explosion.level, pos, radius, randomVectorLength, maxReachSection, maxReachSectionY)));
		}
		for (Future<?> task : tasks) {
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
		for (int i = 0; i < sectionExplosionResistances.size(); i++) {
			if (sectionExplosionResistances.get(i) != null) {
				//50 Bytes estimated CompletableFuture size
				heapAllocationMB.increment(16482);
				maxRequiredHeapCache += 16482;
			}
		}
		cpuTaskCount.increment(taskCount.get());
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
