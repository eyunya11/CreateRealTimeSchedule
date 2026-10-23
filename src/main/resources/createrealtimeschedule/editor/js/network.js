/*
 * The track network as /rtnetwork export and /rtnetwork copy write it (NetworkJson in the mod): the
 * stations, and the legs between them with their length and the ticks a train takes. The editor
 * takes the running time between two stations from it, and the distances for the diagram's axis.
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

  /** The newest version of the JSON the editor reads. Version 2 added the signs of a leg. */
  const VERSION = 2;

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
      legs.push({
        from: leg.from,
        to: leg.to,
        distance: leg.distance,
        via: leg.via === undefined ? [] : leg.via,
        ticks: leg.ticks === undefined ? null : leg.ticks,
      });
    });
    if (problems.length > 0)
      return { network: null, problems };
    return { network: { stations: root.stations.slice(), legs }, problems: [] };
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
