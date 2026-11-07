'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { script, RESOURCES } = require('./editor.js');

const travel = script('travel');

// The same runs as TravelCasesTest in the mod: the editor has to come to the same tick.
const CASES = JSON.parse(fs.readFileSync(path.join(RESOURCES, 'travel', 'cases.json'), 'utf8')).cases;

test('there are cases', () => {
  assert.ok(CASES.length > 0);
});

for (const kase of CASES)
  test('each run takes the ticks it says: ' + kase.about, () => {
    const start = kase.limitAtStart === undefined ? null : kase.limitAtStart;
    assert.equal(travel.ticks(kase.train, kase.throttle, kase.distance, kase.curves, kase.signs, start), kase.ticks);
    assert.equal(travel.limitAfter(kase.signs, start, kase.distance), kase.limitAfter === undefined ? null : kase.limitAfter,
      'the limit at the stop');
  });

test('a run the train cannot finish has no ticks', () => {
  const spec = { topSpeed: 28, turnSpeed: 14, acceleration: 3 };
  assert.equal(travel.ticks(spec, 0, 100, [], [], null), null, 'no throttle');
  assert.equal(travel.ticks(spec, 1, -1, [], [], null), null);
  assert.equal(travel.ticks({ topSpeed: 28, turnSpeed: 0.01, acceleration: 3 }, 1, 100, [[10, 20]], [], null), null, 'a curve it crawls on');
  assert.equal(travel.ticks(spec, 1, 1e6, [], [], 1), null, 'more than a day at 1 km/h');
});

test('the limit at the stop is that of the last sign up to it, a release leaving none', () => {
  const signs = [{ at: 50, release: true }, { at: 20, kmh: 40 }, { at: 90, kmh: 60 }];
  assert.equal(travel.limitAfter(signs, null, 30), 40);
  assert.equal(travel.limitAfter(signs, 25, 60), null);
  assert.equal(travel.limitAfter(signs, 25, 10), 25, 'no sign yet: the one it came with');
  assert.equal(travel.limitAfter(signs, null, 90), 60, 'a sign at the stop is passed');
  assert.equal(travel.limitAfter(undefined, 30, 100), 30);
});
