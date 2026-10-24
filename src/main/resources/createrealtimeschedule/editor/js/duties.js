/*
 * Duties, as the editor strings them together: the trains that can come next in a duty, and the
 * deadheads that take it from its depot to where its first train starts and back from where its last
 * one ends. Like edit.js, each change gives a new timetable and leaves the one it was given.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory(require('./edit.js'));
  else
    (root.rt = root.rt || {}).duties = factory(root.rt.edit);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (edit) {
  'use strict';

  const NONE = edit.NONE;

  function first(train) {
    return train.stops[0];
  }

  function last(train) {
    return train.stops[train.stops.length - 1];
  }

  /** An id for a new duty that no duty has: 1A, 2A and so on. */
  function nextDutyId(timetable) {
    const used = new Set(timetable.duties.map(duty => duty.id));
    let highest = 0;
    for (const duty of timetable.duties) {
      const match = /^(\d+)A$/.exec(duty.id);
      if (match !== null)
        highest = Math.max(highest, Number(match[1]));
    }
    let next = highest + 1;
    while (used.has(next + 'A'))
      next++;
    return next + 'A';
  }

  function dutyAt(next, id) {
    const duty = next.duties.find(each => each.id === id);
    if (duty === undefined)
      throw new Error('there is no duty ' + id);
    return duty;
  }

  function addDuty(timetable, id, depot) {
    const next = edit.copy(timetable);
    next.duties.push({ id, depot, trains: [] });
    return next;
  }

  function removeDuty(timetable, id) {
    const next = edit.copy(timetable);
    next.duties = next.duties.filter(duty => duty.id !== id);
    return next;
  }

  /** Changes a duty's id and depot. */
  function setDuty(timetable, id, fields) {
    const next = edit.copy(timetable);
    const duty = dutyAt(next, id);
    if (fields.id !== undefined)
      duty.id = fields.id;
    if (fields.depot !== undefined)
      duty.depot = fields.depot;
    return next;
  }

  function appendTrain(timetable, id, number) {
    const next = edit.copy(timetable);
    dutyAt(next, id).trains.push(number);
    return next;
  }

  function removeTrainAt(timetable, id, index) {
    const next = edit.copy(timetable);
    dutyAt(next, id).trains.splice(index, 1);
    return next;
  }

  /**
   * The trains that can come next in a duty, earliest first: those in no duty that leave from the
   * station where its last train ends, after that train arrives; or, for a duty with no trains yet,
   * those in no duty that leave from its depot.
   */
  function candidates(timetable, id) {
    const duty = timetable.duties.find(each => each.id === id);
    if (duty === undefined)
      return [];
    const inDuties = new Set(timetable.duties.flatMap(each => each.trains));
    const byNumber = new Map(timetable.trains.map(train => [train.number, train]));
    const before = duty.trains.length === 0 ? null : byNumber.get(duty.trains[duty.trains.length - 1]);
    if (before === undefined)
      return [];
    const station = before === null ? duty.depot : last(before).station;
    const after = before === null ? -Infinity : last(before).arrive;
    return timetable.trains
      .filter(train => !inDuties.has(train.number) && train.stops.length >= 2)
      .filter(train => first(train).station === station && first(train).depart !== NONE && first(train).depart > after)
      .sort((a, b) => first(a).depart - first(b).depart);
  }

  /**
   * Adds the deadheads a duty needs to start and end at its depot: one from the depot to where its
   * first train starts, arriving the dwell before that train leaves, and one from where its last
   * train ends, leaving the dwell after it arrives, back to the depot. Each runs on the line of the
   * train it meets, for the seconds the network gives (see network.js), and its times are on the
   * step, the dwell or more apart from the train's. A duty that already starts or ends at the depot
   * gets none at that end; nor does a way the network has no running time for.
   *
   * @param running the seconds from one station to another, or null if not known
   * @param dwell   the seconds a train stands at a station: a number for every station, or a
   *   function of the station's name
   * @returns {{timetable: object, added: string[], missing: Array<[string, string]>}} the new
   *   timetable, the numbers of the deadheads added, and the ways with no running time
   */
  function addDepotDeadheads(timetable, id, running, dwell, step) {
    let next = edit.copy(timetable);
    const duty = dutyAt(next, id);
    const added = [];
    const missing = [];
    const byNumber = new Map(next.trains.map(train => [train.number, train]));
    const start = byNumber.get(duty.trains[0]);
    const end = byNumber.get(duty.trains[duty.trains.length - 1]);
    const down = seconds => (step > 1 ? Math.floor(seconds / step) * step : Math.floor(seconds));
    const dwellAt = typeof dwell === 'function' ? dwell : () => dwell;

    if (start !== undefined && start.stops.length > 0 && first(start).station !== duty.depot) {
      const to = first(start).station;
      const seconds = running(duty.depot, to);
      if (seconds === null) {
        missing.push([duty.depot, to]);
      } else {
        const arrive = down(first(start).depart - dwellAt(to));
        const depart = down(arrive - seconds);
        const number = edit.nextNumber(next, true);
        next = edit.addTrain(next, { number, line: start.line, type: '', deadhead: true,
          stops: [{ station: duty.depot, arrive: NONE, depart }, { station: to, arrive, depart: NONE }] });
        dutyAt(next, id).trains.unshift(number);
        added.push(number);
      }
    }
    if (end !== undefined && end.stops.length > 0 && last(end).station !== duty.depot) {
      const from = last(end).station;
      const seconds = running(from, duty.depot);
      if (seconds === null) {
        missing.push([from, duty.depot]);
      } else {
        const depart = edit.snapUp(last(end).arrive + dwellAt(from), step);
        const arrive = edit.snapUp(depart + seconds, step);
        const number = edit.nextNumber(next, true);
        next = edit.addTrain(next, { number, line: end.line, type: '', deadhead: true,
          stops: [{ station: from, arrive: NONE, depart }, { station: duty.depot, arrive, depart: NONE }] });
        dutyAt(next, id).trains.push(number);
        added.push(number);
      }
    }
    return { timetable: next, added, missing };
  }

  return { nextDutyId, addDuty, removeDuty, setDuty, appendTrain, removeTrainAt, candidates, addDepotDeadheads };
});
