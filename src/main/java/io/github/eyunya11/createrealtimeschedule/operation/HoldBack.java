package io.github.eyunya11.createrealtimeschedule.operation;

/**
 * Whether to decide now if a set that has come in at a call is to be held there ({@link Recovery}), or
 * to put that off until the sets have been run on from when it came in ({@link LiveTraffic}), so that
 * what the signals hold them up by, late as they are, goes into it. It has no Minecraft types in it so
 * that it can be tested without a game.
 */
public final class HoldBack {

    private HoldBack() {}

    /** What to do now about a set that has come in. */
    public enum Step {
        /** Nothing is to be decided: the set did not come in later than the threshold, or has left since. */
        FORGET,
        /** Decide now: the sets have been run on from when it came in, or it is to leave. */
        DECIDE,
        /** Wait for the run of the sets going on now. */
        WAIT,
        /**
         * Start running the sets on, as no run is going: as after the server was started again, or once
         * the ways between stations, the timetable or the server's config have changed. If none can be
         * run, decide now.
         */
        RUN
    }

    /**
     * What to do now about the set of {@code run}, which came in at {@code cameIn}.
     *
     * @param threshold how late a set is to have come in, in ticks, to be held ({@link Recovery#tooLate})
     * @param liveFrom  the clock value the sets were last run on from ({@link LiveTraffic#from})
     * @param running   whether a run of the sets is going on now
     */
    public static Step step(DutyRun run, long threshold, long cameIn, long now, long liveFrom, boolean running) {
        if (!Recovery.tooLate(run, threshold))
            return Step.FORGET;
        if (liveFrom >= cameIn || run.dueToLeave(now))
            return Step.DECIDE;
        return running ? Step.WAIT : Step.RUN;
    }
}
