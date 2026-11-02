/*
 * The map of the track, as network.js reads it from a network of version 4: which tracks it shows
 * (MapSelection in the mod, rule for rule, held to the same results by shared cases), and a view of it
 * that zooms and pans both ways. The map is the world seen from above: X to the right and Z down, as
 * Minecraft's own maps have it, so north is up.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory(require('./timetable.js'));
  else
    (root.rt = root.rt || {}).map = factory(root.rt.timetable);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (timetableModule) {
  'use strict';

  /** The kinds of things on the map that can be shown or not, as the mod's map screens name them too. */
  const LAYERS = ['stations', 'stationNames', 'signals', 'speedLimits', 'trains'];

  /** The least and the most pixels a block may take. */
  const MIN_SCALE = 1 / 64;
  const MAX_SCALE = 64;

  /**
   * The indexes of the tracks shown, in order (MapSelection.visible in the mod).
   *
   * @param keys      the key of each track, by its index
   * @param legs      the legs between the stations, each {from, to, tracks}
   * @param timetable the timetable, or null, which shows every track
   * @param lines     the ids of the lines shown; none for every track
   */
  function visible(keys, legs, timetable, lines) {
    const indexOf = new Map();
    keys.forEach((key, i) => {
      if (!indexOf.has(key))
        indexOf.set(key, i);
    });
    const shown = new Set();
    if (lines.length === 0 || timetable === null) {
      keys.forEach((key, i) => shown.add(i));
    } else {
      const legOf = new Map();
      for (const leg of legs) {
        const pair = JSON.stringify([leg.from, leg.to]);
        if (!legOf.has(pair))
          legOf.set(pair, leg);
      }
      const wanted = new Set(lines);
      for (const line of timetable.lines) {
        if (!wanted.has(line.id))
          continue;
        const ofLine = new Set();
        const stations = line.stations;
        for (let i = 0; i < stations.length; i++) {
          const last = i === stations.length - 1;
          if (last && !(line.loop && stations.length > 2))
            break;
          const a = stations[i];
          const b = stations[last ? 0 : i + 1];
          for (const pair of [[a, b], [b, a]]) {
            const leg = legOf.get(JSON.stringify(pair));
            if (leg !== undefined)
              leg.tracks.forEach(track => ofLine.add(track));
          }
        }
        for (const key of timetableModule.mapAdd(line))
          if (indexOf.has(key))
            ofLine.add(indexOf.get(key));
        for (const key of timetableModule.mapRemove(line))
          if (indexOf.has(key))
            ofLine.delete(indexOf.get(key));
        ofLine.forEach(track => shown.add(track));
      }
    }
    if (timetable !== null)
      for (const key of timetableModule.mapHidden(timetable))
        if (indexOf.has(key))
          shown.delete(indexOf.get(key));
    return [...shown].filter(track => track >= 0 && track < keys.length).sort((a, b) => a - b);
  }

  /** The keys a timetable names that are not keys of the map's tracks (MapSelection.missing in the mod). */
  function missing(keys, timetable) {
    const known = new Set(keys);
    const found = new Set();
    for (const line of timetable.lines)
      for (const key of [...timetableModule.mapAdd(line), ...timetableModule.mapRemove(line)])
        if (!known.has(key))
          found.add(key);
    for (const key of timetableModule.mapHidden(timetable))
      if (!known.has(key))
        found.add(key);
    return [...found].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  }

  /** The box around the tracks of a dimension, {minX, minZ, maxX, maxZ}, or null if it has none. */
  function bounds(map, dimension) {
    let box = null;
    for (const track of map.tracks) {
      if (track === null || track.dimension !== dimension)
        continue;
      for (let i = 0; i < track.points.length; i += 2) {
        const x = track.points[i];
        const z = track.points[i + 1];
        if (box === null)
          box = { minX: x, minZ: z, maxX: x, maxZ: z };
        else {
          box.minX = Math.min(box.minX, x);
          box.minZ = Math.min(box.minZ, z);
          box.maxX = Math.max(box.maxX, x);
          box.maxZ = Math.max(box.maxZ, z);
        }
      }
    }
    return box;
  }

  /**
   * What part of the world a drawing of the given size shows: the world's point at its left top, and
   * how many pixels a block takes, the same both ways.
   */
  class View {
    constructor(width, height) {
      this.width = width;
      this.height = height;
      this.left = 0;
      this.top = 0;
      this.scale = 1;
    }

    /** Takes a new size, keeping the world's point in the middle where it is. */
    resize(width, height) {
      const middleX = this.worldX(this.width / 2);
      const middleZ = this.worldZ(this.height / 2);
      this.width = width;
      this.height = height;
      this.left = middleX - width / 2 / this.scale;
      this.top = middleZ - height / 2 / this.scale;
    }

    /** Shows the whole box, with margin pixels round it, in the middle. */
    fit(box, margin) {
      const spanX = Math.max(box.maxX - box.minX, 1);
      const spanZ = Math.max(box.maxZ - box.minZ, 1);
      const room = (size) => Math.max(size - 2 * margin, 1);
      this.scale = clamp(Math.min(room(this.width) / spanX, room(this.height) / spanZ), MIN_SCALE, MAX_SCALE);
      this.left = (box.minX + box.maxX) / 2 - this.width / 2 / this.scale;
      this.top = (box.minZ + box.maxZ) / 2 - this.height / 2 / this.scale;
    }

    /** Where the world's x is on the drawing, in pixels from the left. */
    x(worldX) {
      return (worldX - this.left) * this.scale;
    }

    /** Where the world's z is on the drawing, in pixels from the top. */
    y(worldZ) {
      return (worldZ - this.top) * this.scale;
    }

    worldX(x) {
      return this.left + x / this.scale;
    }

    worldZ(y) {
      return this.top + y / this.scale;
    }

    /** Zooms by factor, keeping the world's point under (x, y) where it is. */
    zoom(factor, x, y) {
      const worldX = this.worldX(x);
      const worldZ = this.worldZ(y);
      this.scale = clamp(this.scale * factor, MIN_SCALE, MAX_SCALE);
      this.left = worldX - x / this.scale;
      this.top = worldZ - y / this.scale;
    }

    /** Moves the drawing by (dx, dy) pixels, as a drag does: the world moves with the pointer. */
    pan(dx, dy) {
      this.left -= dx / this.scale;
      this.top -= dy / this.scale;
    }
  }

  function clamp(value, low, high) {
    return Math.min(high, Math.max(low, value));
  }

  /** The SVG path of the tracks given by their indexes, in the view, to a tenth of a pixel. */
  function pathData(map, tracks, view) {
    const parts = [];
    const round = (value) => Math.round(value * 10) / 10;
    for (const index of tracks) {
      const track = map.tracks[index];
      if (track === null)
        continue;
      for (let i = 0; i < track.points.length; i += 2)
        parts.push((i === 0 ? 'M' : 'L') + round(view.x(track.points[i])) + ' ' + round(view.y(track.points[i + 1])));
    }
    return parts.join('');
  }

  /** How far a point is from the segment from (ax, az) to (bx, bz). */
  function fromSegment(x, z, ax, az, bx, bz) {
    const dx = bx - ax;
    const dz = bz - az;
    const length = dx * dx + dz * dz;
    const along = length === 0 ? 0 : clamp(((x - ax) * dx + (z - az) * dz) / length, 0, 1);
    return Math.hypot(x - (ax + along * dx), z - (az + along * dz));
  }

  /**
   * The index of the track of a dimension nearest the world's point (x, z), if it is no further than
   * reach blocks; else -1. Of two as near, the first.
   */
  function nearestTrack(map, dimension, x, z, reach) {
    let best = -1;
    let bestDistance = reach;
    map.tracks.forEach((track, index) => {
      if (track === null || track.dimension !== dimension)
        return;
      for (let i = 2; i < track.points.length; i += 2) {
        const distance = fromSegment(x, z, track.points[i - 2], track.points[i - 1], track.points[i], track.points[i + 1]);
        if (distance <= bestDistance && (best < 0 || distance < bestDistance)) {
          best = index;
          bestDistance = distance;
        }
      }
    });
    return best;
  }

  return { LAYERS, MIN_SCALE, MAX_SCALE, visible, missing, bounds, View, pathData, nearestTrack };
});
