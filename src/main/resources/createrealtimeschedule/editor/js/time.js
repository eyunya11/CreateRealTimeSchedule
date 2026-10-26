/*
 * Times as a timetable writes them, as ServiceTime does in the mod: a count of seconds from 00:00 of
 * the day the timetable starts on, running on past midnight the way printed timetables do, so that a
 * train that leaves at ten past midnight the next day leaves at 24:10. The last time is 47:59:59.
 *
 * Like every file of the editor, it is a plain script: the page loads it with <script src>, which
 * works from a file:// address too, and Node's tests load it with require().
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory();
  else
    (root.rt = root.rt || {}).time = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /** 48:00:00 in seconds: the first time that cannot be written. */
  const LIMIT = 48 * 3600;

  const TIME = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/;

  /** The seconds of H:MM, HH:MM or HH:MM:SS; null if the text is anything else. */
  function parse(text) {
    const match = TIME.exec(text);
    if (match === null)
      return null;
    const hour = Number(match[1]);
    const minute = Number(match[2]);
    const second = match[3] === undefined ? 0 : Number(match[3]);
    if (hour > 47 || minute > 59 || second > 59)
      return null;
    return hour * 3600 + minute * 60 + second;
  }

  function two(number) {
    return String(number).padStart(2, '0');
  }

  /** HH:MM, or HH:MM:SS when there are seconds. Throws for a count of seconds that is no time of the service day. */
  function format(seconds) {
    if (!Number.isInteger(seconds) || seconds < 0 || seconds >= LIMIT)
      throw new RangeError('not a time of the service day: ' + seconds + ' s');
    const hour = Math.floor(seconds / 3600);
    const minute = Math.floor(seconds / 60) % 60;
    const second = seconds % 60;
    return second === 0 ? two(hour) + ':' + two(minute) : two(hour) + ':' + two(minute) + ':' + two(second);
  }

  /** Like format, but a count of seconds that is no time comes out as "-5 s": for a message. */
  function show(seconds) {
    return seconds >= 0 && seconds < LIMIT ? format(seconds) : seconds + ' s';
  }

  return { LIMIT, parse, format, show };
});
