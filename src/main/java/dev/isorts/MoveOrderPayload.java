package dev.isorts;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Client -> server: "unit with this entity id should walk to this block". */
public record MoveOrderPayload(int entityId, BlockPos target) implements CustomPayload {

    public static final CustomPayload.Id<MoveOrderPayload> ID =
            new CustomPayload.Id<>(Identifier.of(IsoRts.MOD_ID, "move_order"));

    public static final PacketCodec<RegistryByteBuf, MoveOrderPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT, MoveOrderPayload::entityId,
                    BlockPos.PACKET_CODEC, MoveOrderPayload::target,
                    MoveOrderPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
