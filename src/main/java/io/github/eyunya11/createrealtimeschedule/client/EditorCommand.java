package io.github.eyunya11.createrealtimeschedule.client;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.Createrealtimeschedule;
import io.github.eyunya11.createrealtimeschedule.editor.EditorFiles;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /rteditor}: opens the timetable editor in the browser. A command of the client, since the
 * editor's files come out of the mod on the player's own computer, into
 * {@code createrealtimeschedule/editor} in the game folder. The folder stays the same, so what the
 * browser keeps of a draft stays too.
 */
@EventBusSubscriber(modid = Createrealtimeschedule.MODID, value = Dist.CLIENT)
public final class EditorCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String KEY = "commands.createrealtimeschedule.rteditor.";

    private EditorCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("rteditor")
            .executes(context -> open(context.getSource())));
    }

    private static int open(CommandSourceStack source) {
        Path page;
        try {
            page = EditorFiles.copyTo(FMLPaths.GAMEDIR.get().resolve("createrealtimeschedule").resolve("editor"));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not copy the timetable editor out of the mod", e);
            source.sendFailure(Component.translatableWithFallback(KEY + "failed", "Could not open the timetable editor: %1$s",
                String.valueOf(e.getMessage())));
            return 0;
        }
        Util.getPlatform().openPath(page);
        source.sendSuccess(() -> Component.translatableWithFallback(KEY + "opened",
            "Opened the timetable editor in your browser: %1$s", page.toString()), false);
        return 1;
    }
}
