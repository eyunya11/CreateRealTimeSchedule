'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { script } = require('./editor.js');

const time = script('time');
const edit = script('edit');

const NONE = edit.NONE;

function t(text) {
  return time.parse(text);
}

/** Two lines, a train on each, and a duty of both trains. */
function timetable() {
  return {
    name: 'Test',
    lines: [{ id: 'a', name: 'A Line', stations: ['X', 'Y'], loop: false }, { id: 'b', name: 'B Line', stations: ['Y', 'Z'], loop: false }],
    trains: [
      { number: '1', line: 'a', type: '', deadhead: false, stops: [{ station: 'X', arrive: NONE, depart: t('08:00') }, { station: 'Y', arrive: t('08:05'), depart: NONE }] },
      { number: '2', line: 'b', type: '', deadhead: false, stops: [{ station: 'Y', arrive: NONE, depart: t('08:10') }, { station: 'Z', arrive: t('08:15'), depart: NONE }] },
    ],
    duties: [{ id: '1A', depot: 'X', trains: ['1', '2'] }],
  };
}

test('a change gives a new timetable and leaves the old one as it was', () => {
  const before = timetable();
  const kept = JSON.stringify(before);
  const after = edit.setName(before, 'Renamed');
  assert.equal(after.name, 'Renamed');
  assert.equal(JSON.stringify(before), kept);
});

test('a line is put in at the end, or in place of one, and its trains follow a new id', () => {
  const added = edit.putLine(timetable(), null, { id: 'c', name: 'C', stations: ['P', 'Q'], loop: true });
  assert.deepEqual(added.lines.map(line => line.id), ['a', 'b', 'c']);
  assert.equal(added.lines[2].loop, true);
  const renamed = edit.putLine(timetable(), 'a', { id: 'main', name: 'Main', stations: ['X', 'Y', 'W'], loop: false });
  assert.deepEqual(renamed.lines.map(line => line.id), ['main', 'b']);
  assert.equal(renamed.trains[0].line, 'main');
  assert.equal(renamed.trains[1].line, 'b');
});

test('a line taken out takes its trains with it, out of their duties too', () => {
  const after = edit.removeLine(timetable(), 'a');
  assert.deepEqual(after.lines.map(line => line.id), ['b']);
  assert.deepEqual(after.trains.map(train => train.number), ['2']);
  assert.deepEqual(after.duties[0].trains, ['2']);
});
