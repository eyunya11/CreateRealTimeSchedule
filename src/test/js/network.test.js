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
  const newer = network.read('{"format": "createrealtimeschedule.network", "version": 5}');
  assert.equal(newer.network, null);
  assert.deepEqual(newer.problems,
    ['version: is 5, but this editor reads networks up to version 4; the editor needs updating to read this one']);
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
