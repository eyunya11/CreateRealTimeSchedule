package io.github.eyunya11.createrealtimeschedule.railway;

import java.lang.reflect.Method;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.graph.TrackGraph;
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
 * aspect out from the track graph, as the head itself does. This asks that table, by reflection too:
 * {@code typeAt} for which head stands at a position, and {@code resolve} for what it shows. Each is
 * looked up on its own, so that a Create Train Next without {@code resolve} still says which heads there
 * are. A Create Train Next from before the table has no answer for such a head, and neither has the table
 * for a head it has never seen loaded. Once {@code typeAt} has failed, this works as with a Create Train
 * Next from before the table. Everything here that asks the table is to be called on the server thread.
 */
public final class TrainNextSignals {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The block entity of Create Train Next's colour light signals. */
    private static final String SIGNAL = "com.eyunya.createtrainnext.signal.MultiAspectSignalBlockEntity";

    /** Create Train Next's table of the heads it has seen, loaded or not. */
    private static final String HEADS = "com.eyunya.createtrainnext.signal.SignalHeads";

    /** What one side of a signal shows: its kind ({@link SignalLooks}) and its lamps. */
    public record Look(String kind, List<Lamp> lamps) {}

    /**
     * What one head shows, with the place of its aspect in Create Train Next's {@code SignalAspect}, from 0
     * for stop, the strictest, so that the strictest of several heads can be told.
     */
    private record Read(Look look, int strictness) {}

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
     * The table's methods, looked up the first time they are needed, each null where Create Train Next has
     * no such method: {@code SignalHeads.typeAt(MinecraftServer, ResourceKey, BlockPos)}, and
     * {@code resolve(TrackGraph, SignalBoundary, boolean, SignalType)}. Nothing that goes wrong in looking
     * them up escapes, so that reading this class never fails.
     */
    private static final class Table {
        static final @Nullable Class<?> HEADS_CLASS = headsClass();
        static final @Nullable Method TYPE_AT = method("typeAt",
            "heads whose chunk is not loaded are taken for Create's signals", MinecraftServer.class, ResourceKey.class, BlockPos.class);
        static final @Nullable Method RESOLVE = TYPE_AT == null ? null : method("resolve",
            "the map shows the aspect last read for a head whose chunk is not loaded", TrackGraph.class, SignalBoundary.class,
            boolean.class, TYPE_AT.getReturnType());

        private static @Nullable Class<?> headsClass() {
            try {
                return Class.forName(HEADS);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
        }

        private static @Nullable Method method(String name, String otherwise, Class<?>... parameters) {
            if (HEADS_CLASS == null)
                return null;
            try {
                return HEADS_CLASS.getMethod(name, parameters);
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
                LOGGER.warn("Create Train Next's SignalHeads.{} cannot be found; {}", name, otherwise, e);
                return null;
            }
        }
    }

    /** Set once {@code typeAt} has failed; from then on, the table is not asked. */
    private static boolean typeAtFailed;

    private static boolean resolveFailed;

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
    private static @Nullable Read read(Level level, BlockPos pos) {
        if (!level.isLoaded(pos))
            return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null)
            return null;
        Methods methods = METHODS.get(entity.getClass());
        if (methods == null)
            return null;
        try {
            return read((Enum<?>) methods.aspect().invoke(entity), (Enum<?>) methods.type().invoke(entity));
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!failed) {
                failed = true;
                LOGGER.warn("Could not read a colour light signal of Create Train Next; the map shows it as Create's signal", e);
            }
            return null;
        }
    }

    private static @Nullable Read read(Enum<?> aspect, Enum<?> type) {
        List<Lamp> lamps = SignalLooks.aspect(aspect.name());
        String kind = SignalLooks.light(type.name());
        return lamps == null || kind == null ? null : new Read(new Look(kind, lamps), aspect.ordinal());
    }

    /**
     * What side {@code side} of {@code signal} (as {@code blockEntities} has its sides), in {@code graph},
     * shows: the strictest of its heads, as Create Train Next holds a train to the strictest. A head is read
     * from itself in a loaded chunk, and in one that is not, worked out by Create Train Next from the graph,
     * if its table says which head stands there and {@code level} is the server's. Null if no head can be
     * read.
     */
    public static @Nullable Look look(Level level, TrackGraph graph, SignalBoundary signal, boolean side) {
        Read strictest = null;
        for (BlockPos pos : signal.blockEntities.get(side).keySet()) {
            Read read = level.isLoaded(pos) ? read(level, pos) : resolved(level, graph, signal, side, pos);
            // Of two as strict, the one first by kind, so that the order of the heads in their hash map does not count.
            if (read != null && (strictest == null || read.strictness() < strictest.strictness()
                || read.strictness() == strictest.strictness() && read.look().kind().compareTo(strictest.look().kind()) < 0))
                strictest = read;
        }
        return strictest == null ? null : strictest.look();
    }

    /** What the head at {@code pos}, in a chunk that is not loaded, shows, as Create Train Next works it out. */
    private static @Nullable Read resolved(Level level, TrackGraph graph, SignalBoundary signal, boolean side, BlockPos pos) {
        Method resolve = Table.RESOLVE;
        if (resolve == null || resolveFailed || !(level instanceof ServerLevel server))
            return null;
        Object type = typeAt(server, pos);
        if (type == null)
            return null;
        try {
            return read((Enum<?>) resolve.invoke(null, graph, signal, side, type), (Enum<?>) type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            resolveFailed = true;
            LOGGER.warn("Could not ask Create Train Next what a head whose chunk is not loaded shows; the map shows the aspect "
                + "last read", e);
            return null;
        }
    }

    /**
     * Create Train Next's {@code SignalType} of the head at {@code pos}, loaded or not, or null if there is
     * none there, it is not known, or the table cannot be asked.
     */
    private static @Nullable Object typeAt(ServerLevel level, BlockPos pos) {
        Method typeAt = Table.TYPE_AT;
        if (typeAt == null || typeAtFailed)
            return null;
        try {
            return typeAt.invoke(null, level.getServer(), level.dimension(), pos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            typeAtFailed = true;
            LOGGER.warn("Could not ask Create Train Next's table of signal heads; a head whose chunk is not loaded is "
                + "taken for Create's signal from now on", e);
            return null;
        }
    }

    /** Whether the table can be asked which head stands where: false without it, and once asking has failed. */
    private static boolean hasTable() {
        return Table.TYPE_AT != null && !typeAtFailed;
    }

    /**
     * The kind ({@link SignalLooks}) of the colour light signal of Create Train Next at {@code pos}, loaded
     * or not, or null if there is none there or it is not known: from Create Train Next's table, which reads
     * the block where the chunk is loaded. Without the table, only a head in a loaded chunk is known.
     */
    public static @Nullable String kind(ServerLevel level, BlockPos pos) {
        if (!hasTable()) {
            Read read = read(level, pos);
            return read == null ? null : read.look().kind();
        }
        Object type = typeAt(level, pos);
        return type == null ? null : SignalLooks.light(((Enum<?>) type).name());
    }

    /**
     * A number that changes when a colour light signal of Create Train Next is put up at one of the
     * positions of {@code signal}'s heads, taken down, or swapped for one of another kind, whether its chunk
     * is loaded or not. It is 0 where the table of heads cannot be asked: then only a loaded head could be
     * seen, and the number would change each time a player came near one. A part of
     * {@link CreateNetwork#fingerprint}, made of hash codes, as it is.
     */
    static long kindsHash(@Nullable MinecraftServer server, SignalBoundary signal) {
        if (server == null || signal.edgeLocation == null || !hasTable())
            return 0;
        ServerLevel level = server.getLevel(signal.edgeLocation.getFirst().dimension);
        if (level == null)
            return 0;
        long hash = 0;
        for (boolean side : new boolean[] {true, false})
            for (BlockPos head : signal.blockEntities.get(side).keySet()) {
                String kind = kind(level, head);
                // Spread, so that kinds swapped between two heads do not give the same sum, and summed, as the heads are held in a hash map.
                if (kind != null)
                    hash += CreateNetwork.spread(31L * (31L * head.hashCode() + Boolean.hashCode(side)) + kind.hashCode());
            }
        // Asking may have failed on the way; the number is then 0, as from then on.
        return hasTable() ? hash : 0;
    }
}
