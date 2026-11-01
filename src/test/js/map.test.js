'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { script, RESOURCES } = require('./editor.js');

const map = script('map');
const network = script('network');
const timetable = script('timetable');

const CASES = path.join(RESOURCES, 'map', 'cases');
const EXAMPLE_MAP = fs.readFileSync(path.join(RESOURCES, 'railway', 'example-map.json'), 'utf8');

// The cases of the mod's MapSelectionTest: the same tracks shown for the same lines.
test('each shared case shows the tracks it says', () => {
  const files = fs.readdirSync(CASES).filter(file => file.endsWith('.json')).sort();
  assert.ok(files.length > 0, 'there are no cases');
  for (const file of files) {
    const root = JSON.parse(fs.readFileSync(path.join(CASES, file), 'utf8'));
    const read = timetable.read(JSON.stringify(root.timetable));
    assert.deepEqual(read.problems, [], file + ': the case\'s timetable');
    for (const one of root.cases)
      assert.deepEqual(map.visible(root.tracks, root.legs, read.timetable, one.lines), one.visible,
        file + ': the tracks shown for the lines ' + JSON.stringify(one.lines));
    assert.deepEqual(map.missing(root.tracks, read.timetable), root.missing, file + ': the keys not on the map');
  }
});

test('with no timetable every track is shown', () => {
  assert.deepEqual(map.visible(['a', 'b'], [], null, ['l']), [0, 1]);
});

test('the map the mod writes is read, with the tracks of each leg', () => {
  const result = network.read(EXAMPLE_MAP);
  assert.deepEqual(result.problems, []);
  const read = result.network.map;
  assert.deepEqual(read.dimensions, ['minecraft:overworld']);
  assert.equal(read.tracks.length, 2);
  assert.deepEqual(read.tracks[1].points, [60.5, 3.5, 66.4, 5.8, 68.7, 8.1, 70.5, 13.5]);
  assert.deepEqual(read.stations.map(station => station.name), ['Alpha', 'Beta', 'Gamma']);
  assert.deepEqual(read.signals[0].sides.map(side => side.lamps), [['G'], ['Y', 'G']]);
  assert.equal(read.signals[0].sides[1].kind, 'light4');
  assert.deepEqual(read.speedLimits.map(sign => [sign.kmh, sign.release]), [[60, false], [0, true]]);
  assert.deepEqual(read.trains[0].carriages[0], { points: [25, 3.5, 16, 3.5], tracks: [0, 0] });
  assert.deepEqual(network.leg(result.network, 'Alpha', 'Gamma').tracks, [0]);
});

test('a network of before version 3 has no map, and its legs no tracks', () => {
  const result = network.read(fs.readFileSync(path.join(RESOURCES, 'railway', 'example.json'), 'utf8'));
  assert.equal(result.network.map, null);
  assert.deepEqual(network.leg(result.network, 'Alpha', 'Beta').tracks, []);
});

test('a map that is not as the mod writes it is not read, and says where', () => {
  const root = JSON.parse(EXAMPLE_MAP);
  root.map.tracks[1].points = [1, 2, 3];
  root.map.stations[0].track = 7;
  root.map.signals[0].sides[0].lamps = ['B'];
  root.map.speedLimits[0].kmh = -5;
  const result = network.read(JSON.stringify(root));
  assert.equal(result.network, null);
  assert.deepEqual(result.problems, [
    'map.tracks[1]: must have a key, the index of its dimension, and points, X and Z in turn, at least two',
    'map.stations[0]: must have the index of its track, and an x and a z',
    'map.signals[0].sides[0]: must have a kind, and lamps of R, Y and G',
    'map.speedLimits[0]: must have a limit in km/h, or be a release',
  ]);
});

test('a leg that runs along a track the map does not have is not read', () => {
  const root = JSON.parse(EXAMPLE_MAP);
  root.legs[0].tracks = [2];
  const result = network.read(JSON.stringify(root));
  assert.equal(result.network, null);
  assert.deepEqual(result.problems, ['legs[0].tracks: must be an array of the indexes of tracks of the map']);
});

test('a view fitted to a box shows it all, in the middle', () => {
  const view = new map.View(400, 200);
  view.fit({ minX: 100, minZ: -50, maxX: 300, maxZ: 50 }, 20);
  // 200 blocks across into 360 pixels, 100 down into 160: the narrower room sets the scale.
  assert.equal(view.scale, 1.6);
  assert.equal(view.x(200), 200);
  assert.equal(view.y(0), 100);
  assert.ok(view.x(100) >= 20 && view.x(300) <= 380);
  assert.ok(view.y(-50) >= 20 - 1e-9 && view.y(50) <= 180 + 1e-9);
});

test('zooming keeps the world under the pointer where it is, and the drawing and the world are each other\'s inverse', () => {
  const view = new map.View(400, 300);
  view.fit({ minX: 0, minZ: 0, maxX: 100, maxZ: 100 }, 0);
  const worldX = view.worldX(123);
  const worldZ = view.worldZ(45);
  view.zoom(2.5, 123, 45);
  assert.ok(Math.abs(view.x(worldX) - 123) < 1e-9);
  assert.ok(Math.abs(view.y(worldZ) - 45) < 1e-9);
  assert.ok(Math.abs(view.worldX(view.x(37.5)) - 37.5) < 1e-9);
  assert.ok(Math.abs(view.worldZ(view.y(-12)) - -12) < 1e-9);
});

test('zooming stops at the least and the most pixels a block may take', () => {
  const view = new map.View(100, 100);
  view.zoom(1e9, 50, 50);
  assert.equal(view.scale, map.MAX_SCALE);
  view.zoom(1e-12, 50, 50);
  assert.equal(view.scale, map.MIN_SCALE);
});

test('a drag moves the world with the pointer', () => {
  const view = new map.View(100, 100);
  const before = view.x(10);
  view.pan(30, -20);
  assert.equal(view.x(10), before + 30);
  assert.equal(view.y(0), -20);
});

test('a resize keeps the middle where it is', () => {
  const view = new map.View(100, 100);
  view.fit({ minX: 0, minZ: 0, maxX: 10, maxZ: 10 }, 0);
  view.resize(300, 50);
  assert.ok(Math.abs(view.worldX(150) - 5) < 1e-9);
  assert.ok(Math.abs(view.worldZ(25) - 5) < 1e-9);
});

test('the box of a dimension is round its tracks', () => {
  const read = network.read(EXAMPLE_MAP).network.map;
  assert.deepEqual(map.bounds(read, 0), { minX: 0.5, minZ: 3.5, maxX: 70.5, maxZ: 13.5 });
  assert.equal(map.bounds(read, 1), null);
});

test('the path of the tracks goes along their points, one line each', () => {
  const read = network.read(EXAMPLE_MAP).network.map;
  const view = new map.View(100, 100);
  assert.equal(map.pathData(read, [0, 1], view), 'M0.5 3.5L60.5 3.5M60.5 3.5L66.4 5.8L68.7 8.1L70.5 13.5');
  view.zoom(2, 0, 0);
  assert.equal(map.pathData(read, [0], view), 'M1 7L121 7');
});
