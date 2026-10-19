package io.github.eyunya11.createrealtimeschedule.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.ListNode;
import io.github.eyunya11.createrealtimeschedule.json.JsonOutput.MapNode;

class JsonOutputTest {

    @Test
    void anObjectOfPlainValuesIsOnOneLineWhenItFits() {
        MapNode map = new MapNode().put("a", JsonOutput.raw("1")).put("b", JsonOutput.text("x"));
        assertEquals("{\"a\": 1, \"b\": \"x\"}\n", JsonOutput.render(map));
    }

    @Test
    void anObjectWithAnObjectInItIsLaidOutOneMemberToALine() {
        MapNode map = new MapNode().put("a", JsonOutput.raw("1")).put("inner", new MapNode().put("b", JsonOutput.raw("2")));
        assertEquals("{\n  \"a\": 1,\n  \"inner\": {\"b\": 2}\n}\n", JsonOutput.render(map));
    }

    @Test
    void aListThatIsTooWideGoesOneToALine() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            names.add("name number " + i);
        String text = JsonOutput.render(JsonOutput.texts(names));
        assertTrue(text.startsWith("[\n  \"name number 0\",\n"), text);
        assertTrue(text.lines().allMatch(line -> line.length() <= JsonOutput.WIDTH), text);
    }

    @Test
    void inlineKeepsAWideListOnOneLineSoThatTheObjectItIsInStaysFlat() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            names.add("name number " + i);
        MapNode map = new MapNode().put("names", JsonOutput.inline(JsonOutput.texts(names)));
        // Too wide for one line itself, so the object is opened, but the list stays on the one line it was put on.
        assertEquals(3, JsonOutput.render(map).lines().count());
        assertTrue(JsonOutput.inline(JsonOutput.texts(names)).json().startsWith("[\"name number 0\", "));
    }

    @Test
    void inlineRefusesAnObject() {
        assertThrows(IllegalArgumentException.class, () -> JsonOutput.inline(new ListNode().add(new MapNode())));
    }

    @Test
    void textIsQuotedAndEscapedAndNonAsciiIsKept() {
        String backslash = String.valueOf((char) 92);
        String quoteMark = String.valueOf((char) 34);
        // a"b\c<line end>  becomes  "a\"b\\c\n"
        String written = JsonOutput.text("a" + quoteMark + "b" + backslash + "c\n").json();
        assertEquals(quoteMark + "a" + backslash + quoteMark + "b" + backslash + backslash + "c" + backslash + "n" + quoteMark, written);
        assertEquals("\"中央\"", JsonOutput.text("中央").json());
    }

    @Test
    void emptyListsAndObjectsAreWrittenAsSuch() {
        assertEquals("[]\n", JsonOutput.render(new ListNode()));
        assertEquals("{}\n", JsonOutput.render(new MapNode()));
    }
}
