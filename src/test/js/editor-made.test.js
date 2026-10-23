'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { script } = require('./editor.js');
const { build } = require('./editor-made-timetable.js');

const timetable = script('timetable');
const problems = script('problems');

const COPY = path.join(__dirname, '..', '..', 'gametest', 'resources', 'io', 'github', 'eyunya11', 'createrealtimeschedule', 'gametest',
  'editor-made-timetable.json');

test('the timetable made with the editor\'s own changes has nothing wrong with it', () => {
  assert.deepEqual(problems.problems(build()), []);
});

test('the game test runs the timetable as the editor makes it now', () => {
  // Made again with: node src/test/js/editor-made-timetable.js > (the file)
  const copy = fs.readFileSync(COPY, 'utf8').replace(/\r\n/g, '\n');
  assert.equal(timetable.write(build()), copy);
});
