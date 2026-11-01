/*
 * The track network as /rtnetwork export and /rteditor copy network write it (NetworkJson in the mod): the
 * stations, and the legs between them with their length and the ticks a train takes. The editor
 * takes the running time between two stations from it, and the distances for the diagram's axis.
 * From version 3 it has the map of the track too (TrackMapJson in the mod), with the tracks each leg
 * runs along; a network of before has none (map null).
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory();
  else
    (root.rt = root.rt || {}).network = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /** The format member of a network in JSON. */
  const FORMAT = 'createrealtimeschedule.network';

  /** The newest version of the JSON the editor reads. Version 2 added the signs of a leg, and version 3 the map. */
  const VERSION = 3;

  /** The colours a lamp of a signal may have, as the map writes them. */
  const LAMPS = ['R', 'Y', 'G'];

  const TICKS_PER_SECOND = 20;

  function isObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  function isStrings(value) {
    return Array.isArray(value) && value.every(item => typeof item === 'string');
  }

  function isNumber(value) {
    return typeof value === 'number' && Number.isFinite(value);
  }

  function isIndex(value, count) {
    return Number.isInteger(value) && value >= 0 && value < count;
  }

  /** The map of a network of version 3, or null after saying in problems what is wrong with it. */
  function readMap(map, problems) {
    if (!isObject(map)) {
      problems.push('map: must be an object');
      return null;
    }
    const before = problems.length;
    const list = (key) => {
      if (Array.isArray(map[key]))
        return map[key];
      problems.push('map.' + key + ': must be an array');
      return [];
    };
    if (!isStrings(map.dimensions))
      problems.push('map.dimensions: must be an array of strings');
    const dimensions = isStrings(map.dimensions) ? map.dimensions.slice() : [];
    const tracks = list('tracks').map((track, i) => {
      if (!isObject(track) || typeof track.key !== 'string' || !isIndex(track.dimension, dimensions.length)
          || !Array.isArray(track.points) || track.points.length < 4 || track.points.length % 2 !== 0
          || !track.points.every(isNumber)) {
        problems.push('map.tracks[' + i + ']: must have a key, the index of its dimension, and points, X and Z in turn, at least two');
        return null;
      }
      return { key: track.key, dimension: track.dimension, points: track.points.slice() };
    });
    const onTrack = (thing, at) => {
      if (!isObject(thing) || !isIndex(thing.track, tracks.length) || !isNumber(thing.x) || !isNumber(thing.z)) {
        problems.push(at + ': must have the index of its track, and an x and a z');
        return false;
      }
      return true;
    };
    const direction = (thing, at) => {
      if (!isNumber(thing.dx) || !isNumber(thing.dz)) {
        problems.push(at + ': must have a direction, dx and dz');
        return false;
      }
      return true;
    };
    const stations = list('stations').map((station, i) => {
      const at = 'map.stations[' + i + ']';
      if (!onTrack(station, at))
        return null;
      if (typeof station.name !== 'string') {
        problems.push(at + '.name: must be a string');
        return null;
      }
      return { name: station.name, track: station.track, x: station.x, z: station.z };
    });
    const signals = list('signals').map((signal, i) => {
      const at = 'map.signals[' + i + ']';
      if (!onTrack(signal, at))
        return null;
      if (!Array.isArray(signal.sides)) {
        problems.push(at + '.sides: must be an array');
        return null;
      }
      const sides = signal.sides.map((side, j) => {
        const where = at + '.sides[' + j + ']';
        if (!isObject(side) || !direction(side, where))
          return null;
        if (typeof side.kind !== 'string' || !Array.isArray(side.lamps) || !side.lamps.every(lamp => LAMPS.includes(lamp))) {
          problems.push(where + ': must have a kind, and lamps of R, Y and G');
          return null;
        }
        return { dx: side.dx, dz: side.dz, kind: side.kind, lamps: side.lamps.slice() };
      });
      return { track: signal.track, x: signal.x, z: signal.z, sides };
    });
    const speedLimits = list('speedLimits').map((sign, i) => {
      const at = 'map.speedLimits[' + i + ']';
      if (!onTrack(sign, at) || !direction(sign, at))
        return null;
      const release = sign.release === true;
      if (!release && !(Number.isInteger(sign.kmh) && sign.kmh > 0)) {
        problems.push(at + ': must have a limit in km/h, or be a release');
        return null;
      }
      return { track: sign.track, x: sign.x, z: sign.z, dx: sign.dx, dz: sign.dz, kmh: release ? 0 : sign.kmh, release };
    });
    const trains = list('trains').map((train, i) => {
      const at = 'map.trains[' + i + ']';
      if (!isObject(train) || typeof train.name !== 'string' || !Array.isArray(train.carriages)) {
        problems.push(at + ': must have a name and carriages');
        return null;
      }
      const carriages = train.carriages.map((carriage, j) => {
        if (!isObject(carriage) || !Array.isArray(carriage.points) || carriage.points.length !== 4 || !carriage.points.every(isNumber)
            || !Array.isArray(carriage.tracks) || carriage.tracks.length !== 2 || !carriage.tracks.every(t => isIndex(t, tracks.length))) {
          problems.push(at + '.carriages[' + j + ']: must have the points of its two ends, and the indexes of their tracks');
          return null;
        }
        return { points: carriage.points.slice(), tracks: carriage.tracks.slice() };
      });
      return { name: train.name, carriages };
    });
    if (problems.length > before)
      return null;
    return { dimensions, tracks, stations, signals, speedLimits, trains };
  }

  /**
   * Reads a network from its JSON text.
   *
   * @returns {{network: ?{stations: string[], legs: object[]}, problems: string[]}} the network, or
   *   null if anything is wrong with the text, and what is
   */
  function read(text) {
    let root;
    try {
      root = JSON.parse(text);
    } catch (e) {
      return { network: null, problems: ['not valid JSON: ' + e.message] };
    }
    if (!isObject(root))
      return { network: null, problems: ['the network must be a JSON object'] };
    if (root.format !== FORMAT)
      return { network: null, problems: ['format: must be "' + FORMAT + '"; this is not a track network of this mod'] };
    if (typeof root.version !== 'number' || !Number.isInteger(root.version) || root.version < 1)
      return { network: null, problems: ['version: must be a whole number, 1 or more'] };
    if (root.version > VERSION)
      return {
        network: null,
        problems: ['version: is ' + root.version + ', but this editor reads networks up to version ' + VERSION
          + '; the editor needs updating to read this one'],
      };

    const problems = [];
    const map = root.version >= 3 ? readMap(root.map, problems) : null;
    // A map that could not be read has said so; the legs are not held to it as well.
    const trackCount = map === null ? Infinity : map.tracks.length;
    if (!isStrings(root.stations))
      problems.push('stations: must be an array of strings');
    if (!Array.isArray(root.legs))
      problems.push('legs: must be an array');
    const legs = [];
    (Array.isArray(root.legs) ? root.legs : []).forEach((leg, i) => {
      const at = 'legs[' + i + ']';
      if (!isObject(leg)) {
        problems.push(at + ': must be an object');
        return;
      }
      if (typeof leg.from !== 'string' || typeof leg.to !== 'string')
        problems.push(at + ': from and to must be strings');
      if (typeof leg.distance !== 'number' || !(leg.distance >= 0))
        problems.push(at + '.distance: must be a number, 0 or more');
      if (leg.via !== undefined && !isStrings(leg.via))
        problems.push(at + '.via: must be an array of strings');
      if (leg.ticks !== undefined && (!Number.isInteger(leg.ticks) || leg.ticks < 0))
        problems.push(at + '.ticks: must be a whole number, 0 or more');
      if (leg.tracks !== undefined && (!Array.isArray(leg.tracks) || !leg.tracks.every(t => isIndex(t, trackCount))))
        problems.push(at + '.tracks: must be an array of the indexes of tracks of the map');
      legs.push({
        from: leg.from,
        to: leg.to,
        distance: leg.distance,
        via: leg.via === undefined ? [] : leg.via,
        ticks: leg.ticks === undefined ? null : leg.ticks,
        tracks: Array.isArray(leg.tracks) ? leg.tracks.slice() : [],
      });
    });
    if (problems.length > 0)
      return { network: null, problems };
    return { network: { stations: root.stations.slice(), legs, map }, problems: [] };
  }

  /** The leg from one station to another, or null if the track does not take a train there. */
  function leg(network, from, to) {
    for (const each of network.legs)
      if (each.from === from && each.to === to)
        return each;
    return null;
  }

  /**
   * The seconds a train takes from one station to another, its ticks rounded up to the next whole
   * second; null if the network has no running time for the way.
   */
  function seconds(network, from, to) {
    const way = leg(network, from, to);
    return way === null || way.ticks === null ? null : Math.ceil(way.ticks / TICKS_PER_SECOND);
  }

  return { FORMAT, VERSION, read, leg, seconds };
});
