/*
 * The changes the editor makes to a timetable. Each takes a timetable and gives a new one, and leaves
 * the one it was given as it was, so that undoing a change is going back to the timetable before it.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory(require('./problems.js'));
  else
    (root.rt = root.rt || {}).edit = factory(root.rt.problems);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (check) {
  'use strict';

  const NONE = check.NONE;

  /** A stop as it is, with a minimumStop only if it has one of its own. */
  function copyStop(stop) {
    const made = { station: stop.station, arrive: stop.arrive, depart: stop.depart };
    if (stop.minimumStop !== undefined && stop.minimumStop !== NONE)
      made.minimumStop = stop.minimumStop;
    return made;
  }

  /** A copy to change; a timetable from before version 3 gets its (empty) stations. */
  function copy(timetable) {
    return {
      name: timetable.name,
      lines: timetable.lines.map(line => ({ id: line.id, name: line.name, stations: line.stations.slice(), loop: line.loop })),
      stations: (timetable.stations || []).map(station => ({ name: station.name, dwell: station.dwell, minimumStop: station.minimumStop })),
      trains: timetable.trains.map(train => ({
        number: train.number,
        line: train.line,
        type: train.type,
        stops: train.stops.map(copyStop),
        deadhead: train.deadhead,
      })),
      duties: timetable.duties.map(duty => ({ id: duty.id, depot: duty.depot, trains: duty.trains.slice() })),
    };
  }

  // ------------------------------------------------------------------ the timetable and its lines

  function setName(timetable, name) {
    const next = copy(timetable);
    next.name = name;
    return next;
  }

  /**
   * Leaves out the times of stations that no line has any more, which the timetable would otherwise
   * be refused for, once a line has lost them.
   */
  function dropStationsOffTheLines(next) {
    const onLines = new Set(next.lines.flatMap(line => line.stations));
    next.stations = next.stations.filter(station => onLines.has(station.name));
    return next;
  }

  /**
   * Puts a line in: in place of the line of id oldId, or at the end if oldId is null. The trains of
   * the line follow a change of its id, and the times of a station it no longer has, which no other
   * line has, go.
   */
  function putLine(timetable, oldId, line) {
    const next = copy(timetable);
    const made = { id: line.id, name: line.name, stations: line.stations.slice(), loop: !!line.loop };
    const at = oldId === null ? -1 : next.lines.findIndex(each => each.id === oldId);
    if (at < 0) {
      next.lines.push(made);
    } else {
      next.lines[at] = made;
      if (oldId !== line.id)
        for (const train of next.trains)
          if (train.line === oldId)
            train.line = line.id;
    }
    return dropStationsOffTheLines(next);
  }

  /** Takes a line out, with its trains, which also leave the duties they were in, and the times of stations no other line has. */
  function removeLine(timetable, id) {
    const next = copy(timetable);
    const gone = new Set(next.trains.filter(train => train.line === id).map(train => train.number));
    next.lines = next.lines.filter(line => line.id !== id);
    next.trains = next.trains.filter(train => train.line !== id);
    for (const duty of next.duties)
      duty.trains = duty.trains.filter(number => !gone.has(number));
    return dropStationsOffTheLines(next);
  }

  // ------------------------------------------------------------------ the stations' times

  /** The times a station has of its own, {name, dwell, minimumStop}; none (NONE) for a station not listed. */
  function stationTimes(timetable, name) {
    const found = (timetable.stations || []).find(station => station.name === name);
    return found === undefined ? { name, dwell: NONE, minimumStop: NONE } : found;
  }

  /** The dwell a train drawn at the station gets: the station's own, or else fallback. */
  function dwellAt(timetable, name, fallback) {
    const dwell = stationTimes(timetable, name).dwell;
    return dwell === NONE ? fallback : dwell;
  }

  /**
   * Gives the stations the times in list ({name, dwell, minimumStop}, NONE for none), in the order
   * the list has them; a station with neither time is left out, as it has none of its own.
   */
  function setStations(timetable, list) {
    const next = copy(timetable);
    next.stations = list.filter(station => station.dwell !== NONE || station.minimumStop !== NONE)
      .map(station => ({ name: station.name, dwell: station.dwell, minimumStop: station.minimumStop }));
    return next;
  }

  // ------------------------------------------------------------------ trains

  /**
   * A number for a new train that no train has: the one after the highest of the numbers that are
   * only digits, or for a deadhead, of the numbers that are D and digits.
   */
  function nextNumber(timetable, deadhead) {
    const prefix = deadhead ? 'D' : '';
    const pattern = deadhead ? /^D(\d+)$/ : /^(\d+)$/;
    const used = new Set(timetable.trains.map(train => train.number));
    let highest = 0;
    for (const train of timetable.trains) {
      const match = pattern.exec(train.number);
      if (match !== null)
        highest = Math.max(highest, Number(match[1]));
    }
    let next = highest + 1;
    while (used.has(prefix + next))
      next++;
    return prefix + next;
  }

  function trainAt(next, number) {
    const train = next.trains.find(each => each.number === number);
    if (train === undefined)
      throw new Error('there is no train ' + number);
    return train;
  }

  function addTrain(timetable, train) {
    const next = copy(timetable);
    next.trains.push(copy({ name: '', lines: [], trains: [train], duties: [] }).trains[0]);
    return next;
  }

  /** Takes a train out, and out of the duties it was in. */
  function removeTrain(timetable, number) {
    const next = copy(timetable);
    next.trains = next.trains.filter(train => train.number !== number);
    for (const duty of next.duties)
      duty.trains = duty.trains.filter(each => each !== number);
    return next;
  }

  /** Changes a train's number, type and deadhead mark; its duties follow a new number. */
  function setTrain(timetable, number, fields) {
    const next = copy(timetable);
    const train = trainAt(next, number);
    if (fields.type !== undefined)
      train.type = fields.type;
    if (fields.deadhead !== undefined)
      train.deadhead = fields.deadhead;
    if (fields.number !== undefined && fields.number !== number) {
      train.number = fields.number;
      for (const duty of next.duties)
        duty.trains = duty.trains.map(each => each === number ? fields.number : each);
    }
    return next;
  }

  function shifted(seconds, delta) {
    return seconds === NONE ? NONE : seconds + delta;
  }

  /**
   * The seconds a train takes at the least from one stop to the next: the running time the track
   * gives (running, from one station's name to another's, null where it does not say), and in any
   * case a second, since a train has to arrive after it leaves.
   */
  function leastRunning(running, from, to) {
    const seconds = running ? running(from.station, to.station) : null;
    return Math.max(1, seconds === null ? 1 : seconds);
  }

  /**
   * How early and how late one stop of a train can be, by the stops either side of it and the track:
   * the train comes in no sooner than the running time after it leaves the stop before, and leaves
   * no later than the running time before it comes in at the stop after. A stop with no arrival time
   * comes in when it leaves.
   *
   * @returns {{earliest: number, latest: number}} the earliest time the train can come in, and the
   *   latest it can leave; -Infinity and Infinity where nothing holds it
   */
  function stopLimits(stops, index, running) {
    const stop = stops[index];
    const before = stops[index - 1];
    const after = stops[index + 1];
    const earliest = before !== undefined && before.depart !== NONE ? before.depart + leastRunning(running, before, stop) : -Infinity;
    const reach = after === undefined ? NONE : after.arrive !== NONE ? after.arrive : after.depart;
    const latest = reach !== NONE ? reach - leastRunning(running, stop, after) : Infinity;
    return { earliest, latest };
  }

  /** The time a stop is come in at, or left at if it has no arrival time. */
  function comesIn(stop) {
    return stop.arrive !== NONE ? stop.arrive : stop.depart;
  }

  /** The time a stop is left at, or come in at if it is the last. */
  function goesOut(stop) {
    return stop.depart !== NONE ? stop.depart : stop.arrive;
  }

  /**
   * Moves the times of one stop of a train by delta seconds, so that it stands there as long as
   * before, but no further than the stops either side of it and the running times to them allow (see
   * stopLimits); a stop already too close to one of them is not moved closer.
   */
  function moveStop(timetable, number, index, delta, running) {
    const next = copy(timetable);
    const stops = trainAt(next, number).stops;
    const stop = stops[index];
    const { earliest, latest } = stopLimits(stops, index, running);
    const by = Math.min(Math.max(delta, Math.min(earliest - comesIn(stop), 0)), Math.max(latest - goesOut(stop), 0));
    stop.arrive = shifted(stop.arrive, by);
    stop.depart = shifted(stop.depart, by);
    return next;
  }

  /**
   * Has a train stand delta seconds longer at one of its stops (shorter, for a delta below 0, but
   * never less than nothing): it leaves later, and the stops after it move by as much, so that the
   * running times stay as they were. A stop that is not both arrived at and left has no dwell to
   * stretch, and is left as it is.
   */
  function stretchStop(timetable, number, index, delta) {
    const next = copy(timetable);
    const stops = trainAt(next, number).stops;
    const stop = stops[index];
    if (stop.arrive === NONE || stop.depart === NONE)
      return next;
    const by = Math.max(delta, stop.arrive - stop.depart);
    stop.depart += by;
    for (let i = index + 1; i < stops.length; i++) {
      stops[i].arrive = shifted(stops[i].arrive, by);
      stops[i].depart = shifted(stops[i].depart, by);
    }
    return next;
  }

  /** Moves all the times of a train by delta seconds. */
  function shiftTrain(timetable, number, delta) {
    const next = copy(timetable);
    for (const stop of trainAt(next, number).stops) {
      stop.arrive = shifted(stop.arrive, delta);
      stop.depart = shifted(stop.depart, delta);
    }
    return next;
  }

  /**
   * Sets one time of a stop: field is 'arrive' or 'depart', and seconds may be NONE. A time is held
   * to what the stops either side and the running times to them allow (see stopLimits), and an
   * arrival to no later than the stop is left, a departure to no sooner than it is arrived at; where
   * the two cannot both hold, the stop before wins.
   */
  function setStopTime(timetable, number, index, field, seconds, running) {
    const next = copy(timetable);
    const stops = trainAt(next, number).stops;
    const stop = stops[index];
    if (seconds === NONE) {
      stop[field] = NONE;
      return next;
    }
    const { earliest, latest } = stopLimits(stops, index, running);
    const lowest = field === 'arrive' || stop.arrive === NONE ? earliest : stop.arrive;
    const highest = field === 'depart' || stop.depart === NONE ? latest : stop.depart;
    stop[field] = Math.max(Math.min(seconds, highest), lowest);
    return next;
  }

  /**
   * Has the train stand seconds at one of its stops: it leaves that long after it arrives, but no
   * later than the running time to the stop after allows.
   */
  function setDwell(timetable, number, index, seconds, running) {
    const next = copy(timetable);
    const stops = trainAt(next, number).stops;
    const stop = stops[index];
    if (stop.arrive === NONE)
      throw new Error('stop ' + index + ' of train ' + number + ' has no arrival to stand from');
    stop.depart = Math.max(Math.min(stop.arrive + seconds, stopLimits(stops, index, running).latest), stop.arrive);
    return next;
  }

  /**
   * The ways between stops that trains run faster than the track lets them: those where a train
   * comes in at a stop sooner after leaving the one before than the running time (running, null
   * where it does not say). Ways the track gives no running time for are not counted.
   *
   * @returns {Array<{number: string, from: string, to: string, given: number, needed: number}>}
   */
  function tooFast(timetable, running) {
    const found = [];
    for (const train of timetable.trains)
      for (let k = 1; k < train.stops.length; k++) {
        const before = train.stops[k - 1];
        const stop = train.stops[k];
        const needed = running(before.station, stop.station);
        if (needed === null || before.depart === NONE || comesIn(stop) === NONE)
          continue;
        const given = comesIn(stop) - before.depart;
        if (given < needed)
          found.push({ number: train.number, from: before.station, to: stop.station, given, needed });
      }
    return found;
  }

  /** Sets the minimum stop of one stop of a train, in seconds; NONE leaves it to the station's. */
  function setStopMinimum(timetable, number, index, seconds) {
    const next = copy(timetable);
    const stop = trainAt(next, number).stops[index];
    if (seconds === NONE)
      delete stop.minimumStop;
    else
      stop.minimumStop = seconds;
    return next;
  }

  function setStopStation(timetable, number, index, station) {
    const next = copy(timetable);
    trainAt(next, number).stops[index].station = station;
    return next;
  }

  /** Takes a stop out of a train; the stops at the ends lose the time an end does not have, and the last its minimum stop. */
  function removeStop(timetable, number, index) {
    const next = copy(timetable);
    const stops = trainAt(next, number).stops;
    stops.splice(index, 1);
    if (stops.length > 0) {
      stops[0].arrive = NONE;
      stops[stops.length - 1].depart = NONE;
      delete stops[stops.length - 1].minimumStop;
    }
    return next;
  }

  // ------------------------------------------------------------------ drawing a train

  /** seconds to the nearest step. */
  function snap(seconds, step) {
    return step > 1 ? Math.round(seconds / step) * step : Math.round(seconds);
  }

  /** seconds up to the next step. */
  function snapUp(seconds, step) {
    return step > 1 ? Math.ceil(seconds / step) * step : Math.ceil(seconds);
  }

  /**
   * The stops of a train being drawn, with a stop at station added where it was clicked, at second
   * clicked. The first stop leaves at the time clicked. A later one is arrived at when the running
   * time from the stop before says (up to the next step), or at the time clicked if that is later,
   * and is left after the dwell, should the train go on. running is null where the track gives no
   * running time; the train then arrives at the time clicked. Clicking the station the train is at
   * changes nothing.
   */
  function drawStop(stops, station, clicked, running, dwell, step) {
    const at = snap(clicked, step);
    if (stops.length === 0)
      return [{ station, arrive: NONE, depart: at }];
    const last = stops[stops.length - 1];
    if (last.station === station)
      return stops;
    const leave = last.depart;
    let arrive = running === null ? at : Math.max(snapUp(leave + running, step), at);
    if (arrive <= leave)
      arrive = leave + Math.max(step, 1);
    const next = stops.map(stop => ({ station: stop.station, arrive: stop.arrive, depart: stop.depart }));
    next.push({ station, arrive, depart: arrive + dwell });
    return next;
  }

  /**
   * The stops of a train being drawn, with the stop at index standing seconds: it is left that long
   * after it is arrived at, and the stops after it move by as much, so that the running times
   * between them stay as they were. A stop that is not arrived at (the first) has no dwell to set.
   */
  function setDrawnDwell(stops, index, seconds) {
    const stop = stops[index];
    if (stop.arrive === NONE || stop.depart === NONE)
      throw new Error('stop ' + index + ' is not stood at');
    const delta = stop.arrive + seconds - stop.depart;
    return stops.map((each, i) => (i < index ? { station: each.station, arrive: each.arrive, depart: each.depart }
      : i === index ? { station: each.station, arrive: each.arrive, depart: each.arrive + seconds }
        : { station: each.station, arrive: shifted(each.arrive, delta), depart: shifted(each.depart, delta) }));
  }

  /**
   * A click at station, at second clicked, on a train being drawn whose last stop is still to be left
   * (leaving) or not. On the row of that stop, it says when the train leaves: there, to the nearest
   * step, but not before it arrives. Anywhere else it is drawStop's click, and the stop it adds is to
   * be left by the next click, unless it is the first, which is only left. Until then the stop stands
   * the dwell, which is how the train leaves if the next click is on another row.
   *
   * @returns {{stops: object[], leaving: boolean, left: boolean}} the stops, whether the last is still
   *   to be left, and whether the click said when the train leaves it
   */
  function drawClick(stops, leaving, station, clicked, running, dwell, step) {
    const last = stops[stops.length - 1];
    if (leaving && last !== undefined && last.station === station)
      return { stops: setDrawnDwell(stops, stops.length - 1, Math.max(0, snap(clicked, step) - last.arrive)), leaving: false, left: true };
    const next = drawStop(stops, station, clicked, running, dwell, step);
    return { stops: next, leaving: next !== stops && next.length > 1, left: false };
  }

  /** The stops of a train drawn to the end: the last stop is only arrived at. Null if there are fewer than two. */
  function finishDrawing(stops) {
    if (stops.length < 2)
      return null;
    const next = stops.map(stop => ({ station: stop.station, arrive: stop.arrive, depart: stop.depart }));
    next[next.length - 1].depart = NONE;
    return next;
  }

  return {
    NONE, copy, setName, putLine, removeLine, stationTimes, dwellAt, setStations,
    nextNumber, addTrain, removeTrain, setTrain, stopLimits, moveStop, stretchStop, shiftTrain, setStopTime, setDwell, setStopMinimum, setStopStation,
    removeStop, tooFast,
    snap, snapUp, drawStop, drawClick, setDrawnDwell, finishDrawing,
  };
});
