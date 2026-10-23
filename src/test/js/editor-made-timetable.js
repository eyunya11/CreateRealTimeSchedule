// A timetable made the way the editor makes one, with its own changes only (edit.js and duties.js):
// the loop line of the mod's game tests, with the depot, A and B on it, and two duties, each of a
// train from A to B with deadheads to and from the depot. A game test runs it on real track
// (OperationTests), which is what the editor is for: a timetable made in it runs as it says.
//
// Run with Node, it prints the timetable, which is how the game test's copy of it is made:
//   node src/test/js/editor-made-timetable.js > src/gametest/resources/io/github/eyunya11/createrealtimeschedule/gametest/editor-made-timetable.json
'use strict';

const { script } = require('./editor.js');

const time = script('time');
const timetable = script('timetable');
const network = script('network');
const edit = script('edit');
const duties = script('duties');

/** The loop of the game tests as /rtnetwork would write it: 30 blocks from the depot to A and on to B, and round back. */
const NETWORK = network.read(JSON.stringify({
  format: 'createrealtimeschedule.network',
  version: 2,
  stations: ['Depot', 'A', 'B'],
  legs: [
    { from: 'Depot', to: 'A', distance: 30, ticks: 140 },
    { from: 'A', to: 'B', distance: 30, ticks: 140 },
    { from: 'B', to: 'Depot', distance: 110, ticks: 600 },
  ],
})).network;

/** The dwell the editor starts with is 20 seconds, the server's minimum stop; a little more leaves room for a set that comes in late. */
const DWELL = 25;
const STEP = 5;

function running(from, to) {
  return network.seconds(NETWORK, from, to);
}

/** Draws a train from A to B, leaving A at the time given, as a click on A's row and one on B's would. */
function drawTrain(made, leaves) {
  let stops = edit.drawStop([], 'A', time.parse(leaves), null, DWELL, STEP);
  stops = edit.drawStop(stops, 'B', time.parse(leaves), running('A', 'B'), DWELL, STEP);
  const number = edit.nextNumber(made, false);
  return { made: edit.addTrain(made, { number, line: 'loop', type: 'local', deadhead: false, stops: edit.finishDrawing(stops) }), number };
}

function build() {
  let made = edit.setName(timetable.empty(), 'Made in the editor');
  made = edit.putLine(made, null, { id: 'loop', name: 'Loop', stations: ['Depot', 'A', 'B'], loop: true });
  for (const leaves of ['12:03:00', '12:03:30']) {
    const drawn = drawTrain(made, leaves);
    const id = duties.nextDutyId(drawn.made);
    made = duties.appendTrain(duties.addDuty(drawn.made, id, 'Depot'), id, drawn.number);
    made = duties.addDepotDeadheads(made, id, running, DWELL, STEP).timetable;
  }
  return made;
}

module.exports = { build };

if (require.main === module)
  process.stdout.write(timetable.write(build()));
