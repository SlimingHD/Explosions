package mod.explosion.helper;

import java.util.LinkedList;
import java.util.Queue;

import mod.explosion.ImprovedExplosionThread;
import mod.explosion.MapUnwrappedExplosionThread;
import mod.explosion.MemoryReducedExplosionThread;
import mod.explosion.MortonExplosionThread;
import mod.explosion.PrecomputedExplosionThread;
import mod.explosion.SemiUnwrappedExplosionThread;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public final class MultithreadedExplosionHandler {

	private static final Queue<ImprovedExplosionThread> explosionThreads = new LinkedList<ImprovedExplosionThread>();
	private static final Queue<MapUnwrappedExplosionThread> mapUnwrappedExplosionThreads = new LinkedList<MapUnwrappedExplosionThread>();
	private static final Queue<SemiUnwrappedExplosionThread> semiUnwrappedExplosionThreads = new LinkedList<SemiUnwrappedExplosionThread>();
	private static final Queue<MortonExplosionThread> mortonExplosionThreads = new LinkedList<MortonExplosionThread>();
	private static final Queue<PrecomputedExplosionThread> precomputedExplosionThreads = new LinkedList<PrecomputedExplosionThread>();
	private static final Queue<MemoryReducedExplosionThread> memoryReducedExplosionThreads = new LinkedList<MemoryReducedExplosionThread>();
	
	@SubscribeEvent
	public static void tick(ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			if (!explosionThreads.isEmpty()) {
				ImprovedExplosionThread thread = explosionThreads.peek();
				if (!thread.isAlive()) {
					explosionThreads.poll();
					thread.getExplosion().finishExplosion(thread.getEditedSections(), thread.getFullSections());
					thread.totalTime.stopBenchmark();
					if (thread.getExplosion().finishWork != null) {
						thread.getExplosion().finishWork.run();
					}
				}
			}
			if (!mapUnwrappedExplosionThreads.isEmpty()) {
				MapUnwrappedExplosionThread thread = mapUnwrappedExplosionThreads.peek();
				if (!thread.isAlive()) {
					mapUnwrappedExplosionThreads.poll();
					thread.getExplosion().finishExplosion(thread.getEditedSections(), thread.getFullSections(), thread.getCenterSection(), thread.getMaxReachSection(), thread.getMaxReachSectionY());
					thread.totalTime.stopBenchmark();
					if (thread.getExplosion().finishWork != null) {
						thread.getExplosion().finishWork.run();
					}
				}
			}
			if (!semiUnwrappedExplosionThreads.isEmpty()) {
				SemiUnwrappedExplosionThread thread = semiUnwrappedExplosionThreads.peek();
				if (!thread.isAlive()) {
					semiUnwrappedExplosionThreads.poll();
					thread.getExplosion().finishExplosion(thread.getEditedSections(), thread.getFullSections(), thread.getCenterSection(), thread.getMaxReachSection(), thread.getMaxReachSectionY());
					thread.totalTime.stopBenchmark();
					if (thread.getExplosion().finishWork != null) {
						thread.getExplosion().finishWork.run();
					}
				}
			}
			if (!mortonExplosionThreads.isEmpty()) {
				MortonExplosionThread thread = mortonExplosionThreads.peek();
				if (!thread.isAlive()) {
					mortonExplosionThreads.poll();
					thread.getExplosion().finishExplosion(thread.getEditedSections(), thread.getFullSections(), thread.getCenterSection(), thread.getMaxReachSection(), thread.getMaxReachSectionY());
					thread.totalTime.stopBenchmark();
					if (thread.getExplosion().finishWork != null) {
						thread.getExplosion().finishWork.run();
					}
				}
			}
			if (!precomputedExplosionThreads.isEmpty()) {
				PrecomputedExplosionThread thread = precomputedExplosionThreads.peek();
				if (!thread.isAlive()) {
					precomputedExplosionThreads.poll();
					thread.getExplosion().finishExplosion(thread.getEditedSections(), thread.getFullSections(), thread.getCenterSection(), thread.getMaxReachSection(), thread.getMaxReachSectionY());
					thread.totalTime.stopBenchmark();
					if (thread.getExplosion().finishWork != null) {
						thread.getExplosion().finishWork.run();
					}
				}
			}
			if (!memoryReducedExplosionThreads.isEmpty()) {
				MemoryReducedExplosionThread thread = memoryReducedExplosionThreads.peek();
				if (!thread.isAlive()) {
					memoryReducedExplosionThreads.poll();
					thread.getExplosion().finishExplosion(thread.getEditedSections(), thread.getFullSections(), thread.getCenterSection(), thread.getMaxReachSection(), thread.getMaxReachSectionY());
					thread.totalTime.stopBenchmark();
					if (thread.getExplosion().finishWork != null) {
						thread.getExplosion().finishWork.run();
					}
				}
			}
		}
	}
	
	public static void enqueue(ImprovedExplosionThread explosionThread) {
		explosionThread.start();
		explosionThreads.add(explosionThread);
	}
	
	public static void enqueueMapUnwrapped(MapUnwrappedExplosionThread explosionThread) {
		explosionThread.start();
		mapUnwrappedExplosionThreads.add(explosionThread);
	}
	
	public static void enqueueSemiUnwrapped(SemiUnwrappedExplosionThread explosionThread) {
		explosionThread.start();
		semiUnwrappedExplosionThreads.add(explosionThread);
	}
	
	public static void enqueueMorton(MortonExplosionThread explosionThread) {
		explosionThread.start();
		mortonExplosionThreads.add(explosionThread);
	}
	
	public static void enqueuePrecomputed(PrecomputedExplosionThread explosionThread) {
		explosionThread.start();
		precomputedExplosionThreads.add(explosionThread);
	}
	
	public static void enqueueMemoryReduced(MemoryReducedExplosionThread explosionThread) {
		explosionThread.start();
		memoryReducedExplosionThreads.add(explosionThread);
	}
}
