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

  return { NONE, copy, setName, putLine, removeLine };
});
