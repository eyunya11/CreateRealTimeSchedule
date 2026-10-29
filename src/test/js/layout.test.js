'use strict';

// The cases of the mod's LineAxisTest, DiagramModelTest (the planned trains and the waits) and
// DiagramViewTest, so that the editor draws a line as the game's diagram does.

const test = require('node:test');
const assert = require('node:assert/strict');
const { script } = require('./editor.js');

const time = script('time');
const axis = script('axis');
const layout = script('layout');

const NONE = -1;

function leg(from, to, distance, via) {
  return { from, to, distance, via: via || [], ticks: null };
}

function t(text) {
  return time.parse(text);
}

function stop(station, arrive, depart) {
  return { station, arrive: arrive === undefined || arrive === null ? NONE : t(arrive), depart: depart === undefined ? NONE : t(depart) };
}

function line(id, stations, loop) {
  return { id, name: id, stations, loop: !!loop };
}

function segment(fromSecond, fromPosition, toSecond, toPosition) {
  return { fromSecond, fromPosition, toSecond, toPosition };
}

// ------------------------------------------------------------------ the axis (LineAxisTest)

test('the stations are as far apart as the track between them', () => {
  const a = axis.of(line('l', ['A', 'M', 'B']), [leg('A', 'M', 40), leg('M', 'B', 60), leg('A', 'B', 100)]);
  assert.deepEqual(a.stations, ['A', 'M', 'B']);
  assert.deepEqual(a.positions, [0, 40, 100]);
  assert.equal(a.length(), 100);
});

test('the shorter way either way round is taken', () => {
  assert.deepEqual(axis.of(line('l', ['A', 'M']), [leg('A', 'M', 40), leg('M', 'A', 160)]).positions, [0, 40]);
  assert.deepEqual(axis.of(line('l', ['A', 'M']), [leg('M', 'A', 30)]).positions, [0, 30]);
});

test('a gap the track does not join is the mean of the others, and with no track at all the stations are evenly apart', () => {
  assert.deepEqual(axis.of(line('l', ['A', 'M', 'B', 'C']), [leg('A', 'M', 40), leg('B', 'C', 60)]).positions, [0, 40, 90, 150]);
  assert.deepEqual(axis.of(line('l', ['A', 'M', 'B']), []).positions, [0, axis.UNKNOWN_GAP, 2 * axis.UNKNOWN_GAP]);
});

test('a way through another station of the line is no gap', () => {
  const a = axis.of(line('l', ['Depot', 'A', 'B'], true),
    [leg('Depot', 'A', 30), leg('A', 'B', 30), leg('Depot', 'B', 60, ['A']), leg('B', 'Depot', 100)]);
  assert.deepEqual(a.stations, ['Depot', 'A', 'B', 'Depot']);
  assert.deepEqual(a.positions, [0, 30, 60, 160]);
});

function loop() {
  return axis.of(line('l', ['Depot', 'A', 'B', 'C'], true), [leg('Depot', 'A', 30), leg('A', 'B', 30), leg('B', 'C', 30), leg('C', 'Depot', 30)]);
}

test('on a loop line the first station comes again at the bottom, and a way across it is two hops', () => {
  const a = loop();
  assert.deepEqual(a.positions, [0, 30, 60, 90, 120]);
  assert.deepEqual(a.hops('Depot', 'A', true), [{ fromRow: 0, toRow: 1, share: 1 }]);
  assert.deepEqual(a.hops('C', 'Depot', true), [{ fromRow: 3, toRow: 4, share: 1 }]);
  assert.deepEqual(a.hops('Depot', 'C', false), [{ fromRow: 4, toRow: 3, share: 1 }]);
  assert.deepEqual(a.hops('A', 'X', true), []);
  assert.deepEqual(a.hops('C', 'A', true), [{ fromRow: 3, toRow: 4, share: 0.5 }, { fromRow: 0, toRow: 1, share: 0.5 }]);
  const up = a.hops('A', 'B', false);
  assert.deepEqual(up[0], { fromRow: 1, toRow: 0, share: 1 / 3 });
  assert.equal(up[1].fromRow, 4);
  assert.equal(up[1].toRow, 2);
});

test('a train runs the way round that passes fewer stations, or the way the track shows', () => {
  const a = loop();
  assert.ok(a.down(['A', 'B', 'C', 'Depot']));
  assert.ok(!a.down(['C', 'B', 'A']));
  assert.ok(a.down(['C', 'A']), 'two stations either way round goes down');
  const shown = axis.of(line('l', ['Depot', 'A', 'B', 'C'], true), [
    leg('Depot', 'A', 30), leg('A', 'B', 30), leg('B', 'C', 30), leg('C', 'Depot', 30),
    leg('Depot', 'B', 60, ['A']), leg('B', 'A', 90, ['C', 'Depot']), leg('A', 'C', 60, ['Depot'])]);
  assert.equal(shown.trackDown('Depot', 'B'), true);
  assert.equal(shown.trackDown('B', 'A'), true);
  assert.equal(shown.trackDown('A', 'C'), false);
  assert.equal(shown.trackDown('C', 'B'), null);
  assert.deepEqual(axis.of(line('l', ['A', 'B']), [leg('A', 'B', 30)]).ways, []);
});

// ------------------------------------------------------------------ the trains (DiagramModelTest)

const LINE = line('l', ['Depot', 'A', 'M', 'B']);
const AXIS = axis.of(LINE, [leg('Depot', 'A', 20), leg('A', 'M', 40), leg('M', 'B', 60)]);

test('a planned train slopes between stations and is level where it stands', () => {
  const one = layout.plannedTrain(AXIS, { number: '1', line: 'l', type: 'local', deadhead: false,
    stops: [stop('A', null, '08:00'), stop('M', '08:05', '08:06'), stop('B', '08:10')] });
  assert.deepEqual(one.segments, [
    segment(t('08:00'), 20, t('08:05'), 60),
    segment(t('08:05'), 60, t('08:06'), 60),
    segment(t('08:06'), 60, t('08:10'), 120)]);
  assert.equal(one.labelSecond, t('08:00'));
  assert.equal(one.labelPosition, 20);
  assert.deepEqual(one.points.map(p => [p.arriveSecond, p.arrivePosition, p.departSecond, p.departPosition]),
    [[NaN, 20, t('08:00'), 20], [t('08:05'), 60, t('08:06'), 60], [t('08:10'), 120, NaN, 120]]);
});

test('the parts of a planned train say which way or which standing each of its segments draws', () => {
  const one = layout.plannedTrain(AXIS, { number: '1', line: 'l', type: 'local', deadhead: false,
    stops: [stop('A', null, '08:00'), stop('M', '08:05', '08:06'), stop('B', '08:10')] });
  assert.deepEqual(one.parts.map(part => [part.kind, part.stop]), [['run', 0], ['run', 1], ['stand', 1]]);
  assert.deepEqual(one.parts.map(part => part.segments), [[one.segments[0]], [one.segments[2]], [one.segments[1]]]);
});

test('a stop with only a departure is passed through at that time', () => {
  const three = layout.plannedTrain(AXIS, { number: '3', line: 'l', type: '', deadhead: false,
    stops: [stop('A', null, '09:00'), stop('M', null, '09:04'), stop('B', '09:10')] });
  assert.deepEqual(three.segments, [segment(t('09:00'), 20, t('09:04'), 60), segment(t('09:04'), 60, t('09:10'), 120)]);
});

test('a train past midnight runs on past 24 hours', () => {
  const late = layout.plannedTrain(AXIS, { number: '9', line: 'l', type: '', deadhead: false, stops: [stop('A', null, '23:58'), stop('M', '24:03')] });
  assert.deepEqual(late.segments, [segment(t('23:58'), 20, 24 * 3600 + 180, 60)]);
});

test('on a loop line the way back to the first station goes down to its second row', () => {
  const a = axis.of(line('loop', ['Depot', 'A', 'B'], true), [leg('Depot', 'A', 30), leg('A', 'B', 30), leg('B', 'Depot', 40)]);
  const round = layout.plannedTrain(a, { number: '1', line: 'loop', type: '', deadhead: false,
    stops: [stop('A', null, '10:00'), stop('B', '10:02', '10:03'), stop('Depot', '10:05', '10:06'), stop('A', '10:08')] });
  assert.deepEqual(round.segments, [
    segment(t('10:00'), 30, t('10:02'), 60),
    segment(t('10:02'), 60, t('10:03'), 60),
    segment(t('10:03'), 60, t('10:05'), 100),
    segment(t('10:05'), 100, t('10:06'), 100),
    segment(t('10:05'), 0, t('10:06'), 0),
    segment(t('10:06'), 0, t('10:08'), 30)]);
  // Where it stands at the depot it came in at the bottom and goes out from the top.
  assert.equal(round.points[2].arrivePosition, 100);
  assert.equal(round.points[2].departPosition, 0);
});

test('a way across the first station of a loop is drawn to the bottom and on from the top', () => {
  const a = axis.of(line('loop', ['Depot', 'A', 'B', 'C'], true),
    [leg('Depot', 'A', 30), leg('A', 'B', 30), leg('B', 'C', 30), leg('C', 'Depot', 30)]);
  const past = layout.plannedTrain(a, { number: 'X', line: 'loop', type: '', deadhead: true, stops: [stop('C', null, '10:00'), stop('A', '10:04')] });
  assert.deepEqual(past.segments, [segment(t('10:00'), 90, t('10:02'), 120), segment(t('10:02'), 0, t('10:04'), 30)]);
});

test('a deadhead that passes stations goes the way the track goes', () => {
  const a = axis.of(line('loop', ['Depot', 'A', 'B'], true),
    [leg('Depot', 'A', 30), leg('A', 'B', 30), leg('B', 'Depot', 40), leg('Depot', 'B', 60, ['A'])]);
  const out = layout.plannedTrain(a, { number: 'X', line: 'loop', type: '', deadhead: true, stops: [stop('Depot', null, '10:00'), stop('B', '10:02')] });
  assert.deepEqual(out.segments, [segment(t('10:00'), 0, t('10:02'), 60)]);
});

test('only the trains of the line are drawn', () => {
  const timetable = { name: '', lines: [LINE], duties: [], trains: [
    { number: '1', line: 'l', type: '', deadhead: false, stops: [stop('A', null, '08:00'), stop('B', '08:10')] },
    { number: '2', line: 'other', type: '', deadhead: false, stops: [stop('A', null, '08:00'), stop('B', '08:10')] }] };
  assert.deepEqual(layout.plannedTrains(timetable, AXIS).map(train => train.number), ['1']);
});

// ------------------------------------------------------------------ the view (DiagramViewTest)

function view() {
  const v = new layout.View(8 * 3600, 3600);
  v.place(10, 20, 360, 100);
  return v;
}

test('time runs across and the axis down', () => {
  const v = view();
  assert.equal(v.x(8 * 3600), 10);
  assert.equal(v.x(9 * 3600), 370);
  assert.equal(v.second(20), 8 * 3600 + 100);
  assert.equal(v.y(0, 200), 20);
  assert.equal(v.y(100, 200), 70);
  assert.equal(v.position(70, 200), 100);
});

test('zooming keeps the time under the pointer where it is, within the narrowest and widest spans', () => {
  const v = view();
  const under = v.second(100);
  v.zoom(0.5, 100);
  assert.equal(v.span, 1800);
  assert.ok(Math.abs(v.second(100) - under) < 1e-9);
  v.zoom(0.001, 100);
  assert.equal(v.span, layout.MIN_SPAN);
  v.zoom(1000, 100);
  assert.equal(v.span, layout.MAX_SPAN);
});

test('panning right brings earlier times in, and the scale is marked in the finest step that leaves room', () => {
  const v = view();
  v.pan(36);
  assert.equal(v.start(), 8 * 3600 - 360);
  v.centreOn(12 * 3600);
  assert.equal(v.start(), 12 * 3600 - 1800);
  assert.equal(view().step(30), 300);
  assert.equal(view().step(40), 600);
  assert.equal(view().step(10000), 6 * 3600);
  v.show(9 * 3600, 11 * 3600);
  assert.equal(v.start(), 9 * 3600);
  assert.equal(v.end(), 11 * 3600);
});

test('a segment is cut to the time shown', () => {
  const v = view();
  const cut = v.clip(segment(7 * 3600 + 3000, 0, 8 * 3600 + 600, 200), 200);
  assert.deepEqual(cut.map(n => Math.round(n * 1e9) / 1e9), [10, 70, 70, 120]);
  assert.deepEqual(v.clip(segment(9 * 3600, 100, 9 * 3600, 100), 200), [370, 70, 370, 70]);
  assert.equal(v.clip(segment(6 * 3600, 0, 7 * 3600, 200), 200), null);
});

// ------------------------------------------------------------------ the waits (DiagramModelTest)

test('a wait is drawn from where the train waited for runs through the station to where the waiting train leaves', () => {
  // A to S1 30 blocks, S1 to S2 30, S2 to B 40: the express runs through S1 at 08:03 by its share of the way.
  const l = line('l', ['A', 'S1', 'S2', 'B']);
  const a = axis.of(l, [leg('A', 'S1', 30), leg('S1', 'S2', 30), leg('S2', 'B', 40)]);
  const local = { number: '1', line: 'l', type: '', deadhead: false,
    stops: [stop('A', null, '07:58'), Object.assign(stop('S2', '08:03', '08:06'), { waits: [{ train: '3001', at: 'S1', maxWait: NONE }] }), stop('B', '08:15')] };
  const express = { number: '3001', line: 'l', type: '', deadhead: false, stops: [stop('A', null, '08:00'), stop('B', '08:10')] };
  const marks = layout.waitMarks({ name: '', lines: [l], trains: [local, express], duties: [] }, a);
  assert.deepEqual(marks, [{ fromSecond: t('08:03'), fromPosition: 30, toSecond: t('08:06'), toPosition: 60 }]);
});

test('a wait is drawn from the last way the train waited for runs through the station, across the first station of a loop too', () => {
  // X runs through Depot at 09:42 and again at 10:02, on its way from C round to A: the local waits for the second.
  const l = line('loop', ['Depot', 'A', 'B', 'C'], true);
  const a = axis.of(l, [leg('Depot', 'A', 30), leg('A', 'B', 30), leg('B', 'C', 30), leg('C', 'Depot', 30)]);
  const express = { number: 'X', line: 'loop', type: '', deadhead: false,
    stops: [stop('C', null, '09:40'), stop('A', '09:44', '09:50'), stop('C', '09:56', '10:00'), stop('A', '10:04')] };
  const local = { number: '1', line: 'loop', type: '', deadhead: false,
    stops: [stop('A', null, '10:06'), Object.assign(stop('B', '10:07', '10:10'), { waits: [{ train: 'X', at: 'Depot', maxWait: NONE }] }),
      stop('C', '10:14')] };
  const marks = layout.waitMarks({ name: '', lines: [l], trains: [express, local], duties: [] }, a);
  assert.deepEqual(marks, [{ fromSecond: t('10:02'), fromPosition: 120, toSecond: t('10:10'), toPosition: 60 }]);
});

test('a wait for a train that calls at the station is drawn from where it leaves, and one for a train on another line not at all', () => {
  const l = line('l', ['A', 'S2', 'B']);
  const a = axis.of(l, [leg('A', 'S2', 50), leg('S2', 'B', 50)]);
  const local = { number: '1', line: 'l', type: '', deadhead: false,
    stops: [stop('A', null, '07:58'), Object.assign(stop('S2', '08:03', '08:06'), { waits: [{ train: '2', at: '', maxWait: NONE }, { train: '9', at: '', maxWait: NONE }] }),
      stop('B', '08:15')] };
  const other = { number: '2', line: 'l', type: '', deadhead: false, stops: [stop('A', null, '07:55'), stop('S2', '08:00', '08:04'), stop('B', '08:12')] };
  const elsewhere = { number: '9', line: 'm', type: '', deadhead: false, stops: [stop('S2', null, '08:00'), stop('Z', '08:05')] };
  const timetable = { name: '', lines: [l, line('m', ['S2', 'Z'])], trains: [local, other, elsewhere], duties: [] };
  assert.deepEqual(layout.waitMarks(timetable, a), [{ fromSecond: t('08:04'), fromPosition: 50, toSecond: t('08:06'), toPosition: 50 }]);
});
