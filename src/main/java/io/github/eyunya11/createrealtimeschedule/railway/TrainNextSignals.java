package io.github.eyunya11.createrealtimeschedule.railway;

import java.lang.reflect.Method;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.signal.SignalBoundary;

import io.github.eyunya11.createrealtimeschedule.map.SignalLooks;
import io.github.eyunya11.createrealtimeschedule.map.TrackMap.Lamp;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Reads what the colour light signals of Create Train Next show, where that mod is there, without
 * depending on it. Such a signal is a head of Create's own signal; its block entity works out the
 * aspect every half second and keeps it, and this reads it by reflection ({@code getAspect} and
 * {@code signalType}, enums read by their names).
 *
 * <p>A head in a chunk that is not loaded cannot be read, but it still holds trains to its aspect: Create
 * Train Next keeps a table of which head stands where ({@code SignalHeads}), on the server, and works the
 * aspect out from the track graph, as the head itself does. This asks that table, by reflection too
 * ({@code typeAt}, {@code graphOf} and {@code resolve}). A Create Train Next from before the table has no
 * answer for such a head, and neither has the table for a head it has never seen loaded.
 * Everything here that asks the table is to be called on the server thread.
 */
public final class TrainNextSignals {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The block entity of Create Train Next's colour light signals. */
    private static final String SIGNAL = "com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity";

    /** Create Train Next's table of the heads it has seen, loaded or not. */
    private static final String HEADS = "com.eyunya.createtrainnext.signal.SignalHeads";

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

    /**
     * {@code SignalHeads.typeAt(MinecraftServer, ResourceKey, BlockPos)}, {@code graphOf(SignalBoundary)} and
     * {@code resolve(TrackGraph, SignalBoundary, boolean, SignalType)}.
     */
    private record Heads(Method typeAt, Method graphOf, Method resolve) {}

    /** The table's methods, looked up the first time they are needed: null where Create Train Next has no table. */
    private static final class HeadsHolder {
        static final @Nullable Heads HEADS_METHODS = find();

        private static @Nullable Heads find() {
            Class<?> heads;
            try {
                heads = Class.forName(HEADS);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
            try {
                Method typeAt = heads.getMethod("typeAt", MinecraftServer.class, ResourceKey.class, BlockPos.class);
                Method graphOf = heads.getMethod("graphOf", SignalBoundary.class);
                Method resolve = heads.getMethod("resolve", graphOf.getReturnType(), SignalBoundary.class, boolean.class,
                    typeAt.getReturnType());
                return new Heads(typeAt, graphOf, resolve);
            } catch (NoSuchMethodException | RuntimeException e) {
                LOGGER.warn("Create Train Next's table of signal heads cannot be read; heads whose chunk is not loaded are "
                    + "taken for Create's signals", e);
                return null;
            }
        }
    }

    private static boolean headsFailed;

    /** Create Train Next's config, whose {@code SERVER} has the speeds of its light signals. */
    private static final String CONFIG = "com.eyunya.createtrainnext.CTNConfig";

    private static boolean speedsFailed;

    private TrainNextSignals() {}

    /**
     * The speeds Create Train Next's light signals hold a train to, as its server config has them; null
     * where it is not there, and its defaults where they cannot be read. They are read by reflection,
     * as the heads are: {@code CTNConfig.SERVER.restrictedSpeed} and the others, each a config value
     * with {@code get()}.
     */
    public static SignalBlocks.@Nullable LightSpeeds speeds() {
        Class<?> config;
        try {
            config = Class.forName(CONFIG);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        try {
            Object server = config.getField("SERVER").get(null);
            return new SignalBlocks.LightSpeeds(speed(server, "restrictedSpeed"), speed(server, "approachSpeed"),
                speed(server, "reducedSpeed"), speed(server, "sixAspectProceedSpeed"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!speedsFailed) {
                speedsFailed = true;
                LOGGER.warn("Could not read the speeds of Create Train Next's light signals; its defaults are taken", e);
            }
            return SignalBlocks.LightSpeeds.DEFAULT;
        }
    }

    private static int speed(Object server, String name) throws ReflectiveOperationException {
        Object value = server.getClass().getField(name).get(server);
        return ((Number) value.getClass().getMethod("get").invoke(value)).intValue();
    }

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

    /**
     * What the head at {@code pos}, on side {@code side} of {@code signal} (as {@code blockEntities} has its
     * sides), shows: read from it in a loaded chunk, and in one that is not, worked out by Create Train
     * Next from the graph, if its table says which head stands there and {@code level} is the server's.
     */
    public static @Nullable Look look(Level level, SignalBoundary signal, boolean side, BlockPos pos) {
        if (level.isLoaded(pos))
            return look(level, pos);
        Heads heads = HeadsHolder.HEADS_METHODS;
        if (heads == null || !(level instanceof ServerLevel server))
            return null;
        try {
            Object type = heads.typeAt().invoke(null, server.getServer(), server.dimension(), pos);
            if (type == null)
                return null;
            Object graph = heads.graphOf().invoke(null, signal);
            Object aspect = heads.resolve().invoke(null, graph, signal, side, type);
            List<Lamp> lamps = SignalLooks.aspect(((Enum<?>) aspect).name());
            String kind = SignalLooks.light(((Enum<?>) type).name());
            return lamps == null || kind == null ? null : new Look(kind, lamps);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnHeads(e);
            return null;
        }
    }

    /**
     * The kind ({@link SignalLooks}) of the colour light signal of Create Train Next at {@code pos}, loaded
     * or not, or null if there is none there or it is not known: from its block where the chunk is loaded,
     * and from Create Train Next's table where it is not.
     */
    public static @Nullable String kind(ServerLevel level, BlockPos pos) {
        Heads heads = HeadsHolder.HEADS_METHODS;
        if (heads == null) {
            Look look = look(level, pos);
            return look == null ? null : look.kind();
        }
        try {
            Object type = heads.typeAt().invoke(null, level.getServer(), level.dimension(), pos);
            return type == null ? null : SignalLooks.light(((Enum<?>) type).name());
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnHeads(e);
            Look look = look(level, pos);
            return look == null ? null : look.kind();
        }
    }

    /**
     * A number that changes when a colour light signal of Create Train Next is put up at one of the
     * positions of {@code signal}'s heads, taken down, or swapped for one of another kind, whether its chunk
     * is loaded or not. It is 0 where Create Train Next keeps no table of its heads: then only a loaded head
     * could be seen, and the number would change each time a player came near one. A part of
     * {@link CreateNetwork#fingerprint}, made of hash codes, as it is.
     */
    static long kindsHash(@Nullable MinecraftServer server, SignalBoundary signal) {
        if (server == null || signal.edgeLocation == null || HeadsHolder.HEADS_METHODS == null)
            return 0;
        ServerLevel level = server.getLevel(signal.edgeLocation.getFirst().dimension);
        if (level == null)
            return 0;
        long hash = 0;
        for (boolean side : new boolean[] {true, false})
            for (BlockPos head : signal.blockEntities.get(side).keySet()) {
                String kind = kind(level, head);
                // Summed, as the heads are held in a hash map.
                if (kind != null)
                    hash += 31L * (31L * head.hashCode() + Boolean.hashCode(side)) + kind.hashCode();
            }
        return hash;
    }

    private static void warnHeads(Exception e) {
        if (!headsFailed) {
            headsFailed = true;
            LOGGER.warn("Could not ask Create Train Next's table of signal heads; a head whose chunk is not loaded is "
                + "taken for Create's signal", e);
        }
    }
}
