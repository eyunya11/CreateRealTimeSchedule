/*
 * The track network as /rtnetwork export and /rteditor copy network write it (NetworkJson in the mod): the
 * stations, and the legs between them with their length and the ticks a train takes. The editor
 * takes the running time between two stations from it, and the distances for the diagram's axis.
 *
 * Version 3 adds the track tags: the world's tags, what is wrong with the tag blocks' sections, the
 * platforms, the legs for a train held to each tag (and to a tag the world has none of, otherTag),
 * and the ways from each platform: where a train of each tag comes to when it is sent to a name. A
 * network of before has none of them; its tags are taken to change nothing, and each station to be
 * one platform.
 *
 * Version 4 adds the map of the track (TrackMapJson in the mod), with the tracks each leg runs along,
 * and has all that version 3 has; a network of before has no map (map null).
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

  /**
   * The newest version of the JSON the editor reads. Version 2 added the signs of a leg, version 3 the
   * track tags, and version 4 the map.
   */
  const VERSION = 4;

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

  /** The map of a network of version 4, or null after saying in problems what is wrong with it. */
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
    const map = root.version >= 4 ? readMap(root.map, problems) : null;
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
      if (leg.tag !== undefined && typeof leg.tag !== 'string')
        problems.push(at + '.tag: must be a string');
      if (leg.otherTag !== undefined && typeof leg.otherTag !== 'boolean')
        problems.push(at + '.otherTag: must be true or false');
      const made = {
        from: leg.from,
        to: leg.to,
        distance: leg.distance,
        via: leg.via === undefined ? [] : leg.via,
        ticks: leg.ticks === undefined ? null : leg.ticks,
        tracks: Array.isArray(leg.tracks) ? leg.tracks.slice() : [],
      };
      tagged(made, leg);
      legs.push(made);
    });
    const stations = isStrings(root.stations) ? root.stations.slice() : [];
    if (root.version < 3) {
      if (problems.length > 0)
        return { network: null, problems };
      // Each station one platform, the ways from it those of its legs.
      const ways = stations.map((station, from) => {
        const to = {};
        for (const each of legs)
          if (each.from === station && stations.includes(each.to))
            to[each.to] = stations.indexOf(each.to);
        return { from, to };
      });
      return { network: { stations, legs, tagged: false, tags: [], tagProblems: [], platforms: stations.map(station => ({ station })), ways, map },
        problems: [] };
    }

    if (!isStrings(root.tags))
      problems.push('tags: must be an array of strings');
    if (!Array.isArray(root.tagProblems) || !root.tagProblems.every(isObject))
      problems.push('tagProblems: must be an array of objects');
    if (!Array.isArray(root.platforms) || !root.platforms.every(platform => isObject(platform) && typeof platform.station === 'string'))
      problems.push('platforms: must be an array of objects with a station');
    const ways = [];
    if (!Array.isArray(root.ways))
      problems.push('ways: must be an array');
    // A platform is a number of one of platforms, from 0.
    const count = Array.isArray(root.platforms) ? root.platforms.length : 0;
    const isPlatform = platform => Number.isInteger(platform) && platform >= 0 && platform < count;
    (Array.isArray(root.ways) ? root.ways : []).forEach((way, i) => {
      const at = 'ways[' + i + ']';
      if (!isObject(way) || !isPlatform(way.from) || !isObject(way.to) || !Object.values(way.to).every(isPlatform))
        problems.push(at + ': must have a platform from and the platforms it comes to, each one of platforms');
      else if (way.tag !== undefined && typeof way.tag !== 'string')
        problems.push(at + '.tag: must be a string');
      else if (way.otherTag !== undefined && typeof way.otherTag !== 'boolean')
        problems.push(at + '.otherTag: must be true or false');
      else
        ways.push(tagged({ from: way.from, to: Object.fromEntries(Object.entries(way.to)) }, way));
    });
    if (problems.length > 0)
      return { network: null, problems };
    return {
      network: {
        stations, legs, tagged: true, tags: root.tags.slice(), tagProblems: root.tagProblems.map(problem => Object.assign({}, problem)),
        platforms: root.platforms.map(platform => ({ station: platform.station })), ways, map,
      },
      problems: [],
    };
  }

  /** Gives made the tag item is for: a tag, or otherTag for a tag the world has none of. */
  function tagged(made, item) {
    if (typeof item.tag === 'string' && item.tag !== '')
      made.tag = item.tag;
    if (item.otherTag === true)
      made.otherTag = true;
    return made;
  }

  /**
   * Whether item (a leg or a way) is for a train held to tag ('' for none): one of that tag, or, for a
   * tag the world has none of, one of otherTag. In a network with no tags, every tag is as none.
   */
  function forTag(network, item, tag) {
    const of = item.tag === undefined ? '' : item.tag;
    if (!network.tagged || tag === '' || tag === undefined)
      return of === '' && item.otherTag !== true;
    return network.tags.includes(tag) ? of === tag && item.otherTag !== true : item.otherTag === true;
  }

  /** The leg from one station to another for a train held to tag (none if left out), or null if the track does not take it there. */
  function leg(network, from, to, tag) {
    for (const each of network.legs)
      if (each.from === from && each.to === to && forTag(network, each, tag))
        return each;
    return null;
  }

  /**
   * The seconds a train held to tag (none if left out) takes from one station to another, its ticks
   * rounded up to the next whole second; null if the network has no running time for the way.
   */
  function seconds(network, from, to, tag) {
    const way = leg(network, from, to, tag);
    return way === null || way.ticks === null ? null : Math.ceil(way.ticks / TICKS_PER_SECOND);
  }

  /** The legs a train held to no tag runs, from which the diagram's axis is drawn. */
  function untaggedLegs(network) {
    return network.legs.filter(each => forTag(network, each, ''));
  }

  /** The platforms of the station named station, by their numbers in the network. */
  function platforms(network, station) {
    const found = [];
    network.platforms.forEach((platform, i) => {
      if (platform.station === station)
        found.push(i);
    });
    return found;
  }

  /** The platform a train held to tag that stands at platform comes to when it is sent to station; -1 if it has no way there. */
  function way(network, platform, station, tag) {
    for (const each of network.ways)
      if (each.from === platform && forTag(network, each, tag) && Object.prototype.hasOwnProperty.call(each.to, station))
        return each.to[station];
    return -1;
  }

  return { FORMAT, VERSION, read, leg, seconds, untaggedLegs, platforms, way };
});
