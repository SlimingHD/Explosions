package mod.network;

import java.util.function.Supplier;

import mod.client.ClientAccess;
import mod.explosion.helper.HeightmapUpdateHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

public class ClientboundUpdateHeightmapsPacket {

	private final ChunkPos pos;
	private final long[][] data;
	
	public ClientboundUpdateHeightmapsPacket(LevelChunk chunk) {
		pos = chunk.getPos();
		data = new long[4][];
		int index = 0;
		for (Heightmap.Types type : HeightmapUpdateHelper.TYPES) {
			data[index++] = chunk.getOrCreateHeightmapUnprimed(type).getRawData();
		}
	}
	
	public ClientboundUpdateHeightmapsPacket(FriendlyByteBuf buffer) {
		pos = new ChunkPos(buffer.readInt(), buffer.readInt());
		data = new long[4][];
		for (int i = 0; i < 4; i++) {
			data[i] = buffer.readLongArray();
		}
	}
	
	public void encode(FriendlyByteBuf buffer) {
		buffer.writeInt(pos.x);
		buffer.writeInt(pos.z);
		for (int i = 0; i < data.length; i++) {
			buffer.writeLongArray(data[i]);
		}
	}
	
	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientAccess.updateHeightmaps(pos, data)));
		ctx.get().setPacketHandled(true);
	}
}
