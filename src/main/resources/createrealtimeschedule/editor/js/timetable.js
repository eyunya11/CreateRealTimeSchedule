/*
 * Reads and writes a timetable as JSON (version 2 of the format), as TimetableJson does in the mod.
 *
 * Writing gives the same text as the mod, member for member and line for line. Reading checks the
 * same things in the same order and says them in the same words, with one difference: the browser's
 * JSON reader takes a member written twice (the last one wins) and a version such as 2.0, which the
 * mod refuses. That does no harm, since what the editor hands the mod is always what it writes.
 *
 * Unlike the mod, reading keeps a timetable that is of the right shape but has mistakes in what it
 * says, such as a train that stops at a station its line does not have, so that it can be opened in
 * the editor and put right. Only a timetable with no problems at all would the mod take.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory(require('./time.js'), require('./problems.js'), require('./json-output.js'));
  else
    (root.rt = root.rt || {}).timetable = factory(root.rt.time, root.rt.problems, root.rt.jsonOutput);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (time, check, out) {
  'use strict';

  /** The format member of a timetable in JSON. */
  const FORMAT = 'createrealtimeschedule.timetable';

  /** The newest version of the JSON the editor reads, and the one it writes. */
  const VERSION = 2;

  /** The most problems reported at once; the rest are counted. */
  const MAX_PROBLEMS = 50;

  const NONE = check.NONE;

  class Problems {
    constructor() {
      this.list = [];
      this.dropped = 0;
    }

    add(path, message) {
      if (this.list.length >= MAX_PROBLEMS)
        this.dropped++;
      else
        this.list.push(path === '' ? message : path + ': ' + message);
    }

    isEmpty() {
      return this.list.length === 0;
    }

    all() {
      return this.dropped === 0 ? this.list.slice() : this.list.concat(['... and ' + this.dropped + ' more']);
    }
  }

  function isObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  /** The members of one JSON object, which are checked off as they are read so that the rest can be reported. */
  class Fields {
    constructor(values, path, problems) {
      this.values = values;
      this.path = path;
      this.problems = problems;
      this.used = new Set();
    }

    static of(value, path, problems) {
      if (isObject(value))
        return new Fields(value, path, problems);
      problems.add(path, path === '' ? 'the timetable must be a JSON object' : 'must be an object');
      return null;
    }

    at(key) {
      return this.path === '' ? key : this.path + '.' + key;
    }

    /** The member's value, or null if it is not there or is null. */
    get(key) {
      this.used.add(key);
      const value = Object.prototype.hasOwnProperty.call(this.values, key) ? this.values[key] : null;
      return value === undefined ? null : value;
    }

    string(key, required) {
      const value = this.get(key);
      if (value === null) {
        if (required)
          this.problems.add(this.at(key), 'is missing');
        return null;
      }
      if (typeof value === 'string')
        return value;
      this.problems.add(this.at(key), 'must be a string');
      return null;
    }

    bool(key, fallback) {
      const value = this.get(key);
      if (value === null)
        return fallback;
      if (typeof value === 'boolean')
        return value;
      this.problems.add(this.at(key), 'must be true or false');
      return fallback;
    }

    /** The elements of an array of objects; none if the array is not there. */
    objects(key, required) {
      const value = this.get(key);
      if (value === null) {
        if (required)
          this.problems.add(this.at(key), 'is missing');
        return [];
      }
      if (!Array.isArray(value)) {
        this.problems.add(this.at(key), 'must be an array');
        return [];
      }
      const fields = [];
      value.forEach((element, i) => {
        const field = Fields.of(element, this.at(key) + '[' + i + ']', this.problems);
        if (field !== null)
          fields.push(field);
      });
      return fields;
    }

    /** The elements of an array of strings; the array has to be there. */
    strings(key) {
      const value = this.get(key);
      if (value === null) {
        this.problems.add(this.at(key), 'is missing');
        return [];
      }
      if (!Array.isArray(value)) {
        this.problems.add(this.at(key), 'must be an array of strings');
        return [];
      }
      const strings = [];
      value.forEach((element, i) => {
        if (typeof element === 'string')
          strings.push(element);
        else
          this.problems.add(this.at(key) + '[' + i + ']', 'must be a string');
      });
      return strings;
    }

    problem(key, message) {
      this.problems.add(this.at(key), message);
    }

    /** Reports every member that nothing asked for. */
    finish() {
      for (const key of Object.keys(this.values))
        if (!this.used.has(key))
          this.problems.add(this.at(key), 'is not a member of this format');
    }
  }

  function orEmpty(text) {
    return text === null ? '' : text;
  }

  /** False if the text is of a newer version: nothing else in it can be read, and that is all there is to say. */
  function readVersion(top, problems) {
    const version = top.get('version');
    if (version === null) {
      problems.add('version', 'is missing');
      return true;
    }
    if (typeof version !== 'number' || !Number.isInteger(version) || Math.abs(version) > 999999999) {
      problems.add('version', 'must be a whole number');
      return true;
    }
    if (version > VERSION) {
      problems.add('version', 'is ' + version + ', but this editor reads timetables up to version ' + VERSION
        + '; the editor needs updating to read this one');
      return false;
    }
    if (version < 1)
      problems.add('version', 'must be 1 or more');
    return true;
  }

  function readTime(fields, key) {
    const text = fields.string(key, false);
    if (text === null)
      return NONE;
    const seconds = time.parse(text);
    if (seconds === null) {
      fields.problem(key, 'must be a time such as "08:10" or "08:10:30", up to 47:59:59');
      return NONE;
    }
    return seconds;
  }

  function readLine(f) {
    const id = orEmpty(f.string('id', true));
    const name = f.string('name', false);
    const stations = f.strings('stations');
    const loop = f.bool('loop', false);
    f.finish();
    return { id, name: name === null ? id : name, stations, loop };
  }

  function readStop(f) {
    const station = orEmpty(f.string('station', true));
    const arrive = readTime(f, 'arrive');
    const depart = readTime(f, 'depart');
    f.finish();
    return { station, arrive, depart };
  }

  function readTrain(f) {
    const number = orEmpty(f.string('number', true));
    const line = orEmpty(f.string('line', true));
    const type = orEmpty(f.string('type', false));
    const deadhead = f.bool('deadhead', false);
    const stops = f.objects('stops', true).map(readStop);
    f.finish();
    return { number, line, type, stops, deadhead };
  }

  function readDuty(f) {
    const id = orEmpty(f.string('id', true));
    const depot = orEmpty(f.string('depot', true));
    const trains = f.strings('trains');
    f.finish();
    return { id, depot, trains };
  }

  function build(root, problems) {
    const top = Fields.of(root, '', problems);
    if (top === null)
      return null;
    const format = top.string('format', true);
    if (format !== null && format !== FORMAT)
      problems.add('format', 'must be "' + FORMAT + '"; this is not a timetable of this mod');
    if (format === null || format !== FORMAT)
      return null; // some other JSON: what it has or lacks is not worth listing
    if (!readVersion(top, problems))
      return null;
    const name = top.string('name', false);
    const lines = top.objects('lines', false).map(readLine);
    const trains = top.objects('trains', false).map(readTrain);
    const duties = top.objects('duties', false).map(readDuty);
    top.finish();
    return { name: name === null ? '' : name, lines, trains, duties };
  }

  /**
   * Reads a timetable from its JSON text.
   *
   * @returns {{timetable: ?object, problems: string[]}} the timetable, or null if the text is not of
   *   the right shape; and everything wrong with it, as the mod would say it. The mod takes the
   *   timetable only when there are no problems.
   */
  function read(text) {
    const problems = new Problems();
    let root;
    try {
      root = JSON.parse(text);
    } catch (e) {
      problems.add('', 'not valid JSON: ' + e.message);
      return { timetable: null, problems: problems.all() };
    }
    const timetable = build(root, problems);
    if (timetable === null || !problems.isEmpty())
      return { timetable: null, problems: problems.all() };
    for (const problem of check.problems(timetable))
      problems.add('', problem);
    return { timetable, problems: problems.all() };
  }

  /**
   * The timetable as JSON, laid out as the mod lays it out: members with their default value (loop
   * and deadhead false, type empty, a time a stop does not have) are left out.
   */
  function write(timetable) {
    const lines = timetable.lines.map(line => {
      const entries = [['id', out.text(line.id)], ['name', out.text(line.name)], ['stations', out.texts(line.stations)]];
      if (line.loop)
        entries.push(['loop', out.raw('true')]);
      return out.map(entries);
    });
    const trains = timetable.trains.map(train => {
      const entries = [['number', out.text(train.number)], ['line', out.text(train.line)]];
      if (train.type !== '')
        entries.push(['type', out.text(train.type)]);
      if (train.deadhead)
        entries.push(['deadhead', out.raw('true')]);
      const stops = train.stops.map(stop => {
        const stopEntries = [['station', out.text(stop.station)]];
        if (stop.arrive !== NONE)
          stopEntries.push(['arrive', out.text(time.show(stop.arrive))]);
        if (stop.depart !== NONE)
          stopEntries.push(['depart', out.text(time.show(stop.depart))]);
        return out.map(stopEntries);
      });
      entries.push(['stops', out.list(stops)]);
      return out.map(entries);
    });
    const duties = timetable.duties.map(duty =>
      out.map([['id', out.text(duty.id)], ['depot', out.text(duty.depot)], ['trains', out.texts(duty.trains)]]));
    return out.render(out.map([
      ['format', out.text(FORMAT)],
      ['version', out.raw(String(VERSION))],
      ['name', out.text(timetable.name)],
      ['lines', out.list(lines)],
      ['trains', out.list(trains)],
      ['duties', out.list(duties)],
    ]));
  }

  /** A timetable with nothing in it. */
  function empty() {
    return { name: '', lines: [], trains: [], duties: [] };
  }

  return { FORMAT, VERSION, NONE, read, write, empty };
});
