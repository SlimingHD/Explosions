package mod.explosion.helper;

import java.util.LinkedList;
import java.util.Queue;

import mod.explosion.ExplosionThread;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public final class MultithreadedExplosionHandler {

	private static final Queue<ExplosionThread> queuedExplosionThreads = new LinkedList<ExplosionThread>();
	private static final Queue<ExplosionThread> explosionThreads = new LinkedList<ExplosionThread>();
	
	@SubscribeEvent
	public static void tick(ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			if (!explosionThreads.isEmpty()) {
				ExplosionThread thread = explosionThreads.peek();
				if (!thread.isAlive()) {
					explosionThreads.poll();
					thread.getExplosion().finishExplosion(thread.getEditedSections(), thread.getFullSections());
					thread.totalTime.stopBenchmark();
					
					if (!queuedExplosionThreads.isEmpty()) {
						ExplosionThread newThread = queuedExplosionThreads.poll();
						newThread.start();
						explosionThreads.add(newThread);
					}
				}
			}
		}
	}
	
	public static void enqueue(ExplosionThread explosionThread) {
		if (explosionThreads.size() >= 4) {
			queuedExplosionThreads.add(explosionThread);
		} else {
			explosionThread.start();
			explosionThreads.add(explosionThread);
		}
	}
}
