#!/usr/bin/env python3
"""A second replica of Create's Navigation.tick, written apart from TravelTime.java.

It follows Create's own code in Navigation.tick (the order of the steps and the way a curve is
treated) rather than the Java model's zones, so that a mistake in the Java port does not repeat
itself here. Both are the same author's reading of the same source, so this catches slips in the
port, not a wrong reading; real trains are the check of that (TravelTimeRunTests).

Run it with `uv run python tools/travel_time_replica.py` (or any Python 3). It prints the cases and
exits with 1 if one differs from the tick count that TravelTimeTest expects.

Units: blocks and seconds, as in Create's config. Zones are (start, end, cap), the head's distance
from where the trip starts and a cap in blocks per second, and are treated as Create treats a curve.

Signs are Create Train Next's speed-limit signs, (position, kmh) with kmh None for a release sign,
and follow that mod's TrainSpeedLimits (cap, scoutAhead, allowedAhead) and TrainMixin rather than
the Java model's SignLimits, for the same reason.
"""
import math
import sys

GIVE_UP_TICKS = 20 * 60 * 60 * 24

# TrainSpeedLimits
KMH_PER_BLOCK_PER_TICK = 3.6 * 20
BRAKING_SHARE = 0.8
REACH_MARGIN = 30
ARRIVE_EARLY = 1


def scout_ahead(signs, position, speed, sign_kmh, top_speed, accel):
    """TrainSpeedLimits.scoutAhead and allowedAhead: what the train may go at now to be down to every limit ahead."""
    if speed == 0 or top_speed <= 0:
        return top_speed
    braking = BRAKING_SHARE * accel
    reach = top_speed * top_speed / (2 * braking) + REACH_MARGIN    # brakingDistance(topSpeed) + REACH_MARGIN
    # scan(): the signs the probe passes on its way from the front, in the order it meets them.
    ahead = sorted((at - position, kmh) for at, kmh in signs if 0 < at - position <= reach)
    allowed = top_speed
    signal_kmh = math.inf            # no signals in these runs
    for distance, kmh in ahead:
        sign_kmh = math.inf if kmh is None else kmh                   # Ahead.LIFTS for a release sign
        lowest = min(sign_kmh, signal_kmh)
        if math.isinf(lowest):
            continue
        limit = lowest / KMH_PER_BLOCK_PER_TICK
        room = max(0, distance - ARRIVE_EARLY)
        allowed = min(allowed, math.sqrt(limit * limit + 2 * braking * room))
    return allowed


def max_speed_with_signs(signs, position, speed, sign_kmh, top_speed, accel):
    """Train.maxSpeed() with TrainMixin: TrainSpeedLimits.cap of what Create's config gives."""
    capped = top_speed
    if sign_kmh is not None:
        capped = min(capped, sign_kmh / KMH_PER_BLOCK_PER_TICK)
    return min(capped, scout_ahead(signs, position, speed, sign_kmh, top_speed, accel))


def passed(signs, before, after, sign_kmh):
    """TrainMixin's front listener: each sign the front moved over sets the limit, in the order it is passed."""
    for at, kmh in sorted(signs):
        if before < at <= after:
            sign_kmh = kmh                                            # releaseSign leaves none
    return sign_kmh


def run(distance, zones=(), throttle=1.0, top=28.0, acceleration=3.0, signs=(), limit_at_start=None):
    """Ticks from the first tick in which the train moves to the one in which it is stopped at the marker."""
    config_speed = top / 20         # what Create's Train.maxSpeed() returns before Create Train Next holds it
    accel = acceleration / 400      # Train.acceleration()
    position = 0.0                  # how far the head has gone
    speed = 0.0                     # Train.speed, blocks per tick
    sign_kmh = limit_at_start       # TrainSpeedLimitData.sign(train)

    for tick in range(1, GIVE_UP_TICKS):
        distance_to_destination = distance - position
        braking_distance = speed * speed / (2 * accel)
        target_distance = distance_to_destination + 0.25     # always overshoot

        if target_distance - abs(speed) < 1 / 32:
            return tick                                       # speed = max(target, 1/32): the move reaches it

        top_speed = max_speed_with_signs(signs, position, speed, sign_kmh, config_speed, accel)
        if target_distance < 10:
            max_approach_speed = top_speed * (target_distance / 10)
            if speed > max_approach_speed:
                speed += (max_approach_speed - abs(speed)) * .5
                if position + speed >= distance:
                    return tick
                sign_kmh = passed(signs, position, position + speed, sign_kmh)
                position += speed
                continue

        top_speed *= throttle
        target_speed = top_speed if target_distance > braking_distance else 0.0

        # Navigation's scout reports the distance to the next curve: 0 while the head is on one.
        distance_to_next_curve = -1.0
        turn_top_speed = 0.0
        for start, end, cap in zones:
            if position > end:
                continue
            d = 0.0 if position >= start else start - position
            if distance_to_next_curve == -1 or d < distance_to_next_curve:
                distance_to_next_curve = d
                turn_top_speed = min(top_speed, cap / 20)

        if distance_to_next_curve != -1:
            slowing_distance = braking_distance - turn_top_speed * turn_top_speed / (2 * accel)
            target_turn_speed = top_speed if distance_to_next_curve > slowing_distance else turn_top_speed
            if abs(target_turn_speed) < abs(target_speed):
                target_speed = target_turn_speed

        if abs(target_speed - speed) >= 1e-5:                 # approachTargetSpeed
            if speed < target_speed:
                speed = min(speed + accel, target_speed)
            else:
                speed = max(speed - accel, target_speed)

        if position + speed >= distance:                      # the front listener stops the train at the marker
            return tick
        sign_kmh = passed(signs, position, position + speed, sign_kmh)
        position += speed
    return None


# (distance, zones, throttle) -> the ticks TravelTimeTest expects. The first five are the runs that
# real trains were timed on (TravelTimeRunTests).
CASES = [
    (20, (), 1.0, 96), (40, (), 1.0, 133), (40, (), 0.25, 154), (40, (), 0.1, 298), (300, (), 1.0, 382),
    (60, (), 1.0, 168), (100, (), 1.0, 218), (262, (), 1.0, 358), (500, (), 1.0, 528),
    (1000, (), 1.0, 882), (5000, (), 1.0, 3746),
    (20, (), 0.25, 96), (60, (), 0.25, 211),
    (1000, ((400, 700, 11.2),), 1.0, 1278),
    (1000, ((400, 700, 5.6),), 1.0, 1863),
    (1000, ((0, 1000, 11.2),), 1.0, 1852),
    (1000, ((200, 300, 16.8), (600, 800, 11.2)), 1.0, 1245),
]


# Create Train Next's signs: (distance, signs, limit at start, run's other arguments) -> the ticks
# TravelTimeTest expects. A sign is (position, kmh), and kmh is None for a release sign.
SIGN_CASES = [
    (1000, ((400, 40), (700, None)), None, {}, 1293),
    (1000, ((400, 40),), None, {}, 1552),
    (1000, ((200, 60), (500, 40), (800, None)), None, {}, 1415),
    (300, ((100, 20), (200, None)), None, {}, 755),
    (40, ((20, 40),), None, {}, 158),
    (1000, (), 40, {}, 1886),
    (1000, ((500, None),), 40, {}, 1413),
    (1000, (), 40, {"throttle": 0.5}, 3651),
    (1000, ((400, 40), (700, None)), None, {"throttle": 0.25}, 4382),
    (100, ((30, 144),), None, {}, 218),
    (100, ((105, 10),), None, {}, 297),
    (1000, ((200, 40),), None, {"zones": ((400, 700, 14),)}, 1765),
    (1000, ((300, 100), (600, None)), None, {"top": 40}, 833),
]


def main():
    failed = 0
    for distance, zones, throttle, expected in CASES:
        got = run(distance, zones, throttle)
        flag = "" if got == expected else "   <-- differs"
        failed += got != expected
        print(f"{distance:>5} blocks, throttle {throttle:<4}, zones {len(zones)}: {got} ticks (expected {expected}){flag}")
    for distance, signs, limit_at_start, others, expected in SIGN_CASES:
        got = run(distance, signs=signs, limit_at_start=limit_at_start, **others)
        flag = "" if got == expected else "   <-- differs"
        failed += got != expected
        print(f"{distance:>5} blocks, signs {signs}, starting under {limit_at_start}, {others}: {got} ticks (expected {expected}){flag}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
