package mod.explosion.helper;

import it.unimi.dsi.fastutil.floats.FloatList;

public class PalettedSectionResistances {

	private final float[] resistancePalette;
	private final int storageBits;
	private final int subIndexCount;
	private final int subIndexShift;
	private final int retrievalMask;
	private final int[] indices;
	
	private final float[] rawData;
	
	private final boolean empty;
	
	public PalettedSectionResistances(FloatList palette, short[] rawIndices) {
		empty = palette.isEmpty() || rawIndices.length < 4096;
		if (empty) {
			resistancePalette = null;
			storageBits = 0;
			subIndexCount = 0;
			subIndexShift = 0;
			retrievalMask = 0;
			indices = null;
			rawData = null;
			return;
		}
		//Above 2048 different values (very unlikely), a normal array becomes more space efficient
		if (palette.size() > 2048) {
			resistancePalette = null;
			storageBits = 0;
			subIndexCount = 0;
			subIndexShift = 0;
			retrievalMask = 0;
			indices = null;
			rawData = new float[4096];
			for (int i = 0; i < 4096; ++i) {
				rawData[i] = palette.getFloat(rawIndices[i]);
			}
		} else {
			rawData = null;
			resistancePalette = palette.toFloatArray();
			int bitCount = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
			int powerOf2 = 1;
			for (int p = 1; p < bitCount; p <<= 1) {
				powerOf2 = p << 1;
			}
			storageBits = powerOf2;
			subIndexCount = 32 / storageBits;
			subIndexShift = Integer.numberOfTrailingZeros(subIndexCount);
			int mask = 0;
			for (int i = 0; i < storageBits; ++i) {
				mask |= 1 << i;
			}
			retrievalMask = mask;
			indices = new int[4096 >> subIndexShift];
			int subIndex = 0;
			int wordIndex = 0;
			for (int i : rawIndices) {
				indices[wordIndex] |= i << (subIndex * storageBits);
				++subIndex;
				if (subIndex >= subIndexCount) {
					subIndex = 0;
					++wordIndex;
				}
			}
		}
	}
	
	public boolean isEmpty() {
		return empty;
	}
	
	public float get(int index) {
		if (resistancePalette != null) {
			int word = indices[index >> subIndexShift];
			int subIndex = index & (subIndexCount - 1);
			return resistancePalette[(word >> (subIndex * storageBits)) & retrievalMask];
		}
		return rawData[index];
	}
	
	public long getByteSize() {
		return resistancePalette != null ? resistancePalette.length * 4 + indices.length * 4 : 16432;
	}
}
