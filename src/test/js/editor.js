// The editor's scripts, for the tests, and the places of the files the tests share with the mod's.
'use strict';

const path = require('node:path');

const SCRIPTS = path.join(__dirname, '..', '..', 'main', 'resources', 'createrealtimeschedule', 'editor', 'js');
const RESOURCES = path.join(__dirname, '..', 'resources', 'io', 'github', 'eyunya11', 'createrealtimeschedule');

function script(name) {
  return require(path.join(SCRIPTS, name + '.js'));
}

module.exports = { script, RESOURCES };
