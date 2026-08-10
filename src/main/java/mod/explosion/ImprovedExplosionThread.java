package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

public final class ImprovedExplosionThread extends Thread {

	private static final int maxRaysPerTask = 25000;
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
	public final Counter heapAllocationMB;
	public final Counter requiredHeapAllocationMB;
	public final Profile cpu;
	public final Counter cpuTaskCount;
	
	final Set<Long> emptySections = ConcurrentHashMap.newKeySet();
	final Set<Long> fullSections = ConcurrentHashMap.newKeySet();
	final ConcurrentHashMap<Long, float[]> sectionExplosionResistances = new ConcurrentHashMap<Long, float[]>();

	public final AtomicLong heapAllocationCache = new AtomicLong();
	public final AtomicLong requiredHeapCache = new AtomicLong();
	public final AtomicLong maxRequiredHeapCache = new AtomicLong();
	
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
			ImprovedExplosionDDATask task = new ImprovedExplosionDDATask(this, rays, explosion.level, pos, radius, randomVectorLength, Math.max(maxRaysPerTask, rays.size() / (Runtime.getRuntime().availableProcessors() * 4)));
			editedSections = pool.invoke(task);
		} else {
			ImprovedExplosionTask task = new ImprovedExplosionTask(this, rays, explosion.level, pos, radius, randomVectorLength, reducedRays, Math.max(maxRaysPerTask, rays.size() / (Runtime.getRuntime().availableProcessors() * 4)));
			editedSections = pool.invoke(task);
		}
		rayTracingTime.stopBenchmark();
		long maxRequiredHeapCache = this.maxRequiredHeapCache.get();
		blockCount.increment(4096 * fullSections.size());
		for (BitSet editedBlocks : editedSections.values()) {
			blockCount.increment(editedBlocks);
		}
		heapAllocationMB.increment(40 * (fullSections.size() + emptySections.size()) + 16432 * sectionExplosionResistances.size());
		maxRequiredHeapCache += 40 * (fullSections.size() + emptySections.size()) + 16432 * sectionExplosionResistances.size();
		heapAllocationMB.increment(heapAllocationCache.get());
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
