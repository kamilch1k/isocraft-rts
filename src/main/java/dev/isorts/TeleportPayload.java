package dev.isorts;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Client -&gt; server: move the player somewhere, so a battle can be staged at a chosen spot.
 * <p>
 * ponytail: this is a development hook driven by the terminal control file, in an offline
 * single-player instance where no second player can ever connect. Gate it on a permission check
 * before this mod ever sees a server.
 */
public record TeleportPayload(BlockPos target) implements CustomPayload {

    public static final CustomPayload.Id<TeleportPayload> ID =
            new CustomPayload.Id<>(Identifier.of(IsoRts.MOD_ID, "teleport"));

    public static final PacketCodec<RegistryByteBuf, TeleportPayload> CODEC =
            PacketCodec.tuple(BlockPos.PACKET_CODEC, TeleportPayload::target, TeleportPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
