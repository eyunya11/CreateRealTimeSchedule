/*
 * What is wrong with a timetable, in words: Timetable.problems() of the mod, rule for rule and word
 * for word, so that the editor finds the mistakes the mod would refuse the timetable for. Shared test
 * cases hold the two to the same results (src/test/resources/.../timetable/cases).
 *
 * A timetable here is plain data, as timetable.js reads it: {name, lines, stations, types, trains,
 * duties}, a line {id, name, stations, loop}, a station {name, dwell, minimumStop} with seconds or
 * NONE, a type {name, color}, a train {number, line, type, stops, deadhead} with keep true if it is
 * never cancelled, a stop {station, arrive, depart, type?, waits?} with the times in seconds or NONE
 * and a minimumStop if it has one of its own, and a duty {id, depot, trains}. A timetable without
 * stations (one made before version 3) has none with times of their own, and one without types (one
 * made before version 6) no types with colours.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory(require('./time.js'));
  else
    (root.rt = root.rt || {}).problems = factory(root.rt.time);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (time) {
  'use strict';

  /** The time of a stop that has no such time. */
  const NONE = -1;

  /** The longest line id, train number or duty id. */
  const MAX_ID_LENGTH = 32;

  /** The longest dwell or minimum stop, in seconds: the longest minimum stop the server's config takes. */
  const MAX_STOP_SECONDS = 600;

  /** The longest a train waits for another past its own time, in seconds: the longest the server's config takes. */
  const MAX_WAIT_SECONDS = 3600;

  const TEXT_RULE = 'must not be empty, start or end with a space, or contain a control character';

  const STOP_RULE = 'must be 0 to ' + MAX_STOP_SECONDS + ' seconds';

  // What Java's Character.isWhitespace takes for a space, which String.strip and isBlank go by; not
  // quite JavaScript's \s, which takes the no-break spaces too.
  const SPACE = '[\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]';
  const SPACE_AT_AN_END = new RegExp('^' + SPACE + '|' + SPACE + '$');
  const ALL_SPACE = new RegExp('^' + SPACE + '*$');
  // What Java's Character.isISOControl takes for a control character.
  const CONTROL = /[\u0000-\u001F\u007F-\u009F]/;
  // A colour as a type has it: TypeColours.isColour of the mod.
  const COLOUR = /^#[0-9a-fA-F]{6}$/;

  function isText(text) {
    return text !== '' && !SPACE_AT_AN_END.test(text) && !CONTROL.test(text);
  }

  /** Whether text is a colour as a type has it: # and six hexadecimal digits (TypeColours.isColour of the mod). */
  function isColour(text) {
    return COLOUR.test(text);
  }

  function isIdentifier(text) {
    return isText(text) && text.length <= MAX_ID_LENGTH;
  }

  function idRule(what) {
    return 'the ' + what + ' must be 1 to ' + MAX_ID_LENGTH + ' characters, with no space at either end and no control character';
  }

  function isTime(seconds) {
    return seconds >= 0 && seconds < time.LIMIT;
  }

  function isStopSeconds(seconds) {
    return seconds >= 0 && seconds <= MAX_STOP_SECONDS;
  }

  function hasMinimumStop(stop) {
    return stop.minimumStop !== undefined && stop.minimumStop !== NONE;
  }

  function changesType(stop) {
    return stop.type !== undefined && stop.type !== '';
  }

  /** line "central", or lines[2] for one that has no usable id to name it by. */
  function label(singular, plural, index, id) {
    return ALL_SPACE.test(id) ? plural + '[' + index + ']' : singular + ' "' + id + '"';
  }

  function hasArrive(stop) {
    return stop.arrive !== NONE;
  }

  function hasDepart(stop) {
    return stop.depart !== NONE;
  }

  function checkLines(timetable, problems) {
    const byId = new Map();
    timetable.lines.forEach((line, i) => {
      const where = label('line', 'lines', i, line.id);
      if (!isIdentifier(line.id))
        problems.push(where + ': ' + idRule('id'));
      else if (byId.has(line.id))
        problems.push(where + ': another line has the same id');
      else
        byId.set(line.id, line);
      if (!isText(line.name))
        problems.push(where + ': the name ' + TEXT_RULE);
      if (line.stations.length < 2)
        problems.push(where + ': needs at least 2 stations');
      const seen = new Set();
      for (const station of line.stations) {
        if (!isText(station))
          problems.push(where + ': a station name ' + TEXT_RULE);
        else if (seen.has(station))
          problems.push(where + ': station "' + station + '" is listed twice');
        else
          seen.add(station);
      }
    });
    return byId;
  }

  function checkStations(timetable, problems) {
    const onLines = new Set();
    for (const line of timetable.lines)
      for (const station of line.stations)
        onLines.add(station);
    const seen = new Set();
    (timetable.stations || []).forEach((station, i) => {
      const where = label('station', 'stations', i, station.name);
      if (!isText(station.name))
        problems.push(where + ': the name ' + TEXT_RULE);
      else if (seen.has(station.name))
        problems.push(where + ': is listed twice');
      else if (!onLines.has(station.name))
        problems.push(where + ': is not a station of any line');
      seen.add(station.name);
      if (station.dwell !== NONE && !isStopSeconds(station.dwell))
        problems.push(where + ': the dwell ' + STOP_RULE);
      if (station.minimumStop !== NONE && !isStopSeconds(station.minimumStop))
        problems.push(where + ': the minimum stop ' + STOP_RULE);
    });
  }

  function checkTypes(timetable, problems) {
    const seen = new Set();
    (timetable.types || []).forEach((type, i) => {
      const where = label('type', 'types', i, type.name);
      if (!isText(type.name))
        problems.push(where + ': the name ' + TEXT_RULE);
      else if (seen.has(type.name))
        problems.push(where + ': another type has the same name');
      else
        seen.add(type.name);
      if (!isColour(type.color))
        problems.push(where + ': the color must be # and six hexadecimal digits, such as #d9822b');
    });
  }

  function checkTrains(timetable, lineById, problems) {
    const byNumber = new Map();
    timetable.trains.forEach((train, i) => {
      const where = label('train', 'trains', i, train.number);
      if (!isIdentifier(train.number))
        problems.push(where + ': ' + idRule('number'));
      else if (byNumber.has(train.number))
        problems.push(where + ': another train has the same number');
      else
        byNumber.set(train.number, train);
      if (train.type !== '' && !isText(train.type))
        problems.push(where + ': the type must not start or end with a space or contain a control character');
      const line = lineById.has(train.line) ? lineById.get(train.line) : null;
      if (line === null)
        problems.push(where + ': there is no line "' + train.line + '"');
      if (train.stops.length < 2)
        problems.push(where + ': needs at least 2 stops');
      else
        checkStops(train, line, where, problems);
    });
    return byNumber;
  }

  function checkStops(train, line, trainLabel, problems) {
    const stops = train.stops;
    const lastIndex = stops.length - 1;
    let previousDeparture = NONE;
    let previousPosition = -1;
    let direction = 0;
    let turnedBack = false;

    for (let k = 0; k <= lastIndex; k++) {
      const stop = stops[k];
      const where = trainLabel + ', stop ' + (k + 1) + ' "' + stop.station + '"';

      // the station
      if (!isText(stop.station))
        problems.push(where + ': a station name ' + TEXT_RULE);
      const position = line === null ? -1 : line.stations.indexOf(stop.station);
      if (line !== null && position < 0)
        problems.push(where + ': is not a station of line "' + line.id + '"');
      if (k > 0 && stop.station === stops[k - 1].station)
        problems.push(where + ': is the same station as the stop before it');

      // which times it has
      if (k === 0 && hasArrive(stop))
        problems.push(where + ': is the first stop, which only has a departure time');
      if (k === lastIndex && hasDepart(stop))
        problems.push(where + ': is the last stop, which only has an arrival time');
      if (k !== lastIndex && !hasDepart(stop))
        problems.push(where + ': needs a departure time');
      if (k === lastIndex && !hasArrive(stop))
        problems.push(where + ': needs an arrival time');
      if (hasArrive(stop) && !isTime(stop.arrive))
        problems.push(where + ': the arrival time is not between 00:00 and 47:59:59');
      if (hasDepart(stop) && !isTime(stop.depart))
        problems.push(where + ': the departure time is not between 00:00 and 47:59:59');
      if (hasMinimumStop(stop) && k === lastIndex)
        problems.push(where + ': is the last stop, which the train does not leave, so it has no minimum stop');
      else if (hasMinimumStop(stop) && !isStopSeconds(stop.minimumStop))
        problems.push(where + ': the minimum stop ' + STOP_RULE);
      if (changesType(stop) && k === lastIndex)
        problems.push(where + ': is the last stop, which the train does not leave, so it changes no type');
      else if (changesType(stop) && !isText(stop.type))
        problems.push(where + ': the type must not start or end with a space or contain a control character');

      // the times in order
      if (hasArrive(stop) && previousDeparture !== NONE && stop.arrive <= previousDeparture)
        problems.push(where + ': arrives at ' + time.show(stop.arrive)
          + ', which is not after the train leaves the stop before at ' + time.show(previousDeparture));
      if (hasArrive(stop) && hasDepart(stop) && stop.depart < stop.arrive)
        problems.push(where + ': leaves at ' + time.show(stop.depart) + ', before it arrives at ' + time.show(stop.arrive));
      if (!hasArrive(stop) && hasDepart(stop) && previousDeparture !== NONE && stop.depart <= previousDeparture)
        problems.push(where + ': leaves at ' + time.show(stop.depart)
          + ', which is not after the train leaves the stop before at ' + time.show(previousDeparture));
      if (hasDepart(stop))
        previousDeparture = stop.depart;

      // one way along the line (a loop line has no end to turn back at)
      if (line !== null && !line.loop && position >= 0) {
        if (previousPosition >= 0 && position !== previousPosition) {
          const step = Math.sign(position - previousPosition);
          if (direction === 0) {
            direction = step;
          } else if (step !== direction && !turnedBack) {
            problems.push(where + ': turns back along line "' + line.id
              + '"; a train goes one way along a line, and a loop line is marked as one');
            turnedBack = true;
          }
        }
        previousPosition = position;
      }
    }
  }

  function checkDuties(timetable, trainByNumber, problems) {
    const ids = new Set();
    const dutyOfTrain = new Map();
    timetable.duties.forEach((duty, i) => {
      const where = label('duty', 'duties', i, duty.id);
      if (!isIdentifier(duty.id))
        problems.push(where + ': ' + idRule('id'));
      else if (ids.has(duty.id))
        problems.push(where + ': another duty has the same id');
      else
        ids.add(duty.id);
      if (!isText(duty.depot))
        problems.push(where + ': the depot, a station name, ' + TEXT_RULE);
      if (duty.trains.length === 0)
        problems.push(where + ': needs at least 1 train');

      let previous = null;
      for (const number of duty.trains) {
        const train = trainByNumber.has(number) ? trainByNumber.get(number) : null;
        if (train === null) {
          problems.push(where + ': there is no train "' + number + '"');
          previous = null;
          continue;
        }
        if (dutyOfTrain.has(number)) {
          const owner = dutyOfTrain.get(number);
          problems.push(where + ': train "' + number + '" is '
            + (owner === duty.id ? 'listed twice in this duty' : 'also in duty "' + owner + '"'));
        } else {
          dutyOfTrain.set(number, duty.id);
        }
        if (previous !== null)
          checkHandover(where, previous, train, problems);
        previous = train;
      }
      if (isText(duty.depot))
        checkDepot(where, duty, trainByNumber, problems);
    });
  }

  /** The duty leaves the depot with its first train and comes back with its last; a deadhead train gets it there. */
  function checkDepot(where, duty, trainByNumber, problems) {
    if (duty.trains.length === 0)
      return;
    const first = trainByNumber.get(duty.trains[0]);
    if (first !== undefined && first.stops.length > 0 && first.stops[0].station !== duty.depot)
      problems.push(where + ': the first train, "' + first.number + '", starts at "' + first.stops[0].station
        + '", not at the depot "' + duty.depot + '"');
    const last = trainByNumber.get(duty.trains[duty.trains.length - 1]);
    if (last !== undefined && last.stops.length > 0 && last.stops[last.stops.length - 1].station !== duty.depot)
      problems.push(where + ': the last train, "' + last.number + '", ends at "' + last.stops[last.stops.length - 1].station
        + '", not at the depot "' + duty.depot + '"');
  }

  /** The train that goes on from where before ended must be at that station when it leaves. */
  function checkHandover(where, before, next, problems) {
    if (before.stops.length === 0 || next.stops.length === 0)
      return;
    const end = before.stops[before.stops.length - 1];
    const start = next.stops[0];
    if (end.station !== start.station)
      problems.push(where + ': train "' + next.number + '" starts at "' + start.station
        + '" but the train before it, "' + before.number + '", ends at "' + end.station + '"');
    else if (hasArrive(end) && hasDepart(start) && start.depart <= end.arrive)
      problems.push(where + ': train "' + next.number + '" leaves at ' + time.show(start.depart)
        + ', which is not after train "' + before.number + '" arrives at ' + time.show(end.arrive));
  }

  // ------------------------------------------------------------------ waits

  function hasWaits(stop) {
    return stop.waits !== undefined && stop.waits.length > 0;
  }

  /**
   * Whether a train that makes stops at the stations named in stops, in order, may run through
   * station of line, without stopping, on its way from stop from to the next: Line.runsThrough of the
   * mod. On a loop line, which way round it goes is the track's, so every other station may be on its way.
   */
  function runsThrough(line, stops, from, station) {
    const a = line.stations.indexOf(stops[from]);
    const b = line.stations.indexOf(stops[from + 1]);
    const q = line.stations.indexOf(station);
    if (a < 0 || b < 0 || q < 0 || q === a || q === b)
      return false;
    return line.loop || (Math.min(a, b) < q && q < Math.max(a, b));
  }

  /** When train leaves station of its line, or leaves the stop before it to run through it, in seconds, in the order of its stops. */
  function leavesOrRunsThrough(train, line, station) {
    const names = train.stops.map(stop => stop.station);
    const leaves = [];
    for (let s = 0; s + 1 < names.length; s++) {
      const stop = train.stops[s];
      if (hasDepart(stop) && (stop.station === station || runsThrough(line, names, s, station)))
        leaves.push(stop.depart);
    }
    return leaves;
  }

  /** The station a wait is at, for a stop at stopStation: its own, or the stop's. */
  function waitStation(wait, stopStation) {
    return wait.at === undefined || wait.at === '' ? stopStation : wait.at;
  }

  /**
   * The trains waited for. Each has to leave the station, or run through it, strictly before the train
   * that waits for it leaves; so waits never go round in a ring, each going back in time.
   */
  function checkWaits(timetable, lineById, trainByNumber, problems) {
    timetable.trains.forEach((train, i) => {
      const trainLabel = label('train', 'trains', i, train.number);
      train.stops.forEach((stop, k) => {
        if (!hasWaits(stop))
          return;
        const where = trainLabel + ', stop ' + (k + 1) + ' "' + stop.station + '"';
        if (k === train.stops.length - 1) {
          problems.push(where + ': is the last stop, which the train does not leave, so it waits for no train');
          return;
        }
        const seen = new Set();
        for (const wait of stop.waits) {
          const at = waitStation(wait, stop.station);
          const what = where + ': waits for train "' + wait.train + '" at "' + at + '"';
          if (seen.has(wait.train + '\n' + at)) {
            problems.push(what + ' twice');
            continue;
          }
          seen.add(wait.train + '\n' + at);
          if (wait.maxWait !== undefined && wait.maxWait !== NONE && (wait.maxWait < 0 || wait.maxWait > MAX_WAIT_SECONDS))
            problems.push(what + ': the longest wait must be 0 to ' + MAX_WAIT_SECONDS + ' seconds');
          if (wait.train === train.number) {
            problems.push(what + ', which is itself');
            continue;
          }
          const other = trainByNumber.has(wait.train) ? trainByNumber.get(wait.train) : null;
          if (other === null) {
            problems.push(what + ', which is not in the timetable');
            continue;
          }
          const line = lineById.has(other.line) ? lineById.get(other.line) : null;
          if (line === null || other.stops.length < 2)
            continue; // said of that train
          if (!line.stations.includes(at)) {
            problems.push(what + ', which is not a station of its line "' + line.id + '"');
            continue;
          }
          const leaves = leavesOrRunsThrough(other, line, at);
          if (leaves.length === 0)
            problems.push(what + ', which neither leaves it nor runs through it');
          else if (hasDepart(stop) && !leaves.some(seconds => seconds < stop.depart))
            problems.push(what + ', which does not leave it or run through it before this train leaves at ' + time.show(stop.depart));
        }
      });
    });
  }

  /** Everything that is wrong with the timetable, in words; empty if it can be used. */
  function problems(timetable) {
    const found = [];
    if (timetable.name !== '' && !isText(timetable.name))
      found.push('the name must not start or end with a space or contain a control character');
    const lineById = checkLines(timetable, found);
    checkStations(timetable, found);
    checkTypes(timetable, found);
    const trainByNumber = checkTrains(timetable, lineById, found);
    checkWaits(timetable, lineById, trainByNumber, found);
    checkDuties(timetable, trainByNumber, found);
    return found;
  }

  return { NONE, MAX_ID_LENGTH, MAX_STOP_SECONDS, MAX_WAIT_SECONDS, problems, isText, isIdentifier, isColour, runsThrough, leavesOrRunsThrough, waitStation };
});
