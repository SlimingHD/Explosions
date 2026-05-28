package mod.explosion.helper;

import java.util.LinkedList;
import java.util.Queue;

import mod.explosion.ImprovedExplosionThread;
import mod.explosion.MapUnwrappedExplosionThread;
import mod.explosion.SemiUnwrappedExplosionThread;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public final class MultithreadedExplosionHandler {

	private static final Queue<ImprovedExplosionThread> queuedExplosionThreads = new LinkedList<ImprovedExplosionThread>();
	private static final Queue<ImprovedExplosionThread> explosionThreads = new LinkedList<ImprovedExplosionThread>();
	private static final Queue<MapUnwrappedExplosionThread> queuedMapUnwrappedExplosionThreads = new LinkedList<MapUnwrappedExplosionThread>();
	private static final Queue<MapUnwrappedExplosionThread> mapUnwrappedExplosionThreads = new LinkedList<MapUnwrappedExplosionThread>();
	private static final Queue<SemiUnwrappedExplosionThread> queuedSemiUnwrappedExplosionThreads = new LinkedList<SemiUnwrappedExplosionThread>();
	private static final Queue<SemiUnwrappedExplosionThread> semiUnwrappedExplosionThreads = new LinkedList<SemiUnwrappedExplosionThread>();
	
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
					
					if (!queuedExplosionThreads.isEmpty()) {
						ImprovedExplosionThread newThread = queuedExplosionThreads.poll();
						newThread.start();
						explosionThreads.add(newThread);
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
					
					if (!queuedMapUnwrappedExplosionThreads.isEmpty()) {
						MapUnwrappedExplosionThread newThread = queuedMapUnwrappedExplosionThreads.poll();
						newThread.start();
						mapUnwrappedExplosionThreads.add(newThread);
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
					
					if (!queuedSemiUnwrappedExplosionThreads.isEmpty()) {
						SemiUnwrappedExplosionThread newThread = queuedSemiUnwrappedExplosionThreads.poll();
						newThread.start();
						semiUnwrappedExplosionThreads.add(newThread);
					}
				}
			}
		}
	}
	
	public static void enqueue(ImprovedExplosionThread explosionThread) {
		if (explosionThreads.size() >= 4) {
			queuedExplosionThreads.add(explosionThread);
		} else {
			explosionThread.start();
			explosionThreads.add(explosionThread);
		}
	}
	
	public static void enqueueMapUnwrapped(MapUnwrappedExplosionThread explosionThread) {
		if (mapUnwrappedExplosionThreads.size() >= 4) {
			queuedMapUnwrappedExplosionThreads.add(explosionThread);
		} else {
			explosionThread.start();
			mapUnwrappedExplosionThreads.add(explosionThread);
		}
	}
	
	public static void enqueueSemiUnwrapped(SemiUnwrappedExplosionThread explosionThread) {
		if (semiUnwrappedExplosionThreads.size() >= 4) {
			queuedSemiUnwrappedExplosionThreads.add(explosionThread);
		} else {
			explosionThread.start();
			semiUnwrappedExplosionThreads.add(explosionThread);
		}
	}
}
