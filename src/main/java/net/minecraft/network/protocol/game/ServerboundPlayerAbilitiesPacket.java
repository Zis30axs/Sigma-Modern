package net.minecraft.network.protocol.game;

import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.world.entity.player.Abilities;
import org.jspecify.annotations.Nullable;

public class ServerboundPlayerAbilitiesPacket implements Packet<ServerGamePacketListener> {
    public static final StreamCodec<FriendlyByteBuf, ServerboundPlayerAbilitiesPacket> STREAM_CODEC = Packet.codec(
        ServerboundPlayerAbilitiesPacket::write, ServerboundPlayerAbilitiesPacket::new
    );
    private static final int FLAG_FLYING = 2;
    private final boolean isFlying;
    // MODIFIED for porting: was VFP networking/player_abilities
    // MixinServerboundPlayerAbilitiesPacket#viaFabricPlus$abilities (@Unique) plus
    // #capturePlayerAbilities (@Inject <init>(Abilities) RETURN). <= 1.15.2 carried the other three
    // ability bits in this packet, so the source Abilities has to survive until write time.
    private final @Nullable Abilities vfpAbilities;

    public ServerboundPlayerAbilitiesPacket(final Abilities abilities) {
        this.isFlying = abilities.flying;
        this.vfpAbilities = abilities;
    }

    private ServerboundPlayerAbilitiesPacket(final FriendlyByteBuf input) {
        byte bitfield = input.readByte();
        this.isFlying = (bitfield & 2) != 0;
        this.vfpAbilities = null;
    }

    private void write(final FriendlyByteBuf output) {
        byte bitfield = 0;
        if (this.isFlying) {
            bitfield = (byte)(bitfield | 2);
        }

        // MODIFIED for porting: was VFP networking/player_abilities
        // MixinServerboundPlayerAbilitiesPacket#implementFlags (@Redirect on writeByte).
        int flags = bitfield;
        if (this.vfpAbilities != null && ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_15_2)) {
            if (this.vfpAbilities.invulnerable) {
                flags |= 1;
            }

            if (this.vfpAbilities.mayfly) {
                flags |= 4;
            }

            if (this.vfpAbilities.instabuild) {
                flags |= 8;
            }
        }

        output.writeByte(flags);
    }

    @Override
    public PacketType<ServerboundPlayerAbilitiesPacket> type() {
        return GamePacketTypes.SERVERBOUND_PLAYER_ABILITIES;
    }

    public void handle(final ServerGamePacketListener listener) {
        listener.handlePlayerAbilities(this);
    }

    public boolean isFlying() {
        return this.isFlying;
    }
}