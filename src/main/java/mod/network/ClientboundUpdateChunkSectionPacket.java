package mod.network;

import java.util.BitSet;
import java.util.function.Supplier;

import mod.client.ClientAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

public class ClientboundUpdateChunkSectionPacket {

	private final BitSet changed;
	private final boolean allAffected, updateLight;
	private final int sectionX, sectionY, sectionZ;
	
	public ClientboundUpdateChunkSectionPacket(SectionPos pos, BitSet changed, boolean allAffected, boolean updateLight) {
		this.sectionX = pos.getX();
		this.sectionY = pos.getY();
		this.sectionZ = pos.getZ();
		this.allAffected = allAffected;
		this.updateLight = updateLight;
		this.changed = changed;
	}
	
	public ClientboundUpdateChunkSectionPacket(FriendlyByteBuf buffer) {
		sectionX = buffer.readInt();
		sectionY = buffer.readInt();
		sectionZ = buffer.readInt();
		allAffected = buffer.readBoolean();
		updateLight = buffer.readBoolean();
		
		if (!allAffected) {
			changed = buffer.readBitSet();
		} else {
			changed = new BitSet(0);
		}
	}
	
	public void encode(FriendlyByteBuf buffer) {
		buffer.writeInt(sectionX);
		buffer.writeInt(sectionY);
		buffer.writeInt(sectionZ);
		buffer.writeBoolean(allAffected);
		buffer.writeBoolean(updateLight);
		
		if (!allAffected) {
			buffer.writeBitSet(changed);
		}
	}
	
	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
			SectionPos pos = SectionPos.of(sectionX, sectionY, sectionZ);
			if (updateLight) {
				ClientAccess.updateChunkSectionLight(pos, changed);
			} else {
				ClientAccess.updateChunkSection(pos, changed, allAffected);
			}
		}));
		ctx.get().setPacketHandled(true);
	}
}
