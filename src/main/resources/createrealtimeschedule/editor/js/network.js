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

  /** The newest version of the JSON the editor reads. Version 2 added the signs of a leg, and version 3 the track tags. */
  const VERSION = 3;

  const TICKS_PER_SECOND = 20;

  function isObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

  function isStrings(value) {
    return Array.isArray(value) && value.every(item => typeof item === 'string');
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
      if (leg.tag !== undefined && typeof leg.tag !== 'string')
        problems.push(at + '.tag: must be a string');
      const made = {
        from: leg.from,
        to: leg.to,
        distance: leg.distance,
        via: leg.via === undefined ? [] : leg.via,
        ticks: leg.ticks === undefined ? null : leg.ticks,
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
      return { network: { stations, legs, tagged: false, tags: [], tagProblems: [], platforms: stations.map(station => ({ station })), ways },
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
    (Array.isArray(root.ways) ? root.ways : []).forEach((way, i) => {
      const at = 'ways[' + i + ']';
      if (!isObject(way) || !Number.isInteger(way.from) || !isObject(way.to)
        || !Object.values(way.to).every(platform => Number.isInteger(platform)))
        problems.push(at + ': must have a platform from and the platforms it comes to');
      else if (way.tag !== undefined && typeof way.tag !== 'string')
        problems.push(at + '.tag: must be a string');
      else
        ways.push(tagged({ from: way.from, to: Object.assign({}, way.to) }, way));
    });
    if (problems.length > 0)
      return { network: null, problems };
    return {
      network: {
        stations, legs, tagged: true, tags: root.tags.slice(), tagProblems: root.tagProblems.map(problem => Object.assign({}, problem)),
        platforms: root.platforms.map(platform => ({ station: platform.station })), ways,
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
