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

test('a new train gets the number after the highest, and a deadhead the D number after the highest', () => {
  const before = timetable();
  assert.equal(edit.nextNumber(before, false), '3');
  assert.equal(edit.nextNumber(before, true), 'D1');
  const more = edit.addTrain(edit.addTrain(before, { number: 'D4', line: 'a', type: '', deadhead: true, stops: [] }),
    { number: '10A', line: 'a', type: '', deadhead: false, stops: [] });
  assert.equal(edit.nextNumber(more, true), 'D5');
  assert.equal(edit.nextNumber(more, false), '3');
});

test('a train taken out leaves its duties, and a train renumbered takes its duties with it', () => {
  const removed = edit.removeTrain(timetable(), '1');
  assert.deepEqual(removed.trains.map(train => train.number), ['2']);
  assert.deepEqual(removed.duties[0].trains, ['2']);
  const renamed = edit.setTrain(timetable(), '1', { number: '101', type: 'rapid', deadhead: true });
  assert.equal(renamed.trains[0].number, '101');
  assert.equal(renamed.trains[0].type, 'rapid');
  assert.equal(renamed.trains[0].deadhead, true);
  assert.deepEqual(renamed.duties[0].trains, ['101', '2']);
});

test('a stop moves with its dwell kept, and a train moves as a whole', () => {
  const three = edit.addTrain(timetable(), { number: '3', line: 'a', type: '', deadhead: false, stops: [
    { station: 'X', arrive: NONE, depart: t('09:00') }, { station: 'Y', arrive: t('09:05'), depart: t('09:06') }, { station: 'X', arrive: t('09:10'), depart: NONE }] });
  const moved = edit.moveStop(three, '3', 1, 30);
  assert.deepEqual(moved.trains[2].stops[1], { station: 'Y', arrive: t('09:05:30'), depart: t('09:06:30') });
  assert.deepEqual(moved.trains[2].stops[0], three.trains[2].stops[0]);
  const all = edit.shiftTrain(three, '3', -60);
  assert.deepEqual(all.trains[2].stops.map(stop => [stop.arrive, stop.depart]),
    [[NONE, t('08:59')], [t('09:04'), t('09:05')], [t('09:09'), NONE]]);
  const set = edit.setStopTime(three, '3', 1, 'arrive', NONE);
  assert.equal(set.trains[2].stops[1].arrive, NONE);
  assert.equal(edit.setStopStation(three, '3', 2, 'Z').trains[2].stops[2].station, 'Z');
});

test('a stop taken out leaves the ends with only the times an end has', () => {
  const three = edit.addTrain(timetable(), { number: '3', line: 'a', type: '', deadhead: false, stops: [
    { station: 'X', arrive: NONE, depart: t('09:00') }, { station: 'Y', arrive: t('09:05'), depart: t('09:06') }, { station: 'X', arrive: t('09:10'), depart: NONE }] });
  assert.deepEqual(edit.removeStop(three, '3', 2).trains[2].stops.map(stop => [stop.station, stop.arrive, stop.depart]),
    [['X', NONE, t('09:00')], ['Y', t('09:05'), NONE]]);
  assert.deepEqual(edit.removeStop(three, '3', 0).trains[2].stops.map(stop => [stop.station, stop.arrive, stop.depart]),
    [['Y', NONE, t('09:06')], ['X', t('09:10'), NONE]]);
});

test('times snap to the step, to the nearest or up', () => {
  assert.equal(edit.snap(t('08:00:02'), 5), t('08:00'));
  assert.equal(edit.snap(t('08:00:03'), 5), t('08:00:05'));
  assert.equal(edit.snapUp(t('08:00:01'), 5), t('08:00:05'));
  assert.equal(edit.snapUp(t('08:00:05'), 5), t('08:00:05'));
  assert.equal(edit.snap(100.4, 1), 100);
});

test('a train is drawn stop by stop, at the running time or later, standing the dwell', () => {
  let stops = edit.drawStop([], 'X', t('08:00:02'), null, 20, 5);
  assert.deepEqual(stops, [{ station: 'X', arrive: NONE, depart: t('08:00') }]);
  // 67 seconds of running time from X to Y, clicked earlier than that: up to the step after it.
  stops = edit.drawStop(stops, 'Y', t('08:00:30'), 67, 20, 5);
  assert.deepEqual(stops[1], { station: 'Y', arrive: t('08:01:10'), depart: t('08:01:30') });
  assert.equal(edit.drawStop(stops, 'Y', t('08:05'), 67, 20, 5), stops, 'clicking the station the train is at changes nothing');
  // Clicked later than the running time: the train arrives then.
  stops = edit.drawStop(stops, 'Z', t('08:05:01'), 30, 20, 5);
  assert.deepEqual(stops[2], { station: 'Z', arrive: t('08:05'), depart: t('08:05:20') });
  // With no running time, at the time clicked, but never before the train leaves.
  const early = edit.drawStop(stops, 'W', t('08:00'), null, 20, 5);
  assert.equal(early[3].arrive, t('08:05:25'));
  const done = edit.finishDrawing(stops);
  assert.deepEqual(done.map(stop => [stop.station, stop.arrive, stop.depart]),
    [['X', NONE, t('08:00')], ['Y', t('08:01:10'), t('08:01:30')], ['Z', t('08:05'), NONE]]);
  assert.equal(edit.finishDrawing([stops[0]]), null);
});

test('a station\'s own dwell is the one a train drawn there gets, and the setting\'s where it has none', () => {
  const tt = edit.setStations(timetable(), [
    { name: 'Y', dwell: 45, minimumStop: NONE }, { name: 'Z', dwell: NONE, minimumStop: 30 }, { name: 'X', dwell: NONE, minimumStop: NONE }]);
  assert.deepEqual(tt.stations, [{ name: 'Y', dwell: 45, minimumStop: NONE }, { name: 'Z', dwell: NONE, minimumStop: 30 }],
    'a station with neither time is left out');
  assert.equal(edit.dwellAt(tt, 'Y', 20), 45);
  assert.equal(edit.dwellAt(tt, 'Z', 20), 20);
  assert.equal(edit.dwellAt(timetable(), 'Y', 20), 20, 'a timetable from before version 3 has no stations');
  assert.deepEqual(edit.stationTimes(tt, 'Z'), { name: 'Z', dwell: NONE, minimumStop: 30 });
  assert.deepEqual(edit.stationTimes(tt, 'Q'), { name: 'Q', dwell: NONE, minimumStop: NONE });
  assert.deepEqual(edit.copy(tt).stations, tt.stations, 'a copy keeps the stations\' times');
});

test('a stop is given its dwell and its own minimum stop, and the last stop loses its minimum stop when it becomes the last', () => {
  const three = edit.addTrain(timetable(), { number: '3', line: 'a', type: '', deadhead: false, stops: [
    { station: 'X', arrive: NONE, depart: t('09:00') }, { station: 'Y', arrive: t('09:05'), depart: t('09:06') }, { station: 'Z', arrive: t('09:10'), depart: NONE }] });
  const dwelt = edit.setDwell(three, '3', 1, 90);
  assert.deepEqual(dwelt.trains[2].stops[1], { station: 'Y', arrive: t('09:05'), depart: t('09:06:30') });
  assert.throws(() => edit.setDwell(three, '3', 0, 30), 'the first stop has no arrival to stand from');
  const held = edit.setStopMinimum(dwelt, '3', 1, 15);
  assert.deepEqual(held.trains[2].stops[1], { station: 'Y', arrive: t('09:05'), depart: t('09:06:30'), minimumStop: 15 });
  assert.deepEqual(edit.copy(held).trains[2].stops[1], held.trains[2].stops[1], 'a copy keeps a stop\'s minimum stop');
  assert.deepEqual(edit.setStopMinimum(held, '3', 1, NONE).trains[2].stops[1], dwelt.trains[2].stops[1]);
  const shortened = edit.removeStop(held, '3', 2);
  assert.deepEqual(shortened.trains[2].stops[1], { station: 'Y', arrive: t('09:05'), depart: NONE });
});
