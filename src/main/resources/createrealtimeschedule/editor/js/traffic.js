/*
 * The trains of a timetable running on time together, a tick at a time, with the signals between them,
 * as Traffic and TimetableTraffic do in the mod: how much longer than on an empty line each leg takes,
 * for the light signals of Create Train Next a train behind another passes at a lower aspect, and for
 * the signals at red it stops at. Each step is the mod's, in the same order, so that the two come to the
 * same tick (the shared cases in src/test/resources/.../traffic/ hold them to it); the comments of the
 * mod's classes say why each step is as it is.
 *
 * A leg is as the network has it: {distance, curves: [[start, end]], signs: [{at, kmh} or {at, release}],
 * signals: [{at, signal}], block}. The blocks are the network's: {count, crossings: [[a, b]], signals:
 * [{block, next, lights, cross}], speeds: {restricted, approach, reduced, sixProceed} or null}.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory(require('./travel.js'), require('./problems.js'), require('./timetable.js'));
  else
    (root.rt = root.rt || {}).traffic = factory(root.rt.travel, root.rt.problems, root.rt.timetable);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (travel, check, timetables) {
  'use strict';

  const NONE = check.NONE;
  const TICKS_PER_SECOND = 20;
  /** How many times at most a train is moved on, in all, before the simulation gives up (Traffic.MAX_STEPS). */
  const MAX_STEPS = 5000000;
  /** How long after the last departure a train that has not arrived is given up on: an hour. */
  const GIVE_UP_TICKS = 20 * 60 * 60;
  /** How far behind a train may fall before it is taken off the line: ten minutes. */
  const TOO_LATE_TICKS = 20 * 60 * 10;
  /** Create does not leave a station while a signal this near ahead is at red, in blocks. */
  const PRE_DEPARTURE_LOOK_AHEAD = 4.5;
  /** Create counts a train this near a signal it waits for as standing at it. */
  const AT_SIGNAL = 1.0 / 16;
  /** Create reserves a block only if it is not past the signal it waits at by more than this. */
  const RESERVE_SLACK = 0.25;
  /** Create counts the wait for a signal over this near the stop. */
  const NEAR_THE_STOP = 0.5;
  /** Create leaves a station only if it has further to go than this. */
  const LEAVE_DISTANCE = 1.0 / 32;
  /** The aspects of a light signal, from the most restrictive. */
  const STOP = 0;
  const RESTRICTED = 1;
  const APPROACH = 2;
  const REDUCED_SPEED = 3;
  const PROCEED = 4;
  const HIGH_SPEED_PROCEED = 5;

  /** Create Train Next's ladder for a head of that many aspects, most permissive first. */
  function ladder(aspects) {
    switch (aspects) {
      case 2: return [PROCEED, STOP];
      case 3: return [PROCEED, APPROACH, STOP];
      case 4: return [PROCEED, REDUCED_SPEED, APPROACH, STOP];
      case 5: return [PROCEED, REDUCED_SPEED, APPROACH, RESTRICTED, STOP];
      case 6: return [HIGH_SPEED_PROCEED, PROCEED, REDUCED_SPEED, APPROACH, STOP];
      default: throw new Error('a light signal shows 2 to 6 aspects: ' + aspects);
    }
  }

  function crossingsOf(blocks) {
    const with_ = [];
    for (let b = 0; b < blocks.count; b++)
      with_.push([]);
    for (const pair of blocks.crossings) {
      with_[pair[0]].push(pair[1]);
      with_[pair[1]].push(pair[0]);
    }
    const crossing = [];
    for (let b = 0; b < blocks.count; b++) {
      const found = [b];
      for (let i = 0; i < found.length; i++)
        for (const other of with_[found[i]])
          if (!found.includes(other))
            found.push(other);
      crossing.push(found);
    }
    return crossing;
  }

  /**
   * Runs the trains of runs on blocks (Traffic.run): each {name, spec, throttle, stays, stints: [{leg,
   * departs, minimumStop, newTrain, length}]}, ticks and blocks. {trips, finished}: for each run, a trip
   * for each leg it set out on, or was to and was taken off before it did, {departed, arrived (-1 if it
   * never did), free (-1 where it cannot get
   * there), waits: [{signal, from, ticks}]}; and whether it ran to the end and did not give up after
   * maxSteps (MAX_STEPS if left out) moves of a train, the trips of one that gave up not to be gone by.
   */
  function run(blocks, runs, maxSteps) {
    const most = maxSteps === undefined ? MAX_STEPS : maxSteps;
    let steps = 0;
    const signals = blocks.signals;
    const speeds = blocks.speeds;
    const crossing = crossingsOf(blocks);
    const inBlock = [];
    const reserved = [];
    for (let b = 0; b < blocks.count; b++) {
      inBlock.push([]);
      reserved.push(-1);
    }
    let touched = [];
    const waiting = [];
    let moving = [];
    const parked = [];
    let now = 0;

    const trains = runs.map(each => newTrain(each));
    const coming = trains.slice().sort((a, b) => appears(a) - appears(b));
    let nextToCome = 0;
    if (trains.length === 0)
      return { trips: [], finished: true };

    function appears(train) {
      return train.run.stints[0].departs;
    }

    function newTrain(each) {
      const train = {
        run: each, trips: [], stint: 0, done: false, leaves: 0, drive: null, leg: each.stints[0].leg,
        length: each.stints[0].length, front: 0, behind: null, signAtStart: null, signalKmh: Infinity, atStation: false,
        waitingFor: -1, waitAt: 0, waitChain: [], chain: new Set(), nextSignal: 0, departed: 0, free: -1, waits: [], waitFrom: -1,
        parkedBlocks: [],
      };
      train.ahead = {
        count: () => train.leg.signals.length - train.nextSignal,
        position: i => train.leg.signals[train.nextSignal + i].at,
        kmh: i => shownTo(train.leg.signals[train.nextSignal + i].signal),
      };
      return train;
    }

    function appear(train) {
      moving.push(train);
      train.leaves = appears(train);
      train.atStation = true;
    }

    /** The limit of a sign train leaves the start of stint, its next leg, under: none for a new train of the timetable. */
    function signAtLeaving(train, stint) {
      return train.stint === 0 || stint.newTrain ? null : travel.limitAfter(train.leg.signs, train.signAtStart, train.leg.distance);
    }

    /** The ticks stint takes on an empty line, leaving under sign; -1 where it never gets there. */
    function freeTicks(train, stint, sign) {
      const ticks = travel.ticks(train.run.spec, train.run.throttle, stint.leg.distance, stint.leg.curves, stint.leg.signs, sign);
      return ticks === null ? -1 : ticks;
    }

    function leave(train) {
      const stint = train.run.stints[train.stint];
      if (train.stint > 0) {
        train.signAtStart = signAtLeaving(train, stint);
        train.behind = train.leg;
      }
      if (train.stint === 0 || stint.newTrain)
        train.signalKmh = Infinity;
      const leg = stint.leg;
      train.leg = leg;
      train.length = stint.length;
      train.front = 0;
      train.nextSignal = 0;
      const spec = train.run.spec;
      const zones = (leg.curves || []).map(curve => ({ start: curve[0], end: curve[1], cap: spec.turnSpeed }));
      train.drive = travel.drive(spec, train.run.throttle, zones, travel.sorted(leg.signs), train.signAtStart);
      train.drive.setSignalLimit(train.signalKmh);
      train.departed = train.leaves;
      train.free = freeTicks(train, stint, train.signAtStart);
      train.waits = [];
    }

    /** Too late to leave the station it stands at, train is taken off there: the leg it was to set out on is never arrived at. */
    function dropBeforeLeaving(train) {
      const stint = train.run.stints[train.stint];
      train.trips.push({ departed: train.leaves, arrived: -1, free: freeTicks(train, stint, signAtLeaving(train, stint)), waits: [] });
      train.done = true;
    }

    function arrive(train, at) {
      train.trips.push({ departed: train.departed, arrived: at, free: train.free, waits: train.waits });
      train.signalKmh = train.drive.signalLimit();
      train.front = train.leg.distance;
      train.drive = null;
      train.chain.clear();
      train.waitChain = [];
      train.waitingFor = -1;
      train.atStation = true;
      train.stint++;
      if (train.stint === train.run.stints.length) {
        train.done = true;
        if (train.run.stays) {
          parked.push(train);
          train.parkedBlocks = body(train);
        }
        return;
      }
      const next = train.run.stints[train.stint];
      train.leaves = Math.max(next.departs, at + next.minimumStop);
    }

    function startWait(train, at) {
      if (train.waitFrom === -1)
        train.waitFrom = at;
    }

    function endWait(train, at) {
      if (train.waitFrom !== -1)
        train.waits.push({ signal: train.waitingFor, from: train.waitFrom, ticks: at - train.waitFrom });
      train.waitFrom = -1;
    }

    function blockOf(leg, i) {
      return signals[leg.signals[i].signal].block;
    }

    function add(taken, block) {
      if (block >= 0 && !taken.includes(block))
        taken.push(block);
    }

    /** The blocks of leg from from up to to; a signal at to is passed only atTheEnd, for a front at the stop marker. */
    function span(leg, from, to, atTheEnd, taken) {
      let block = leg.block;
      let i = 0;
      while (i < leg.signals.length && leg.signals[i].at < from)
        i++;
      if (i > 0)
        block = blockOf(leg, i - 1);
      add(taken, block);
      for (; i < leg.signals.length && (leg.signals[i].at < to || atTheEnd && leg.signals[i].at <= to); i++)
        add(taken, blockOf(leg, i));
    }

    function body(train) {
      const at = train.drive === null ? train.front : train.drive.position();
      const tail = at - train.length;
      const taken = [];
      if (tail < 0 && train.behind !== null)
        span(train.behind, Math.max(0, train.behind.distance + tail), train.behind.distance, true, taken);
      span(train.leg, Math.max(0, tail), at, train.drive === null && at >= train.leg.distance, taken);
      return taken;
    }

    function enter(block, train) {
      if (block < 0)
        return;
      const there = inBlock[block];
      if (there.length === 0 && reserved[block] === -1)
        touched.push(block);
      if (!there.includes(train))
        there.push(train);
    }

    function reserve(block, signal) {
      if (block < 0)
        return;
      if (inBlock[block].length === 0 && reserved[block] === -1)
        touched.push(block);
      reserved[block] = signal;
    }

    function occupiedFor(block, train) {
      if (block < 0)
        return false;
      for (const each of crossing[block]) {
        const there = inBlock[each];
        if (reserved[each] !== -1 || there.length > 1 || there.length > 0 && !there.includes(train))
          return true;
      }
      return false;
    }

    function occupiedPast(block, signal) {
      if (block < 0)
        return true;
      for (const each of crossing[block])
        if (inBlock[each].length > 0 || reserved[each] !== -1 && reserved[each] !== signal)
          return true;
      return false;
    }

    function clearBlocks(signal, limit) {
      if (limit <= 0)
        return 0;
      const side = signals[signal];
      if (occupiedPast(side.block, signal))
        return 0;
      if (side.next.length === 0)
        return limit;
      let beyond = limit - 1;
      for (const next of side.next)
        beyond = Math.min(beyond, clearBlocks(next, limit - 1));
      return 1 + beyond;
    }

    function aspect(signal, aspects) {
      const steps = ladder(aspects);
      const clear = clearBlocks(signal, steps.length - 1);
      return steps[Math.max(0, steps.length - 1 - clear)];
    }

    function speedOf(aspects, shown) {
      switch (shown) {
        case PROCEED: return aspects === 6 ? speeds.sixProceed : Infinity;
        case HIGH_SPEED_PROCEED: return Infinity;
        case STOP:
        case RESTRICTED: return speeds.restricted;
        case APPROACH: return speeds.approach;
        default: return speeds.reduced;
      }
    }

    function seenFrom(signal) {
      let kmh = Infinity;
      for (const aspects of signals[signal].lights)
        kmh = Math.min(kmh, speedOf(aspects, aspect(signal, aspects)));
      return kmh;
    }

    function shownTo(signal) {
      if (speeds === null)
        return NaN;
      let kmh = Infinity;
      let stop = false;
      for (const aspects of signals[signal].lights) {
        const shown = aspect(signal, aspects);
        if (shown === STOP)
          stop = true;
        else
          kmh = Math.min(kmh, speedOf(aspects, shown));
      }
      return stop && kmh === Infinity ? NaN : kmh;
    }

    function resolved(train, toGo) {
      if (toGo < NEAR_THE_STOP)
        return true;
      const signal = signals[train.waitingFor];
      if (signal.cross) {
        for (const held of train.waitChain)
          if (occupiedFor(held[0], train))
            return false;
        return true;
      }
      return !occupiedFor(signal.block, train);
    }

    function reserveChain(train, chain) {
      for (const held of chain) {
        train.chain.add(held[0]);
        reserve(held[0], held[1]);
      }
      chain.length = 0;
    }

    function scan(train, front, toGo) {
      const braking = train.drive.brakingDistance();
      const noFlicker = braking + 3 - braking % 3;
      const least = train.atStation ? PRE_DEPARTURE_LOOK_AHEAD : 0;
      const scanDistance = noFlicker < least ? least : Math.min(noFlicker, toGo);
      let distanceToSignal = train.waitingFor === -1 ? Number.MAX_VALUE : train.waitAt - front;
      let tracking = -1;
      let trackingAt = 0;
      const chain = [];
      const legSignals = train.leg.signals;
      for (let i = train.nextSignal; i < legSignals.length; i++) {
        const passed = legSignals[i];
        const distance = passed.at - front;
        const tracked = tracking !== -1;
        if (!tracked && distance > scanDistance)
          break;
        const signal = signals[passed.signal];
        const occupied = occupiedFor(signal.block, train);
        if (!tracked) {
          if (signal.cross) {
            tracking = passed.signal;
            trackingAt = passed.at;
            chain.push([signal.block, passed.signal]);
          }
          if (occupied) {
            train.waitingFor = passed.signal;
            train.waitAt = passed.at;
            distanceToSignal = distance;
            if (!signal.cross)
              break;
          }
          if (!occupied && !signal.cross && distance < distanceToSignal + RESERVE_SLACK && distance < noFlicker)
            reserve(signal.block, passed.signal);
          continue;
        }
        chain.push([signal.block, passed.signal]);
        if (occupied) {
          train.waitingFor = tracking;
          train.waitAt = trackingAt;
          distanceToSignal = trackingAt - front;
          if (!signal.cross)
            break;
        }
        if (!signal.cross) {
          if (distance < distanceToSignal + RESERVE_SLACK) {
            tracking = -1;
            reserveChain(train, chain);
            continue;
          }
          break;
        }
      }
      if (tracking !== -1 && train.waitingFor === -1)
        reserveChain(train, chain);
      train.waitChain = chain;
    }

    function pass(train, signal) {
      if (speeds !== null)
        train.drive.setSignalLimit(seenFrom(signal));
      const block = signals[signal].block;
      reserve(block, signal);
      enter(block, train);
      train.chain.delete(block);
    }

    function control(train) {
      steps++;
      const leg = train.leg;
      const drive = train.drive;
      const front = drive.position();
      const toGo = leg.distance - front;

      if (train.waitingFor !== -1 && resolved(train, toGo)) {
        endWait(train, now);
        train.waitingFor = -1;
        train.waitChain = [];
      }
      if (train.waitingFor === -1 || train.waitAt - front > AT_SIGNAL)
        scan(train, front, toGo);

      const isWaiting = train.waitingFor !== -1;
      const stopAt = isWaiting ? train.waitAt : leg.distance;
      if (train.atStation && stopAt - front + 0.25 > LEAVE_DISTANCE) {
        if (isWaiting && train.waitAt - front < PRE_DEPARTURE_LOOK_AHEAD) {
          startWait(train, now);
          return false;
        }
        train.atStation = false;
      }

      const speed = drive.speed();
      const reached = drive.step(stopAt, train.ahead);
      const moved = drive.position();
      while (train.nextSignal < leg.signals.length && leg.signals[train.nextSignal].at < moved)
        pass(train, leg.signals[train.nextSignal++].signal);
      const changed = moved !== front || drive.speed() !== speed;
      if (!reached)
        return changed;
      if (isWaiting) {
        startWait(train, now);
        if (speeds !== null)
          drive.setSignalLimit(seenFrom(train.waitingFor));
        return changed;
      }
      // A signal at the stop marker is passed, as the leg has it: the next leg starts past it.
      while (train.nextSignal < leg.signals.length && leg.signals[train.nextSignal].at <= leg.distance)
        pass(train, leg.signals[train.nextSignal++].signal);
      arrive(train, now);
      return true;
    }

    function tick() {
      let changed = false;
      if (nextToCome < coming.length && appears(coming[nextToCome]) <= now) {
        while (nextToCome < coming.length && appears(coming[nextToCome]) <= now)
          appear(coming[nextToCome++]);
        changed = true;
      }
      for (const block of touched) {
        inBlock[block].length = 0;
        reserved[block] = -1;
      }
      touched = [];
      const onTheLine = waiting.concat(moving);
      for (const train of parked)
        for (const block of train.parkedBlocks)
          enter(block, train);
      for (const train of onTheLine) {
        for (const block of body(train))
          enter(block, train);
        for (const block of train.chain)
          enter(block, train);
      }

      for (const train of onTheLine) {
        const waitedFor = train.waitingFor;
        if (train.drive === null) {
          if (now <= train.leaves)
            continue;
          if (train.leaves - train.run.stints[train.stint].departs > TOO_LATE_TICKS) {
            dropBeforeLeaving(train);
            changed = true;
            continue;
          }
          leave(train);
          changed = true;
        } else if (now - train.departed > Math.max(train.free, 0) + TOO_LATE_TICKS) {
          train.trips.push({ departed: train.departed, arrived: -1, free: train.free, waits: train.waits });
          train.drive = null;
          train.done = true;
          changed = true;
          continue;
        }
        changed = control(train) || train.waitingFor !== waitedFor || changed;
      }

      // Trains that stopped waiting go to the end of the moving ones, and those that started to the end of the waiting ones.
      for (let i = 0; i < waiting.length; ) {
        const train = waiting[i];
        if (train.done) {
          waiting.splice(i, 1);
        } else if (train.waitingFor === -1) {
          moving.push(train);
          waiting.splice(i, 1);
        } else {
          i++;
        }
      }
      const stillMoving = [];
      for (const train of moving) {
        if (train.done)
          continue;
        if (train.waitingFor !== -1)
          waiting.push(train);
        else
          stillMoving.push(train);
      }
      moving = stillMoving;
      return changed;
    }

    now = appears(coming[0]);
    let giveUp = -Infinity;
    for (const train of trains)
      for (const stint of train.run.stints)
        giveUp = Math.max(giveUp, stint.departs);
    giveUp += GIVE_UP_TICKS;
    while (now <= giveUp && (nextToCome < coming.length || waiting.length > 0 || moving.length > 0)) {
      if (steps > most)
        break;
      if (tick()) {
        now++;
        continue;
      }
      let next = nextToCome < coming.length ? appears(coming[nextToCome]) : Infinity;
      for (const train of waiting.concat(moving)) {
        if (train.drive === null)
          next = Math.min(next, train.leaves + 1);
        else
          // A train held up for good is taken off at its deadline, which may set others free.
          next = Math.min(next, train.departed + Math.max(train.free, 0) + TOO_LATE_TICKS + 1);
      }
      if (next === Infinity || next <= now)
        break;
      now = next;
    }
    const trips = trains.map(train => {
      if (train.drive !== null)
        train.trips.push({ departed: train.departed, arrived: -1, free: train.free, waits: train.waits });
      return train.trips;
    });
    return { trips, finished: steps <= most };
  }

  /** The minimum stop of a stop in seconds: its own, or else its station's; NONE for the server's. */
  function minimumStopOf(timetable, stop) {
    if (stop.minimumStop !== undefined && stop.minimumStop !== NONE)
      return stop.minimumStop;
    const station = (timetable.stations || []).find(each => each.name === stop.station);
    return station === undefined ? NONE : station.minimumStop;
  }

  /** DutyPlan.calls: the calls a set makes running numbers one after another; null if a train is not there. */
  function calls(timetable, numbers, byNumber) {
    const made = [];
    let previous = null;
    for (const number of numbers) {
      const train = byNumber.get(number);
      if (train === undefined)
        return null;
      const stops = train.stops;
      const first = stops[0];
      if (previous === null) {
        made.push({ station: first.station, depart: first.depart, arriving: '', leaving: number,
          minimumStop: minimumStopOf(timetable, first), tag: timetables.tagAt(train, 0) });
      } else {
        const end = made.pop();
        made.push({ station: end.station, depart: first.depart, arriving: previous.number, leaving: number,
          minimumStop: minimumStopOf(timetable, first), tag: timetables.tagAt(train, 0) });
      }
      for (let k = 1; k < stops.length; k++) {
        const last = k === stops.length - 1;
        made.push({ station: stops[k].station, depart: last ? NONE : stops[k].depart, arriving: number, leaving: last ? '' : number,
          minimumStop: last ? NONE : minimumStopOf(timetable, stops[k]), tag: last ? '' : timetables.tagAt(train, k) });
      }
      previous = train;
    }
    return made;
  }

  function key(train, stop) {
    return train + '\n' + stop;
  }

  /**
   * The trains of timetable run on time together (TimetableTraffic.of): each duty one set, a train in no
   * duty a set of its own.
   *
   * @param legOf (from, to, tag) the leg between two stations for a train held to tag, or null
   * @param blocks the network's signal blocks
   * @param spec the trains' {topSpeed, turnSpeed, acceleration}
   * @param defaults {minimumStop, length}: the minimum stop in seconds and the length in blocks where the
   *   timetable gives none
   * @returns {{finished: boolean, trip: function(string, number): ?Object, ticks: function(string, number): ?number,
   *   signalTicks: function(string, number): number}}
   *   whether the simulation ran to the end (if not, it says nothing of any train); how the leg from a
   *   train's stop (from 0) to the next went, null where the train was never to set out on it (one taken
   *   off before it could has a trip that never arrives); the ticks it took, null where it never got there;
   *   and the ticks the signals added to it, waits at red and all, 0 where the train did not set out on it,
   *   never got there, or runs on a way it cannot finish on an empty line (TimetableTraffic.signalTicks)
   */
  function ofTimetable(timetable, legOf, blocks, spec, defaults) {
    const runs = [];
    const keys = [];
    const byNumber = new Map();
    for (const train of timetable.trains)
      if (!byNumber.has(train.number))
        byNumber.set(train.number, train);
    const inDuties = new Set();
    const sets = [];
    for (const duty of timetable.duties || []) {
      for (const number of duty.trains)
        inDuties.add(number);
      sets.push({ id: duty.id, trains: duty.trains, duty: true });
    }
    for (const train of timetable.trains)
      if (!inDuties.has(train.number))
        sets.push({ id: train.number, trains: [train.number], duty: false });
    for (const set of sets) {
      const made = calls(timetable, set.trains, byNumber);
      if (made === null)
        continue;
      const stints = [];
      const legKeys = [];
      let stop = 0;
      for (let i = 0; i + 1 < made.length; i++) {
        const call = made[i];
        const isNew = i === 0 || call.leaving !== call.arriving;
        stop = isNew ? 0 : stop + 1;
        const leg = legOf(call.station, made[i + 1].station, call.tag);
        if (leg === null || leg === undefined || call.depart === NONE)
          break;
        const train = byNumber.get(call.leaving);
        const length = train !== undefined && check.hasLength(train) ? train.length : defaults.length;
        const minimum = i === 0 ? 0
          : (call.minimumStop === NONE ? defaults.minimumStop : call.minimumStop) * TICKS_PER_SECOND;
        stints.push({ leg, departs: call.depart * TICKS_PER_SECOND, minimumStop: minimum, newTrain: isNew, length });
        legKeys.push(key(call.leaving, stop));
      }
      if (stints.length === 0)
        continue;
      runs.push({ name: set.id, spec, throttle: 1, stints, stays: set.duty && stints.length === made.length - 1 });
      keys.push(legKeys);
    }
    const trips = new Map();
    const ran = run(blocks, runs);
    if (ran.finished)
      ran.trips.forEach((made, r) => made.forEach((trip, i) => trips.set(keys[r][i], trip)));
    return {
      finished: ran.finished,
      trip: (train, stop) => trips.get(key(train, stop)) || null,
      ticks: (train, stop) => {
        const trip = trips.get(key(train, stop));
        return trip === undefined || trip.arrived === -1 ? null : trip.arrived - trip.departed;
      },
      signalTicks: (train, stop) => {
        const trip = trips.get(key(train, stop));
        return trip === undefined || trip.arrived === -1 || trip.free < 0 ? 0 : Math.max(0, trip.arrived - trip.departed - trip.free);
      },
    };
  }

  return { run, ofTimetable, TOO_LATE_TICKS, MAX_STEPS };
});
