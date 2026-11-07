'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { script, RESOURCES } = require('./editor.js');

const traffic = script('traffic');
const timetables = script('timetable');

// The same cases as TrafficCasesTest in the mod: the editor has to come to the same trips.
const CASES = JSON.parse(fs.readFileSync(path.join(RESOURCES, 'traffic', 'cases.json'), 'utf8'));

function blocks(kase) {
  return { count: kase.count, crossings: kase.crossings, signals: kase.signals, speeds: kase.lightSpeeds || null };
}

test('there are cases of both kinds', () => {
  assert.ok(CASES.runs.length > 0);
  assert.ok(CASES.timetables.length > 0);
  assert.ok(CASES.runs.some(kase => kase.trips.some(trips => trips.some(trip => trip.waits.length > 0))), 'one with a wait at red');
});

for (const kase of CASES.runs)
  test('the trains make the trips the case says: ' + kase.about, () => {
    const ran = traffic.run(blocks(kase.blocks), kase.runs);
    assert.equal(ran.finished, true);
    assert.deepEqual(ran.trips, kase.trips);
  });

for (const kase of CASES.timetables)
  test('the trains of the timetable make the trips the case says: ' + kase.about, () => {
    const read = timetables.read(JSON.stringify(kase.timetable));
    assert.deepEqual(read.problems, []);
    const legOf = (from, to, tag) => kase.legs.find(leg => leg.from === from && leg.to === to && leg.tag === tag) || null;
    const ran = traffic.ofTimetable(read.timetable, legOf, blocks(kase.blocks), kase.spec, kase.defaults);
    assert.equal(ran.finished, true);
    const trips = [];
    for (const train of read.timetable.trains)
      for (let stop = 0; stop + 1 < train.stops.length; stop++) {
        const trip = ran.trip(train.number, stop);
        if (trip !== null)
          trips.push({ train: train.number, stop, trip, signalTicks: ran.signalTicks(train.number, stop) });
      }
    assert.deepEqual(trips, kase.trips);
    for (const each of kase.trips)
      assert.equal(ran.ticks(each.train, each.stop), each.trip.arrived === -1 ? null : each.trip.arrived - each.trip.departed);
  });

test('a timetable on a network without signal blocks runs as on an empty line', () => {
  const kase = CASES.timetables[0];
  const read = timetables.read(JSON.stringify(kase.timetable));
  const legOf = (from, to, tag) => {
    const leg = kase.legs.find(each => each.from === from && each.to === to && each.tag === tag);
    return leg === undefined ? null : Object.assign({}, leg, { signals: [], block: -1 });
  };
  const ran = traffic.ofTimetable(read.timetable, legOf, { count: 0, crossings: [], signals: [], speeds: null }, kase.spec, kase.defaults);
  for (const train of read.timetable.trains) {
    const trip = ran.trip(train.number, 0);
    assert.equal(trip.arrived - trip.departed, trip.free, 'train ' + train.number);
  }
});

test('a simulation that would take too long gives up, and says nothing of any train', () => {
  const kase = CASES.runs.find(each => each.about === 'a train behind a moving one');
  const ran = traffic.run(blocks(kase.blocks), kase.runs, 100);
  assert.equal(ran.finished, false);
  assert.equal(traffic.run(blocks(kase.blocks), kase.runs, 1e9).finished, true);
  assert.equal(traffic.MAX_STEPS, 5000000, 'Traffic.MAX_STEPS in the mod');
});
