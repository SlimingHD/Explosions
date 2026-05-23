package mod.client;

import java.util.BitSet;

import mod.explosion.helper.HeightmapUpdateHelper;
import mod.explosion.helper.LightUpdateHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;

public class ClientAccess {
	
	public static void updateChunkSection(SectionPos pos, BitSet changed, boolean allAffected) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		
		if (level == null) {
			return;
		}

		LevelLightEngine engine = level.getLightEngine();
		LevelChunk chunk = level.getChunk(pos.getX(), pos.getZ());
		
		LevelChunkSection section = chunk.getSection(pos.getY());
		PalettedContainer<BlockState> states = section.getStates();
		
		if (allAffected) {
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					for (int y = 0; y < 16; y++) {
						BlockPos blockpos = new BlockPos((pos.getX() << 4) + x, ((pos.getY() + level.getMinSection()) << 4) + y, (pos.getZ() << 4) + z);			
						chunk.removeBlockEntity(blockpos);
						states.set(x, y, z, Blocks.AIR.defaultBlockState());
					}
				}
			}
		} else {
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					for (int y = 0; y < 16; y++) {
						if (!changed.get((x << 8) | (y << 4) | z)) {
							continue;
						}
						BlockPos blockpos = new BlockPos((pos.getX() << 4) + x, ((pos.getY() + level.getMinSection()) << 4) + y, (pos.getZ() << 4) + z);
						chunk.removeBlockEntity(blockpos);
						states.set(x, y, z, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
		
		section.recalcBlockCounts();
		engine.updateSectionStatus(SectionPos.of(pos.getX(), pos.getY() + level.getMinSection(), pos.getZ()), section.hasOnlyAir());
	}
	
	public static void updateChunkSectionLight(SectionPos pos, BitSet changed) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		
		if (level == null) {
			return;
		}

		LevelLightEngine engine = level.getLightEngine();
		
		level.queueLightUpdate(() -> {
			int sectionX = pos.getX() << 4;
			int sectionY = (pos.getY() + level.getMinSection()) << 4;
			int sectionZ = pos.getZ() << 4;
			for (int x = 0; x < 16; ++x) {
				for (int z = 0; z < 16; ++z) {
					for (int y = 15; y >= 0; --y) {
						if (!changed.get((x << 8) | (y << 4) | z)) {
							continue;
						}
						engine.checkBlock(new BlockPos(sectionX + x, sectionY + y, sectionZ + z));
					}
				}
			}
			engine.runLightUpdates();
		});
	}
	
	public static void updateChunkSkyLightSources(ChunkPos pos, int[] data) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		
		if (level == null) {
			return;
		}
		
		BitStorage heightmap = null;
		
		try {
			heightmap = (BitStorage)LightUpdateHelper.HEIGHTMAP_FIELD.get(level.getChunk(pos.x, pos.z).getSkyLightSources());
		} catch (IllegalAccessException | NullPointerException e) {
			e.printStackTrace();
		}

		if (heightmap != null) {
			for (int i = 0; i < 256; i++) {
				heightmap.set(i, data[i]);
			}
		}
	}
	
	public static void updateHeightmaps(ChunkPos pos, long[][] data) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		
		if (level == null) {
			return;
		}
		
		LevelChunk chunk = level.getChunk(pos.x, pos.z);
		int index = 0;
		for (Heightmap.Types type : HeightmapUpdateHelper.TYPES) {
			chunk.getOrCreateHeightmapUnprimed(type).setRawData(chunk, type, data[index++]);
		}
	}
}
