package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

public final class ImprovedExplosionThread extends Thread {

	private static final int minVectorsPerThread = 25000;
	protected static final ForkJoinPool pool = new ForkJoinPool();
	
	private final ExplosionBase explosion;
	private final List<Vec3i> rays;
	private final BlockPos pos;
	private final int radius;
	private final float randomVectorLength;
	private final boolean reducedRays;
	private final boolean dda;
	
	public final Profile totalTime;
	public final Counter blockCount;
	public final Profile rayTracingTime;
	public final Counter sectionAccessCount;
	public final Counter heapAllocationMB;
	public final Counter requiredHeapAllocationMB;
	public final Profile cpu;
	public final Counter cpuTaskCount;
	
	final Set<Long> emptySections = ConcurrentHashMap.newKeySet();
	final Set<Long> fullSections = ConcurrentHashMap.newKeySet();
	final ConcurrentHashMap<Long, float[]> sectionExplosionResistances = new ConcurrentHashMap<Long, float[]>();

	public final AtomicInteger sectionAccessesCache = new AtomicInteger();
	public final AtomicInteger heapAllocationCache = new AtomicInteger();
	public final AtomicInteger maxRequiredHeapCache = new AtomicInteger();
	
	private Map<Long, BitSet> editedSections = new Long2ObjectOpenHashMap<BitSet>();
	
	int taskCount = 0;
	
	ImprovedExplosionThread(ExplosionBase explosion, List<Vec3i> rays, BlockPos pos, int radius, float randomVectorLength, boolean reducedRays, boolean dda) {
		this.explosion = explosion;
		this.rays = rays;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		this.reducedRays = reducedRays;
		this.dda = dda;
		
		totalTime = explosion.profiler.getProfile(0);
		blockCount = totalTime.addCounter(0);
		rayTracingTime = explosion.profiler.getProfile(1);
		Profile sectionAccesses = explosion.profiler.createProfile("explosions.benchmark.section_accesses");
		sectionAccessCount = sectionAccesses.addCounter(0);
		Profile heapAllocations = explosion.profiler.createProfile("explosions.benchmark.heap_allocations");
		heapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		requiredHeapAllocationMB = heapAllocations.addCounter(0, v -> v / 1048576);
		heapAllocationMB.increment(rays.size() * 36);
		requiredHeapAllocationMB.increment(rays.size() * 36);
		cpu = explosion.profiler.createProfile("explosions.benchmark.cpu");
		cpuTaskCount = cpu.addCounter(0);
	}
	
	@Override
	public void run() {
		if (dda) {
			ImprovedExplosionDDATask task = new ImprovedExplosionDDATask(this, rays, explosion.level, pos, radius, randomVectorLength, Math.max(minVectorsPerThread, rays.size() / (Runtime.getRuntime().availableProcessors() * 4)));
			editedSections = pool.invoke(task);
		} else {
			ImprovedExplosionTask task = new ImprovedExplosionTask(this, rays, explosion.level, pos, radius, randomVectorLength, reducedRays, Math.max(minVectorsPerThread, rays.size() / (Runtime.getRuntime().availableProcessors() * 4)));
			editedSections = pool.invoke(task);
		}
		rayTracingTime.stopBenchmark();
		sectionAccessCount.increment(sectionAccessesCache.get());
		int maxRequiredHeapCache = this.maxRequiredHeapCache.get();
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
		heapAllocationMB.increment(heapAllocationCache.get());
		for (BitSet editedBlocks : editedSections.values()) {
			blockCount.increment(editedBlocks);
		}
		cpuTaskCount.increment(taskCount);
		requiredHeapAllocationMB.increment(maxRequiredHeapCache);
	}

	public Map<Long, BitSet> getEditedSections() {
		return editedSections;
	}

	public Set<Long> getFullSections() {
		return fullSections;
	}
	
	public ExplosionBase getExplosion() {
		return explosion;
	}
}
