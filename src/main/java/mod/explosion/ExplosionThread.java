package mod.explosion;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3f;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import mod.explosion.helper.ExplosionProfiler.Counter;
import mod.explosion.helper.ExplosionProfiler.Profile;
import net.minecraft.world.phys.Vec3;

public final class ExplosionThread extends Thread {

	private static final int minVectorsPerThread = 25000;
	private static final ForkJoinPool pool = new ForkJoinPool();
	
	private final ImprovedExplosion explosion;
	private final List<Vector3f> vectors;
	private final Vec3 pos;
	private final int radius;
	private final float randomVectorLength;
	
	public final Profile totalTime;
	public final Counter blockCount;
	public final Profile rayTracingTime;
	public final Counter sectionAccessCount;
	public final Counter heapAllocationMB;
	public final Counter requiredHeapAllocationMB;
	
	final Set<Long> emptySections = ConcurrentHashMap.newKeySet();
	final Set<Long> fullSections = ConcurrentHashMap.newKeySet();
	final ConcurrentHashMap<Long, float[]> sectionExplosionResistances = new ConcurrentHashMap<Long, float[]>();

	public final AtomicInteger sectionAccessesCache = new AtomicInteger();
	public final AtomicInteger heapAllocationCache = new AtomicInteger();
	public final AtomicInteger maxRequiredHeapCache = new AtomicInteger();
	
	private Map<Long, BitSet> editedSections = new Long2ObjectOpenHashMap<BitSet>();
	
	int taskCount = 0;
	
	ExplosionThread(ImprovedExplosion explosion, List<Vector3f> vectors, Vec3 pos, int radius, float randomVectorLength) {
		this.explosion = explosion;
		this.vectors = vectors;
		this.pos = pos;
		this.radius = radius;
		this.randomVectorLength = randomVectorLength;
		totalTime = explosion.profiler.getProfile(0);
		blockCount = totalTime.addCounter("%1$i", 0);
		rayTracingTime = explosion.profiler.getProfile(1);
		Profile sectionAccesses = explosion.profiler.createProfile("explosions.benchmark.section_accesses");
		sectionAccessCount = sectionAccesses.addCounter("%1$i", 0);
		Profile heapAllocations = explosion.profiler.createProfile("explosions.benchmark.heap_allocations");
		heapAllocationMB = heapAllocations.addCounter("%1$i", 0, v -> v / 1048576);
		requiredHeapAllocationMB = heapAllocations.addCounter("%1$i", 0, v -> v / 1048576);
	}
	
	@Override
	public void run() {
		ExplosionTask task = new ExplosionTask(this, vectors, explosion.level, pos, radius, randomVectorLength, Math.max(minVectorsPerThread, vectors.size() / (Runtime.getRuntime().availableProcessors() * 4)), explosion.level.random);
		editedSections = pool.invoke(task);
		rayTracingTime.stopBenchmark();
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
		for (BitSet editedBlocks : editedSections.values()) {
			blockCount.increment(editedBlocks);
		}
		requiredHeapAllocationMB.increment(maxRequiredHeapCache);
	}
	
	public Map<Long, BitSet> getEditedSections() {
		return editedSections;
	}
	
	public Set<Long> getFullSections() {
		return fullSections;
	}
	
	public ImprovedExplosion getExplosion() {
		return explosion;
	}
}
