'use strict';

// Every script of the page loads, as a browser would load it, and the words of the page are the same
// in both languages: a script that does not parse leaves the page blank, which no other test sees.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { script } = require('./editor.js');

const SCRIPTS = path.join(__dirname, '..', '..', 'main', 'resources', 'createrealtimeschedule', 'editor', 'js');

test('every script of the page parses', () => {
  const files = fs.readdirSync(SCRIPTS).filter(name => name.endsWith('.js'));
  assert.ok(files.includes('app.js') && files.includes('i18n.js'), 'the page\'s scripts are not where they were looked for');
  for (const name of files)
    assert.doesNotThrow(() => new vm.Script(fs.readFileSync(path.join(SCRIPTS, name), 'utf8'), { filename: name }), name);
});

test('the words of the page are there in Japanese and in English alike', () => {
  const i18n = script('i18n');
  assert.deepEqual(Object.keys(i18n.WORDS.ja).sort(), Object.keys(i18n.WORDS.en).sort());
  for (const language of ['ja', 'en'])
    for (const [key, words] of Object.entries(i18n.WORDS[language]))
      assert.ok(typeof words === 'string' && words !== '', language + '.' + key + ' has no words');
});
