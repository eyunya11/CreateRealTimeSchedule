package io.github.eyunya11.createrealtimeschedule.network;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.ByteBuf;

/**
 * Tells a client the clock's offset. With the game time it already has, that is everything it needs
 * to read the clock, so this is sent on login and whenever the offset changes, and never per tick.
 */
public record ClockSyncPayload(long offset) implements CustomPacketPayload {

    public static final Type<ClockSyncPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "clock_sync"));

    public static final StreamCodec<ByteBuf, ClockSyncPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.VAR_LONG, ClockSyncPayload::offset, ClockSyncPayload::new);

    @Override
    public Type<ClockSyncPayload> type() {
        return TYPE;
    }
}
