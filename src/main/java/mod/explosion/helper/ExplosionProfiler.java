package mod.explosion.helper;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.Function;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class ExplosionProfiler {
	
	private final ServerLevel level;
	
	private final List<Profile> profiles = new ArrayList<Profile>();
	
	private String titleTranslationKey = "explosions.benchmarking.explosion_title";
	
	public ExplosionProfiler(ServerLevel level) {
		this.level = level;
	}
	
	public void setTitle(String titleTranslationKey) {
		this.titleTranslationKey = titleTranslationKey;
	}
	
	public Profile createProfile(String messageTranslationKey) {
		Profile profile = new Profile(messageTranslationKey);
		profiles.add(profile);
		return profile;
	}
	
	public Profile getProfile(int index) {
		return profiles.get(index);
	}
	
	public void printResults() {
		MutableComponent message = Component.translatable(titleTranslationKey).append(":").withStyle(ChatFormatting.AQUA);
		for (Profile profile : profiles) {
			double time = (profile.stopTime - profile.startTime) / 1000d;
			Object[] args = new Object[1 + profile.counters.size()];
			args[0] = Component.literal(String.format("%1$.2f", time)).withStyle(ChatFormatting.GOLD);
			for (int i = 1; i < profile.counters.size() + 1; i++) {
				Counter counter = profile.counters.get(i - 1);
				args[i] = Component.literal(String.format("%,d", counter.getPrintValue())).withStyle(ChatFormatting.GOLD);
			}
			message.append("\n  ").append(Component.translatable(profile.messageTranslationKey, args).withStyle(ChatFormatting.WHITE));
		}
		for (ServerPlayer player : level.players()) {
			player.sendSystemMessage(message, false);
		}
	}
	
	public static class Profile {
		
		private final List<Counter> counters = new ArrayList<Counter>();
		private final String messageTranslationKey;
		
		private long startTime;
		private long stopTime;
		
		public Profile(String messageTranslationKey) {
			this.messageTranslationKey = messageTranslationKey;
		}
		
		public void startBenchmark() {
			startTime = System.currentTimeMillis();
		}
		
		public void stopBenchmark() {
			stopTime = System.currentTimeMillis();
		}
		
		public Counter addCounter(long initialValue) {
			Counter counter = new Counter(initialValue, v -> v);
			counters.add(counter);
			return counter;
		}

		public Counter addCounter(long initialValue, Function<Long, Long> printValueFunction) {
			Counter counter = new Counter(initialValue, printValueFunction);
			counters.add(counter);
			return counter;
		}
	}
	
	public static class Counter {

		private final Function<Long, Long> printValueFunction;
		
		private long value;
		
		public Counter(long value, Function<Long, Long> printValueFunction) {
			this.printValueFunction = printValueFunction;
			this.value = value;
		}
		
		private long getPrintValue() {
			return printValueFunction.apply(value);
		}
		
		public void increment(long amount) {
			value += amount;
		}
		
		public void increment(BitSet amount) {
			value += amount.cardinality();
		}
		
		public void decrement(long amount) {
			value -= amount;
		}
		
		public void decrement(BitSet amount) {
			value -= amount.cardinality();
		}
	}
}