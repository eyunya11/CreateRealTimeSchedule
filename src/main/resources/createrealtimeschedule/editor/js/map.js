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
  /** How much a notch of the wheel zooms, as on the diagram (layout.js) and the mod's map screens. */
  const WHEEL = Math.cbrt(1.25);

  /**
   * The indexes of the tracks shown, in order (MapSelection.visible in the mod).
   *
   * @param keys      the key of each track, by its index
   * @param legs      the legs between the stations, each {from, to, tracks}, and tag or otherTag for a
   *                  train held to a track tag
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
      // By from and to, and the tag: '' for none, null for a tag the world has none of.
      const legOf = new Map();
      const known = new Set();
      for (const leg of legs) {
        const tag = leg.otherTag === true ? null : (typeof leg.tag === 'string' ? leg.tag : '');
        const pair = JSON.stringify([leg.from, leg.to, tag]);
        if (!legOf.has(pair))
          legOf.set(pair, leg);
        if (tag !== null && tag !== '')
          known.add(tag);
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
            const leg = legOf.get(JSON.stringify([pair[0], pair[1], '']));
            if (leg !== undefined)
              leg.tracks.forEach(track => ofLine.add(track));
          }
        }
        for (const train of timetable.trains) {
          if (train.line !== line.id)
            continue;
          for (let k = 0; k + 1 < train.stops.length; k++) {
            const tag = timetableModule.tagAt(train, k);
            if (tag === '')
              continue;
            const leg = legOf.get(JSON.stringify([train.stops[k].station, train.stops[k + 1].station, known.has(tag) ? tag : null]));
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

  /**
   * The indexes of the tracks a line's trains run along, as if the line added and took away none and
   * no map hid any: those of its stations' legs, and the ways of the tags its trains are held to.
   */
  function route(keys, legs, timetable, line) {
    const bare = Object.assign({}, timetable, { lines: [Object.assign({}, line, { mapAdd: [], mapRemove: [] })], mapHidden: [] });
    return visible(keys, legs, bare, [line.id]);
  }

  /** The ids of the lines chosen that the timetable has, in its order: a line taken away or renamed is no longer chosen. */
  function chosenLines(timetable, ids) {
    const chosen = new Set(Array.isArray(ids) ? ids : []);
    return timetable.lines.map(line => line.id).filter(id => chosen.has(id));
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

    /** Zooms for the wheel turned notches away (in) or, below nought, towards (out), about (x, y). */
    wheel(notches, x, y) {
      this.zoom(Math.pow(WHEEL, notches), x, y);
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

  /** What "no tag" is named among the tags turned off (TagView.NO_TAG in the mod). */
  const NO_TAG = '';

  const EPSILON = 1e-9;

  /** The places of the two ends of a track by its key: its dimension and each end; a key that is not one gives places of its own. */
  function trackEnds(key, track) {
    const parts = key.split(' ');
    if (parts.length !== 3)
      return ['track ' + track + ' start', 'track ' + track + ' end'];
    return [parts[0] + ' ' + parts[1], parts[0] + ' ' + parts[2]];
  }

  /**
   * The stretches of each track, from its first point to its last, each {from, to, tagged, shown,
   * tags}: whether tags are on it, whether it is shown, and the tags on it that are on, whose colours
   * it is drawn in (TagView.segments in the mod, rule for rule, held to the same results by shared
   * cases).
   *
   * @param tracks the tracks of the map, each {key, tags}, tags the stretches that tags are on
   * @param off    the tags turned off, NO_TAG among them for "no tag"
   */
  function tagSegments(tracks, off) {
    const offSet = new Set(off);
    const noTagOn = !offSet.has(NO_TAG);
    const all = tracks.map(track => {
      const segments = [];
      let at = 0;
      for (const piece of (track === null ? [] : track.tags || [])) {
        if (piece.from - at > EPSILON)
          segments.push({ from: at, to: piece.from, tagged: false, shown: noTagOn, tags: [] });
        const on = piece.tags.filter(tag => !offSet.has(tag));
        segments.push({ from: piece.from, to: piece.to, tagged: true, shown: on.length > 0, tags: on });
        at = piece.to;
      }
      if (1 - at > EPSILON || segments.length === 0)
        segments.push({ from: at, to: 1, tagged: false, shown: noTagOn, tags: [] });
      return segments;
    });
    if (noTagOn)
      return all;

    // The track with no tag, as stretches between places: the ends of tracks, by the places their keys
    // give, and the places along a track where a stretch with tags starts or ends.
    const meeting = new Set();
    const bare = [];
    const joins = [];
    const bareAt = new Map();
    all.forEach((segments, track) => {
      const ends = trackEnds(tracks[track] === null ? '' : tracks[track].key, track);
      segments.forEach((segment, k) => {
        const first = segment.from <= EPSILON ? 'end ' + ends[0] : 'track ' + track + ' ' + segment.from;
        const last = segment.to >= 1 - EPSILON ? 'end ' + ends[1] : 'track ' + track + ' ' + segment.to;
        if (segment.tagged) {
          if (segment.shown) {
            meeting.add(first);
            meeting.add(last);
          }
          return;
        }
        const number = bare.length;
        bare.push([track, k]);
        joins.push([first, last]);
        for (const place of [first, last]) {
          if (!bareAt.has(place))
            bareAt.set(place, []);
          bareAt.get(place).push(number);
        }
      });
    });
    // Take away the stretches that lead nowhere, one after another from the end, so that a siding goes
    // whole: a stretch with a place that no other stretch with no tag reaches and no stretch shown meets.
    const reaching = new Map();
    for (const join of joins)
      for (const place of join)
        reaching.set(place, (reaching.get(place) || 0) + 1);
    const gone = bare.map(() => false);
    const leaves = [];
    for (const [place, count] of reaching)
      if (count === 1 && !meeting.has(place))
        leaves.push(place);
    while (leaves.length > 0) {
      const place = leaves.shift();
      for (const i of bareAt.get(place)) {
        if (gone[i])
          continue;
        gone[i] = true;
        for (const other of joins[i]) {
          reaching.set(other, reaching.get(other) - 1);
          if (reaching.get(other) === 1 && !meeting.has(other))
            leaves.push(other);
        }
      }
    }
    // Of what is left, a run that meets stretches shown in two places or more is shown.
    const parent = bare.map((each, i) => i);
    const find = (i) => {
      while (parent[i] !== i)
        i = parent[i] = parent[parent[i]];
      return i;
    };
    // Runs are not joined where they meet a stretch shown: a siding from there that leads nowhere else
    // stays a run of its own, and meets them in one place only.
    for (const [place, together] of bareAt) {
      if (meeting.has(place))
        continue;
      let first = -1;
      for (const i of together)
        if (!gone[i]) {
          if (first < 0)
            first = i;
          else
            parent[find(first)] = find(i);
        }
    }
    const meets = new Map();
    bare.forEach((each, i) => {
      if (gone[i])
        return;
      for (const place of joins[i])
        if (meeting.has(place)) {
          const run = find(i);
          if (!meets.has(run))
            meets.set(run, new Set());
          meets.get(run).add(place);
        }
    });
    bare.forEach(([track, k], i) => {
      if (!gone[i] && meets.has(find(i)) && meets.get(find(i)).size >= 2)
        all[track][k] = Object.assign({}, all[track][k], { shown: true });
    });
    return all;
  }

  /** Whether the place share of the way along a track is on a stretch shown (TagView.shownAt in the mod). */
  function shownAt(segments, share) {
    return segments.some(segment => segment.shown && segment.from - EPSILON <= share && share <= segment.to + EPSILON);
  }

  /** How far along a line through points (X and Z in turn) the place nearest (x, z) is, as a share of its length. */
  function shareOf(points, x, z) {
    let total = 0;
    let best = Infinity;
    let bestAlong = 0;
    for (let i = 0; i + 3 < points.length; i += 2) {
      const dx = points[i + 2] - points[i];
      const dz = points[i + 3] - points[i + 1];
      const length = Math.hypot(dx, dz);
      const t = length === 0 ? 0 : clamp(((x - points[i]) * dx + (z - points[i + 1]) * dz) / (length * length), 0, 1);
      const distance = Math.hypot(points[i] + dx * t - x, points[i + 1] + dz * t - z);
      if (distance < best) {
        best = distance;
        bestAlong = total + t * length;
      }
      total += length;
    }
    return total === 0 ? 0 : bestAlong / total;
  }

  /** Whether a train that runs on tags is shown: one of them on, NO_TAG for none (TagView.trainShown in the mod). */
  function trainShown(tags, off) {
    const offSet = new Set(off);
    if (tags.length === 0)
      return !offSet.has(NO_TAG);
    return tags.some(tag => !offSet.has(tag));
  }

  /** The SVG path of the stretch of a track from the share from of its length to to. */
  function piecePath(track, from, to, view) {
    const points = track.points;
    const lengths = [0];
    for (let i = 0; i + 3 < points.length; i += 2)
      lengths.push(lengths[lengths.length - 1] + Math.hypot(points[i + 2] - points[i], points[i + 3] - points[i + 1]));
    const total = lengths[lengths.length - 1];
    const round = (value) => Math.round(value * 10) / 10;
    const at = (share) => {
      const along = share * total;
      for (let j = 1; j < lengths.length; j++)
        if (along <= lengths[j] || j === lengths.length - 1) {
          const span = lengths[j] - lengths[j - 1];
          const t = span === 0 ? 0 : clamp((along - lengths[j - 1]) / span, 0, 1);
          const i = (j - 1) * 2;
          return [points[i] + (points[i + 2] - points[i]) * t, points[i + 1] + (points[i + 3] - points[i + 1]) * t];
        }
      return [points[0], points[1]];
    };
    const parts = [at(from)];
    for (let j = 1; j < lengths.length - 1; j++)
      if (lengths[j] > from * total && lengths[j] < to * total)
        parts.push([points[j * 2], points[j * 2 + 1]]);
    parts.push(at(to));
    return parts.map((point, i) => (i === 0 ? 'M' : 'L') + round(view.x(point[0])) + ' ' + round(view.y(point[1]))).join('');
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

  return {
    LAYERS, MIN_SCALE, MAX_SCALE, WHEEL, NO_TAG, visible, route, chosenLines, missing, bounds, View, pathData, nearestTrack,
    tagSegments, shownAt, shareOf, trainShown, piecePath,
  };
});
