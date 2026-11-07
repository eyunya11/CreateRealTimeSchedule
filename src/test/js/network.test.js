'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { script, RESOURCES } = require('./editor.js');

const network = script('network');

const EXAMPLE = fs.readFileSync(path.join(RESOURCES, 'railway', 'example.json'), 'utf8');

test('the network the mod writes is read', () => {
  const result = network.read(EXAMPLE);
  assert.deepEqual(result.problems, []);
  assert.deepEqual(result.network.stations, ['Alpha', 'Beta', 'Gamma']);
  assert.equal(result.network.legs.length, 3);
  const alphaGamma = network.leg(result.network, 'Alpha', 'Gamma');
  assert.equal(alphaGamma.distance, 40);
  assert.deepEqual(alphaGamma.via, ['Beta']);
  assert.equal(alphaGamma.ticks, 133);
});

test('the running time is the ticks rounded up to whole seconds', () => {
  const read = network.read(EXAMPLE).network;
  assert.equal(network.seconds(read, 'Alpha', 'Beta'), 5); // 96 ticks
  assert.equal(network.seconds(read, 'Alpha', 'Gamma'), 7); // 133 ticks
  assert.equal(network.seconds(read, 'Beta', 'Alpha'), null, 'the track runs one way only');
});

test('a leg with no ticks has no running time', () => {
  const read = network.read(JSON.stringify({
    format: 'createrealtimeschedule.network', version: 2, stations: ['A', 'B'], legs: [{ from: 'A', to: 'B', distance: 30 }],
  })).network;
  assert.equal(network.seconds(read, 'A', 'B'), null);
  assert.equal(network.leg(read, 'A', 'B').distance, 30);
});

const TAGGED = fs.readFileSync(path.join(RESOURCES, 'railway', 'example-tags.json'), 'utf8');

test('a network with track tags is read, with the legs and the ways of each tag', () => {
  const result = network.read(TAGGED);
  assert.deepEqual(result.problems, []);
  const read = result.network;
  assert.deepEqual(read.tags, ['express', 'local']);
  assert.deepEqual(network.platforms(read, 'A'), [1, 2, 4, 5]);
  assert.equal(network.leg(read, 'A', 'B').distance, 60);
  assert.equal(network.leg(read, 'A', 'B', 'local').distance, 62, 'the local runs from A on the siding');
  assert.equal(network.leg(read, 'A', 'B', 'freight').distance, 60, 'a tag the world has none of has the legs of otherTag');
  assert.equal(network.leg(read, 'Depot', 'A', 'freight'), null);
  assert.equal(network.seconds(read, 'A', 'B', 'local'), 9); // 172 ticks
  assert.equal(network.untaggedLegs(read).length, 3);
  assert.equal(network.way(read, 0, 'A', 'local'), 2);
  assert.equal(network.way(read, 0, 'A', 'express'), 1);
  assert.equal(network.way(read, 0, 'A', 'freight'), -1);
  assert.equal(network.way(read, 1, 'B', 'freight'), 3, 'a train leaves the section it stands in');
});

test('in a network of before the tags, every tag is as none, and each station is one platform', () => {
  const read = network.read(EXAMPLE).network;
  assert.deepEqual(network.platforms(read, 'Beta'), [1]);
  assert.equal(network.way(read, 0, 'Gamma', 'local'), 2);
  assert.equal(network.way(read, 1, 'Alpha', ''), -1, 'the track runs one way only');
  assert.equal(network.seconds(read, 'Alpha', 'Beta', 'local'), 5);
  assert.equal(network.untaggedLegs(read).length, 3);
});

test('something that is not a network of this mod, or a newer one, is not read', () => {
  assert.equal(network.read('{"format": "createrealtimeschedule.timetable", "version": 2}').network, null);
  const newer = network.read('{"format": "createrealtimeschedule.network", "version": 6}');
  assert.equal(newer.network, null);
  assert.deepEqual(newer.problems,
    ['version: is 6, but this editor reads networks up to version 5; the editor needs updating to read this one']);
  const bad = network.read(JSON.stringify({ format: 'createrealtimeschedule.network', version: 1, stations: ['A'], legs: [{ from: 'A' }] }));
  assert.equal(bad.network, null);
  assert.deepEqual(bad.problems, ['legs[0]: from and to must be strings', 'legs[0].distance: must be a number, 0 or more']);
});

test('a network with track tags whose ways come to no platform it has is not read', () => {
  const root = JSON.parse(TAGGED);
  const count = root.platforms.length;
  const read = changed => network.read(JSON.stringify(Object.assign({}, root, changed))).problems;
  assert.deepEqual(read({ ways: [{ from: count, to: { A: 1 } }] }),
    ['ways[0]: must have a platform from and the platforms it comes to, each one of platforms']);
  assert.deepEqual(read({ ways: [{ from: 0, to: { A: count } }, { from: 0, to: { A: -1 } }, { from: 0, to: { A: 1.5 } }] }), [0, 1, 2].map(i =>
    'ways[' + i + ']: must have a platform from and the platforms it comes to, each one of platforms'));
  assert.deepEqual(read({ ways: [{ from: 0, to: { A: count - 1 } }] }), []);
  assert.deepEqual(read({ ways: [{ from: 0, otherTag: 'yes', to: { A: 1 } }] }), ['ways[0].otherTag: must be true or false']);
  assert.deepEqual(read({ legs: [{ from: 'A', to: 'B', distance: 1, otherTag: 1 }] }), ['legs[0].otherTag: must be true or false']);
});

/**
 * A 20 km/h sign 90 blocks into the 100 from A to B, and from B to C the 1000 blocks of the shared
 * travel cases: a 40 km/h sign at 400 and a release at 700 (1293 ticks, 2340 under 20 km/h).
 */
function signed(train) {
  return network.read(JSON.stringify({
    format: 'createrealtimeschedule.network', version: 2, train, stations: ['A', 'B', 'C'],
    legs: [
      { from: 'A', to: 'B', distance: 100, signs: [{ at: 90, kmh: 20 }], ticks: 200 },
      { from: 'B', to: 'C', distance: 1000, signs: [{ at: 400, kmh: 40 }, { at: 700, release: true }], ticks: 1293 },
      { from: 'C', to: 'A', distance: 500, curves: [[100, 150]], ticks: 600 },
    ],
  }));
}

const TRAIN = { topSpeed: 28, turnSpeed: 14, acceleration: 3, throttle: 1 };

test('a train still under the limit of a sign it passed before is slower until the next sign', () => {
  const read = signed(TRAIN);
  assert.deepEqual(read.problems, []);
  assert.equal(network.limitAfter(read.network, 'A', 'B', '', null), 20);
  assert.equal(network.seconds(read.network, 'B', 'C'), 65, '1293 ticks');
  assert.equal(network.seconds(read.network, 'B', 'C', '', null), 65);
  assert.equal(network.seconds(read.network, 'B', 'C', '', 20), 117, '2340 ticks, as the mod has it');
  assert.equal(network.limitAfter(read.network, 'B', 'C', '', 20), null, 'released before C');
  assert.equal(network.limitAfter(read.network, 'C', 'A', '', 20), 20, 'no sign: the limit goes on');
  assert.ok(network.seconds(read.network, 'C', 'A', '', 20) > network.seconds(read.network, 'C', 'A'));
  assert.equal(network.limitAfter(read.network, 'B', 'A', '', 30), 30, 'a way the network does not have keeps the limit');
  assert.equal(network.seconds(read.network, 'B', 'A', '', 30), null);
});

test('the ticks under a limit are worked out once for each leg and limit', () => {
  const travel = script('travel');
  const ticks = travel.ticks;
  let runs = 0;
  travel.ticks = (...args) => {
    runs++;
    return ticks(...args);
  };
  try {
    const read = signed(TRAIN);
    for (let k = 0; k < 3; k++) {
      assert.equal(network.seconds(read.network, 'B', 'C', '', 20), 117);
      assert.ok(network.seconds(read.network, 'B', 'C', '', 30) < 117);
      assert.equal(network.seconds(read.network, 'B', 'C', '', null), 65, 'no limit, no run');
    }
    assert.equal(runs, 4, 'with and without the limit, for each of the two limits');
    // Another network read from the same text has legs of its own.
    assert.equal(network.seconds(signed(TRAIN).network, 'B', 'C', '', 20), 117);
    assert.equal(runs, 6);
  } finally {
    travel.ticks = ticks;
  }
});

test('a network with no train that moves takes the ticks of its legs as they are', () => {
  for (const train of [undefined, { topSpeed: 0, turnSpeed: 14, acceleration: 3, throttle: 1 }, { ...TRAIN, throttle: 2 }]) {
    const read = signed(train);
    assert.deepEqual(read.problems, []);
    assert.equal(read.network.train, null);
    assert.equal(network.seconds(read.network, 'B', 'C', '', 20), 65);
  }
});

test('a leg with curves or signs that are not as the mod writes them is not read', () => {
  const read = legs => network.read(JSON.stringify({ format: 'createrealtimeschedule.network', version: 2, stations: ['A', 'B'], legs })).problems;
  const curves = '.curves: must be an array of [start, end], each a number and the end not before the start';
  const signs = '.signs: must be an array of objects with a number at and either a kmh of 1 or more or release: true';
  assert.deepEqual(read([{ from: 'A', to: 'B', distance: 1, curves: [[2, 1]] }, { from: 'A', to: 'B', distance: 1, curves: [1, 2] }]),
    ['legs[0]' + curves, 'legs[1]' + curves]);
  assert.deepEqual(read([{ from: 'A', to: 'B', distance: 1, signs: [{ at: 1, kmh: 0 }] }, { from: 'A', to: 'B', distance: 1, signs: [{ at: 1 }] },
    { from: 'A', to: 'B', distance: 1, signs: [{ at: 1, kmh: 40, release: true }] }, { from: 'A', to: 'B', distance: 1, signs: {} }]),
    [0, 1, 2, 3].map(i => 'legs[' + i + ']' + signs));
  assert.deepEqual(read([{ from: 'A', to: 'B', distance: 1, curves: [], signs: [{ at: -2, kmh: 5 }, { at: 3, release: true }] }]), []);
});

const SIGNALS = fs.readFileSync(path.join(RESOURCES, 'railway', 'example-signals.json'), 'utf8');

test('the signal blocks of a network of version 5 are read', () => {
  const result = network.read(SIGNALS);
  assert.deepEqual(result.problems, []);
  const blocks = result.network.blocks;
  assert.equal(blocks.count, 3);
  assert.deepEqual(blocks.crossings, []);
  assert.deepEqual(blocks.signals, [{ block: 1, next: [1], lights: [5], cross: false }, { block: 2, next: [], lights: [], cross: true }]);
  assert.deepEqual(blocks.speeds, { restricted: 25, approach: 45, reduced: 75, sixProceed: 130 });
  const alphaGamma = network.leg(result.network, 'Alpha', 'Gamma');
  assert.equal(alphaGamma.block, 0);
  assert.deepEqual(alphaGamma.signals, [{ at: 15, signal: 0 }, { at: 50, signal: 1 }]);
  assert.equal(result.network.map, null, 'version 5 may come without the map');
});

test('a network of before version 5 has no blocks, and legs with none', () => {
  const read = network.read(EXAMPLE).network;
  assert.equal(read.blocks, null);
  assert.equal(network.leg(read, 'Alpha', 'Beta').block, -1);
  assert.deepEqual(network.leg(read, 'Alpha', 'Beta').signals, []);
});

test('signal blocks that are not as the mod writes them are not read', () => {
  const root = JSON.parse(SIGNALS);
  const read = changed => network.read(JSON.stringify(Object.assign({}, root, changed))).problems;
  assert.deepEqual(read({ blocks: -1 }), ['blocks: must be a whole number, 0 or more']);
  assert.deepEqual(read({ crossings: [[0, 3]] }), ['crossings: must be an array of [block, block], each a number of one of the blocks']);
  assert.deepEqual(read({ signals: [{ block: 3 }] }), ['signals[0]: must be an object with the block it leads into, a number of one of the blocks']);
  assert.deepEqual(read({ signals: [{ block: 0, next: [2], lights: [7], cross: 1 }] }), [
    'signals[0].next: must be an array of the numbers of signals',
    'signals[0].lights: must be an array of the numbers of aspects, 2 to 6',
    'signals[0].cross: must be true or false']);
  assert.deepEqual(read({ lightSpeeds: { restricted: 25 } }),
    ['lightSpeeds: must have restricted, approach, reduced and sixProceed, each a whole number of km/h, 1 or more']);
  const legs = root.legs.slice(0, 1).map(leg => Object.assign({}, leg, { block: 3, signals: [{ at: 1, signal: 2 }] }));
  assert.deepEqual(read({ legs }), ['legs[0].block: must be the number of one of the blocks',
    'legs[0].signals: must be an array of objects with a number at and the number of one of the signals']);
  const noSpeeds = Object.assign({}, root);
  delete noSpeeds.lightSpeeds;
  assert.equal(network.read(JSON.stringify(noSpeeds)).network.blocks.speeds, null, 'no Create Train Next, no speeds');
});
