package io.github.eyunya11.createrealtimeschedule.railway;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * The signal blocks of a {@link RailNetwork}, which Create calls signal edge groups, and the signals
 * between them, with nothing from the game in it: what a light signal of Create Train Next shows, and
 * whether a signal is red, comes of which blocks the trains are in.
 *
 * <p>Blocks are numbered from 0. Each edge of the network is in one block from its start up to its
 * first signal, and in the block a signal leads into from there to the next ({@link #edges}). A signal
 * is numbered by its side, as the network's {@link RailNetwork.Point}s of kind {@code SIGNAL} number it:
 * the side a train running one way meets.
 *
 * @param edges     for every edge of the network, where it changes block, in order: the first at 0, for
 *                  the block the edge starts in, and one at each signal on it (one a train cannot pass
 *                  too); empty for an edge whose blocks are not known
 * @param signals   every signal side, by its number
 * @param count     how many blocks there are
 * @param crossings the pairs of blocks whose tracks cross, each pair once with the lower number first:
 *                  a train in either takes up both, as Create has it
 * @param speeds    the speeds Create Train Next's light signals hold a train to, or null where it is not
 *                  there
 */
public record SignalBlocks(List<List<Change>> edges, List<Signal> signals, int count, List<List<Integer>> crossings,
                           @Nullable LightSpeeds speeds) {

    /** A network whose blocks are not known. */
    public static final SignalBlocks NONE = new SignalBlocks(List.of(), List.of(), 0, List.of(), null);

    public SignalBlocks {
        edges = edges.stream().map(List::copyOf).toList();
        signals = List.copyOf(signals);
        crossings = crossings.stream().map(List::copyOf).toList();
        if (count < 0)
            throw new IllegalArgumentException("a network has no fewer than no blocks: " + count);
        for (List<Change> changes : edges)
            for (Change change : changes)
                block(change.block(), count);
        for (Signal signal : signals) {
            block(signal.block(), count);
            for (int next : signal.next())
                if (next < 0 || next >= signals.size())
                    throw new IllegalArgumentException("a signal leads on to signal " + next + ", which is not there");
        }
        for (List<Integer> pair : crossings) {
            if (pair.size() != 2)
                throw new IllegalArgumentException("a crossing is of two blocks: " + pair);
            block(pair.get(0), count);
            block(pair.get(1), count);
        }
    }

    private static void block(int block, int count) {
        if (block < 0 || block >= count)
            throw new IllegalArgumentException("there is no block " + block + " of " + count);
    }

    /** Where an edge goes into another block: from {@code position} on, it is in {@code block}. */
    public record Change(double position, int block) {}

    /**
     * One side of a signal.
     *
     * @param block  the block a train that passes it goes into
     * @param next   the signal sides that end that block, which a train in it meets next: more than one
     *               where the block has a junction in it; none where the track ends in it
     * @param lights for each light signal of Create Train Next on this side, how many aspects it shows (2
     *               to 6); none for a signal of Create's own
     * @param cross  whether it is a cross signal of Create (a chain signal), which holds a train until the
     *               blocks after it are clear as well
     */
    public record Signal(int block, List<Integer> next, List<Integer> lights, boolean cross) {
        public Signal {
            next = List.copyOf(next);
            lights = List.copyOf(lights);
            for (int aspects : lights)
                if (aspects < 2 || aspects > 6)
                    throw new IllegalArgumentException("a light signal shows 2 to 6 aspects: " + aspects);
        }
    }

    /**
     * The speeds in km/h that Create Train Next's light signals hold a train to (its config
     * {@code signals.*}): at 警戒 (restricted), 注意 (approach), 減速 (reduced), and at 進行 of the six-aspect
     * signal (sixProceed).
     */
    public record LightSpeeds(int restricted, int approach, int reduced, int sixProceed) {

        /** Create Train Next's defaults. */
        public static final LightSpeeds DEFAULT = new LightSpeeds(25, 45, 75, 130);
    }

    /** The block a train is in at {@code position} on edge {@code edge}; -1 if it is not known. */
    public int blockAt(int edge, double position) {
        if (edge < 0 || edge >= edges.size())
            return -1;
        int block = -1;
        for (Change change : edges.get(edge)) {
            if (change.position() > position)
                break;
            block = change.block();
        }
        return block;
    }
}
