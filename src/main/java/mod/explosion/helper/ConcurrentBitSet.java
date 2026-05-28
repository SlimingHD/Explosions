package mod.explosion.helper;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;

public class ConcurrentBitSet {

	private static final VarHandle HANDLER = MethodHandles.arrayElementVarHandle(long[].class);
	private final long[] words;
	
	public ConcurrentBitSet(int size) {
		words = new long[(size + 63) >> 6];
	}
	
	public int size() {
		return words.length << 6;
	}
	
	public void set(int index) {
		int wordIndex = index >> 6;
		int offset = index & 63;
		long mask = 1l << offset;
		setWordInternal(wordIndex, mask);
	}
	
	public boolean get(int index) {
		int wordIndex = index >> 6;
		int offset = index & 63;
		long mask = 1l << offset;
		return (getWordInternal(wordIndex) & mask) != 0;
	}
	
	public int cardinality() {
		int sum = 0;
		for (int wordIndex = 0; wordIndex < words.length; ++wordIndex) {
			sum += Long.bitCount(getWordInternal(wordIndex));
		}
		return sum;
	}
	
	public BitSet toLegacyBitSet() {
		return BitSet.valueOf(words);
	}
	
	private void setWordInternal(int wordIndex, long mask) {
		long currentValue;
		long newValue;
		do {
			currentValue = getWordInternal(wordIndex);
			if ((currentValue & mask) != 0) {
				return;
			}
			newValue = currentValue | mask;
		} while (!HANDLER.compareAndSet(words, wordIndex, currentValue, newValue));
	}
	
	private long getWordInternal(int wordIndex) {
		return (long)HANDLER.getVolatile(words, wordIndex);
	}
}
