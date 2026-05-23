package mod.explosion;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import mod.explosion.helper.ExplosionProfiler;
import mod.explosion.helper.HeightmapUpdateHelper;
import mod.explosion.helper.LightUpdateHelper;
import mod.network.ClientboundUpdateChunkSectionPacket;
import mod.network.PacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraftforge.network.PacketDistributor;

public abstract class ExplosionBase {

	protected final ServerLevel level;
	protected final RandomSource random;
	public final ExplosionProfiler profiler;
	
	private Runnable finishWork;
	
	public ExplosionBase(ServerLevel level) {
		this.level = level;
		random = RandomSource.create(0l);
		profiler = new ExplosionProfiler(level);
	}
	
	public void setFinishWork(Runnable finishWork) {
		this.finishWork = finishWork;
	}
	
	public abstract void explode();
	
	public void finishExplosion(Collection<BlockPos> toExplode) {
		Map<Long, BitSet> editedSections = new Long2ObjectOpenHashMap<BitSet>();
		Set<Long> fullSections = new LongOpenHashSet();
		for (BlockPos pos : toExplode) {
			long sectionPos = SectionPos.asLong(pos);
			if (fullSections.contains(sectionPos)) {
				continue;
			}
			BitSet editedBlocks = editedSections.computeIfAbsent(sectionPos, l -> new BitSet(4096));
			int blockIndex = ((pos.getX() & 15) << 8) | ((pos.getY() & 15) << 4) | (pos.getZ() & 15);
			editedBlocks.set(blockIndex);
			if (editedBlocks.nextClearBit(blockIndex) >= 4096) {
				fullSections.add(sectionPos);
				editedSections.remove(sectionPos);
			}
		}
		finishExplosion(editedSections, fullSections);
	}
	
	public void finishExplosion(Map<Long, BitSet> editedSections, Set<Long> fullSections) {
		HashMap<LevelChunk, BitSet> chunks = new HashMap<>();
		Long2ObjectMap<LightUpdateHelper.LightDataHolder> dataLayerCache = new Long2ObjectOpenHashMap<>();
		
		for (long encodedPos : fullSections) {
			SectionPos pos = SectionPos.of(encodedPos);
			LevelChunk chunk = level.getChunk(pos.x(), pos.z());
			LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(pos.y()));
			PalettedContainer<BlockState> states = section.getStates();
			chunk.setLoaded(true);
			for (int i = 0; i < 4096; i++) {
				states.set(i >> 8, (i >> 4) & 15, i & 15, Blocks.AIR.defaultBlockState());
			}
			section.recalcBlockCounts();
			PacketHandler.CHANNEL.send(PacketDistributor.ALL.noArg(), new ClientboundUpdateChunkSectionPacket(SectionPos.of(pos.x(), level.getSectionIndexFromSectionY(pos.y()), pos.z()), new BitSet(0), true, false));	
			if (!chunks.containsKey(chunk)) {
				chunks.put(chunk, new BitSet());
			}
			chunks.get(chunk).set(pos.y() - (level.getMinSection() - 1));
			chunk.setUnsaved(true);
		}
		
		for (Entry<Long, BitSet> entry : editedSections.entrySet()) {
			SectionPos pos = SectionPos.of(entry.getKey());
			BitSet toRemove = entry.getValue();
			LevelChunk chunk = level.getChunk(pos.x(), pos.z());
			LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(pos.y()));
			PalettedContainer<BlockState> states = section.getStates();
			
			chunk.setLoaded(true);
			
			for (int i = 0; i < 4096; i++) {
				if (toRemove.get(i)) {
					states.set(i >> 8, (i >> 4) & 15, i & 15, Blocks.AIR.defaultBlockState());
				}
			}
			section.recalcBlockCounts();	
			PacketHandler.CHANNEL.send(PacketDistributor.ALL.noArg(), new ClientboundUpdateChunkSectionPacket(SectionPos.of(pos.x(), level.getSectionIndexFromSectionY(pos.y()), pos.z()), toRemove, false, false));
			if (!chunks.containsKey(chunk)) {
				chunks.put(chunk, new BitSet());
			}
			chunks.get(chunk).set(pos.y() - (level.getMinSection() - 1));
			chunk.setUnsaved(true);
		}

		HeightmapUpdateHelper.updateHeightmaps((ServerLevel)level, chunks.keySet());
		LightUpdateHelper.updateDirectSkyLight((ServerLevel)level, chunks, dataLayerCache);
		LightUpdateHelper.updateIndirectSkyLight((ServerLevel)level, chunks, dataLayerCache);
		
		level.save(null, false, false);
		if (finishWork != null) {
			finishWork.run();
		}
	}
}
