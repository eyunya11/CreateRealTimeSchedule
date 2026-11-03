package io.github.eyunya11.createrealtimeschedule.railway;

import java.lang.reflect.Method;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.eyunya11.createrealtimeschedule.map.SignalLooks;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Reads what the colour light signals of Create Train Next show, where that mod is there, without
 * depending on it. Such a signal is a head of Create's own signal; its block entity works out the
 * aspect every half second and keeps it, and this reads it by reflection ({@code getAspect} and
 * {@code signalType}, enums read by their names). A head in a chunk that is not loaded cannot be read.
 */
public final class TrainNextSignals {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The block entity of Create Train Next's colour light signals. */
    private static final String SIGNAL = "com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity";

    /** What one side of a signal shows: its kind ({@link SignalLooks}) and its lamps. */
    public record Look(String kind, List<Lamp> lamps) {}

    /** {@code getAspect} and {@code signalType} of the class, or null for a class that is not the signal. */
    private record Methods(Method aspect, Method type) {}

    private static final ClassValue<@Nullable Methods> METHODS = new ClassValue<>() {
        @Override
        protected @Nullable Methods computeValue(Class<?> type) {
            if (!type.getName().equals(SIGNAL))
                return null;
            try {
                return new Methods(type.getMethod("getAspect"), type.getMethod("signalType"));
            } catch (NoSuchMethodException e) {
                LOGGER.warn("Create Train Next's colour light signals cannot be read; the map shows them as Create's signals", e);
                return null;
            }
        }
    };

    private static boolean failed;

    private TrainNextSignals() {}

    /**
     * What the head at {@code pos} shows, if it is a colour light signal of Create Train Next that can
     * be read: in a loaded chunk, and of an aspect and a type this knows.
     */
    public static @Nullable Look look(Level level, BlockPos pos) {
        if (!level.isLoaded(pos))
            return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null)
            return null;
        Methods methods = METHODS.get(entity.getClass());
        if (methods == null)
            return null;
        try {
            List<Lamp> lamps = SignalLooks.aspect(((Enum<?>) methods.aspect().invoke(entity)).name());
            String kind = SignalLooks.light(((Enum<?>) methods.type().invoke(entity)).name());
            return lamps == null || kind == null ? null : new Look(kind, lamps);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!failed) {
                failed = true;
                LOGGER.warn("Could not read a colour light signal of Create Train Next; the map shows it as Create's signal", e);
            }
            return null;
        }
    }
}
