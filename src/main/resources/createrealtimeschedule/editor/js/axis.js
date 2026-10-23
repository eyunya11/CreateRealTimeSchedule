/*
 * The vertical axis of a line's diagram, as LineAxis has it in the mod: the line's stations from the
 * top down, each at the distance along the track from the first, in blocks.
 *
 * The distance between two stations next to each other on the line is that of the shorter way
 * between them, either way round, that passes no other station of the line. Where the track does not
 * join them so, the gap is the mean of the gaps that are known, or 100 blocks if none is. On a loop
 * line the first station comes again at the bottom, and which way round a train goes between two of
 * its stations is the way the track shows, where it shows one.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory();
  else
    (root.rt = root.rt || {}).axis = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /** The gap between two stations, in blocks, when no gap of the line is known. */
  const UNKNOWN_GAP = 100;

  function floorMod(a, n) {
    return ((a % n) + n) % n;
  }

  function sameList(a, b) {
    return a.length === b.length && a.every((item, i) => item === b[i]);
  }

  /** The length of the shorter way between a and b, either way round, that passes none of the line's other stations; NaN if none. */
  function gap(a, b, legs, lineStations) {
    let found = NaN;
    for (const leg of legs) {
      const between = leg.from === a && leg.to === b || leg.from === b && leg.to === a;
      if (between && !leg.via.some(station => lineStations.includes(station)) && (Number.isNaN(found) || leg.distance < found))
        found = leg.distance;
    }
    return found;
  }

  /** The ways round of a loop line's stations that the legs show: [{from, to, down}]. */
  function ways(stations, legs) {
    const n = stations.length;
    const found = [];
    for (const leg of legs) {
      const i = stations.indexOf(leg.from);
      const j = stations.indexOf(leg.to);
      if (i < 0 || j < 0 || i === j)
        continue;
      const passed = leg.via.filter(station => stations.includes(station));
      const down = [];
      for (let k = floorMod(i + 1, n); k !== j; k = floorMod(k + 1, n))
        down.push(stations[k]);
      const up = [];
      for (let k = floorMod(i - 1, n); k !== j; k = floorMod(k - 1, n))
        up.push(stations[k]);
      const byDown = sameList(passed, down);
      if (byDown !== sameList(passed, up))
        found.push({ from: leg.from, to: leg.to, down: byDown });
    }
    return found;
  }

  class LineAxis {
    constructor(line, loop, stations, positions, ways) {
      this.line = line;
      this.loop = loop;
      this.stations = stations;
      this.positions = positions;
      this.ways = ways;
    }

    /** How far down the bottom station is, in blocks. */
    length() {
      return this.positions[this.positions.length - 1];
    }

    position(row) {
      return this.positions[row];
    }

    /** The first row the station is at, or -1. */
    row(station) {
      return this.stations.indexOf(station);
    }

    /** How many stations the line has: on a loop line, one fewer than the rows. */
    size() {
      return this.loop ? this.stations.length - 1 : this.stations.length;
    }

    /** Whether the track goes down the axis from one station to the other; null if it does not show which way. */
    trackDown(from, to) {
      for (const way of this.ways)
        if (way.from === from && way.to === to)
          return way.down;
      return null;
    }

    /**
     * Whether a train making these stops runs down a loop line rather than up it, where the track does
     * not show it: the way round that passes fewer stations over all its stops, and down if both pass
     * as many. Always true on a line that is not a loop.
     */
    down(stops) {
      if (!this.loop)
        return true;
      const n = this.size();
      let downSteps = 0;
      let upSteps = 0;
      for (let k = 0; k + 1 < stops.length; k++) {
        const i = this.row(stops[k]);
        const j = this.row(stops[k + 1]);
        if (i < 0 || j < 0)
          continue;
        downSteps += floorMod(j - i, n);
        upSteps += floorMod(i - j, n);
      }
      return downSteps <= upSteps;
    }

    /**
     * The way from one station to the next of a train's stops, down the axis or up it, as hops
     * [{fromRow, toRow, share}]: one, or two across the first station of a loop line. Empty if either
     * station is not on the axis.
     */
    hops(from, to, down) {
      const i = this.row(from);
      const j = this.row(to);
      if (i < 0 || j < 0)
        return [];
      if (!this.loop)
        return [{ fromRow: i, toRow: j, share: 1 }];
      const bottom = this.stations.length - 1;
      if (down) {
        const end = j === 0 ? bottom : j;
        if (end > i)
          return [{ fromRow: i, toRow: end, share: 1 }];
        return this.split(i, bottom, 0, end);
      }
      const start = i === 0 ? bottom : i;
      if (j < start)
        return [{ fromRow: start, toRow: j, share: 1 }];
      return this.split(start, 0, bottom, j);
    }

    split(a, b, c, d) {
      const first = Math.abs(this.position(b) - this.position(a));
      const second = Math.abs(this.position(d) - this.position(c));
      const share = first + second === 0 ? 0.5 : first / (first + second);
      return [{ fromRow: a, toRow: b, share }, { fromRow: c, toRow: d, share: 1 - share }];
    }
  }

  /** The axis of a timetable line ({id, stations, loop}), with the gaps taken from the network's legs (none if there is no network). */
  function of(line, legs) {
    legs = legs || [];
    const stations = line.stations.slice();
    if (line.loop && stations.length > 0)
      stations.push(stations[0]);
    const gaps = [];
    let known = 0;
    let knownCount = 0;
    for (let i = 0; i + 1 < stations.length; i++) {
      const found = gap(stations[i], stations[i + 1], legs, line.stations);
      gaps.push(found);
      if (!Number.isNaN(found)) {
        known += found;
        knownCount++;
      }
    }
    const fill = knownCount === 0 ? UNKNOWN_GAP : known / knownCount;
    const positions = [0];
    let at = 0;
    for (const each of gaps) {
      at += Number.isNaN(each) ? fill : each;
      positions.push(at);
    }
    if (stations.length === 0)
      positions.length = 0;
    return new LineAxis(line.id, line.loop, stations, positions, line.loop ? ways(line.stations, legs) : []);
  }

  return { UNKNOWN_GAP, of, LineAxis };
});
