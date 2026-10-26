/*
 * What a line's diagram draws of the timetable's trains, and where on the screen it goes: the planned
 * part of DiagramModel and DiagramView in the mod, line for line, so that a train looks the same in
 * the editor as in the game. Times are seconds from 00:00 of the service day and run on past 24:00;
 * positions are those of the axis.
 *
 * Besides the segments the mod draws, each train has the points of its stops, for the editor to take
 * hold of: where the train arrives at each stop and where it leaves; and its parts, the same segments
 * by what they draw: the way from one stop to the next, or the standing at a stop.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory(require('./problems.js'));
  else
    (root.rt = root.rt || {}).layout = factory(root.rt.problems);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (check) {
  'use strict';

  const NONE = check.NONE;

  function seconds(serviceTime) {
    return serviceTime === NONE ? NaN : serviceTime;
  }

  /**
   * The segments of a train's way through its visits, as DiagramModel's path(): the way between two
   * stations is that of the axis's hops, standing is drawn at the row the train came in at, and also
   * at the row it goes out from if that is another. Also the row each visit comes in at and goes out
   * from, and the parts: {kind: 'run', stop, segments} for the way from visit stop to the next, and
   * {kind: 'stand', stop, segments} for the standing at visit stop, in the order they are made.
   */
  function path(axis, visits) {
    const n = visits.length;
    const rowIn = new Array(n).fill(-1);
    const rowOut = new Array(n).fill(-1);
    const segments = [];
    const parts = [];
    for (let k = 0; k + 1 < n; k++) {
      const from = visits[k];
      const to = visits[k + 1];
      const track = axis.trackDown(from.station, to.station);
      const hops = axis.hops(from.station, to.station, track !== null ? track : from.down);
      if (hops.length === 0)
        continue;
      rowOut[k] = hops[0].fromRow;
      rowIn[k + 1] = hops[hops.length - 1].toRow;
      if (Number.isNaN(from.depart) || Number.isNaN(to.arrive))
        continue;
      let at = from.depart;
      const run = { kind: 'run', stop: k, segments: [] };
      hops.forEach((hop, h) => {
        const until = h === hops.length - 1 ? to.arrive : at + (to.arrive - from.depart) * hop.share;
        const segment = { fromSecond: at, fromPosition: axis.position(hop.fromRow), toSecond: until, toPosition: axis.position(hop.toRow) };
        segments.push(segment);
        run.segments.push(segment);
        at = until;
      });
      parts.push(run);
    }
    for (let k = 0; k < n; k++) {
      const visit = visits[k];
      if (Number.isNaN(visit.arrive) || Number.isNaN(visit.depart) || visit.depart <= visit.arrive)
        continue;
      const into = rowIn[k] >= 0 ? rowIn[k] : rowOut[k] >= 0 ? rowOut[k] : axis.row(visit.station);
      if (into < 0)
        continue;
      const stand = { kind: 'stand', stop: k, segments: [] };
      stand.segments.push({ fromSecond: visit.arrive, fromPosition: axis.position(into), toSecond: visit.depart, toPosition: axis.position(into) });
      if (rowOut[k] >= 0 && rowOut[k] !== into)
        stand.segments.push({ fromSecond: visit.arrive, fromPosition: axis.position(rowOut[k]), toSecond: visit.depart, toPosition: axis.position(rowOut[k]) });
      segments.push(...stand.segments);
      parts.push(stand);
    }
    // A stable sort, as Java's List.sort is, so that segments that start together keep their order.
    segments.sort((a, b) => a.fromSecond - b.fromSecond);
    return { segments, rowIn, rowOut, parts };
  }

  /**
   * A train of the timetable as the diagram of the axis's line draws it:
   * {number, type, deadhead, segments, parts, labelSecond, labelPosition, points}. A point is
   * {stop, arriveSecond, arrivePosition, departSecond, departPosition}, NaN for what the stop does not
   * have; a part is a run or a standing of path(), with the segments it draws.
   */
  function plannedTrain(axis, train) {
    const down = axis.down(train.stops.map(stop => stop.station));
    const visits = train.stops.map((stop, k) => ({
      station: stop.station,
      // A stop with only a departure is a short one: the train comes in then too.
      arrive: seconds(stop.arrive !== NONE || k === 0 ? stop.arrive : stop.depart),
      depart: seconds(stop.depart),
      down,
    }));
    const { segments, rowIn, rowOut, parts } = path(axis, visits);
    const first = rowOut.length > 0 && rowOut[0] >= 0 ? rowOut[0] : visits.length === 0 ? -1 : axis.row(visits[0].station);
    const points = visits.map((visit, k) => {
      const inRow = rowIn[k] >= 0 ? rowIn[k] : rowOut[k] >= 0 ? rowOut[k] : axis.row(visit.station);
      const outRow = rowOut[k] >= 0 ? rowOut[k] : inRow;
      return {
        stop: k,
        arriveSecond: visit.arrive,
        arrivePosition: inRow >= 0 ? axis.position(inRow) : NaN,
        departSecond: visit.depart,
        departPosition: outRow >= 0 ? axis.position(outRow) : NaN,
      };
    });
    return {
      number: train.number,
      type: train.type,
      deadhead: train.deadhead,
      segments,
      parts,
      labelSecond: train.stops.length > 0 ? train.stops[0].depart : NaN,
      labelPosition: first >= 0 ? axis.position(first) : 0,
      points,
    };
  }

  /** The timetable's trains on the axis's line, in the timetable's order. */
  function plannedTrains(timetable, axis) {
    return timetable.trains.filter(train => train.line === axis.line).map(train => plannedTrain(axis, train));
  }

  /** The narrowest span of time that can be shown, in seconds. */
  const MIN_SPAN = 5 * 60;
  /** The widest, in seconds: two days, as far as a timetable's times go. */
  const MAX_SPAN = 48 * 3600;
  /** The steps the time scale can be marked in, in seconds. */
  const STEPS = [60, 5 * 60, 10 * 60, 30 * 60, 3600, 2 * 3600, 6 * 3600];

  function clampSpan(span) {
    return Math.max(MIN_SPAN, Math.min(MAX_SPAN, span));
  }

  /** Which part of a diagram is on the screen, and where a point of it goes, as DiagramView has it. */
  class View {
    constructor(start, span) {
      this.span = clampSpan(span);
      this.startSecond = start;
      this.left = 0;
      this.top = 0;
      this.width = 1;
      this.height = 1;
    }

    place(left, top, width, height) {
      this.left = left;
      this.top = top;
      this.width = Math.max(1, width);
      this.height = Math.max(1, height);
    }

    start() {
      return this.startSecond;
    }

    end() {
      return this.startSecond + this.span;
    }

    x(second) {
      return this.left + (second - this.startSecond) / this.span * this.width;
    }

    y(position, length) {
      return length <= 0 ? this.top : this.top + position / length * this.height;
    }

    second(x) {
      return this.startSecond + (x - this.left) / this.width * this.span;
    }

    /** The position on an axis this long at y down the screen. */
    position(y, length) {
      return length <= 0 ? 0 : (y - this.top) / this.height * length;
    }

    zoom(factor, x) {
      const fixed = this.second(x);
      const share = (fixed - this.startSecond) / this.span;
      this.span = clampSpan(this.span * factor);
      this.startSecond = fixed - share * this.span;
    }

    pan(dx) {
      this.startSecond -= dx / this.width * this.span;
    }

    centreOn(second) {
      this.startSecond = second - this.span / 2;
    }

    /** Shows the time from one second to another. */
    show(from, to) {
      this.span = clampSpan(to - from);
      this.startSecond = (from + to) / 2 - this.span / 2;
    }

    step(minGap) {
      for (const step of STEPS)
        if (step / this.span * this.width >= minGap)
          return step;
      return STEPS[STEPS.length - 1];
    }

    /** The part of a segment in the time shown, as [x1, y1, x2, y2] on the screen; null if none of it is. */
    clip(segment, length) {
      let from = segment.fromSecond;
      let to = segment.toSecond;
      if (to < this.startSecond || from > this.end())
        return null;
      let fromPosition = segment.fromPosition;
      let toPosition = segment.toPosition;
      if (to > from) {
        const a = Math.max(from, this.startSecond);
        const b = Math.min(to, this.end());
        const slope = (toPosition - fromPosition) / (to - from);
        const aPosition = fromPosition + (a - from) * slope;
        const bPosition = fromPosition + (b - from) * slope;
        from = a;
        to = b;
        fromPosition = aPosition;
        toPosition = bPosition;
      }
      return [this.x(from), this.y(fromPosition, length), this.x(to), this.y(toPosition, length)];
    }
  }

  /**
   * The arrows the diagram draws for the waits of the trains on the axis's line: from where the train
   * waited for leaves its station, or runs through it, to where the waiting train leaves; both on the
   * line. Of a train that does either more than once, the last before the waiting train leaves, as the
   * mod waits for.
   */
  function waitMarks(timetable, axis) {
    const planned = new Map(plannedTrains(timetable, axis).map(train => [train.number, train]));
    const line = timetable.lines.find(each => each.id === axis.line);
    const marks = [];
    for (const train of timetable.trains) {
      if (train.line !== axis.line)
        continue;
      train.stops.forEach((stop, k) => {
        if (stop.waits === undefined || k === train.stops.length - 1 || stop.depart === NONE)
          return;
        const point = planned.get(train.number).points[k];
        for (const wait of stop.waits) {
          const other = timetable.trains.find(each => each.number === wait.train);
          if (other === undefined || other.line !== axis.line || other.number === train.number)
            continue;
          const from = leavesAt(other, planned.get(other.number), line, axis, check.waitStation(wait, stop.station), stop.depart);
          if (from !== null && !Number.isNaN(point.departSecond))
            marks.push({ fromSecond: from.second, fromPosition: from.position, toSecond: point.departSecond, toPosition: point.departPosition });
        }
      });
    }
    return marks;
  }

  /**
   * Where on the diagram train leaves station, or runs through it, for the last time before
   * before seconds; null if it does neither.
   */
  function leavesAt(train, planned, line, axis, station, before) {
    const names = train.stops.map(stop => stop.station);
    let found = null;
    for (let s = 0; s + 1 < names.length; s++) {
      const stop = train.stops[s];
      if (stop.depart === NONE || stop.depart >= before)
        continue;
      if (stop.station === station)
        found = { second: planned.points[s].departSecond, position: planned.points[s].departPosition };
      else if (check.runsThrough(line, names, s, station))
        found = through(planned, stop.depart, train.stops[s + 1], axis, station) || found;
    }
    return found;
  }

  /** Where the way that leaves at from and comes in at the stop next crosses the row of station. */
  function through(planned, from, next, axis, station) {
    const until = next.arrive !== NONE ? next.arrive : next.depart;
    for (let row = 0; row < axis.stations.length; row++) {
      if (axis.stations[row] !== station)
        continue;
      const position = axis.position(row);
      for (const segment of planned.segments) {
        const low = Math.min(segment.fromPosition, segment.toPosition);
        const high = Math.max(segment.fromPosition, segment.toPosition);
        if (segment.fromSecond < from || segment.toSecond > until || position <= low || position >= high)
          continue;
        const share = (position - segment.fromPosition) / (segment.toPosition - segment.fromPosition);
        return { second: segment.fromSecond + share * (segment.toSecond - segment.fromSecond), position };
      }
    }
    return null;
  }

  return { plannedTrain, plannedTrains, waitMarks, View, MIN_SPAN, MAX_SPAN };
});
