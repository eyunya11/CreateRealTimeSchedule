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

  function copy(timetable) {
    return {
      name: timetable.name,
      lines: timetable.lines.map(line => ({ id: line.id, name: line.name, stations: line.stations.slice(), loop: line.loop })),
      trains: timetable.trains.map(train => ({
        number: train.number,
        line: train.line,
        type: train.type,
        stops: train.stops.map(stop => ({ station: stop.station, arrive: stop.arrive, depart: stop.depart })),
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
   * Puts a line in: in place of the line of id oldId, or at the end if oldId is null. The trains of
   * the line follow a change of its id.
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
    return next;
  }

  /** Takes a line out, with its trains, which also leave the duties they were in. */
  function removeLine(timetable, id) {
    const next = copy(timetable);
    const gone = new Set(next.trains.filter(train => train.line === id).map(train => train.number));
    next.lines = next.lines.filter(line => line.id !== id);
    next.trains = next.trains.filter(train => train.line !== id);
    for (const duty of next.duties)
      duty.trains = duty.trains.filter(number => !gone.has(number));
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

  /** Moves the times of one stop of a train by delta seconds, so that it stands there as long as before. */
  function moveStop(timetable, number, index, delta) {
    const next = copy(timetable);
    const stop = trainAt(next, number).stops[index];
    stop.arrive = shifted(stop.arrive, delta);
    stop.depart = shifted(stop.depart, delta);
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

  /** Sets one time of a stop: field is 'arrive' or 'depart', and seconds may be NONE. */
  function setStopTime(timetable, number, index, field, seconds) {
    const next = copy(timetable);
    trainAt(next, number).stops[index][field] = seconds;
    return next;
  }

  function setStopStation(timetable, number, index, station) {
    const next = copy(timetable);
    trainAt(next, number).stops[index].station = station;
    return next;
  }

  /** Takes a stop out of a train; the stops at the ends lose the time an end does not have. */
  function removeStop(timetable, number, index) {
    const next = copy(timetable);
    const stops = trainAt(next, number).stops;
    stops.splice(index, 1);
    if (stops.length > 0) {
      stops[0].arrive = NONE;
      stops[stops.length - 1].depart = NONE;
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

  /** The stops of a train drawn to the end: the last stop is only arrived at. Null if there are fewer than two. */
  function finishDrawing(stops) {
    if (stops.length < 2)
      return null;
    const next = stops.map(stop => ({ station: stop.station, arrive: stop.arrive, depart: stop.depart }));
    next[next.length - 1].depart = NONE;
    return next;
  }

  return {
    NONE, copy, setName, putLine, removeLine,
    nextNumber, addTrain, removeTrain, setTrain, moveStop, shiftTrain, setStopTime, setStopStation, removeStop,
    snap, snapUp, drawStop, finishDrawing,
  };
});
