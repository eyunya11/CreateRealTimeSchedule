# CreateRealTimeSchedule

A [Create](https://modrinth.com/mod/create) addon for Minecraft 1.21.1 (NeoForge) that lets trains run to a real timetable.

It adds a clock that runs at real-world speed, and a schedule wait condition, Real Time of Day, that follows it. A train that is early waits for its departure time. A train that is late stops for a minimum time only, so it makes up the delay by stopping less.

Status: early (0.1.0-SNAPSHOT). The clock, the sky and the wait condition work and are tested; see [what is and is not checked](#what-is-and-is-not-checked). Command names may still change. What is planned next (travel time estimates, a time-distance diagram, train duties, overtaking waits) is in [docs/proposal.md](docs/proposal.md), which is written in Japanese.

日本語: [README.ja.md](README.ja.md)

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1 (built against 21.1.262)
- Create 6.0.11 or later
- This mod on the server and on every client. A schedule that holds the new condition cannot be read without it.

## The clock

The clock is the game time plus an offset that is saved in the world. Twenty ticks are one second and a day is 24 real hours (1,728,000 ticks), 72 times a vanilla day.

- It counts ticks, not wall-clock time. So it stays in step with the trains, stands still while the server is paused or stopped, and goes faster or slower with `/tick rate`. If the server's tick rate falls below 20, the clock falls behind real time with it.
- A world that has no saved clock yet starts at `start_time` (06:00 by default).
- On a server that runs around the clock, the clock stays close to real time. Every restart sets it back by the time the server was down; `/rtclock align`, or the `align_on_start` setting, sets it to the server's own time of day again.

| Command | Who | What it does |
| --- | --- | --- |
| `/rtclock get` | everyone | Shows the day and the time of the clock |
| `/rtclock set <H:MM[:SS]>` | operators | Sets the time of day and keeps the day. Trains that are waiting follow it: a later time lets them go, an earlier one makes them wait longer |
| `/rtclock align` | operators | Sets the time of day to the server's own |

## The sky

By default the sky follows the clock: noon at 12:00, midnight at 00:00, and the moon changes phase once a real day. To do that the mod keeps the game rule `doDaylightCycle` off and sets the day time from the clock every tick. So:

- Sleeping does not skip the night, and `/time set` is put back on the next tick.
- Do not use it with mods that change the length of the day, unless you turn `sync_sky` off.
- Setting `sync_sky` to `false` hands the sky back to vanilla and puts `doDaylightCycle` back as it was.
- If you remove the mod while `sync_sky` is on, `doDaylightCycle` stays off. Turn `sync_sky` off and start the world once before removing the mod, or run `/gamerule doDaylightCycle true` afterwards.

## The wait condition: Real Time of Day

In the schedule window, give a stop a condition and pick Real Time of Day as its type. Its one line of settings has:

- the time the train is meant to leave (scroll over the hours and over the minutes), such as 08:10;
- the minimum stop, from 0 seconds to 10 minutes. A new condition starts with `default_minimum_stop_seconds`, 20 seconds by default;
- how often the time repeats: daily, or every 12, 6, 4, 3, 2 or 1 hours, or every 45, 30 or 15 minutes. With 08:10 repeating every 15 minutes, the train leaves at :10, :25, :40 and :55 of every hour.

When a train starts to wait at the stop, it works out once when it leaves: at its slot (the departure time that belongs to it), or when the minimum stop is over if it arrived late, whichever is later.

For a slot of 08:10, a minimum stop of 30 seconds and a scheduled arrival of 08:08:

| Arrives | Leaves | Stands for | Delay left |
| --- | --- | --- | --- |
| 08:08:00 (on time) | 08:10:00 | 2 min | none |
| 08:09:00 (1 min late) | 08:10:00 | 1 min | none: made up |
| 08:11:00 (3 min late) | 08:11:30 | 30 s | 1 min 30 s |

The slot is not simply the next 08:10 on the clock. It follows the slot the train used at its previous Real Time of Day stop (the first one after it that matches this stop's time). So a train that is late stays late against its own timetable instead of skipping ahead, and a round that asks for the same time twice means tomorrow's the second time. A train takes the slot nearest to its arrival instead when it has no previous slot (the first such stop of a schedule, or right after it got a different schedule) or when the chained slot is a whole repeat interval or more away from its arrival.

The departure time is kept in the train as a clock value, so it survives saving the world and follows `/rtclock set`. While a train waits, its status text, which Create's Display Link can show, reads "Departs at HH:MM:SS".

## Settings

The settings are per world, in `serverconfig/createrealtimeschedule-server.toml` of the world folder. A client uses the server's values while it is connected.

| Setting | Default | What it does |
| --- | --- | --- |
| `sync_sky` | `true` | Makes the sky follow the clock (see above) |
| `start_time` | `"06:00"` | The time of day the clock starts at in a world that has no saved clock |
| `align_on_start` | `false` | Sets the clock to the server's own time of day whenever the server starts |
| `default_minimum_stop_seconds` | `20` | The minimum stop (0 to 600) a new Real Time of Day condition starts with in the schedule window. Conditions that already exist keep their own value |

## What is and is not checked

Checked by the tests (`./gradlew test` and `./gradlew runGameTestServer`): the clock arithmetic, saving and sending the clock, the sky sync, the commands, the condition's rule and how slots follow each other, saving a train that waits on the condition, and a real train running on real track through three stations in a row, arriving early and late.

Not checked yet:

- loops, curves, signals and several trains with the condition on real track;
- how the settings line looks in the schedule window (it only compiles);
- several players on a dedicated server seeing the same time and sky;
- that a client without the mod is refused by a server that has it;
- that a client takes the server's `default_minimum_stop_seconds` when it joins. NeoForge's code sends the server's config files to a joining client, but this has not been tried with a real client.

## Building

Java 21. `./gradlew build` makes the jar in `build/libs`. The first run downloads Minecraft, NeoForge and Create, and takes a while (about ten minutes).

```
./gradlew test                # unit tests
./gradlew runGameTestServer   # game tests, on a test server
./gradlew runClient           # a development client
./gradlew runServer           # a development server; accept the EULA in run/eula.txt on the first start
```

## License

MIT. See [LICENSE](LICENSE).
