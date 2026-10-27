'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { script, RESOURCES } = require('./editor.js');

const time = script('time');
const timetable = script('timetable');
const jsonOutput = script('json-output');

const CASES = path.join(RESOURCES, 'timetable', 'cases');

function readText(file) {
  // A checkout on Windows may have turned the line ends into CR LF.
  return fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
}

test('times read and write as the mod has them', () => {
  assert.equal(time.parse('8:10'), 8 * 3600 + 600);
  assert.equal(time.parse('08:10:30'), 8 * 3600 + 630);
  assert.equal(time.parse('47:59:59'), time.LIMIT - 1);
  for (const bad of ['48:00', '08:60', '08:10:60', '8:1', '08:10 ', ' 08:10', '08-10', '', '1:00:00:00', '٠٨:10'])
    assert.equal(time.parse(bad), null, bad);
  assert.equal(time.format(0), '00:00');
  assert.equal(time.format(24 * 3600 + 600), '24:10');
  assert.equal(time.format(8 * 3600 + 5), '08:00:05');
  assert.throws(() => time.format(time.LIMIT));
  assert.throws(() => time.format(-1));
  assert.equal(time.show(-1), '-1 s');
  assert.equal(time.show(time.LIMIT), time.LIMIT + ' s');
});

test('every shared case has the problems it says, word for word as the mod says them', () => {
  const files = fs.readdirSync(CASES).filter(name => name.endsWith('.json')).sort();
  assert.ok(files.length > 0, 'there are no cases');
  for (const name of files) {
    const kase = JSON.parse(readText(path.join(CASES, name)));
    const result = timetable.read(JSON.stringify(kase.timetable));
    assert.deepEqual(result.problems, kase.problems, name + ': ' + kase.about);
  }
});

test('the example is written back as it was read, as the mod writes it', () => {
  const example = readText(path.join(RESOURCES, 'timetable', 'example.json'));
  const result = timetable.read(example);
  assert.deepEqual(result.problems, []);
  assert.equal(timetable.write(result.timetable), example);
});

test('a timetable of the right shape with mistakes in it is still read, for the editor to put right', () => {
  const kase = JSON.parse(readText(path.join(CASES, 'stops.json')));
  const result = timetable.read(JSON.stringify(kase.timetable));
  assert.ok(result.timetable !== null);
  assert.equal(result.timetable.trains.length, 5);
  assert.equal(result.timetable.trains[1].stops[1].depart, 8 * 3600 + 58 * 60);
});

test('a timetable not of the right shape is not read', () => {
  const kase = JSON.parse(readText(path.join(CASES, 'shape.json')));
  assert.equal(timetable.read(JSON.stringify(kase.timetable)).timetable, null);
  const broken = timetable.read('{"format": "createrealtimeschedule.timetable", ');
  assert.equal(broken.timetable, null);
  assert.equal(broken.problems.length, 1);
  assert.ok(broken.problems[0].startsWith('not valid JSON: '), broken.problems[0]);
});

test('a newer version is not read, and says the editor needs updating', () => {
  const result = timetable.read('{"format": "createrealtimeschedule.timetable", "version": 6, "whatever": 1}');
  assert.equal(result.timetable, null);
  assert.deepEqual(result.problems,
    ['version: is 6, but this editor reads timetables up to version 5; the editor needs updating to read this one']);
});

test('the trains a stop waits for are read and written back as the mod writes them, as version 5', () => {
  const text = readText(path.join(RESOURCES, 'timetable', 'example-waits.json'));
  const result = timetable.read(text);
  assert.deepEqual(result.problems, []);
  assert.deepEqual(result.timetable.trains[0].stops[1].waits, [{ train: '3001', at: 'S1', maxWait: -1 }, { train: '2', at: '', maxWait: 120 }]);
  assert.equal('waits' in result.timetable.trains[0].stops[0], false, 'a stop that waits for nothing has no waits');
  assert.equal(timetable.version(result.timetable), 5);
  assert.equal(timetable.write(result.timetable), text);
  assert.deepEqual(timetable.read(text.replace('"max_wait": 120', '"max_wait": -1')).problems,
    ['trains[0].stops[1].wait_for[1].max_wait: must be 0 to 3600 seconds']);
});

test('a train that is never cancelled is read and written back as the mod writes it, as version 4', () => {
  const text = readText(path.join(RESOURCES, 'timetable', 'example-keep.json'));
  const result = timetable.read(text);
  assert.deepEqual(result.problems, []);
  assert.deepEqual(result.timetable.trains.map(train => train.keep === true), [false, false, true, false]);
  assert.equal('keep' in result.timetable.trains[0], false, 'a train that may be cancelled has no keep');
  assert.equal(timetable.version(result.timetable), 4);
  assert.equal(timetable.write(result.timetable), text);
  const stopTimes = timetable.read(readText(path.join(RESOURCES, 'timetable', 'example-stop-times.json'))).timetable;
  stopTimes.trains[0].keep = true;
  assert.equal(timetable.version(stopTimes), 4, 'version 4 holds what version 3 added too');
});

test('the stations\' times and the stops\' minimum stops are read and written back as the mod writes them, as version 3', () => {
  const text = readText(path.join(RESOURCES, 'timetable', 'example-stop-times.json'));
  const result = timetable.read(text);
  assert.deepEqual(result.problems, []);
  assert.deepEqual(result.timetable.stations, [
    { name: 'A', dwell: 30, minimumStop: 15 }, { name: 'B', dwell: 45, minimumStop: -1 }, { name: 'Depot', dwell: -1, minimumStop: 0 }]);
  assert.equal(result.timetable.trains[1].stops[1].minimumStop, 10);
  assert.equal('minimumStop' in result.timetable.trains[1].stops[0], false, 'a stop with none of its own has no minimumStop');
  assert.equal(timetable.write(result.timetable), text);
});

test('a timetable with none of what version 3 added is written as version 2, which an older mod still takes', () => {
  const example = timetable.read(readText(path.join(RESOURCES, 'timetable', 'example.json'))).timetable;
  assert.equal(timetable.version(example), 2);
  assert.ok(timetable.write(example).includes('"version": 2,'));
  const old = { name: '', lines: [], trains: [], duties: [] };
  assert.equal(timetable.version(old), 2, 'a draft from before version 3 has no stations');
  assert.ok(!timetable.write(old).includes('stations'));
});

test('seconds that are not a whole number, or are below 0, are refused where they are', () => {
  const text = readText(path.join(RESOURCES, 'timetable', 'example-stop-times.json'));
  assert.deepEqual(timetable.read(text.replace('"dwell": 45', '"dwell": "45"')).problems, ['stations[1].dwell: must be a whole number of seconds']);
  assert.deepEqual(timetable.read(text.replace('"minimum_stop": 0', '"minimum_stop": -1')).problems, ['stations[2].minimum_stop: must be 0 to 600 seconds']);
  assert.deepEqual(timetable.read(text.replace('"minimum_stop": 10', '"minimum_stop": 1.5')).problems,
    ['trains[1].stops[1].minimum_stop: must be a whole number of seconds']);
});

test('strings are escaped as Gson escapes them', () => {
  assert.equal(jsonOutput.quote('a"b\\c'), '"a\\"b\\\\c"');
  assert.equal(jsonOutput.quote('\t\n\r\b\f'), '"\\t\\n\\r\\b\\f"');
  assert.equal(jsonOutput.quote('\u0001\u001f\u007f'), '"\\u0001\\u001f\u007f"');
  assert.equal(jsonOutput.quote('  '), '"\\u2028\\u2029"');
  assert.equal(jsonOutput.quote('駅  é'), '"駅  é"');
});

test('lists are laid out on one line while they fit in 100 columns', () => {
  // An object with a list in it is never on one line, as in the mod; the list in it is.
  const short = jsonOutput.map([['stations', jsonOutput.texts(['A', 'B'])]]);
  assert.equal(jsonOutput.render(short), '{\n  "stations": ["A", "B"]\n}\n');
  assert.equal(jsonOutput.render(jsonOutput.map([['station', jsonOutput.text('A')]])), '{"station": "A"}\n');
  const long = jsonOutput.map([['stations', jsonOutput.texts(Array.from({ length: 12 }, (_, i) => 'Station ' + i))]]);
  const text = jsonOutput.render(long);
  assert.ok(text.startsWith('{\n  "stations": [\n    "Station 0",\n'), text);
});
