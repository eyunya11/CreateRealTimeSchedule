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

test('something that is not a network of this mod, or a newer one, is not read', () => {
  assert.equal(network.read('{"format": "createrealtimeschedule.timetable", "version": 2}').network, null);
  const newer = network.read('{"format": "createrealtimeschedule.network", "version": 3}');
  assert.equal(newer.network, null);
  assert.deepEqual(newer.problems,
    ['version: is 3, but this editor reads networks up to version 2; the editor needs updating to read this one']);
  const bad = network.read(JSON.stringify({ format: 'createrealtimeschedule.network', version: 1, stations: ['A'], legs: [{ from: 'A' }] }));
  assert.equal(bad.network, null);
  assert.deepEqual(bad.problems, ['legs[0]: from and to must be strings', 'legs[0].distance: must be a number, 0 or more']);
});
