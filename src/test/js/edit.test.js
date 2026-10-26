'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { script } = require('./editor.js');

const time = script('time');
const edit = script('edit');
const problems = script('problems');

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

test('a train is marked never to be cancelled, and keeps the mark through other changes', () => {
  const kept = edit.setTrain(timetable(), '1', { keep: true });
  assert.equal(kept.trains[0].keep, true);
  assert.equal('keep' in kept.trains[1], false, 'the other train is not marked');
  // Any change copies the timetable, and the copy must not lose the mark.
  assert.equal(edit.copy(kept).trains[0].keep, true);
  assert.equal(edit.setTrain(kept, '1', { type: 'rapid' }).trains[0].keep, true);
  assert.equal(edit.shiftTrain(kept, '1', 60).trains[0].keep, true);
  const unmarked = edit.setTrain(kept, '1', { keep: false });
  assert.equal('keep' in unmarked.trains[0], false, 'a train that may be cancelled has no keep');
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

test('a stop drawn already is given its dwell, and the stops after it move with it', () => {
  let stops = edit.drawStop([], 'X', t('08:00'), null, 20, 5);
  stops = edit.drawStop(stops, 'Y', t('08:01'), 60, 20, 5);
  stops = edit.drawStop(stops, 'Z', t('08:03'), 90, 20, 5);
  // Y stood 20 seconds and Z was arrived at at 08:03, when it was clicked; Y now stands 25 seconds more.
  const longer = edit.setDrawnDwell(stops, 1, 45);
  assert.deepEqual(longer, [
    { station: 'X', arrive: NONE, depart: t('08:00') },
    { station: 'Y', arrive: t('08:01'), depart: t('08:01:45') },
    { station: 'Z', arrive: t('08:03:25'), depart: t('08:03:45') }]);
  assert.equal(stops[1].depart, t('08:01:20'), 'the stops given are left as they were');
  assert.deepEqual(edit.setDrawnDwell(longer, 2, 0)[2], { station: 'Z', arrive: t('08:03:25'), depart: t('08:03:25') });
  assert.throws(() => edit.setDrawnDwell(stops, 0, 30), 'the first stop is not stood at');
});

test('a stop stretched stands longer or shorter, and the stops after it move by as much', () => {
  const three = edit.addTrain(timetable(), { number: '3', line: 'a', type: '', deadhead: false, stops: [
    { station: 'X', arrive: NONE, depart: t('09:00') }, { station: 'Y', arrive: t('09:05'), depart: t('09:06') }, { station: 'Z', arrive: t('09:10'), depart: NONE }] });
  const longer = edit.stretchStop(three, '3', 1, 30);
  assert.deepEqual(longer.trains[2].stops.map(stop => [stop.arrive, stop.depart]),
    [[NONE, t('09:00')], [t('09:05'), t('09:06:30')], [t('09:10:30'), NONE]]);
  const none = edit.stretchStop(three, '3', 1, -600);
  assert.deepEqual(none.trains[2].stops.map(stop => [stop.arrive, stop.depart]),
    [[NONE, t('09:00')], [t('09:05'), t('09:05')], [t('09:09'), NONE]], 'it never stands less than nothing');
  assert.deepEqual(edit.stretchStop(three, '3', 0, 30).trains[2].stops, three.trains[2].stops, 'the first stop is not stood at');
  assert.deepEqual(three.trains[2].stops[1], { station: 'Y', arrive: t('09:05'), depart: t('09:06') }, 'the timetable given is left as it was');
});

test('a click on the row of the stop just arrived at says when the train leaves it, and a click elsewhere goes on', () => {
  let drawn = edit.drawClick([], false, 'X', t('08:00'), null, 20, 5);
  assert.deepEqual(drawn, { stops: [{ station: 'X', arrive: NONE, depart: t('08:00') }], leaving: false, left: false }, 'the first stop is only left');
  // 60 seconds of running time from X: arrived at 08:01, and to be left by the next click.
  drawn = edit.drawClick(drawn.stops, drawn.leaving, 'Y', t('08:00:30'), 60, 20, 5);
  assert.equal(drawn.leaving, true);
  assert.deepEqual(drawn.stops[1], { station: 'Y', arrive: t('08:01'), depart: t('08:01:20') }, 'until then it stands the dwell');
  // Clicked on Y's row at 08:01:47: the train leaves Y then, to the step.
  const left = edit.drawClick(drawn.stops, drawn.leaving, 'Y', t('08:01:47'), 0, 20, 5);
  assert.deepEqual(left, { stops: [drawn.stops[0], { station: 'Y', arrive: t('08:01'), depart: t('08:01:45') }], leaving: false, left: true });
  // Clicked before the train arrives: it leaves as it arrives.
  assert.equal(edit.drawClick(drawn.stops, drawn.leaving, 'Y', t('08:00:10'), 0, 20, 5).stops[1].depart, t('08:01'));
  // Clicked on another row instead: the train leaves Y after the dwell and goes on to Z.
  const on = edit.drawClick(drawn.stops, drawn.leaving, 'Z', t('08:02'), 30, 20, 5);
  assert.deepEqual(on.stops.map(stop => [stop.station, stop.arrive, stop.depart]),
    [['X', NONE, t('08:00')], ['Y', t('08:01'), t('08:01:20')], ['Z', t('08:02'), t('08:02:20')]]);
  assert.equal(on.leaving, true);
  // Once Y is left, a click on its row again changes nothing.
  assert.deepEqual(edit.drawClick(left.stops, left.leaving, 'Y', t('08:03'), 0, 20, 5), { stops: left.stops, leaving: false, left: false });
});

test('the times of a station no line has any more go with the line, and stay while another line has it', () => {
  const tt = edit.setStations(timetable(), [{ name: 'X', dwell: 30, minimumStop: NONE }, { name: 'Y', dwell: NONE, minimumStop: 10 }]);
  // Y is on both lines: it keeps its times when line a goes; X, only on line a, does not.
  assert.deepEqual(edit.removeLine(tt, 'a').stations, [{ name: 'Y', dwell: NONE, minimumStop: 10 }]);
  // Line b without Y: Y is still on line a.
  assert.deepEqual(edit.putLine(tt, 'b', { id: 'b', name: 'B Line', stations: ['Z', 'W'] }).stations.map(station => station.name), ['X', 'Y']);
  // Line a without X: X goes.
  const without = edit.putLine(tt, 'a', { id: 'a', name: 'A Line', stations: ['Y', 'V'] });
  assert.deepEqual(without.stations.map(station => station.name), ['Y']);
  assert.deepEqual(problems.problems(without).filter(problem => problem.startsWith('station')), [], 'no station is left on no line');
});

/** Train 3, X 09:00 to Y (09:05 to 09:06) to Z 09:10, with the track's running times: 4 minutes from X to Y, 3 from Y to Z. */
function threeWithTrack() {
  const tt = edit.addTrain(timetable(), { number: '3', line: 'a', type: '', deadhead: false, stops: [
    { station: 'X', arrive: NONE, depart: t('09:00') }, { station: 'Y', arrive: t('09:05'), depart: t('09:06') }, { station: 'Z', arrive: t('09:10'), depart: NONE }] });
  const running = (from, to) => (from === 'X' && to === 'Y' ? 240 : from === 'Y' && to === 'Z' ? 180 : null);
  return { tt, running };
}

test('a stop dragged stops where the track\'s running time to the stops either side runs out', () => {
  const { tt, running } = threeWithTrack();
  assert.deepEqual(edit.stopLimits(tt.trains[2].stops, 1, running), { earliest: t('09:04'), latest: t('09:07') });
  const times = next => next.trains[2].stops[1];
  // Two minutes earlier would come in at 09:03, a minute short of the running time from X: it stops at 09:04.
  assert.deepEqual(times(edit.moveStop(tt, '3', 1, -120, running)), { station: 'Y', arrive: t('09:04'), depart: t('09:05') });
  assert.deepEqual(times(edit.moveStop(tt, '3', 1, 300, running)), { station: 'Y', arrive: t('09:06'), depart: t('09:07') });
  assert.deepEqual(times(edit.moveStop(tt, '3', 1, 30, running)), { station: 'Y', arrive: t('09:05:30'), depart: t('09:06:30') }, 'within the limits it moves freely');
  // Without the track, a stop still stays after the one before is left.
  assert.deepEqual(times(edit.moveStop(tt, '3', 1, -600)), { station: 'Y', arrive: t('09:00:01'), depart: t('09:01:01') });
  // A stop already too close is not moved closer, but may move away.
  const close = edit.moveStop(tt, '3', 1, -120);
  assert.equal(times(edit.moveStop(close, '3', 1, -10, running)).arrive, t('09:03'));
  assert.equal(times(edit.moveStop(close, '3', 1, 20, running)).arrive, t('09:03:20'));
});

test('a time typed into the table is held to the running time and to the stop\'s other time', () => {
  const { tt, running } = threeWithTrack();
  const stop = (next, i) => next.trains[2].stops[i];
  assert.equal(stop(edit.setStopTime(tt, '3', 1, 'arrive', t('09:02'), running), 1).arrive, t('09:04'), 'no sooner than the running time from X');
  assert.equal(stop(edit.setStopTime(tt, '3', 1, 'arrive', t('09:08'), running), 1).arrive, t('09:06'), 'no later than it leaves');
  assert.equal(stop(edit.setStopTime(tt, '3', 1, 'depart', t('09:09'), running), 1).depart, t('09:07'), 'no later than the running time to Z');
  assert.equal(stop(edit.setStopTime(tt, '3', 1, 'depart', t('09:04'), running), 1).depart, t('09:05'), 'no sooner than it arrives');
  assert.equal(stop(edit.setStopTime(tt, '3', 2, 'arrive', t('09:08'), running), 2).arrive, t('09:09'));
  assert.equal(stop(edit.setStopTime(tt, '3', 0, 'depart', t('09:03'), running), 0).depart, t('09:01'));
  assert.equal(stop(edit.setStopTime(tt, '3', 1, 'arrive', NONE, running), 1).arrive, NONE, 'a time can still be taken out');
  assert.equal(stop(edit.setDwell(tt, '3', 1, 300, running), 1).depart, t('09:07'), 'a dwell no longer than the running time to Z allows');
});

test('the ways trains run faster than the track are found', () => {
  const { tt, running } = threeWithTrack();
  assert.deepEqual(edit.tooFast(tt, running), []);
  const fast = edit.setStopTime(tt, '3', 1, 'arrive', t('09:02'));
  assert.deepEqual(edit.tooFast(fast, running), [{ number: '3', from: 'X', to: 'Y', given: 120, needed: 240 }]);
});

test('an arrival dragged moves alone, so the train stands longer or shorter, between the running time and its departure', () => {
  const { tt, running } = threeWithTrack();
  const y = next => next.trains[2].stops[1];
  assert.deepEqual(y(edit.moveArrival(tt, '3', 1, -30, running)), { station: 'Y', arrive: t('09:04:30'), depart: t('09:06') }, 'it leaves as before');
  assert.equal(y(edit.moveArrival(tt, '3', 1, -300, running)).arrive, t('09:04'), 'no sooner than the running time from X');
  assert.equal(y(edit.moveArrival(tt, '3', 1, 300, running)).arrive, t('09:06'), 'no later than it leaves');
  assert.deepEqual(edit.moveArrival(tt, '3', 1, 30, running).trains[2].stops[2], tt.trains[2].stops[2], 'the stops after it stay');
  // The last stop has only its arrival, which moves with nothing after it to hold it.
  assert.equal(edit.moveArrival(tt, '3', 2, 600, running).trains[2].stops[2].arrive, t('09:20'));
  // A stop with no arrival time moves as a whole.
  assert.equal(edit.moveArrival(tt, '3', 0, -60, running).trains[2].stops[0].depart, t('08:59'));
});

test('a way dragged moves alone, keeping its running time, so the train stands longer at one end and shorter at the other', () => {
  const { tt, running } = threeWithTrack();
  const times = next => next.trains[2].stops.map(stop => [stop.arrive, stop.depart]);
  // Y to Z, 09:06 to 09:10, half a minute later: Y is stood at longer, Z is come in at later.
  assert.deepEqual(times(edit.moveRun(tt, '3', 1, 30, running)), [[NONE, t('09:00')], [t('09:05'), t('09:06:30')], [t('09:10:30'), NONE]]);
  // No sooner than the train comes in at Y: it leaves as it arrives.
  assert.deepEqual(times(edit.moveRun(tt, '3', 1, -300, running))[1], [t('09:05'), t('09:05')]);
  // X to Y, no later than the train leaves Y.
  assert.deepEqual(times(edit.moveRun(tt, '3', 0, 300, running)).slice(0, 2), [[NONE, t('09:01')], [t('09:06'), t('09:06')]]);
  // From the first stop, which is only left, it may go as early as it likes.
  assert.deepEqual(times(edit.moveRun(tt, '3', 0, -600, running)).slice(0, 2), [[NONE, t('08:50')], [t('08:55'), t('09:06')]]);
});

test('with a step, a drag held at a limit still lands on the step', () => {
  const { tt } = threeWithTrack();
  // 3 minutes 57 from X to Y and 2 minutes 58 from Y to Z, which no step of 5 seconds falls on.
  const running = (from, to) => (from === 'X' && to === 'Y' ? 237 : from === 'Y' && to === 'Z' ? 178 : null);
  const y = next => next.trains[2].stops[1];
  // The arrival at Y no sooner than 09:03:57, so on the step no sooner than 09:04:00.
  assert.equal(y(edit.moveArrival(tt, '3', 1, -300, running, 5)).arrive, t('09:04'));
  assert.equal(y(edit.moveArrival(tt, '3', 1, -300, running)).arrive, t('09:03:57'), 'without a step, the limit itself');
  // Y moved as a whole: it leaves no later than 09:07:02, so it comes in no later than 09:06:00 on the step.
  assert.deepEqual(y(edit.moveStop(tt, '3', 1, 600, running, 5)), { station: 'Y', arrive: t('09:06'), depart: t('09:07') });
  // The way from Y: Z is come in at with no stop after to hold it; Y is left no sooner than it arrives.
  assert.equal(y(edit.moveRun(tt, '3', 1, -600, running, 5)).depart, t('09:05'));
  // A stop stretched shorter than nothing stands no time, on the step.
  const offStep = edit.setStopTime(tt, '3', 1, 'arrive', t('09:05:02'));
  assert.equal(y(edit.stretchStop(offStep, '3', 1, -600, 5)).depart, t('09:05:05'), 'rounded up to the step, the arrival being off it');
});

test('a drag lands the time it takes hold of on the step, whichever part of the train it is', () => {
  const { tt } = threeWithTrack();
  // Off the step: X leaves 09:00:03, Y is come in at 09:05:07 and left 09:06:01.
  let off = edit.setStopTime(tt, '3', 0, 'depart', t('09:00:03'));
  off = edit.setStopTime(off, '3', 1, 'arrive', t('09:05:07'));
  off = edit.setStopTime(off, '3', 1, 'depart', t('09:06:01'));
  const running = (from, to) => (from === 'X' && to === 'Y' ? 237 : from === 'Y' && to === 'Z' ? 178 : null);
  const stops = moved => moved.timetable.trains[2].stops;
  assert.equal(edit.draggedTime(off, '3', 1, 'arrive'), t('09:05:07'));
  assert.equal(edit.draggedTime(off, '3', 1, 'run'), t('09:06:01'));
  assert.equal(edit.draggedTime(off, '3', -1, 'stop'), t('09:00:03'));
  // A little earlier: the arrival lands on 09:05:05, with a step of 5.
  assert.equal(stops(edit.drag(off, '3', 1, 'arrive', -3, running, 5))[1].arrive, t('09:05:05'));
  // The departure, and the way from Y, a little later: on 09:06:15 with a step of 15.
  assert.equal(stops(edit.drag(off, '3', 1, 'stretch', 10, running, 15))[1].depart, t('09:06:15'));
  assert.equal(stops(edit.drag(off, '3', 1, 'run', 10, running, 15))[1].depart, t('09:06:15'));
  // The whole train: its first departure on 09:00:15.
  assert.equal(stops(edit.drag(off, '3', -1, 'stop', 10, running, 15))[0].depart, t('09:00:15'));
  // A time on the step, not moved far enough to reach another: nothing changes, and the timetable given comes back.
  const still = edit.drag(tt, '3', 1, 'arrive', 4, running, 15);
  assert.equal(still.delta, 0);
  assert.equal(still.timetable, tt);
  assert.ok(edit.sameStops(off, edit.setStopTime(off, '1', 0, 'depart', t('07:00')), '3'), 'another train changing is no change of this one');
  assert.ok(!edit.sameStops(off, tt, '3'));
});
