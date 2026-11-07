/*
 * How long a train takes from a stop to the next, as TravelTime works it out in the mod: Create's
 * speed control one tick at a time, with the curves of the way and the speed-limit signs of Create
 * Train Next. The editor needs it for a train that leaves a stop still under the limit of a sign it
 * passed before, which the network's ticks, for a train under none, do not say. It does each step as
 * the mod does, in the same order, so that the two come to the same tick (the shared cases in
 * src/test/resources/.../travel/cases.json hold them to it).
 *
 * Positions are blocks from the start of the run, speeds blocks a second, limits km/h.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory();
  else
    (root.rt = root.rt || {}).travel = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /** Create aims this much past the stop so that the train's travelling point crosses it. */
  const OVERSHOOT = 0.25;
  /** Within this many blocks of the stop, Create limits the speed in proportion to what is left. */
  const APPROACH_BLOCKS = 10;
  /** The step below which Create considers the train to have arrived, in blocks. */
  const ARRIVAL_SNAP = 1.0 / 32;
  /** Create's Mth.equal. */
  const SPEED_EPSILON = 1.0e-5;
  /** A run that takes longer than a day of game time is taken to be one the train never finishes. */
  const GIVE_UP_TICKS = 20 * 60 * 60 * 24;
  /** The lowest cap of a curve, in blocks a second, that is taken as a speed and not as a stop. */
  const MIN_CAP = 0.05;
  /** Create Train Next takes a block a second to be 3.6 km/h, so a block a tick is 72 km/h. */
  const KMH_PER_BLOCK_PER_TICK = 3.6 * 20;
  /** The share of the train's braking that Create Train Next counts on to be down to a limit in time. */
  const SIGN_BRAKING_SHARE = 0.8;
  /** How far past its braking distance Create Train Next looks for signs, in blocks. */
  const SIGN_REACH_MARGIN = 30;
  /** How far before a sign Create Train Next has the train down to its limit, in blocks. */
  const SIGN_ARRIVE_EARLY = 1;

  /** The signs in the order they come, as SpeedSigns keeps them. */
  function sorted(signs) {
    return (signs || []).slice().sort((a, b) => a.at - b.at);
  }

  /**
   * The limit a train is under once its front has passed position: that of the last sign after the
   * start and not after position, or limitAtStart if it has passed none; null for no limit, which a
   * release sign also leaves (SpeedSigns.limitAfter).
   *
   * @param signs [{at, kmh}, or {at, release: true}]
   * @param limitAtStart the limit it starts under, or null for none
   */
  function limitAfter(signs, limitAtStart, position) {
    let limit = limitAtStart === undefined ? null : limitAtStart;
    for (const sign of sorted(signs)) {
      if (sign.at > position)
        break;
      if (sign.at > 0)
        limit = sign.release ? null : sign.kmh;
    }
    return limit;
  }

  function ticksAt(limit, blocks) {
    return limit !== null ? blocks / (limit / KMH_PER_BLOCK_PER_TICK) : 0;
  }

  /** The fewest ticks the run can take for its signs alone (TravelTime.ticksUnderSigns). */
  function ticksUnderSigns(signs, limitAtStart, distance) {
    let ticks = 0;
    let from = 0;
    let limit = limitAtStart;
    for (const sign of signs) {
      if (sign.at <= 0)
        continue;
      if (sign.at >= distance)
        break;
      ticks += ticksAt(limit, sign.at - from);
      from = sign.at;
      limit = sign.release ? null : sign.kmh;
    }
    return ticks + ticksAt(limit, distance - from);
  }

  /** The limits of the signs as the run goes on, as TravelTime.SignLimits keeps them; speeds in blocks a tick. */
  function signLimits(signs, limitAtStart, topPerTick, acceleration) {
    const braking = SIGN_BRAKING_SHARE * acceleration;
    const reach = topPerTick * topPerTick / (2 * braking) + SIGN_REACH_MARGIN;
    let next = 0;
    let limit = limitAtStart !== null ? limitAtStart / KMH_PER_BLOCK_PER_TICK : Infinity;

    function pass(position) {
      while (next < signs.length && signs[next].at <= position) {
        const sign = signs[next++];
        if (sign.at > 0)
          limit = sign.release ? Infinity : sign.kmh / KMH_PER_BLOCK_PER_TICK;
      }
    }

    function cap(top, position, speed) {
      let capped = Math.min(top, limit);
      if (speed === 0)
        return capped;
      for (let i = next; i < signs.length; i++) {
        const sign = signs[i];
        const ahead = sign.at - position;
        if (ahead > reach)
          break;
        if (sign.release)
          continue;
        const kmh = sign.kmh / KMH_PER_BLOCK_PER_TICK;
        const room = Math.max(0, ahead - SIGN_ARRIVE_EARLY);
        capped = Math.min(capped, Math.sqrt(kmh * kmh + 2 * braking * room));
      }
      return capped;
    }

    pass(0);
    return { cap, pass };
  }

  /**
   * The ticks a train takes from a stop to one distance blocks on, from the first tick in which it
   * moves to the one in which it is stopped at the marker (TravelTime.ticks); null if it cannot get
   * there (or not within a day).
   *
   * @param spec {topSpeed, turnSpeed, acceleration}, as the network's train has them
   * @param throttle the share of the top speed it is allowed, from more than 0 to 1
   * @param curves [[start, end], ...]: where it is held to its turn speed
   * @param signs [{at, kmh}, or {at, release: true}]: the speed-limit signs that face its way, also
   *   those past the stop, which it brakes for on the way in
   * @param limitAtStart the limit of a sign it passed before, or null (or left out) for none
   */
  function ticks(spec, throttle, distance, curves, signs, limitAtStart) {
    const start = limitAtStart === undefined ? null : limitAtStart;
    if (!(throttle > 0) || throttle > 1 || !(distance >= 0))
      return null;
    const zones = (curves || []).map(curve => ({ start: curve[0], end: curve[1], cap: spec.turnSpeed }));
    for (const zone of zones) {
      if (!(zone.cap >= MIN_CAP))
        return null;
      const inside = Math.min(zone.end, distance) - Math.max(zone.start, 0);
      if (inside / (zone.cap / 20) > GIVE_UP_TICKS)
        return null;
    }
    const ordered = sorted(signs);
    if (ticksUnderSigns(ordered, start, distance) > GIVE_UP_TICKS)
      return null;
    if (distance === 0)
      return 0;

    const topPerTick = spec.topSpeed / 20;
    const acceleration = spec.acceleration / 400;
    const limits = signLimits(ordered, start, topPerTick, acceleration);

    let position = 0;
    let speed = 0;
    for (let tick = 1; tick <= GIVE_UP_TICKS; tick++) {
      const remaining = distance + OVERSHOOT - position;
      const brakingDistance = speed * speed / (2 * acceleration);

      if (remaining - Math.abs(speed) < ARRIVAL_SNAP)
        return tick;

      const top = limits.cap(topPerTick, position, speed);

      if (remaining < APPROACH_BLOCKS) {
        const maxApproachSpeed = top * (remaining / APPROACH_BLOCKS);
        if (speed > maxApproachSpeed) {
          speed += (maxApproachSpeed - Math.abs(speed)) * 0.5;
          if (position + speed >= distance)
            return tick;
          position += speed;
          limits.pass(position);
          continue;
        }
      }

      const cruise = top * throttle;
      let target = remaining > brakingDistance ? cruise : 0;

      let inside = Infinity;
      for (const zone of zones) {
        const cap = Math.min(zone.cap / 20, cruise);
        if (position >= zone.start && position <= zone.end)
          inside = Math.min(inside, cap);
        const ahead = zone.start - position;
        if (ahead > 0 && cap < speed) {
          const slowingDistance = (speed * speed - cap * cap) / (2 * acceleration);
          if (ahead <= slowingDistance)
            target = Math.min(target, cap);
        }
      }
      target = Math.min(target, inside);

      if (Math.abs(target - speed) >= SPEED_EPSILON) {
        if (speed < target)
          speed = Math.min(speed + acceleration, target);
        else
          speed = Math.max(speed - acceleration, target);
      }
      if (position + speed >= distance)
        return tick;
      position += speed;
      limits.pass(position);
    }
    return null;
  }

  return { ticks, limitAfter };
});
