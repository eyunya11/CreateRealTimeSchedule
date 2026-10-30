package io.github.eyunya11.createrealtimeschedule.diagram;

import java.util.List;
import java.util.Objects;

import io.github.eyunya11.createrealtimeschedule.operation.DutyRun;

/**
 * The times one duty really made on one service day, as the server sends them for the diagram.
 *
 * @param duty       the duty's id
 * @param day        the service day
 * @param train      the name of the train that runs or ran it; empty if that is not known
 * @param status     how it stands
 * @param arrivals   when it arrived at each of its calls, as clock values, or {@link DutyRun#NOT_YET}
 * @param departures when it left each of them, or {@link DutyRun#NOT_YET}
 * @param skipped    the calls it does not make, its set having been held past them ({@link DutyRun#hold})
 * @param passes     when it ran through stations without stopping, of those that are watched ({@link DutyRun#passes})
 */
public record DutyTimes(String duty, long day, String train, Status status, List<Long> arrivals, List<Long> departures,
                        List<Integer> skipped, List<DutyRun.Pass> passes) {

    public enum Status {
        RUNNING,
        FINISHED,
        GIVEN_UP
    }

    public DutyTimes {
        Objects.requireNonNull(duty, "duty");
        Objects.requireNonNull(train, "train");
        Objects.requireNonNull(status, "status");
        arrivals = List.copyOf(arrivals);
        departures = List.copyOf(departures);
        skipped = List.copyOf(skipped);
        passes = List.copyOf(passes);
        if (arrivals.size() != departures.size())
            throw new IllegalArgumentException(arrivals.size() + " arrivals and " + departures.size() + " departures");
        for (int index : skipped)
            if (index <= 0 || index >= arrivals.size() - 1)
                throw new IllegalArgumentException("call " + index + " of " + arrivals.size() + " is not one a run can leave out");
    }

    /** The times of a duty that ran through no watched station. */
    public DutyTimes(String duty, long day, String train, Status status, List<Long> arrivals, List<Long> departures,
                     List<Integer> skipped) {
        this(duty, day, train, status, arrivals, departures, skipped, List.of());
    }

    /** The times of a duty that has made every call so far, and ran through no watched station. */
    public DutyTimes(String duty, long day, String train, Status status, List<Long> arrivals, List<Long> departures) {
        this(duty, day, train, status, arrivals, departures, List.of());
    }

    /** The times of {@code run} so far. */
    public static DutyTimes of(DutyRun run, String train, Status status) {
        return new DutyTimes(run.duty(), run.day(), train, status, run.arrivals(), run.departures(), run.skipped(), run.passes());
    }
}
