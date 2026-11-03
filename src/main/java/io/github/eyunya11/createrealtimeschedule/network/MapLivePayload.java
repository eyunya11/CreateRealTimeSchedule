package io.github.eyunya11.createrealtimeschedule.network;

import java.util.ArrayList;
import java.util.List;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.map.LiveMap;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.SignalSide;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Spot;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** What the signals of the player's dimension show, where its trains are, and what its speed-limit signs say ({@link LiveMap}). */
public record MapLivePayload(LiveMap live) implements CustomPacketPayload {

    /** The most signals, trains and signs sent, and carriages of a train. */
    public static final int MAX_THINGS = 20_000;
    public static final int MAX_CARRIAGES = 256;
    /** The most track tags a train is sent with: those it runs on in its duty. */
    public static final int MAX_TAGS = 64;

    public static final Type<MapLivePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "map_live"));

    public static final StreamCodec<ByteBuf, MapLivePayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> write(new FriendlyByteBuf(buffer), payload.live()),
        buffer -> new MapLivePayload(read(new FriendlyByteBuf(buffer))));

    private static void write(FriendlyByteBuf out, LiveMap live) {
        List<LiveMap.Signal> signals = live.signals().subList(0, Math.min(MAX_THINGS, live.signals().size()));
        out.writeVarInt(signals.size());
        for (LiveMap.Signal signal : signals) {
            out.writeUtf(signal.track(), 512);
            spot(out, signal.at());
            out.writeVarInt(signal.sides().size());
            for (SignalSide side : signal.sides()) {
                out.writeFloat((float) side.direction().x());
                out.writeFloat((float) side.direction().z());
                out.writeUtf(side.kind(), 32);
                out.writeVarInt(side.lamps().size());
                for (Lamp lamp : side.lamps())
                    out.writeByte(lamp.ordinal());
                out.writeBoolean(side.left());
            }
        }
        List<LiveMap.Train> trains = live.trains().subList(0, Math.min(MAX_THINGS, live.trains().size()));
        out.writeVarInt(trains.size());
        for (LiveMap.Train train : trains) {
            out.writeUtf(train.name(), 256);
            List<LiveMap.Carriage> carriages = train.carriages().subList(0, Math.min(MAX_CARRIAGES, train.carriages().size()));
            out.writeVarInt(carriages.size());
            for (LiveMap.Carriage carriage : carriages) {
                spot(out, carriage.front());
                spot(out, carriage.back());
                out.writeUtf(carriage.frontTrack(), 512);
                out.writeUtf(carriage.backTrack(), 512);
            }
            List<String> tags = train.tags().subList(0, Math.min(MAX_TAGS, train.tags().size()));
            out.writeVarInt(tags.size());
            for (String tag : tags)
                out.writeUtf(tag, 256);
        }
        List<LiveMap.Limit> limits = live.limits().subList(0, Math.min(MAX_THINGS, live.limits().size()));
        out.writeVarInt(limits.size());
        for (LiveMap.Limit limit : limits) {
            out.writeUtf(limit.track(), 512);
            spot(out, limit.at());
            out.writeFloat((float) limit.direction().x());
            out.writeFloat((float) limit.direction().z());
            out.writeVarInt(limit.kmh());
            out.writeBoolean(limit.release());
            out.writeBoolean(limit.left());
        }
    }

    private static LiveMap read(FriendlyByteBuf in) {
        int signalCount = count(in, MAX_THINGS);
        List<LiveMap.Signal> signals = new ArrayList<>();
        for (int i = 0; i < signalCount; i++) {
            String track = in.readUtf(512);
            Spot at = spot(in);
            int sideCount = count(in, 2);
            List<SignalSide> sides = new ArrayList<>();
            for (int j = 0; j < sideCount; j++) {
                Spot direction = new Spot(in.readFloat(), in.readFloat());
                String kind = in.readUtf(32);
                int lampCount = count(in, 4);
                List<Lamp> lamps = new ArrayList<>();
                for (int k = 0; k < lampCount; k++)
                    lamps.add(Lamp.values()[Math.floorMod(in.readByte(), Lamp.values().length)]);
                sides.add(new SignalSide(direction, kind, lamps, in.readBoolean()));
            }
            signals.add(new LiveMap.Signal(track, at, sides));
        }
        int trainCount = count(in, MAX_THINGS);
        List<LiveMap.Train> trains = new ArrayList<>();
        for (int i = 0; i < trainCount; i++) {
            String name = in.readUtf(256);
            int carriageCount = count(in, MAX_CARRIAGES);
            List<LiveMap.Carriage> carriages = new ArrayList<>();
            for (int j = 0; j < carriageCount; j++)
                carriages.add(new LiveMap.Carriage(spot(in), spot(in), in.readUtf(512), in.readUtf(512)));
            int tagCount = count(in, MAX_TAGS);
            List<String> tags = new ArrayList<>();
            for (int j = 0; j < tagCount; j++)
                tags.add(in.readUtf(256));
            trains.add(new LiveMap.Train(name, carriages, tags));
        }
        int limitCount = count(in, MAX_THINGS);
        List<LiveMap.Limit> limits = new ArrayList<>();
        for (int i = 0; i < limitCount; i++)
            limits.add(new LiveMap.Limit(in.readUtf(512), spot(in), new Spot(in.readFloat(), in.readFloat()), in.readVarInt(), in.readBoolean(), in.readBoolean()));
        return new LiveMap(signals, trains, limits);
    }

    private static int count(FriendlyByteBuf in, int most) {
        int count = in.readVarInt();
        if (count < 0 || count > most)
            throw new IllegalArgumentException("a count of " + count + ", more than the " + most + " sent");
        return count;
    }

    private static void spot(FriendlyByteBuf out, Spot spot) {
        out.writeDouble(spot.x());
        out.writeDouble(spot.z());
    }

    private static Spot spot(FriendlyByteBuf in) {
        return new Spot(in.readDouble(), in.readDouble());
    }

    @Override
    public Type<MapLivePayload> type() {
        return TYPE;
    }
}
