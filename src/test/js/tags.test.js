'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { script, RESOURCES } = require('./editor.js');

const edit = script('edit');
const network = script('network');
const timetable = script('timetable');
const problems = script('problems');

const WAYS = path.join(RESOURCES, 'operation', 'ways');

/** The platforms and ways of a network as network.js has them, for edit.noWay. */
function trackOf(read) {
  return { platforms: station => network.platforms(read, station), way: (platform, station, tag) => network.way(read, platform, station, tag) };
}

test('every shared case of ways with no way on a tag has the ways it says, as the mod finds them', () => {
  const files = fs.readdirSync(WAYS).filter(file => file.endsWith('.json')).sort();
  assert.ok(files.length > 0, 'there are no cases');
  for (const file of files) {
    const kase = JSON.parse(fs.readFileSync(path.join(WAYS, file), 'utf8'));
    const read = timetable.read(JSON.stringify(kase.timetable));
    assert.deepEqual(read.problems, [], file);
    const net = Object.assign({ tagged: true, stations: [], legs: [] }, kase.network);
    assert.deepEqual(edit.noWay(read.timetable, trackOf(net)), kase.noWays, file + ': ' + kase.about);
  }
});

test('the tags a timetable holds trains to are those of the ways they run', () => {
  const kase = JSON.parse(fs.readFileSync(path.join(WAYS, 'siding.json'), 'utf8'));
  assert.deepEqual(edit.tagsOf(timetable.read(JSON.stringify(kase.timetable)).timetable), ['local', 'express', 'freight']);
});

/** Train 3, X 09:00 to Y (09:05 to 09:06) to Z 09:10, held to the tag local, with 4 minutes from X to Y on it, 2 on no tag, and none known on others. */
function tagged() {
  const tt = edit.addTrain(edit.copy(timetable.empty()), {
    number: '3', line: 'a', type: '', deadhead: false, stops: [
      { station: 'X', arrive: -1, depart: 9 * 3600 },
      { station: 'Y', arrive: 9 * 3600 + 300, depart: 9 * 3600 + 360 },
      { station: 'Z', arrive: 9 * 3600 + 600, depart: -1 }],
  });
  const running = (from, to, tag) => (from === 'X' && to === 'Y' ? (tag === 'local' ? 240 : tag === '' ? 120 : null) : null);
  return { tt: edit.setTrain(tt, '3', { tag: 'local' }), running };
}

test('a train and a stop are given a tag, and lose it for none', () => {
  const { tt } = tagged();
  assert.equal(tt.trains[0].tag, 'local');
  const stop = edit.setStopTag(tt, '3', 0, 'main');
  assert.equal(stop.trains[0].stops[0].tag, 'main');
  assert.equal('tag' in edit.setStopTag(stop, '3', 0, '').trains[0].stops[0], false);
  assert.equal('tag' in edit.setTrain(tt, '3', { tag: '' }).trains[0], false);
  assert.equal(edit.copy(stop).trains[0].stops[0].tag, 'main', 'a copy keeps the tags');
  assert.equal(edit.copy(stop).trains[0].tag, 'local');
});

test('a stop that comes to be the last loses its tag, as the last stop cannot have one', () => {
  const { tt } = tagged();
  const withTag = edit.setStopTag(tt, '3', 1, 'main');
  const cut = edit.removeStop(withTag, '3', 2);
  assert.equal('tag' in cut.trains[0].stops[1], false);
  assert.deepEqual(problems.problems(cut).filter(problem => problem.includes('track tag')), []);
});

test('a way is timed on the tag a train runs it on', () => {
  const { tt, running } = tagged();
  assert.deepEqual(edit.tooFast(tt, running), []);
  const fast = edit.setStopTime(tt, '3', 1, 'arrive', 9 * 3600 + 180, running);
  assert.equal(fast.trains[0].stops[1].arrive, 9 * 3600 + 240, 'held to the running time on the train\'s tag');
  const own = edit.setStopTag(tt, '3', 0, 'main');
  assert.equal(edit.setStopTime(own, '3', 1, 'arrive', 9 * 3600 + 180, running).trains[0].stops[1].arrive, 9 * 3600 + 180,
    'on a tag of the stop\'s own, the running time of that tag');
  const slow = edit.setStopTime(edit.setStopTag(tt, '3', 0, 'main'), '3', 1, 'arrive', 9 * 3600 + 100, running);
  assert.deepEqual(edit.tooFast(slow, running), [], 'no running time for main is known');
  const untagged = edit.setTrain(slow, '3', { tag: '' });
  const quick = edit.setStopTag(untagged, '3', 0, '');
  assert.deepEqual(edit.tooFast(quick, running), [{ number: '3', from: 'X', to: 'Y', given: 100, needed: 120 }]);
});
