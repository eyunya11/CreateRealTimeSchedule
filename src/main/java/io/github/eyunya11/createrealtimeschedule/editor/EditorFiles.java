package io.github.eyunya11.createrealtimeschedule.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import net.neoforged.fml.ModList;

/**
 * The timetable editor, a web page that comes in the mod's jar under {@code createrealtimeschedule/editor}.
 * It runs from the files as they are, so {@code /rteditor} copies them out to a folder and opens the
 * page there in the browser, and the same files are published on GitHub Pages.
 */
public final class EditorFiles {

    /** Where the editor is in the mod. */
    private static final String[] IN_THE_MOD = {"createrealtimeschedule", "editor"};

    /** The editor's page, which the browser opens. */
    public static final String PAGE = "index.html";

    private EditorFiles() {}

    /**
     * Copies the editor out of the mod into {@code folder}, in place of whatever the folder held, so that
     * the editor there is always the one of this version of the mod.
     *
     * @return the editor's page in {@code folder}
     * @throws IOException if the mod has no editor, or the files cannot be written
     */
    public static Path copyTo(Path folder) throws IOException {
        Path from = ModList.get().getModFileById(Createrealtimeschedule.MODID).getFile().findResource(IN_THE_MOD);
        if (!Files.isDirectory(from))
            throw new IOException("the mod has no editor at " + String.join("/", IN_THE_MOD));
        delete(folder);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(from)) {
            files = walk.toList();
        }
        for (Path file : files) {
            // Path by path, since the mod's files may be in a jar, which names them otherwise.
            Path target = folder;
            for (Path part : from.relativize(file))
                if (!part.toString().isEmpty())
                    target = target.resolve(part.toString());
            if (Files.isDirectory(file)) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return folder.resolve(PAGE);
    }

    /** Deletes {@code folder} and all in it, if it is there. */
    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder))
            return;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(folder)) {
            files = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path file : files)
            Files.delete(file);
    }
}
