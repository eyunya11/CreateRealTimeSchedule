package io.github.eyunya11.createrealtimeschedule.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.editor.EditorFiles;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The timetable editor that comes in the mod, with {@code ./gradlew runGameTestServer}: {@code /rteditor}
 * copies it out of the mod before it opens it in the browser, which a test cannot do, so the copying
 * is checked here.
 */
@GameTestHolder(Createrealtimeschedule.MODID)
@PrefixGameTestTemplate(false)
public class EditorTests {

    private static final String EMPTY = "empty";

    /** The page comes out as it is in the mod, and what the folder held before is gone. */
    @GameTest(template = EMPTY, batch = "realtimeeditor_files")
    public static void theEditorIsCopiedOutOfTheModInPlaceOfWhatWasThere(GameTestHelper helper) {
        Path folder = null;
        try {
            folder = Files.createTempDirectory("rteditor");
            Path stale = folder.resolve("old.js");
            Files.writeString(stale, "left from an older version", StandardCharsets.UTF_8);

            Path page = EditorFiles.copyTo(folder);
            helper.assertTrue(page.equals(folder.resolve(EditorFiles.PAGE)) && Files.isRegularFile(page), "the page is not at " + page);
            String inTheMod = Files.readString(ModList.get().getModFileById(Createrealtimeschedule.MODID).getFile()
                .findResource("createrealtimeschedule", "editor", EditorFiles.PAGE), StandardCharsets.UTF_8);
            helper.assertTrue(Files.readString(page, StandardCharsets.UTF_8).equals(inTheMod), "the page is not the one in the mod");
            helper.assertTrue(!Files.exists(stale), "a file left from before is still there");
            helper.succeed();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(folder);
        }
    }

    private static void delete(Path folder) {
        if (folder == null || !Files.exists(folder))
            return;
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> files = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path file : files)
                Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
