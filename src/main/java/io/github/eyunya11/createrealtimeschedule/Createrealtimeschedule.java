package io.github.eyunya11.createrealtimeschedule;

import io.github.eyunya11.createrealtimeschedule.clock.RealClockConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * The mod's entry point. Everything else hooks itself in through {@code @EventBusSubscriber} classes
 * (the clock, the wait condition, the network channel), so all that is left to do here is to
 * register the config.
 */
@Mod(Createrealtimeschedule.MODID)
public class Createrealtimeschedule {

    // The value here should match an entry in the META-INF/neoforge.mods.toml file
    public static final String MODID = "createrealtimeschedule";

    public Createrealtimeschedule(ModContainer modContainer) {
        // The settings belong to the world, so they live in the world's serverconfig folder.
        modContainer.registerConfig(ModConfig.Type.SERVER, RealClockConfig.SPEC);
    }
}
