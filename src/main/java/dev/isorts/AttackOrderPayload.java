package dev.isorts;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -&gt; server: "unit should attack this other entity". */
public record AttackOrderPayload(int unitId, int targetId) implements CustomPayload {

    public static final CustomPayload.Id<AttackOrderPayload> ID =
            new CustomPayload.Id<>(Identifier.of(IsoRts.MOD_ID, "attack_order"));

    public static final PacketCodec<RegistryByteBuf, AttackOrderPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT, AttackOrderPayload::unitId,
                    PacketCodecs.VAR_INT, AttackOrderPayload::targetId,
                    AttackOrderPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
