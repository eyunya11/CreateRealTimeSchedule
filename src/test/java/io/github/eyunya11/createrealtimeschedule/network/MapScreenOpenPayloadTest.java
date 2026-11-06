package io.github.eyunya11.createrealtimeschedule.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;

/** The lines a map screen's settings are opened with, as they are sent. */
class MapScreenOpenPayloadTest {

    private static MapScreenOpenPayload sentAndRead(MapScreenOpenPayload payload) {
        ByteBuf buffer = Unpooled.buffer();
        MapScreenOpenPayload.STREAM_CODEC.encode(buffer, payload);
        return MapScreenOpenPayload.STREAM_CODEC.decode(buffer);
    }

    @Test
    void aLineWithANameTooLongToSendIsSentCutShort() {
        String name = "Main line ".repeat(30);
        MapScreenOpenPayload read = sentAndRead(new MapScreenOpenPayload(BlockPos.ZERO, List.of(new MapScreenOpenPayload.Line("main", name))));
        MapScreenOpenPayload.Line line = read.lines().getFirst();
        assertEquals("main", line.id());
        assertEquals(256, line.name().length());
        assertEquals(name.substring(0, 255) + "…", line.name());
    }

    @Test
    void aNameIsNotCutInTheMiddleOfACharacter() {
        // A character outside the basic plane is two chars; one of them would be the 255th.
        String name = "a".repeat(254) + "🚆".repeat(10);
        String shortened = MapScreenOpenPayload.shortened(name);
        assertEquals("a".repeat(254) + "…", shortened);
        assertTrue(shortened.chars().noneMatch(c -> Character.isSurrogate((char) c)));
    }

    @Test
    void aNameThatFitsIsSentAsItIs() {
        String name = "n".repeat(256);
        MapScreenOpenPayload read = sentAndRead(new MapScreenOpenPayload(BlockPos.ZERO, List.of(new MapScreenOpenPayload.Line("n", name))));
        assertEquals(name, read.lines().getFirst().name());
    }
}
