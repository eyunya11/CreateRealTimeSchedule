'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { script } = require('./editor.js');

const time = script('time');
const duties = script('duties');
const problems = script('problems');

const NONE = -1;

function t(text) {
  return time.parse(text);
}

function train(number, from, depart, to, arrive) {
  return { number, line: 'l', type: '', deadhead: false, stops: [{ station: from, arrive: NONE, depart: t(depart) }, { station: to, arrive: t(arrive), depart: NONE }] };
}

/** A line from the depot to A and B, trains between A and B, and a duty with nothing in it yet. */
function timetable() {
  return {
    name: '',
    lines: [{ id: 'l', name: 'L', stations: ['Depot', 'A', 'B'], loop: false }],
    trains: [
      train('1', 'A', '08:00', 'B', '08:10'),
      train('2', 'B', '08:20', 'A', '08:30'),
      train('3', 'B', '08:05', 'A', '08:15'),
      train('4', 'B', '09:20', 'A', '09:30'),
      train('5', 'A', '07:00', 'B', '07:10'),
    ],
    duties: [{ id: '1A', depot: 'Depot', trains: [] }],
  };
}

test('a new duty gets the id after the highest', () => {
  assert.equal(duties.nextDutyId(timetable()), '2A');
  assert.equal(duties.nextDutyId(duties.addDuty(timetable(), '7A', 'Depot')), '8A');
  assert.equal(duties.nextDutyId({ name: '', lines: [], trains: [], duties: [] }), '1A');
});

test('the trains that can come next leave from where the last one ended, after it arrived, earliest first', () => {
  let tt = duties.appendTrain(timetable(), '1A', '1');
  assert.deepEqual(duties.candidates(tt, '1A').map(each => each.number), ['2', '4']);
  // A train in a duty already is no candidate.
  tt = duties.appendTrain(duties.addDuty(tt, '2A', 'Depot'), '2A', '2');
  assert.deepEqual(duties.candidates(tt, '1A').map(each => each.number), ['4']);
});

test('a duty with no trains yet can start with the trains that leave its depot', () => {
  const tt = timetable();
  assert.deepEqual(duties.candidates(tt, '1A'), []);
  const fromDepot = duties.setDuty(tt, '1A', { depot: 'A' });
  assert.deepEqual(duties.candidates(fromDepot, '1A').map(each => each.number), ['5', '1']);
});

test('a duty is renamed, and a train taken out of it, and the duty taken out', () => {
  let tt = duties.appendTrain(duties.appendTrain(timetable(), '1A', '1'), '1A', '2');
  tt = duties.setDuty(tt, '1A', { id: '9Z' });
  assert.equal(tt.duties[0].id, '9Z');
  assert.deepEqual(duties.removeTrainAt(tt, '9Z', 0).duties[0].trains, ['2']);
  assert.deepEqual(duties.removeDuty(tt, '9Z').duties, []);
});

/** 30 seconds between any two stations, but none from B to the depot. */
function running(from, to) {
  return from === 'B' && to === 'Depot' ? null : 30;
}

test('deadheads take a duty from its depot and back, the dwell before and after its trains', () => {
  const tt = duties.appendTrain(duties.appendTrain(timetable(), '1A', '1'), '1A', '2');
  const result = duties.addDepotDeadheads(tt, '1A', () => 47, 20, 5);
  assert.deepEqual(result.added, ['D1', 'D2']);
  assert.deepEqual(result.missing, []);
  assert.deepEqual(result.timetable.duties[0].trains, ['D1', '1', '2', 'D2']);
  const out = result.timetable.trains.find(each => each.number === 'D1');
  // Arrive at A by 07:59:40, 20 seconds before train 1 leaves, having left 47 seconds before, on the step.
  assert.deepEqual(out.stops, [{ station: 'Depot', arrive: NONE, depart: t('07:58:50') }, { station: 'A', arrive: t('07:59:40'), depart: NONE }]);
  assert.equal(out.deadhead, true);
  assert.equal(out.line, 'l');
  const back = result.timetable.trains.find(each => each.number === 'D2');
  assert.deepEqual(back.stops, [{ station: 'A', arrive: NONE, depart: t('08:30:20') }, { station: 'Depot', arrive: t('08:31:10'), depart: NONE }]);
  assert.deepEqual(problems.problems(result.timetable), [], 'the duty with its deadheads is one the mod takes');
});

test('no deadhead where the duty is at its depot already, nor where the network has no running time', () => {
  // The depot is A, where train 1 starts: only the way back from B is needed.
  const tt = duties.appendTrain(duties.setDuty(timetable(), '1A', { depot: 'A' }), '1A', '1');
  const result = duties.addDepotDeadheads(tt, '1A', running, 20, 5);
  assert.deepEqual(result.added, ['D1']);
  assert.deepEqual(result.missing, []);
  assert.deepEqual(result.timetable.duties[0].trains, ['1', 'D1']);
  const home = duties.addDepotDeadheads(duties.appendTrain(tt, '1A', '2'), '1A', running, 20, 5);
  assert.deepEqual(home.added, [], 'a duty that starts and ends at its depot needs none');
  const fromDepot = duties.appendTrain(timetable(), '1A', '1');
  const partly = duties.addDepotDeadheads(fromDepot, '1A', running, 20, 5);
  assert.deepEqual(partly.added, ['D1']);
  assert.deepEqual(partly.missing, [['B', 'Depot']]);
  assert.deepEqual(partly.timetable.duties[0].trains, ['D1', '1']);
});
