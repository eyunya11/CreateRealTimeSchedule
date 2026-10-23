package io.github.eyunya11.createrealtimeschedule.network;

import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.diagram.DutyTimes;
import io.github.eyunya11.createrealtimeschedule.diagram.LineAxis;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What a client showing the diagram is sent, once a second while it asks (see
 * {@link DiagramRequestPayload}): the times the duties have made, and the timetable and the axes of
 * its lines when the client's copy of them is old. The client reads the timetable from its JSON with
 * the same code as the server, so that both work out the duties' calls the same way.
 *
 * @param revision the server's {@code OperationData.revision()} the timetable and the axes are of
 * @param content  the timetable and its axes; null if the client already has those of {@code revision}
 * @param now       the clock on the server
 * @param duties    the times of the duties running and of those that stopped running on the service
 *                  day of now and the one before
 */
public record DiagramSnapshotPayload(int revision, @Nullable Content content, long now, List<DutyTimes> duties)
    implements CustomPacketPayload {

    /**
     * The longest timetable sent, in characters. The payload may be larger than one packet holds,
     * since NeoForge splits one too large and puts it back together on the other side.
     */
    public static final int MAX_JSON_LENGTH = 4 * 1024 * 1024;

    /**
     * The timetable as the diagram needs it.
     *
     * @param json     its text; empty if the server runs none, or it is longer than {@link #MAX_JSON_LENGTH}
     * @param tooLarge whether it was left out for being too long
     * @param axes     the axis of each of its lines
     */
    public record Content(String json, boolean tooLarge, List<LineAxis> axes) {
        public Content {
            Objects.requireNonNull(json, "json");
            axes = List.copyOf(axes);
        }
    }

    public static final Type<DiagramSnapshotPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(Createrealtimeschedule.MODID, "diagram_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, DiagramSnapshotPayload> STREAM_CODEC =
        StreamCodec.ofMember(DiagramSnapshotPayload::write, DiagramSnapshotPayload::read);

    public DiagramSnapshotPayload {
        duties = List.copyOf(duties);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(revision);
        buf.writeBoolean(content != null);
        if (content != null) {
            buf.writeUtf(content.json(), MAX_JSON_LENGTH);
            buf.writeBoolean(content.tooLarge());
            buf.writeCollection(content.axes(), DiagramSnapshotPayload::writeAxis);
        }
        buf.writeVarLong(now);
        buf.writeCollection(duties, DiagramSnapshotPayload::writeDuty);
    }

    private static DiagramSnapshotPayload read(FriendlyByteBuf buf) {
        int revision = buf.readVarInt();
        Content content = null;
        if (buf.readBoolean())
            content = new Content(buf.readUtf(MAX_JSON_LENGTH), buf.readBoolean(), buf.readList(DiagramSnapshotPayload::readAxis));
        long now = buf.readVarLong();
        return new DiagramSnapshotPayload(revision, content, now, buf.readList(DiagramSnapshotPayload::readDuty));
    }

    private static void writeAxis(FriendlyByteBuf buf, LineAxis axis) {
        buf.writeUtf(axis.line());
        buf.writeBoolean(axis.loop());
        buf.writeCollection(axis.stations(), FriendlyByteBuf::writeUtf);
        buf.writeCollection(axis.positions(), FriendlyByteBuf::writeDouble);
    }

    private static LineAxis readAxis(FriendlyByteBuf buf) {
        return new LineAxis(buf.readUtf(), buf.readBoolean(), buf.readList(FriendlyByteBuf::readUtf), buf.readList(FriendlyByteBuf::readDouble));
    }

    private static void writeDuty(FriendlyByteBuf buf, DutyTimes duty) {
        buf.writeUtf(duty.duty());
        buf.writeVarLong(duty.day());
        buf.writeUtf(duty.train());
        buf.writeEnum(duty.status());
        buf.writeCollection(duty.arrivals(), FriendlyByteBuf::writeLong);
        buf.writeCollection(duty.departures(), FriendlyByteBuf::writeLong);
    }

    private static DutyTimes readDuty(FriendlyByteBuf buf) {
        return new DutyTimes(buf.readUtf(), buf.readVarLong(), buf.readUtf(), buf.readEnum(DutyTimes.Status.class),
            buf.readList(FriendlyByteBuf::readLong), buf.readList(FriendlyByteBuf::readLong));
    }

    @Override
    public Type<DiagramSnapshotPayload> type() {
        return TYPE;
    }
}
