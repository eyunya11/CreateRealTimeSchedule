/*
 * JSON text laid out the same way every time, as JsonOutput does in the mod, so that a timetable the
 * editor writes is the same text the mod writes for it: two spaces of indent, the members in the
 * order they were put, and a list or an object that holds only plain values on one line when it fits
 * in 100 columns, one member to a line when it does not.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory();
  else
    (root.rt = root.rt || {}).jsonOutput = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /** Wider than this and a list of values goes one to a line. */
  const WIDTH = 100;

  /** A value on one line, already in JSON. */
  function leaf(json) {
    return { kind: 'leaf', json };
  }

  function list(items) {
    return { kind: 'list', items: items || [] };
  }

  /** An object, from its members as [name, node] pairs in order. */
  function map(entries) {
    return { kind: 'map', entries: entries || [] };
  }

  /** A string, quoted and escaped. */
  function text(value) {
    return leaf(quote(value));
  }

  /** A value written as it is: a number, true. */
  function raw(json) {
    return leaf(json);
  }

  function texts(values) {
    return list(values.map(text));
  }

  const SHORT = { 0x22: '\\"', 0x5c: '\\\\', 0x09: '\\t', 0x08: '\\b', 0x0a: '\\n', 0x0d: '\\r', 0x0c: '\\f' };

  /**
   * A string in quotes, escaped as Gson escapes it in the mod: the quote, the backslash and the
   * characters below U+0020, and U+2028 and U+2029. Other characters are kept as they are.
   */
  function quote(value) {
    let out = '"';
    for (let i = 0; i < value.length; i++) {
      const code = value.charCodeAt(i);
      if (SHORT[code] !== undefined)
        out += SHORT[code];
      else if (code < 0x20 || code === 0x2028 || code === 0x2029)
        out += '\\u' + code.toString(16).padStart(4, '0');
      else
        out += value[i];
    }
    return out + '"';
  }

  /** The node on one line, or null if there is anything but plain values in it. */
  function flat(node) {
    switch (node.kind) {
      case 'leaf':
        return node.json;
      case 'list': {
        const parts = [];
        for (const item of node.items) {
          if (item.kind !== 'leaf')
            return null;
          parts.push(item.json);
        }
        return '[' + parts.join(', ') + ']';
      }
      default: {
        const parts = [];
        for (const [key, value] of node.entries) {
          if (value.kind !== 'leaf')
            return null;
          parts.push(quote(key) + ': ' + value.json);
        }
        return '{' + parts.join(', ') + '}';
      }
    }
  }

  function renderNode(node, depth, out) {
    const line = flat(node);
    if (line !== null && depth * 2 + line.length <= WIDTH) {
      out.push(line);
      return;
    }
    const indent = '  '.repeat(depth);
    if (node.kind === 'leaf') {
      out.push(node.json);
    } else if (node.kind === 'list') {
      out.push('[\n');
      node.items.forEach((item, i) => {
        out.push(indent + '  ');
        renderNode(item, depth + 1, out);
        out.push(i < node.items.length - 1 ? ',\n' : '\n');
      });
      out.push(indent + ']');
    } else {
      out.push('{\n');
      node.entries.forEach(([key, value], i) => {
        out.push(indent + '  ' + quote(key) + ': ');
        renderNode(value, depth + 1, out);
        out.push(i < node.entries.length - 1 ? ',\n' : '\n');
      });
      out.push(indent + '}');
    }
  }

  /** The node as text, with a line end after it. */
  function render(node) {
    const out = [];
    renderNode(node, 0, out);
    return out.join('') + '\n';
  }

  return { WIDTH, leaf, list, map, text, raw, texts, quote, render };
});
