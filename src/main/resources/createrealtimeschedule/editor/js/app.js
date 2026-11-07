/*
 * The editor's page: the timetable being edited, the lists of its lines, trains and duties, the
 * diagram of the line chosen, and the dialogs that bring a timetable and the track network in and
 * take the timetable out. What it changes it changes through edit.js, so that every change can be
 * undone; what it checks it checks with problems.js, as the mod does.
 */
(function () {
  'use strict';

  const rt = window.rt;
  const { time, i18n } = rt;
  const t = i18n.t;

  const STORE = 'createrealtimeschedule-editor';
  /** Where the network is kept, apart from the draft: with a map it can be large, and a draft too large to keep would lose the timetable too. */
  const NETWORK_STORE = 'createrealtimeschedule-editor-network';
  const SVG = 'http://www.w3.org/2000/svg';
  const MAX_UNDO = 200;
  /** How far apart, in pixels, the two points of a stop are drawn at the least, so that both can be taken. */
  const STRETCH_GAP = 10;

  const state = {
    timetable: rt.timetable.empty(),
    networkText: null,
    network: null,
    lineId: null,
    selection: null,
    // Times dragged or drawn land on the step, or on the fine step while Shift is held.
    settings: {
      language: i18n.guess(navigator.language), dwell: 20, snap: 15, fineSnap: 5, length: rt.edit.DEFAULT_LENGTH,
      // The diagram or the map, the lines whose track the map shows (none for all), what it shows on it,
      // and the track tags turned off ('' for no tag).
      view: 'diagram', mapLines: [], mapLayers: {}, mapTagsOff: [],
    },
    undo: [],
    redo: [],
  };

  /** What part of the diagram is shown; made again when another line is chosen. */
  let view = null;
  let viewLine = null;

  // ------------------------------------------------------------------ small things

  function $(id) {
    return document.getElementById(id);
  }

  /** An HTML element with attributes (and on... handlers) and children. */
  function h(tag, attributes, ...children) {
    const element = document.createElement(tag);
    for (const [name, value] of Object.entries(attributes || {})) {
      if (value === null || value === undefined || value === false)
        continue;
      if (name.startsWith('on'))
        element.addEventListener(name.slice(2), value);
      else if (name === 'class')
        element.className = value;
      else if (value === true)
        element.setAttribute(name, '');
      else
        element.setAttribute(name, value);
    }
    for (const child of children.flat())
      if (child !== null && child !== undefined && child !== false)
        element.append(child instanceof Node ? child : String(child));
    return element;
  }

  /** An SVG element with attributes. */
  function s(tag, attributes) {
    const element = document.createElementNS(SVG, tag);
    for (const [name, value] of Object.entries(attributes || {}))
      if (value !== null && value !== undefined)
        element.setAttribute(name, value);
    return element;
  }

  /** The colour a type is drawn in, as the game's diagram draws it: the timetable's for it, or the one picked from its name. */
  function typeColour(type) {
    const colour = rt.timetable.colour(state.timetable, type);
    return colour === null ? 'var(--train)' : colour;
  }

  /** A label of the time scale. */
  function scaleLabel(className, x, y, anchor, text) {
    const label = s('text', { class: className, x, y, 'text-anchor': anchor });
    label.textContent = text;
    return label;
  }

  /**
   * The hour a time is in, as the timetable counts it: on past 24 for the day after (24:10 is in hour
   * 24), and from 23 back for a time before 00:00, which is the day before.
   */
  function hourOf(second) {
    const hour = Math.floor(second / 3600);
    return String(hour < 0 ? ((hour % 24) + 24) % 24 : hour);
  }

  function timeText(seconds) {
    return seconds === rt.edit.NONE ? '' : time.show(seconds);
  }

  let measure = null;

  /** How wide a text is in the diagram's font. */
  function textWidth(text) {
    if (measure === null)
      measure = document.createElement('canvas').getContext('2d');
    measure.font = '12px ' + getComputedStyle(document.documentElement).fontFamily;
    return measure.measureText(text).width;
  }

  // ------------------------------------------------------------------ keeping the draft

  function save() {
    try {
      localStorage.setItem(STORE, JSON.stringify({
        version: 1,
        timetable: rt.timetable.write(state.timetable),
        network: null,
        lineId: state.lineId,
        settings: state.settings,
      }));
    } catch (e) {
      // A browser that keeps nothing for a page opened from a file: the draft lasts as long as the page.
    }
    try {
      if (state.networkText === null)
        localStorage.removeItem(NETWORK_STORE);
      else if (localStorage.getItem(NETWORK_STORE) !== state.networkText)
        localStorage.setItem(NETWORK_STORE, state.networkText);
    } catch (e) {
      // Too large to keep: the network lasts as long as the page, and has to be loaded again after.
      // The one kept before is not this one, and is not brought back beside this timetable.
      try {
        localStorage.removeItem(NETWORK_STORE);
      } catch (ignored) {
        // Nothing kept to take away.
      }
    }
  }

  let saveTimer = null;

  function saveSoon() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 300);
  }

  function restore() {
    let stored = null;
    try {
      stored = JSON.parse(localStorage.getItem(STORE));
    } catch (e) {
      return;
    }
    if (stored === null || typeof stored !== 'object')
      return;
    if (stored.settings && typeof stored.settings === 'object') {
      // Settings kept before there was a fine step have the step of 5 seconds that was then the
      // default, which the fine step now is: they take the new default step instead, and the
      // settings say so, for one who had chosen 5 seconds to choose it again.
      if (stored.settings.fineSnap === undefined && stored.settings.snap === 5) {
        delete stored.settings.snap;
        stepChanged = true;
      }
      Object.assign(state.settings, stored.settings);
    }
    if (typeof stored.timetable === 'string') {
      const read = rt.timetable.read(stored.timetable);
      if (read.timetable !== null)
        state.timetable = read.timetable;
    }
    // A draft kept before the network was kept apart has it in the draft.
    let networkText = typeof stored.network === 'string' ? stored.network : null;
    try {
      const kept = localStorage.getItem(NETWORK_STORE);
      if (kept !== null)
        networkText = kept;
    } catch (e) {
      // Nothing kept apart.
    }
    if (networkText !== null) {
      const read = rt.network.read(networkText);
      if (read.network !== null) {
        state.network = read.network;
        state.networkText = networkText;
      }
    }
    if (typeof stored.lineId === 'string')
      state.lineId = stored.lineId;
  }

  // ------------------------------------------------------------------ changes

  /** Makes next the timetable, keeping the one before for undo. */
  function change(next) {
    state.undo.push(state.timetable);
    if (state.undo.length > MAX_UNDO)
      state.undo.shift();
    state.redo = [];
    state.timetable = next;
    render();
  }

  function undo() {
    if (state.undo.length === 0)
      return;
    state.redo.push(state.timetable);
    state.timetable = state.undo.pop();
    render();
  }

  function redo() {
    if (state.redo.length === 0)
      return;
    state.undo.push(state.timetable);
    state.timetable = state.redo.pop();
    render();
  }

  function currentLine() {
    const lines = state.timetable.lines;
    let line = lines.find(each => each.id === state.lineId);
    if (line === undefined && lines.length > 0) {
      line = lines[0];
      state.lineId = line.id;
    }
    return line === undefined ? null : line;
  }

  function select(selection) {
    drawing = null;
    hover = null;
    if (selection === null || selection.kind !== 'duty' || state.selection === null || state.selection.id !== selection.id) {
      picking = false;
      dutyNote = '';
    }
    trainNote = '';
    state.selection = selection;
    if (selection !== null && selection.kind === 'train') {
      const train = state.timetable.trains.find(each => each.number === selection.number);
      if (train !== undefined && state.timetable.lines.some(line => line.id === train.line))
        state.lineId = train.line;
    }
    render();
  }

  function selectedDuty() {
    const selection = state.selection;
    if (selection === null || selection.kind !== 'duty')
      return null;
    const duty = state.timetable.duties.find(each => each.id === selection.id);
    return duty === undefined ? null : duty;
  }

  function selectedTrain() {
    const selection = state.selection;
    if (selection === null || selection.kind !== 'train')
      return null;
    const train = state.timetable.trains.find(each => each.number === selection.number);
    return train === undefined ? null : train;
  }

  // ------------------------------------------------------------------ drawing the page

  function render() {
    renderBar();
    renderTypeNames();
    renderTagNames();
    renderLines();
    renderTrains();
    renderDuties();
    renderCentre();
    renderInspector();
    renderProblems();
    renderStatus();
    saveSoon();
  }

  /** The words of the page that do not change, in the language chosen. */
  function renderWords() {
    i18n.setLanguage(state.settings.language);
    document.documentElement.lang = i18n.current();
    for (const element of document.querySelectorAll('[data-i18n]'))
      element.textContent = t(element.dataset.i18n);
    for (const element of document.querySelectorAll('[data-i18n-placeholder]'))
      element.placeholder = t(element.dataset.i18nPlaceholder);
    document.title = t('title');
    $('language').value = i18n.current();
  }

  function renderBar() {
    const name = $('timetable-name');
    if (document.activeElement !== name)
      name.value = state.timetable.name;
    $('undo').disabled = state.undo.length === 0;
    $('redo').disabled = state.redo.length === 0;
    const line = currentLine();
    const draw = $('draw-train');
    draw.disabled = line === null || line.stations.length < 2;
    draw.classList.toggle('active', drawing !== null);
  }

  function renderLines() {
    const list = $('lines');
    list.replaceChildren();
    const current = currentLine();
    for (const line of state.timetable.lines)
      list.append(h('li', {
        class: current !== null && line.id === current.id ? 'selected' : null,
        title: line.stations.join(' - '),
        onclick: () => {
          state.lineId = line.id;
          render();
        },
        ondblclick: () => lineDialog(line),
      }, line.name || line.id, ' ', h('span', { class: 'sub' }, (line.loop ? '↻ ' : '') + line.stations.length)));
  }

  /** The names the boxes for a type offer: the types of the list. */
  function renderTypeNames() {
    $('type-names').replaceChildren(...rt.edit.typeList(state.timetable).map(type => h('option', { value: type.name })));
  }

  /** The names the boxes for a track tag offer: the network's tags, and those the trains are held to. */
  function renderTagNames() {
    const names = (state.network === null ? [] : state.network.tags).slice();
    for (const tag of rt.edit.tagsOf(state.timetable))
      if (!names.includes(tag))
        names.push(tag);
    $('tag-names').replaceChildren(...names.map(name => h('option', { value: name })));
  }

  function firstDeparture(train) {
    return train.stops.length > 0 ? train.stops[0].depart : Infinity;
  }

  function renderTrains() {
    const list = $('trains');
    list.replaceChildren();
    const line = currentLine();
    if (line === null)
      return;
    const trains = state.timetable.trains.filter(train => train.line === line.id);
    trains.sort((a, b) => firstDeparture(a) - firstDeparture(b));
    const selected = selectedTrain();
    for (const train of trains) {
      const first = train.stops[0];
      const last = train.stops[train.stops.length - 1];
      const route = first === undefined ? '' : first.station + ' ' + timeText(first.depart) + ' → ' + last.station + ' ' + timeText(last.arrive);
      list.append(h('li', {
        class: selected !== null && selected.number === train.number ? 'selected' : null,
        onclick: () => select({ kind: 'train', number: train.number }),
      }, train.number, train.deadhead ? ' (' + t('deadhead') + ')' : '', train.keep === true ? ' (' + t('keep') + ')' : '', ' ',
        h('span', { class: 'sub' }, route)));
    }
  }

  function renderDuties() {
    const list = $('duties');
    list.replaceChildren();
    for (const duty of state.timetable.duties)
      list.append(h('li', {
        class: state.selection !== null && state.selection.kind === 'duty' && state.selection.id === duty.id ? 'selected' : null,
        onclick: () => select({ kind: 'duty', id: duty.id }),
      }, duty.id, ' ', h('span', { class: 'sub' }, duty.depot + ': ' + duty.trains.join(', '))));
  }

  function renderStatus() {
    const timetable = state.timetable;
    $('status').textContent = t('status', timetable.name || t('untitled'), timetable.lines.length, timetable.trains.length, timetable.duties.length)
      + '  ' + (state.network === null ? t('statusNoNetwork') : t('statusNetwork', state.network.stations.length, state.network.legs.length));
    $('versions').textContent = t('versions', rt.timetable.VERSION, rt.network.VERSION);
  }

  /** The train, duty, line or station a problem is about, from the way the mod words it. */
  function problemTarget(problem) {
    if (/^stations?[ []/.test(problem))
      return { kind: 'station' };
    if (/^types?[ []/.test(problem))
      return { kind: 'types' };
    const match = /^(line|train|duty) "((?:[^"\\]|\\.)*)"/.exec(problem);
    if (match === null)
      return null;
    if (match[1] === 'line')
      return { kind: 'line', id: match[2] };
    return match[1] === 'train' ? { kind: 'train', number: match[2] } : { kind: 'duty', id: match[2] };
  }

  function renderProblems() {
    const found = rt.problems.problems(state.timetable);
    // Not what the mod refuses a timetable for, but a train that cannot keep its times on this track,
    // or run at all on the track tag it is held to, and what is wrong with the track's tags.
    const network = state.network;
    const fast = network === null ? [] : rt.edit.tooFast(state.timetable, runningTimes());
    const reds = network === null ? [] : rt.edit.redSignals(state.timetable, runningTimes());
    // A timetable that holds up too many trains for too long to be worked out: the signals are left out.
    const ran = traffic();
    const unworked = ran !== null && !ran.finished ? 1 : 0;
    const track = network === null ? null
      : { platforms: station => rt.network.platforms(network, station), way: (platform, station, tag) => rt.network.way(network, platform, station, tag) };
    const noWays = track === null ? [] : rt.edit.noWay(state.timetable, track);
    // A network of before the tags (version 2 and older) cannot say which tags the world has.
    const unknown = network === null || !network.tagged ? [] : rt.edit.tagsOf(state.timetable).filter(tag => !network.tags.includes(tag));
    const tagProblems = network === null ? [] : network.tagProblems;
    const notes = fast.length + reds.length + unworked + noWays.length + unknown.length + tagProblems.length;
    $('problems-title').textContent = (found.length === 0 ? t('noProblems') : t('problemCount', found.length))
      + (notes === 0 ? '' : t('tooFastCount', notes));
    // Tracks the timetable names that the map does not have: laid again since, or taken up.
    const missing = network === null || network.map === null ? []
      : rt.map.missing(network.map.tracks.map(track => track.key), state.timetable);
    if (missing.length > 0)
      $('problems-title').textContent += t('mapMissingCount', missing.length);
    const list = $('problems');
    list.replaceChildren();
    for (const key of missing)
      list.append(h('li', { class: 'warning' }, t('mapMissing', key)));
    const place = at => (Array.isArray(at) ? at.join(' ') : '?');
    for (const problem of tagProblems)
      list.append(h('li', { class: 'warning' }, problem.problem === 'junction'
        ? t('tagJunction', place(problem.block)) : t('tagMixed', place(problem.block), place(problem.other))));
    for (const tag of unknown)
      list.append(h('li', { class: 'warning' }, t('unknownTag', tag)));
    for (const way of noWays)
      list.append(h('li', { class: 'warning', onclick: () => select({ kind: 'train', number: way.train }) },
        t('noWay', way.train, way.from, way.stop + 1, way.to, way.tag)));
    for (const way of fast)
      list.append(h('li', { class: 'warning', onclick: () => select({ kind: 'train', number: way.number }) },
        way.signals === undefined ? t('tooFast', way.number, way.from, way.to, way.given, way.needed)
          : t('tooFastSignals', way.number, way.from, way.to, way.given, way.needed, way.signals)));
    if (unworked > 0)
      list.append(h('li', { class: 'warning' }, t('trafficUnfinished')));
    for (const way of reds)
      list.append(h('li', { class: 'warning', onclick: () => select({ kind: 'train', number: way.number }) },
        way.stuck ? t('heldUp', way.number, way.from, way.to)
          : t('redSignal', way.number, way.from, way.to, way.at === null ? '?' : way.at, way.seconds)));
    for (const problem of found)
      list.append(h('li', {
        onclick: () => {
          const target = problemTarget(problem);
          if (target === null)
            return;
          if (target.kind === 'station') {
            stationTimesDialog();
          } else if (target.kind === 'types') {
            typesDialog();
          } else if (target.kind === 'line') {
            state.lineId = target.id;
            render();
          } else {
            select(target);
          }
        },
      }, problem));
  }

  // ------------------------------------------------------------------ the diagram

  /**
   * A train being drawn: the stops so far, the type and mark it will have, the dwell it is given,
   * and whether its last stop is still to be left, which is so from a click that arrives at a stop
   * until a second click on the same row says when the train leaves. Null when none is.
   */
  let drawing = null;
  /** Where the pointer is while a train is drawn: the station row nearest it, and the time. */
  let hover = null;
  /** A stop (index) or a whole train (index -1) being dragged, from the timetable as it was. */
  let dragging = null;
  /** The timetable as the drag going on would leave it; null when nothing is dragged. */
  let shown = null;
  /** The line's axis and the rows of its stations on the screen, as the diagram was last drawn. */
  let drawn = null;
  /** Whether a click on a train adds it to the duty chosen, rather than choosing the train. */
  let picking = false;
  /** What the last change to a duty's deadheads had to say. */
  let dutyNote = '';
  /** Whether the step kept from before the fine step came in was changed to the new default, which the settings tell. */
  let stepChanged = false;
  /** What the last time typed for the train chosen had to say, if it was held to the track's running times. */
  let trainNote = '';

  function lineAxis(line) {
    return rt.axis.of(line, state.network === null ? [] : rt.network.untaggedLegs(state.network));
  }

  function shownTimetable() {
    return shown !== null ? shown : state.timetable;
  }

  /** The seconds a train held to the track tag tag ('' or none for none) takes from one station to the other, by the network; null if it does not say. */
  function running(from, to, tag) {
    return state.network === null ? null : rt.network.seconds(state.network, from, to, tag || '');
  }

  /**
   * The minimum stop of the server where the timetable gives none, as the mod's setting
   * default_minimum_stop_seconds has it unless it is changed; the editor cannot know a server's own.
   */
  const SERVER_MINIMUM_STOP = 20;
  /** The trains of the timetable run with the signals, as last worked out, and what for. */
  let trafficMemo = null;

  /**
   * The trains of the timetable running on time together with the signals of the network (traffic.js),
   * worked out again only when the timetable, the network or the length of a train changes: while a
   * stop is dragged, the timetable it is dragged from is what they run. Null for a network without
   * signal blocks (version 4 and before) or without the speeds of its train.
   */
  function traffic() {
    const network = state.network;
    if (network === null || !network.blocks || !network.train)
      return null;
    const timetable = state.timetable;
    const length = state.settings.length;
    if (trafficMemo !== null && trafficMemo.timetable === timetable && trafficMemo.network === network && trafficMemo.length === length)
      return trafficMemo.ran;
    const ran = rt.traffic.ofTimetable(timetable, (from, to, tag) => rt.network.leg(network, from, to, tag || ''), network.blocks,
      network.train, { minimumStop: SERVER_MINIMUM_STOP, length });
    trafficMemo = { timetable, network, length, ran };
    return ran;
  }

  /**
   * The running times of the network with its speed-limit signs, as the changes of edit take them
   * (edit.trackOf): a train still under the limit of a sign it passed before is slower until it
   * passes the next. With signal blocks, a train of the timetable is slower too by what the signals
   * add to its ways running on time with the others (traffic). Null without a network.
   */
  function runningTimes() {
    if (state.network === null)
      return null;
    const times = {
      seconds: (from, to, tag, limit) => rt.network.seconds(state.network, from, to, tag || '', limit),
      limitAfter: (from, to, tag, limit) => rt.network.limitAfter(state.network, from, to, tag || '', limit),
    };
    const ran = traffic();
    if (ran === null || !ran.finished)
      return times;
    times.ticks = (from, to, tag, limit) => rt.network.ticks(state.network, from, to, tag || '', limit);
    times.signalTicks = (number, i) => ran.signalTicks(number, i);
    times.trip = (number, i) => ran.trip(number, i);
    times.leg = (from, to, tag) => rt.network.leg(state.network, from, to, tag || '');
    return times;
  }

  /** The seconds a train drawn at the station stands there: the station's own, or the setting's. */
  function dwellAt(station) {
    return rt.edit.dwellAt(state.timetable, station, state.settings.dwell);
  }

  /** The seconds the train being drawn stands at the station: its own dwell, if it is given one, or else the station's. */
  function drawingDwellAt(station) {
    return drawing.dwell !== rt.edit.NONE ? drawing.dwell : dwellAt(station);
  }

  /** Shows all the trains of the line, or the morning if it has none. */
  function fitView(line) {
    const trains = state.timetable.trains.filter(train => train.line === line.id && train.stops.length > 0);
    let from = Infinity;
    let to = -Infinity;
    for (const train of trains)
      for (const stop of train.stops)
        for (const second of [stop.arrive, stop.depart])
          if (second !== rt.edit.NONE) {
            from = Math.min(from, second);
            to = Math.max(to, second);
          }
    if (from === Infinity) {
      view.show(6 * 3600, 10 * 3600);
    } else {
      const pad = Math.max(10 * 60, (to - from) * 0.1);
      view.show(from - pad, to + pad);
    }
  }

  function pathOf(segments, length) {
    return segments.map(segment => 'M' + view.x(segment.fromSecond).toFixed(1) + ',' + view.y(segment.fromPosition, length).toFixed(1)
      + 'L' + view.x(segment.toSecond).toFixed(1) + ',' + view.y(segment.toPosition, length).toFixed(1)).join('');
  }

  function renderDiagram() {
    const svg = $('diagram');
    svg.replaceChildren();
    svg.classList.toggle('drawing', drawing !== null);
    drawn = null;
    const line = currentLine();
    $('empty-message').textContent = line === null ? t('noLine') : line.stations.length < 2 ? t('noStations') : '';
    if (line === null || line.stations.length < 2)
      return;
    const area = $('diagram-area');
    const width = area.clientWidth;
    const height = area.clientHeight;
    const axisOfLine = lineAxis(line);
    const length = axisOfLine.length();

    let labelWidth = 0;
    for (const station of axisOfLine.stations)
      labelWidth = Math.max(labelWidth, textWidth(station));
    const left = Math.ceil(labelWidth) + 20;
    const top = 18;
    const right = 18;
    const bottom = 30;
    if (view === null || viewLine !== line.id) {
      view = new rt.layout.View(0, 4 * 3600);
      view.place(left, top, width - left - right, height - top - bottom);
      fitView(line);
      viewLine = line.id;
    }
    view.place(left, top, width - left - right, height - top - bottom);

    const plotRight = width - right;
    const plotBottom = height - bottom;
    const defs = s('defs');
    const arrow = s('marker', { id: 'wait-arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' });
    arrow.append(s('path', { class: 'wait-head', d: 'M 0 0 L 10 5 L 0 10 z' }));
    defs.append(arrow);
    const clip = s('clipPath', { id: 'plot' });
    clip.append(s('rect', { x: left, y: top - 6, width: Math.max(0, plotRight - left), height: Math.max(0, plotBottom - top + 12) }));
    defs.append(clip);
    svg.append(defs);

    // the stations
    const rows = [];
    axisOfLine.stations.forEach((station, row) => {
      const y = view.y(axisOfLine.position(row), length);
      rows.push({ station, y });
      svg.append(s('line', { class: 'grid strong', x1: left, y1: y, x2: plotRight, y2: y }));
      const label = s('text', { class: 'station', x: left - 8, y: y + 4, 'text-anchor': 'end' });
      label.textContent = station;
      svg.append(label);
    });
    drawn = { axis: axisOfLine, length, rows };

    // The time scale: the hour, large, on each hour's line, and the minutes past it, small, on the
    // lines between; and the hour the diagram starts in at its left edge, should no hour's line be there.
    const step = view.step(40);
    const first = Math.ceil(view.start() / step) * step;
    if (first % 3600 !== 0)
      svg.append(scaleLabel('label hour', left, plotBottom + 20, 'start', hourOf(view.start())));
    for (let second = first; second <= view.end(); second += step) {
      const x = view.x(second);
      const hour = second % 3600 === 0;
      svg.append(s('line', { class: hour ? 'grid strong' : 'grid', x1: x, y1: top, x2: x, y2: plotBottom }));
      // The minutes next to the hour at the left edge would run into it.
      if (!hour && first % 3600 !== 0 && x - left < 28)
        continue;
      svg.append(hour ? scaleLabel('label hour', x, plotBottom + 20, 'middle', hourOf(second))
        : scaleLabel('label minute', x, plotBottom + 18, 'middle', String(Math.floor(((second % 3600) + 3600) % 3600 / 60))));
    }

    // the trains
    const plot = s('g', { 'clip-path': 'url(#plot)' });
    svg.append(plot);
    const selected = selectedTrain();
    const duty = selectedDuty();
    const inDuty = new Set(duty === null ? [] : duty.trains);
    for (const planned of rt.layout.plannedTrains(shownTimetable(), axisOfLine)) {
      const d = pathOf(planned.segments, length);
      const isSelected = selected !== null && selected.number === planned.number;
      const colourOf = type => (planned.deadhead ? 'var(--deadhead)' : typeColour(type));
      const colour = colourOf(planned.type);
      const marks = (planned.deadhead ? ' deadhead' : '') + (isSelected ? ' selected' : '')
        + (duty === null ? '' : inDuty.has(planned.number) ? ' in-duty' : ' dim');
      const group = s('g', { class: 'train' + marks, 'data-number': planned.number });
      // A line for each colour: a train that changes its type at a stop changes its colour there.
      const byColour = new Map();
      planned.segments.forEach((segment, i) => {
        const each = colourOf(planned.types[i]);
        if (!byColour.has(each))
          byColour.set(each, []);
        byColour.get(each).push(segment);
      });
      for (const [each, segments] of byColour)
        group.append(s('path', { class: 'shown', d: segments.length === planned.segments.length ? d : pathOf(segments, length), stroke: each }));
      // Each part of the line takes hold of its own: a press on it chooses the train, and dragging it
      // on moves the way between two stops alone, or the whole train from where it stands; a press
      // that does not move is only the choosing.
      for (const part of planned.parts) {
        const hit = s('path', { class: part.kind === 'run' ? 'hit run' : 'hit', d: pathOf(part.segments, length) });
        hit.addEventListener('pointerdown', event => {
          if (drawing !== null || event.button !== 0)
            return; // a click while drawing goes to the row under it
          if (picking && duty !== null) {
            event.stopPropagation();
            dutyNote = '';
            change(rt.duties.appendTrain(state.timetable, duty.id, planned.number));
            return;
          }
          if (!isSelected)
            select({ kind: 'train', number: planned.number });
          // With Ctrl, a way moves the whole train too, for a train that stands nowhere on the way.
          if (part.kind === 'run' && !event.ctrlKey && !event.metaKey)
            startDrag(planned.number, part.stop, event, 'run');
          else
            startDrag(planned.number, -1, event);
        });
        group.append(hit);
      }
      if (!Number.isNaN(planned.labelSecond)) {
        const number = s('text', { class: 'number', x: view.x(planned.labelSecond) + 3, y: view.y(planned.labelPosition, length) - 4, fill: colour });
        number.textContent = planned.number;
        group.append(number);
      }
      plot.append(group);
      if (isSelected && drawing === null) {
        const train = shownTimetable().trains.find(each => each.number === planned.number);
        const stops = train.stops;
        for (const point of planned.points) {
          // Of a stop both arrived at and left, by the timetable, the point it comes in at moves the
          // arrival alone and the one it leaves at stretches the stop: either way it stands longer or
          // shorter. A stop with one time (the first, the last, one with no arrival time, which the
          // diagram draws as arrived at when it is left) moves by its points as a whole.
          const stop = stops[point.stop];
          const both = stop.arrive !== rt.edit.NONE && stop.depart !== rt.edit.NONE;
          const arrivalPart = stop.arrive !== rt.edit.NONE ? 'arrive' : 'stop';
          for (const [second, position, part] of [[point.arriveSecond, point.arrivePosition, arrivalPart],
            [point.departSecond, point.departPosition, both ? 'stretch' : 'stop']])
            if (!Number.isNaN(second) && !Number.isNaN(position)) {
              let x = view.x(second);
              // A stop stood at for no time, or next to none, has its two points in one place: the one
              // it is left at goes beside the other, so that either can be taken.
              if (part === 'stretch' && x - view.x(point.arriveSecond) < STRETCH_GAP)
                x = view.x(point.arriveSecond) + STRETCH_GAP;
              const handle = s('circle', { class: both ? 'point stretch' : 'point', cx: x, cy: view.y(position, length), r: 5,
                fill: colourOf(rt.timetable.typeAt(train, point.stop)) });
              handle.addEventListener('pointerdown', event => {
                if (event.button === 0)
                  startDrag(planned.number, point.stop, event, part);
              });
              plot.append(handle);
            }
        }
      }
    }

    for (const guide of fastestRuns(axisOfLine))
      plot.append(s('path', { class: 'guide', d: pathOf(guide, length) }));
    // The waits: from where the train waited for leaves its station, or runs through it, to where the train that waits leaves.
    for (const mark of rt.layout.waitMarks(shownTimetable(), axisOfLine))
      plot.append(s('path', {
        class: 'wait', 'marker-end': 'url(#wait-arrow)',
        d: 'M ' + view.x(mark.fromSecond) + ' ' + view.y(mark.fromPosition, length) + ' L ' + view.x(mark.toSecond) + ' ' + view.y(mark.toPosition, length),
      }));
    plot.append(s('g', { id: 'preview' }));
    renderPreview();
    svg.append(s('g', { id: 'drag-label' }));
    renderDragLabel();
  }

  /** What a drag going on has made of the times it takes hold of, in words, for the label beside the pointer. */
  function dragText() {
    const stops = shownTimetable().trains.find(each => each.number === dragging.number).stops;
    if (dragging.index < 0)
      return t('departAt', time.show(stops[0].depart));
    const stop = stops[dragging.index];
    const standing = stop.arrive !== rt.edit.NONE && stop.depart !== rt.edit.NONE ? ' ' + t('standing', stop.depart - stop.arrive) : '';
    switch (dragging.part) {
      case 'arrive':
        return t('arriveAt', time.show(stop.arrive)) + standing;
      case 'stretch':
        return t('departAt', time.show(stop.depart)) + standing;
      case 'run': {
        const next = stops[dragging.index + 1];
        return t('departAt', time.show(stop.depart)) + ' → ' + t('arriveAt', time.show(next.arrive !== rt.edit.NONE ? next.arrive : next.depart));
      }
      default:
        return stop.depart !== rt.edit.NONE ? t('departAt', time.show(stop.depart)) : t('arriveAt', time.show(stop.arrive));
    }
  }

  /** The times a drag going on has come to, beside the pointer, kept inside the diagram. */
  function renderDragLabel() {
    const group = document.getElementById('drag-label');
    if (group === null)
      return;
    group.replaceChildren();
    if (dragging === null || dragging.px === undefined)
      return;
    const text = dragText();
    const width = textWidth(text) + 12;
    const area = $('diagram').clientWidth;
    const x = dragging.px + 14 + width <= area ? dragging.px + 14 : Math.max(0, dragging.px - 14 - width);
    const y = Math.max(0, dragging.py - 34);
    group.append(s('rect', { class: 'drag-box', x, y, width, height: 20, rx: 4 }));
    const label = s('text', { class: 'drag-text', x: x + 6, y: y + 14 });
    label.textContent = text;
    group.append(label);
  }

  /**
   * While a stop or a way of a train is dragged, the ways it changes as the train would run them
   * fastest: leaving the stop before when it does, and coming in at the next the track's running time
   * later. The diagram draws them as a guide, segments for each way; none for a way the track gives no
   * running time for, nor while the whole train is moved, which changes no way.
   */
  function fastestRuns(axisOfLine) {
    if (dragging === null || dragging.index < 0)
      return [];
    const train = shownTimetable().trains.find(each => each.number === dragging.number);
    if (train === undefined || train.line !== axisOfLine.line)
      return [];
    const k = dragging.index;
    // The way into the stop for its arrival, the way out of it for its departure or the way itself,
    // and both for a stop with one time.
    const ways = dragging.part === 'arrive' ? [k - 1] : dragging.part === 'stretch' || dragging.part === 'run' ? [k] : [k - 1, k];
    const guides = [];
    const way = rt.edit.forTrain(runningTimes(), train);
    for (const i of ways) {
      const from = train.stops[i];
      const to = train.stops[i + 1];
      if (from === undefined || to === undefined || from.depart === rt.edit.NONE)
        continue;
      const seconds = way === null ? null : way(train.stops, i);
      if (seconds === null)
        continue;
      const fastest = { number: '', line: train.line, type: '', deadhead: false, stops: [
        { station: from.station, arrive: rt.edit.NONE, depart: from.depart },
        { station: to.station, arrive: from.depart + seconds, depart: rt.edit.NONE }] };
      guides.push(rt.layout.plannedTrain(axisOfLine, fastest).segments);
    }
    return guides;
  }

  /** The train being drawn, with the stop it would get where the pointer is. */
  function renderPreview() {
    const group = document.getElementById('preview');
    if (group === null || drawn === null)
      return;
    group.replaceChildren();
    if (drawing === null)
      return;
    const click = hover === null ? null : clickAt(hover);
    const stops = click === null ? drawing.stops : click.stops;
    const following = click !== null && click.left;
    if (stops.length === 0)
      return;
    const train = { number: '', line: drawn.axis.line, type: drawing.type, deadhead: drawing.deadhead,
      stops: stops.length >= 2 ? rt.edit.finishDrawing(stops) : stops };
    const planned = rt.layout.plannedTrain(drawn.axis, train);
    group.append(s('path', { class: 'preview', d: pathOf(planned.segments, drawn.length) }));
    for (const point of planned.points)
      for (const [second, position] of [[point.arriveSecond, point.arrivePosition], [point.departSecond, point.departPosition]])
        if (!Number.isNaN(second) && !Number.isNaN(position))
          group.append(s('circle', { class: 'point', cx: view.x(second), cy: view.y(position, drawn.length), r: 3, fill: 'var(--accent)' }));
    const end = stops[stops.length - 1];
    const endY = view.y(drawn.axis.position(Math.max(0, drawn.axis.row(end.station))), drawn.length);
    // The diagram has the last stop only arrived at; while it is still to be left, it stands until its departure.
    if (stops.length >= 2 && end.arrive !== rt.edit.NONE && end.depart !== rt.edit.NONE) {
      group.append(s('path', { class: 'preview', d: 'M' + view.x(end.arrive).toFixed(1) + ',' + endY.toFixed(1) + 'L' + view.x(end.depart).toFixed(1) + ',' + endY.toFixed(1) }));
      group.append(s('circle', { class: 'point', cx: view.x(end.depart), cy: endY, r: 3, fill: 'var(--accent)' }));
    }
    const at = end.arrive !== rt.edit.NONE ? end.arrive : end.depart;
    const label = s('text', { class: 'label', x: view.x(at) + 6, y: endY + 14 });
    label.textContent = following
      ? timeText(end.arrive) + ' - ' + timeText(end.depart) + ' (' + (end.depart - end.arrive) + ' ' + t('seconds') + ')'
      : timeText(at);
    group.append(label);
  }

  /**
   * The running time from the last stop of the train being drawn to station, under the limit of a
   * speed-limit sign it passed since its first stop; null where the track does not say.
   */
  function drawnRunning(station) {
    const way = rt.edit.forTrain(runningTimes(), {});
    const stops = drawing.stops.concat([{ station }]);
    return way === null ? null : way(stops, stops.length - 2);
  }

  /** What a click at point does to the train being drawn (see edit.drawClick). */
  function clickAt(point) {
    const last = drawing.stops[drawing.stops.length - 1];
    return rt.edit.drawClick(drawing.stops, drawing.leaving, point.station, point.second,
      last === undefined ? null : drawnRunning(point.station), drawingDwellAt(point.station), stepOf(point.fine));
  }

  /** The step times land on: the fine one while Shift is held, else the usual one. */
  function stepOf(fine) {
    return fine ? state.settings.fineSnap : state.settings.snap;
  }

  /** The station row nearest the pointer, and the time under it. */
  function pointAt(event) {
    const box = $('diagram').getBoundingClientRect();
    const x = event.clientX - box.left;
    const y = event.clientY - box.top;
    let best = null;
    for (const row of drawn.rows)
      if (best === null || Math.abs(row.y - y) < Math.abs(best.y - y))
        best = row;
    return { station: best.station, second: view.second(x), fine: event.shiftKey };
  }

  /** Keeps the pointer's events coming to the diagram while a button is held, if the browser still knows the pointer. */
  function capture(pointerId) {
    try {
      $('diagram').setPointerCapture(pointerId);
    } catch (e) {
      // A pointer already gone: the drag goes on while the pointer stays over the diagram.
    }
  }

  /**
   * Starts dragging a stop (index), or the whole train (index -1). part says what of a stop is dragged:
   * 'stop' moves it as a whole, 'arrive' its arrival alone, 'stretch' its departure and the stops after
   * it, and 'run' the way from it to the next stop.
   */
  function startDrag(number, index, event, part) {
    event.stopPropagation();
    const box = $('diagram').getBoundingClientRect();
    dragging = { number, index, part: part || 'stop', x0: event.clientX, base: state.timetable, delta: 0, id: event.pointerId,
      px: event.clientX - box.left, py: event.clientY - box.top };
    capture(event.pointerId);
    // The guide of the fastest ways, and the label of the times, from the first moment of a drag.
    renderDiagram();
  }

  function startDrawing() {
    const line = currentLine();
    if (line === null || line.stations.length < 2)
      return;
    drawing = { stops: [], type: drawing !== null ? drawing.type : '', deadhead: false, dwell: drawing !== null ? drawing.dwell : rt.edit.NONE, leaving: false };
    hover = null;
    state.selection = null;
    // A train is drawn on the diagram.
    state.settings.view = 'diagram';
    render();
  }

  function addDrawnStop(point) {
    const done = clickAt(point);
    drawing.stops = done.stops;
    drawing.leaving = done.leaving;
    renderInspector();
    renderPreview();
  }

  function finishDrawing() {
    if (drawing === null)
      return;
    const stops = rt.edit.finishDrawing(drawing.stops);
    const line = currentLine();
    const { type, deadhead } = drawing;
    drawing = null;
    hover = null;
    if (stops === null || line === null) {
      render();
      return;
    }
    const number = rt.edit.nextNumber(state.timetable, deadhead);
    state.selection = { kind: 'train', number };
    change(rt.edit.addTrain(state.timetable, { number, line: line.id, type, deadhead, stops }));
  }

  function cancelDrawing() {
    drawing = null;
    hover = null;
    render();
  }

  function bindDiagram() {
    const svg = $('diagram');
    svg.addEventListener('wheel', event => {
      if (view === null)
        return;
      event.preventDefault();
      const box = svg.getBoundingClientRect();
      view.wheel(rt.layout.wheelNotches(event.deltaY, event.deltaMode), event.clientX - box.left);
      renderDiagram();
    }, { passive: false });

    let gesture = null;
    svg.addEventListener('pointerdown', event => {
      if (view === null || event.button !== 0 || dragging !== null)
        return;
      gesture = { x: event.clientX, startX: event.clientX, id: event.pointerId, moved: false };
      capture(event.pointerId);
    });
    svg.addEventListener('pointermove', event => {
      if (dragging !== null && event.pointerId === dragging.id) {
        const box = svg.getBoundingClientRect();
        dragging.px = event.clientX - box.left;
        dragging.py = event.clientY - box.top;
        const raw = (event.clientX - dragging.x0) / view.width * view.span;
        const moved = rt.edit.drag(dragging.base, dragging.number, dragging.index, dragging.part, raw, runningTimes(), stepOf(event.shiftKey));
        if (moved.delta !== dragging.delta) {
          dragging.delta = moved.delta;
          shown = moved.delta === 0 ? null : moved.timetable;
          renderDiagram();
        } else {
          renderDragLabel();
        }
        return;
      }
      if (gesture !== null && event.pointerId === gesture.id) {
        if (!gesture.moved && Math.abs(event.clientX - gesture.startX) < 4)
          return;
        gesture.moved = true;
        view.pan(event.clientX - gesture.x);
        gesture.x = event.clientX;
        renderDiagram();
        return;
      }
      if (drawing !== null && drawn !== null) {
        hover = pointAt(event);
        renderPreview();
      }
    });
    const end = event => {
      if (dragging !== null && event.pointerId === dragging.id) {
        const next = shown;
        const { base, number } = dragging;
        shown = null;
        dragging = null;
        // A drag that changed nothing (a stop stretched no shorter than nothing) is not a change to
        // undo; a drag changes only the train dragged, so only it is compared.
        if (next !== null && event.type === 'pointerup' && !rt.edit.sameStops(next, base, number))
          change(next);
        else
          renderDiagram();
        return;
      }
      if (gesture === null || event.pointerId !== gesture.id)
        return;
      const clicked = !gesture.moved && event.type === 'pointerup';
      gesture = null;
      if (!clicked)
        return;
      if (drawing !== null && drawn !== null)
        addDrawnStop(pointAt(event));
      else if (state.selection !== null)
        select(null);
    };
    svg.addEventListener('pointerup', end);
    svg.addEventListener('pointercancel', end);
    svg.addEventListener('dblclick', () => finishDrawing());
    svg.addEventListener('pointerleave', () => {
      if (drawing !== null && hover !== null) {
        hover = null;
        renderPreview();
      }
    });
    new ResizeObserver(() => renderCentre()).observe($('diagram-area'));
  }

  // ------------------------------------------------------------------ the map

  /** What part of the map is shown, and the map it was fitted to: fitted again when another comes in. */
  let mapView = null;
  let mapFor = null;
  /** The index of the dimension shown, in the map's list. */
  let mapDimension = 0;
  /** The index of the track chosen on the map, or null. */
  let mapTrack = null;

  function onMap() {
    return state.settings.view === 'map';
  }

  function layerOn(name) {
    return !state.settings.mapLayers || state.settings.mapLayers[name] !== false;
  }

  /** The diagram or the map, whichever is shown. */
  function renderCentre() {
    $('view-diagram').classList.toggle('active', !onMap());
    $('view-map').classList.toggle('active', onMap());
    // An svg has no hidden property, as an HTML element has: the attribute it is.
    $('diagram').toggleAttribute('hidden', onMap());
    $('map').toggleAttribute('hidden', !onMap());
    $('map-tools').hidden = !onMap();
    if (onMap())
      renderMap();
    else
      renderDiagram();
  }

  function setView(name) {
    if (state.settings.view === name)
      return;
    state.settings.view = name;
    if (name === 'map') {
      drawing = null;
      hover = null;
    }
    render();
  }

  /** The tracks of the dimension shown that a line's trains run along, as if it added and took away none. */
  function routeOf(map, line) {
    return new Set(rt.map.route(map.tracks.map(track => track.key), state.network.legs, state.timetable, line));
  }

  function renderMapTools() {
    const lines = $('map-lines-list');
    lines.replaceChildren();
    const chosen = new Set(rt.map.chosenLines(state.timetable, state.settings.mapLines));
    lines.append(h('p', { class: 'note' }, t('mapLinesHelp')));
    for (const line of state.timetable.lines)
      lines.append(h('label', { class: 'check' }, h('input', {
        type: 'checkbox', checked: chosen.has(line.id),
        onchange: event => {
          const next = new Set(chosen);
          if (event.target.checked)
            next.add(line.id);
          else
            next.delete(line.id);
          state.settings.mapLines = state.timetable.lines.map(each => each.id).filter(id => next.has(id));
          render();
        },
      }), line.name));
    $('map-lines-summary').textContent = chosen.size === 0 ? t('mapAllLines') : t('mapSomeLines', chosen.size);

    renderMapTags();

    const layers = $('map-layers-list');
    layers.replaceChildren();
    for (const name of rt.map.LAYERS)
      layers.append(h('label', { class: 'check' }, h('input', {
        type: 'checkbox', checked: layerOn(name),
        onchange: event => {
          state.settings.mapLayers = Object.assign({}, state.settings.mapLayers, { [name]: event.target.checked });
          render();
        },
      }), t('layer_' + name)));

    const select = $('map-dimension');
    const map = state.network === null ? null : state.network.map;
    const dimensions = map === null ? [] : map.dimensions;
    select.hidden = dimensions.length < 2;
    select.replaceChildren(...dimensions.map((name, i) => h('option', { value: String(i), selected: i === mapDimension }, name)));
  }

  /** The track tags turned off on the map, '' among them for no tag. */
  function tagsOff() {
    return Array.isArray(state.settings.mapTagsOff) ? state.settings.mapTagsOff : [];
  }

  /** The colour a track tag is drawn in: the one the world gave it, or the one its name picks. */
  function tagColour(network, tag) {
    const colours = network === null ? {} : network.tagColours || {};
    return typeof colours[tag] === 'string' ? colours[tag] : rt.timetable.colourByName(tag);
  }

  /** The menu of the track tags to show: no tag, then the world's tags, each in its colour. */
  function renderMapTags() {
    const list = $('map-tags-list');
    list.replaceChildren();
    const network = state.network;
    const tags = network === null ? [] : network.tags;
    const off = new Set(tagsOff());
    list.append(h('p', { class: 'note' }, tags.length === 0 ? t('mapNoTags') : t('mapTagsHelp')));
    const toggle = (tag) => event => {
      const next = new Set(tagsOff());
      if (event.target.checked)
        next.delete(tag);
      else
        next.add(tag);
      state.settings.mapTagsOff = [...next];
      render();
    };
    list.append(h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !off.has(rt.map.NO_TAG), onchange: toggle(rt.map.NO_TAG) }),
      t('mapNoTag')));
    for (const tag of tags)
      list.append(h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: !off.has(tag), onchange: toggle(tag) }),
        h('span', { class: 'tag-swatch', style: 'background: ' + tagColour(network, tag) }), tag));
    // A tag turned off that the world no longer has hides nothing, and is not counted.
    const hidden = [...off].filter(tag => tag === rt.map.NO_TAG || tags.includes(tag)).length;
    $('map-tags-summary').textContent = hidden === 0 ? t('mapAllTags') : t('mapSomeTagsOff', hidden);
  }

  /** The map's place for a world's point, in pixels. */
  function mapPoint(x, z) {
    return [mapView.x(x), mapView.y(z)];
  }

  function renderMap() {
    const svg = $('map');
    svg.replaceChildren();
    renderMapTools();
    const network = state.network;
    const map = network === null ? null : network.map;
    $('empty-message').textContent = network === null ? t('mapNoNetwork') : map === null ? t('mapOld')
      : map.tracks.length === 0 ? t('mapEmpty') : '';
    if (map === null || map.tracks.length === 0)
      return;
    if (mapDimension >= map.dimensions.length)
      mapDimension = 0;
    const area = $('diagram-area');
    const width = area.clientWidth;
    const height = area.clientHeight;
    if (mapView === null || mapFor !== map) {
      mapView = new rt.map.View(width, height);
      const box = rt.map.bounds(map, mapDimension);
      if (box !== null)
        mapView.fit(box, 40);
      mapFor = map;
    } else if (mapView.width !== width || mapView.height !== height) {
      mapView.resize(width, height);
    }

    const keys = map.tracks.map(track => track.key);
    const shownTracks = new Set(rt.map.visible(keys, network.legs, state.timetable, rt.map.chosenLines(state.timetable, state.settings.mapLines)));
    const hidden = new Set(rt.timetable.mapHidden(state.timetable));
    const here = [];
    map.tracks.forEach((track, i) => {
      if (track !== null && track.dimension === mapDimension)
        here.push(i);
    });
    // The stretches the tags turned off leave shown; a track the lines do not show is not shown at all.
    const off = tagsOff();
    const segments = rt.map.tagSegments(map.tracks, off);
    const on = (track, x, z) => shownTracks.has(track) && map.tracks[track].dimension === mapDimension
      && rt.map.shownAt(segments[track], rt.map.shareOf(map.tracks[track].points, x, z));
    svg.append(s('path', { class: 'track other', d: rt.map.pathData(map, here.filter(i => !shownTracks.has(i) && !hidden.has(keys[i])), mapView) }));
    svg.append(s('path', { class: 'track hidden', d: rt.map.pathData(map, here.filter(i => hidden.has(keys[i])), mapView) }));
    renderMapSegments(svg, map, here.filter(i => shownTracks.has(i)), segments);
    if (mapTrack !== null && mapTrack < map.tracks.length && map.tracks[mapTrack].dimension === mapDimension)
      svg.append(s('path', { class: 'track chosen', d: rt.map.pathData(map, [mapTrack], mapView) }));

    if (layerOn('speedLimits'))
      for (const sign of map.speedLimits)
        if (sign !== null && on(sign.track, sign.x, sign.z))
          svg.append(speedSign(sign));
    if (layerOn('signals'))
      for (const signal of map.signals)
        if (signal !== null && on(signal.track, signal.x, signal.z))
          for (const side of signal.sides)
            if (side !== null)
              svg.append(signalHead(signal, side));
    if (layerOn('trains'))
      for (const train of map.trains) {
        if (train === null || !rt.map.trainShown(train.tags, off))
          continue;
        const carriages = train.carriages.filter(carriage => carriage !== null
          && (on(carriage.tracks[0], carriage.points[0], carriage.points[1]) || on(carriage.tracks[1], carriage.points[2], carriage.points[3])));
        if (carriages.length === 0)
          continue;
        const group = s('g', { class: 'map-train' });
        for (const carriage of carriages) {
          const [x1, y1] = mapPoint(carriage.points[0], carriage.points[1]);
          const [x2, y2] = mapPoint(carriage.points[2], carriage.points[3]);
          group.append(s('line', { x1, y1, x2, y2 }));
        }
        const [x, y] = mapPoint(carriages[0].points[0], carriages[0].points[1]);
        // Below the train, where the stations' names, which are above them, are not.
        const label = s('text', { x: x + 6, y: y + 16 });
        label.textContent = train.name;
        group.append(label);
        svg.append(group);
      }
    if (layerOn('stations') || layerOn('stationNames'))
      renderMapStations(svg, map, on);
  }

  /**
   * The tracks the lines show, a stretch at a time: one shown with tags in the colours of its tags on,
   * in stripes for more than one; one shown with no tag as a track shown; and one the tags turned off
   * do not show faint, as a track not shown, in no tag's colour.
   */
  function renderMapSegments(svg, map, tracks, segments) {
    const faint = [];
    const plain = [];
    const striped = [];
    for (const index of tracks)
      for (const segment of segments[index]) {
        const d = rt.map.piecePath(map.tracks[index], segment.from, segment.to, mapView);
        if (!segment.shown)
          faint.push(d);
        else if (segment.tags.length === 0)
          plain.push(d);
        else
          striped.push([d, segment.tags]);
      }
    svg.append(s('path', { class: 'track other', d: faint.join('') }));
    svg.append(s('path', { class: 'track shown', d: plain.join('') }));
    const dash = 8;
    for (const [d, tags] of striped)
      tags.forEach((tag, i) => {
        const attributes = { class: 'track tagged', d, stroke: tagColour(state.network, tag) };
        if (tags.length > 1) {
          attributes['stroke-dasharray'] = dash + ' ' + dash * (tags.length - 1);
          attributes['stroke-dashoffset'] = String(-dash * i);
        }
        svg.append(s('path', attributes));
      });
  }

  /** The stations: a dot for each marker, and the name once for the markers of a name, in the middle of them. */
  function renderMapStations(svg, map, on) {
    const line = currentLine();
    const ofLine = new Set(line === null ? [] : line.stations);
    const byName = new Map();
    for (const station of map.stations) {
      if (station === null || !on(station.track, station.x, station.z))
        continue;
      if (layerOn('stations'))
        svg.append(s('circle', {
          class: 'map-station' + (ofLine.has(station.name) ? ' current' : ''),
          cx: mapView.x(station.x), cy: mapView.y(station.z), r: 4,
        }));
      if (!byName.has(station.name))
        byName.set(station.name, []);
      byName.get(station.name).push(station);
    }
    if (!layerOn('stationNames'))
      return;
    for (const [name, markers] of byName) {
      // Close in, every name; far out, only those of the line chosen, so that they do not run into each other.
      if (mapView.scale < 0.75 && !ofLine.has(name))
        continue;
      const x = markers.reduce((sum, station) => sum + station.x, 0) / markers.length;
      const z = markers.reduce((sum, station) => sum + station.z, 0) / markers.length;
      const label = s('text', { class: 'map-station-name' + (ofLine.has(name) ? ' current' : ''), x: mapView.x(x) + 7, y: mapView.y(z) - 7 });
      label.textContent = name;
      svg.append(label);
    }
  }

  /** The colours the lamps are drawn in. */
  const LAMP_COLOURS = { R: '#e5483f', Y: '#f2c230', G: '#2fbf71' };

  /** Where a thing beside the track for trains running (dx, dz) is drawn: to their right, as Create puts signals. */
  function besideTheTrack(thing, dx, dz, away) {
    const length = Math.hypot(dx, dz) || 1;
    return [mapView.x(thing.x) - dz / length * away, mapView.y(thing.z) + dx / length * away];
  }

  /** One way of a signal: its lamps, lit as they were, in a dark head to the right of the trains it is for. */
  function signalHead(signal, side) {
    const [x, y] = besideTheTrack(signal, side.dx, side.dz, 9);
    const length = Math.hypot(side.dx, side.dz) || 1;
    const ux = side.dx / length;
    const uy = side.dz / length;
    const group = s('g', { class: 'map-signal' });
    const count = side.lamps.length;
    const reach = (count - 1) * 3.5 + 4;
    group.append(s('line', { class: 'head', x1: x - ux * reach, y1: y - uy * reach, x2: x + ux * reach, y2: y + uy * reach }));
    side.lamps.forEach((lamp, i) => {
      const along = (i - (count - 1) / 2) * 7;
      group.append(s('circle', { cx: x + ux * along, cy: y + uy * along, r: 2.6, fill: LAMP_COLOURS[lamp] }));
    });
    const title = s('title');
    title.textContent = t('mapSignal', side.kind === 'create' ? t('mapSignalCreate') : t('mapSignalLight', side.kind.slice(5)));
    group.append(title);
    return group;
  }

  /** A speed-limit sign to the right of the trains it holds: its limit, or a slash for one that ends the limit. */
  function speedSign(sign) {
    const [x, y] = besideTheTrack(sign, sign.dx, sign.dz, 10);
    const group = s('g', { class: 'map-limit' + (sign.release ? ' release' : '') });
    group.append(s('circle', { cx: x, cy: y, r: 8 }));
    if (sign.release) {
      group.append(s('line', { x1: x - 5, y1: y + 5, x2: x + 5, y2: y - 5 }));
    } else {
      const text = s('text', { x, y: y + 3.5, 'text-anchor': 'middle' });
      text.textContent = String(sign.kmh);
      group.append(text);
    }
    const title = s('title');
    title.textContent = sign.release ? t('mapRelease') : t('mapLimit', sign.kmh);
    group.append(title);
    return group;
  }

  function bindMap() {
    const svg = $('map');
    svg.addEventListener('wheel', event => {
      if (mapView === null)
        return;
      event.preventDefault();
      const box = svg.getBoundingClientRect();
      mapView.wheel(rt.layout.wheelNotches(event.deltaY, event.deltaMode), event.clientX - box.left, event.clientY - box.top);
      renderMap();
    }, { passive: false });
    let gesture = null;
    svg.addEventListener('pointerdown', event => {
      if (mapView === null || event.button !== 0)
        return;
      gesture = { x: event.clientX, y: event.clientY, startX: event.clientX, startY: event.clientY, id: event.pointerId, moved: false };
      svg.setPointerCapture(event.pointerId);
    });
    svg.addEventListener('pointermove', event => {
      if (gesture === null || event.pointerId !== gesture.id)
        return;
      if (!gesture.moved && Math.hypot(event.clientX - gesture.startX, event.clientY - gesture.startY) < 4)
        return;
      gesture.moved = true;
      mapView.pan(event.clientX - gesture.x, event.clientY - gesture.y);
      gesture.x = event.clientX;
      gesture.y = event.clientY;
      renderMap();
    });
    const end = event => {
      if (gesture === null || event.pointerId !== gesture.id)
        return;
      const clicked = !gesture.moved && event.type === 'pointerup';
      gesture = null;
      if (!clicked || state.network === null || state.network.map === null)
        return;
      const box = svg.getBoundingClientRect();
      const x = mapView.worldX(event.clientX - box.left);
      const z = mapView.worldZ(event.clientY - box.top);
      const found = rt.map.nearestTrack(state.network.map, mapDimension, x, z, 8 / mapView.scale);
      mapTrack = found < 0 ? null : found;
      render();
    };
    svg.addEventListener('pointerup', end);
    svg.addEventListener('pointercancel', end);
    $('view-diagram').addEventListener('click', () => setView('diagram'));
    $('view-map').addEventListener('click', () => setView('map'));
    $('map-fit').addEventListener('click', () => {
      mapView = null;
      render();
    });
    $('map-dimension').addEventListener('change', event => {
      mapDimension = Number(event.target.value);
      mapView = null;
      mapTrack = null;
      render();
    });
  }

  /** The track chosen on the map: which lines run along it, and how the line chosen and every map have it. */
  function trackInspector(panel) {
    const map = state.network.map;
    const track = map.tracks[mapTrack];
    panel.append(h('h2', null, t('mapTrack')));
    panel.append(h('p', { class: 'note key' }, track.key));
    const along = state.timetable.lines.filter(line => routeOf(map, line).has(mapTrack)).map(line => line.name);
    panel.append(h('p', null, along.length === 0 ? t('mapTrackNoLines') : t('mapTrackLines', along.join(', '))));

    const line = currentLine();
    if (line === null) {
      panel.append(h('p', { class: 'note' }, t('mapNoLine')));
    } else {
      panel.append(h('h2', null, t('mapForLine', line.name)));
      const how = rt.timetable.mapAdd(line).includes(track.key) ? 'add' : rt.timetable.mapRemove(line).includes(track.key) ? 'remove' : 'route';
      const runs = routeOf(map, line).has(mapTrack);
      const choices = [['route', runs ? t('mapRouteShown') : t('mapRouteNotShown')], ['add', t('mapAdd')], ['remove', t('mapRemove')]];
      for (const [value, label] of choices)
        panel.append(h('label', { class: 'check' }, h('input', {
          type: 'radio', name: 'map-how', checked: how === value,
          onchange: () => change(rt.edit.setLineTrack(state.timetable, line.id, track.key, value)),
        }), label));
    }
    const hidden = rt.timetable.mapHidden(state.timetable).includes(track.key);
    panel.append(h('h2', null, t('mapEveryMap')));
    panel.append(h('label', { class: 'check' }, h('input', {
      type: 'checkbox', checked: hidden,
      onchange: event => change(rt.edit.setTrackHidden(state.timetable, track.key, event.target.checked)),
    }), t('mapHide')));
    panel.append(h('div', { class: 'buttons' }, h('button', {
      type: 'button',
      onclick: () => {
        mapTrack = null;
        render();
      },
    }, t('mapDeselect'))));
  }

  // ------------------------------------------------------------------ the inspector

  function field(label, input) {
    return h('div', { class: 'field' }, h('label', null, label), input);
  }

  function renderInspector() {
    const panel = $('inspector');
    panel.replaceChildren();
    if (drawing !== null) {
      drawingInspector(panel);
      return;
    }
    if (onMap() && mapTrack !== null && state.network !== null && state.network.map !== null && mapTrack < state.network.map.tracks.length) {
      trackInspector(panel);
      return;
    }
    const train = selectedTrain();
    if (train !== null) {
      trainInspector(panel, train);
      return;
    }
    const duty = selectedDuty();
    if (duty !== null) {
      dutyInspector(panel, duty);
      return;
    }
    panel.append(h('p', { class: 'note' }, t('noSelection')));
    settingsInspector(panel);
  }

  function drawingInspector(panel) {
    panel.append(h('h2', null, t('drawing')));
    panel.append(h('p', { class: 'note' }, t('drawHelp')));
    panel.append(field(t('type'), h('input', {
      type: 'text', value: drawing.type, spellcheck: 'false', list: 'type-names',
      onchange: event => {
        drawing.type = event.target.value.trim();
        renderPreview();
      },
    })));
    panel.append(field(t('deadhead'), h('span', null, h('input', {
      type: 'checkbox', checked: drawing.deadhead,
      onchange: event => {
        drawing.deadhead = event.target.checked;
      },
    }))));
    panel.append(field(t('dwell'), secondsInput(drawing.dwell, t('stationDwell'), rt.problems.MAX_STOP_SECONDS, t('badSeconds'), seconds => {
      drawing.dwell = seconds;
      renderInspector();
      renderPreview();
    })));
    panel.append(h('p', { class: 'note' }, t('drawDwellHelp')));
    if (drawing.stops.length > 0) {
      const rows = drawing.stops.map((stop, i) => h('tr', null,
        h('td', null, stop.station),
        h('td', null, timeText(stop.arrive)),
        h('td', null, timeText(stop.depart)),
        h('td', null, stop.arrive === rt.edit.NONE ? null : secondsInput(stop.depart - stop.arrive, '', Number.MAX_SAFE_INTEGER, t('badDwell'), seconds => {
          if (seconds !== rt.edit.NONE) {
            drawing.stops = rt.edit.setDrawnDwell(drawing.stops, i, seconds);
            // A dwell typed for the last stop says when the train leaves it, as a click on its row would.
            if (i === drawing.stops.length - 1)
              drawing.leaving = false;
          }
          renderInspector();
          renderPreview();
        }))));
      panel.append(h('table', null,
        h('thead', null, h('tr', null, h('th', null, t('station')), h('th', null, t('arrive')), h('th', null, t('depart')), h('th', null, t('dwellShort')))),
        h('tbody', null, rows)));
    }
    panel.append(h('div', { class: 'buttons' },
      h('button', { type: 'button', class: 'primary', disabled: drawing.stops.length < 2, onclick: finishDrawing }, t('finish')),
      h('button', { type: 'button', onclick: cancelDrawing }, t('cancel'))));
  }

  /** A text box for a time: empty for none. Badly written, it is marked and nothing changes. */
  function timeInput(seconds, disabled, apply) {
    const input = h('input', {
      type: 'text', value: timeText(seconds), disabled, spellcheck: 'false',
      onchange: event => {
        const text = event.target.value.trim();
        const read = text === '' ? rt.edit.NONE : time.parse(text);
        if (read === null) {
          event.target.classList.add('bad');
          event.target.title = t('badTime');
          return;
        }
        apply(read);
      },
    });
    return input;
  }

  /**
   * A whole number of seconds from text, from 0 to most; NONE for an empty text, and null for one
   * that is not such a number.
   */
  function readSeconds(text, most) {
    const trimmed = text.trim();
    if (trimmed === '')
      return rt.edit.NONE;
    if (!/^\d{1,6}$/.test(trimmed))
      return null;
    const seconds = Number(trimmed);
    return seconds <= most ? seconds : null;
  }

  /** A box for seconds: empty for none, or the placeholder. Badly written, it is marked with why, and nothing changes. */
  function secondsInput(seconds, placeholder, most, why, apply) {
    return h('input', {
      type: 'text', inputmode: 'numeric', class: 'seconds', value: seconds === rt.edit.NONE ? '' : String(seconds), placeholder, spellcheck: 'false',
      onchange: event => {
        const read = readSeconds(event.target.value, most);
        if (read === null) {
          event.target.classList.add('bad');
          event.target.title = why;
          return;
        }
        apply(read);
      },
    });
  }

  /**
   * The row under a stop's times: how long it stands there, the least it stands when late, the type
   * the train has from there on, and the trains it waits for.
   */
  function stopTimesRow(train, i) {
    const stop = train.stops[i];
    const number = train.number;
    const NONE = rt.edit.NONE;
    if (stop.depart === NONE)
      return null; // the last stop: the train does not leave it
    const station = rt.edit.stationTimes(state.timetable, stop.station);
    const own = stop.minimumStop === undefined ? NONE : stop.minimumStop;
    const minimum = own !== NONE ? own : station.minimumStop;
    const cells = [];
    if (stop.arrive !== NONE) {
      const dwell = stop.depart - stop.arrive;
      const input = secondsInput(dwell, '', Number.MAX_SAFE_INTEGER, t('badDwell'),
        seconds => (seconds === NONE ? render()
          : changeStop(rt.edit.setDwell(state.timetable, number, i, seconds, runningTimes()), number, i, 'depart', stop.arrive + seconds)));
      if (minimum !== NONE && dwell < minimum) {
        input.classList.add('warn');
        input.title = t('dwellShorter', minimum);
      }
      cells.push(h('label', null, t('dwellShort'), ' ', input, ' ', t('seconds')));
    }
    cells.push(h('label', null, t('minimumShort'), ' ',
      secondsInput(own, station.minimumStop !== NONE ? String(station.minimumStop) : t('serverDefault'), rt.problems.MAX_STOP_SECONDS,
        t('badSeconds'), seconds => change(rt.edit.setStopMinimum(state.timetable, number, i, seconds))),
      ' ', t('seconds')));
    cells.push(h('label', null, t('typeFrom'), ' ', h('input', {
      type: 'text', class: 'type-from', value: stop.type || '', placeholder: t('typeUnchanged'), spellcheck: 'false', list: 'type-names',
      onchange: event => change(rt.edit.setStopType(state.timetable, number, i, event.target.value.trim())),
    })));
    cells.push(h('label', null, t('tagFrom'), ' ', h('input', {
      type: 'text', class: 'type-from', value: stop.tag || '', placeholder: t('tagOfTrain'), spellcheck: 'false', list: 'tag-names',
      onchange: event => change(rt.edit.setStopTag(state.timetable, number, i, event.target.value.trim())),
    })));
    cells.push(waitsCell(train, i));
    return h('tr', { class: 'stop-times' }, h('td', { colspan: '4' }, cells));
  }

  /**
   * The trains a stop waits for before it leaves, each with the station it leaves or runs through and
   * the longest wait, and a train and a station to add one.
   */
  function waitsCell(train, i) {
    const stop = train.stops[i];
    const number = train.number;
    const NONE = rt.edit.NONE;
    const waits = stop.waits === undefined ? [] : stop.waits;
    const set = next => change(rt.edit.setStopWaits(state.timetable, number, i, next));
    const parts = [h('span', { class: 'waits-title' }, t('waitsFor'))];
    waits.forEach((wait, w) => {
      parts.push(h('span', { class: 'wait' }, t('waitFor', wait.train, rt.problems.waitStation(wait, stop.station)), ' ',
        h('label', null, t('maxWaitShort'), ' ',
          secondsInput(wait.maxWait, t('serverDefault'), rt.problems.MAX_WAIT_SECONDS, t('badWait'),
            seconds => set(waits.map((each, k) => k === w ? { train: each.train, at: each.at, maxWait: seconds } : each))),
          ' ', t('seconds')),
        h('button', { type: 'button', title: t('removeWait'), onclick: () => set(waits.filter((each, k) => k !== w)) }, '×')));
    });
    const others = state.timetable.trains.map(each => each.number).filter(each => each !== number)
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    const trainSelect = h('select', { title: t('waitTrain') },
      h('option', { value: '' }, t('waitTrain')), others.map(each => h('option', { value: each }, each)));
    const stationSelect = h('select', { title: t('waitStation') });
    // The stations of the chosen train's line, the stop's own first chosen if it is one of them.
    const fill = () => {
      const other = state.timetable.trains.find(each => each.number === trainSelect.value);
      const line = other === undefined ? undefined : state.timetable.lines.find(each => each.id === other.line);
      const stations = line === undefined ? [] : line.stations;
      stationSelect.replaceChildren(...stations.map(station => h('option', { value: station, selected: station === stop.station }, station)));
    };
    trainSelect.addEventListener('change', fill);
    const add = h('button', {
      type: 'button',
      onclick: () => {
        if (trainSelect.value === '' || stationSelect.value === '')
          return;
        set(waits.concat([{ train: trainSelect.value, at: stationSelect.value === stop.station ? '' : stationSelect.value, maxWait: NONE }]));
      },
    }, t('addWait'));
    parts.push(h('span', { class: 'wait-add' }, trainSelect, ' ', stationSelect, ' ', add));
    return h('div', { class: 'waits' }, parts);
  }

  /**
   * Makes next the timetable, after a time asked for one of a train's stops: if the stops either side
   * and the track's running times held it to another, the train's panel says which.
   */
  function changeStop(next, number, index, field, asked) {
    const got = next.trains.find(each => each.number === number).stops[index][field];
    trainNote = got !== asked ? t('timeHeld', timeText(got)) : '';
    change(next);
  }

  function trainInspector(panel, train) {
    const number = train.number;
    panel.append(h('h2', null, t('trains') + ' ' + number + (train.deadhead ? ' (' + t('deadhead') + ')' : '')));
    const error = h('p', { class: 'error' });
    panel.append(field(t('number'), h('input', {
      type: 'text', value: number, spellcheck: 'false',
      onchange: event => {
        const next = event.target.value.trim();
        if (next === '') {
          error.textContent = t('numberEmpty');
          return;
        }
        if (next !== number && state.timetable.trains.some(other => other.number === next)) {
          error.textContent = t('numberTaken');
          return;
        }
        state.selection = { kind: 'train', number: next };
        change(rt.edit.setTrain(state.timetable, number, { number: next }));
      },
    })));
    panel.append(field(t('type'), h('input', {
      type: 'text', value: train.type, spellcheck: 'false', list: 'type-names',
      onchange: event => change(rt.edit.setTrain(state.timetable, number, { type: event.target.value.trim() })),
    })));
    panel.append(field(t('tag'), h('input', {
      type: 'text', value: train.tag || '', spellcheck: 'false', list: 'tag-names',
      onchange: event => change(rt.edit.setTrain(state.timetable, number, { tag: event.target.value.trim() })),
    })));
    panel.append(field(t('deadhead'), h('span', null, h('input', {
      type: 'checkbox', checked: train.deadhead,
      onchange: event => change(rt.edit.setTrain(state.timetable, number, { deadhead: event.target.checked })),
    }))));
    panel.append(field(t('keep'), h('span', { title: t('keepHelp') }, h('input', {
      type: 'checkbox', checked: train.keep === true,
      onchange: event => change(rt.edit.setTrain(state.timetable, number, { keep: event.target.checked })),
    }))));
    panel.append(field(t('trainLength'), h('input', {
      type: 'number', min: '1', max: String(rt.problems.MAX_LENGTH), step: '1',
      value: rt.problems.hasLength(train) ? String(train.length) : '', placeholder: String(state.settings.length),
      title: t('trainLengthHelp', state.settings.length),
      onchange: event => {
        // Left empty, the train takes the length of the settings; what is not a length is not taken.
        const length = rt.edit.typedLength(event.target.value);
        if (length === null) {
          error.textContent = t('badLength');
          return;
        }
        change(rt.edit.setTrain(state.timetable, number, { length }));
      },
    })));
    panel.append(error);

    const line = state.timetable.lines.find(each => each.id === train.line);
    const stations = line === undefined ? [] : line.stations;
    const last = train.stops.length - 1;
    const rows = train.stops.map((stop, i) => {
      const choices = stations.includes(stop.station) ? stations : [stop.station].concat(stations);
      const select = h('select', {
        onchange: event => change(rt.edit.setStopStation(state.timetable, number, i, event.target.value)),
      }, choices.map(station => h('option', { value: station, selected: station === stop.station }, station)));
      return [h('tr', null,
        h('td', null, select),
        h('td', null, timeInput(stop.arrive, i === 0 && stop.arrive === rt.edit.NONE,
          seconds => changeStop(rt.edit.setStopTime(state.timetable, number, i, 'arrive', seconds, runningTimes()), number, i, 'arrive', seconds))),
        h('td', null, timeInput(stop.depart, i === last && stop.depart === rt.edit.NONE,
          seconds => changeStop(rt.edit.setStopTime(state.timetable, number, i, 'depart', seconds, runningTimes()), number, i, 'depart', seconds))),
        h('td', null, h('button', {
          type: 'button', title: t('removeStop'), disabled: train.stops.length <= 2,
          onclick: () => change(rt.edit.removeStop(state.timetable, number, i)),
        }, '×'))), stopTimesRow(train, i)];
    });
    panel.append(h('table', null,
      h('thead', null, h('tr', null, h('th', null, t('station')), h('th', null, t('arrive')), h('th', null, t('depart')), h('th'))),
      h('tbody', null, rows.flat())));
    if (trainNote !== '')
      panel.append(h('p', { class: 'note held' }, trainNote));
    panel.append(h('p', { class: 'note' }, t('stopTimesHelp')));
    panel.append(h('p', { class: 'note' }, t('waitsHelp')));
    panel.append(h('p', { class: 'note' }, t('dragHelp')));
    panel.append(h('div', { class: 'buttons' }, h('button', {
      type: 'button',
      onclick: () => {
        state.selection = null;
        change(rt.edit.removeTrain(state.timetable, number));
      },
    }, t('removeTrain'))));
  }

  /** The stations a depot can be: those of the lines, and of the network. */
  function depotChoices(current) {
    const stations = [];
    for (const line of state.timetable.lines)
      stations.push(...line.stations);
    if (state.network !== null)
      stations.push(...state.network.stations);
    if (current !== '')
      stations.unshift(current);
    return [...new Set(stations)];
  }

  function dutyInspector(panel, duty) {
    const id = duty.id;
    panel.append(h('h2', null, t('duties') + ' ' + id));
    const error = h('p', { class: 'error' });
    panel.append(field(t('dutyId'), h('input', {
      type: 'text', value: id, spellcheck: 'false',
      onchange: event => {
        const next = event.target.value.trim();
        if (next === '') {
          error.textContent = t('numberEmpty');
          return;
        }
        if (next !== id && state.timetable.duties.some(other => other.id === next)) {
          error.textContent = t('dutyIdTaken');
          return;
        }
        state.selection = { kind: 'duty', id: next };
        change(rt.duties.setDuty(state.timetable, id, { id: next }));
      },
    })));
    panel.append(field(t('depot'), h('select', {
      onchange: event => change(rt.duties.setDuty(state.timetable, id, { depot: event.target.value })),
    }, depotChoices(duty.depot).map(station => h('option', { value: station, selected: station === duty.depot }, station)))));
    panel.append(error);

    panel.append(h('h2', null, t('dutyTrains')));
    const byNumber = new Map(state.timetable.trains.map(train => [train.number, train]));
    panel.append(h('ol', { class: 'list duty-trains' }, duty.trains.map((number, i) => {
      const train = byNumber.get(number);
      const route = train === undefined || train.stops.length === 0 ? ''
        : train.stops[0].station + ' ' + timeText(train.stops[0].depart) + ' → ' + train.stops[train.stops.length - 1].station + ' '
          + timeText(train.stops[train.stops.length - 1].arrive);
      return h('li', null, h('span', null, number, ' ', h('span', { class: 'sub' }, route)),
        h('button', { type: 'button', title: t('remove'), onclick: () => change(rt.duties.removeTrainAt(state.timetable, id, i)) }, '×'));
    })));

    panel.append(h('div', { class: 'buttons' },
      h('button', {
        type: 'button', class: picking ? 'active' : null,
        onclick: () => {
          picking = !picking;
          render();
        },
      }, picking ? t('done') : t('pickTrains'))));
    if (picking)
      panel.append(h('p', { class: 'note' }, t('picking')));

    panel.append(h('h2', null, t('candidates')));
    const next = rt.duties.candidates(state.timetable, id);
    panel.append(next.length === 0 ? h('p', { class: 'note' }, t('noCandidates')) : h('ul', { class: 'list candidates' }, next.map(train =>
      h('li', null, h('button', {
        type: 'button',
        onclick: () => {
          dutyNote = '';
          change(rt.duties.appendTrain(state.timetable, id, train.number));
        },
      }, '+'), ' ', train.number, ' ', h('span', { class: 'sub' }, train.stops[0].station + ' ' + timeText(train.stops[0].depart))))));

    panel.append(h('div', { class: 'buttons' },
      h('button', {
        type: 'button', disabled: duty.trains.length === 0,
        onclick: () => {
          const result = rt.duties.addDepotDeadheads(state.timetable, id, running, dwellAt, state.settings.snap);
          const notes = result.missing.map(([from, to]) => t('noRunningTime', from, to));
          if (result.added.length > 0)
            notes.unshift(t('deadheadsAdded', result.added.length));
          else if (result.missing.length === 0)
            notes.push(t('noDeadheadNeeded'));
          dutyNote = notes.join(' ');
          if (result.added.length > 0)
            change(result.timetable);
          else
            render();
        },
      }, t('addDeadheads')),
      h('button', {
        type: 'button',
        onclick: () => {
          state.selection = null;
          change(rt.duties.removeDuty(state.timetable, id));
        },
      }, t('removeDuty'))));
    if (dutyNote !== '')
      panel.append(h('p', { class: 'note' }, dutyNote));
  }

  function settingsInspector(panel) {
    panel.append(h('h2', null, t('settings')));
    panel.append(field(t('dwell'), h('input', {
      type: 'number', min: '0', max: '600', step: '1', value: String(state.settings.dwell),
      onchange: event => {
        const value = Math.round(Number(event.target.value));
        state.settings.dwell = Number.isFinite(value) ? Math.max(0, Math.min(600, value)) : 20;
        saveSoon();
      },
    })));
    panel.append(h('p', { class: 'note' }, t('dwellHelp')));
    panel.append(field(t('defaultLength'), h('input', {
      type: 'number', min: '1', max: String(rt.problems.MAX_LENGTH), step: '1', value: String(state.settings.length),
      onchange: event => {
        state.settings.length = rt.edit.typedDefaultLength(event.target.value);
        saveSoon();
        render();
      },
    })));
    panel.append(h('p', { class: 'note' }, t('defaultLengthHelp')));
    panel.append(field(t('snap'), h('select', {
      onchange: event => {
        state.settings.snap = Number(event.target.value);
        stepChanged = false;
        saveSoon();
      },
    }, [1, 5, 10, 15, 30, 60].map(step => h('option', { value: String(step), selected: step === state.settings.snap }, String(step))))));
    panel.append(field(t('fineSnap'), h('select', {
      onchange: event => {
        state.settings.fineSnap = Number(event.target.value);
        saveSoon();
      },
    }, [1, 5, 10, 15, 30, 60].map(step => h('option', { value: String(step), selected: step === state.settings.fineSnap }, String(step))))));
    panel.append(h('p', { class: 'note' }, t('snapHelp')));
    if (stepChanged)
      panel.append(h('p', { class: 'note held' }, t('stepChanged', state.settings.snap, state.settings.fineSnap)));
  }

  // ------------------------------------------------------------------ dialogs

  /**
   * Shows the dialog with a title, what fill puts in it, and buttons; a button whose action gives
   * false keeps the dialog open.
   */
  function dialog(title, fill, buttons) {
    const box = $('dialog');
    box.replaceChildren();
    box.append(h('h2', null, title));
    const body = h('div');
    box.append(body);
    fill(body);
    const row = h('div', { class: 'buttons' });
    for (const button of buttons)
      row.append(h('button', {
        type: 'button',
        class: button.primary ? 'primary' : null,
        onclick: () => {
          if (!button.action || button.action() !== false)
            box.close();
        },
      }, button.label));
    box.append(row);
    box.showModal();
  }

  /** A dialog to paste JSON into or open it from a file, read with readText; done gets what was read. */
  function pasteDialog(title, help, readText, done) {
    let area = null;
    let error = null;
    dialog(title, body => {
      body.append(h('p', { class: 'help' }, help));
      area = h('textarea', { spellcheck: 'false' });
      body.append(area);
      body.append(h('p', null, h('input', {
        type: 'file',
        accept: '.json,application/json',
        onchange: async event => {
          const file = event.target.files[0];
          if (file !== undefined)
            area.value = await file.text();
        },
      })));
      error = h('p', { class: 'error' });
      body.append(error);
      setTimeout(() => area.focus(), 0);
    }, [
      {
        label: t('read'),
        primary: true,
        action: () => {
          const read = readText(area.value);
          if (read.value === null) {
            error.textContent = t('notRead') + '\n' + read.problems.join('\n');
            return false;
          }
          done(read.value, area.value);
          return true;
        },
      },
      { label: t('cancel') },
    ]);
  }

  function openTimetable() {
    pasteDialog(t('openTitle'), t('openHelp'), text => {
      const read = rt.timetable.read(text);
      return { value: read.timetable, problems: read.problems };
    }, timetable => {
      state.selection = null;
      state.lineId = timetable.lines.length > 0 ? timetable.lines[0].id : null;
      view = null;
      change(timetable);
    });
  }

  function loadNetwork() {
    pasteDialog(t('networkTitle'), t('networkHelp'), text => {
      const read = rt.network.read(text);
      return { value: read.network, problems: read.problems };
    }, (network, text) => {
      state.network = network;
      state.networkText = text;
      mapView = null;
      mapTrack = null;
      mapDimension = 0;
      render();
    });
  }

  function exportTimetable() {
    const text = rt.timetable.write(state.timetable);
    const found = rt.problems.problems(state.timetable);
    let note = null;
    let area = null;
    dialog(t('exportTitle'), body => {
      body.append(h('p', { class: 'help' }, t('exportHelp')));
      if (found.length > 0)
        body.append(h('p', { class: 'error' }, t('exportProblems', found.length)));
      area = h('textarea', { readonly: true, spellcheck: 'false' });
      area.value = text;
      body.append(area);
      note = h('p', { class: 'note' });
      body.append(note);
    }, [
      {
        label: t('copy'),
        primary: true,
        action: () => {
          copyText(text, area).then(done => {
            note.textContent = done ? t('copied') : t('copyFailed');
          });
          return false;
        },
      },
      {
        label: t('save'),
        action: () => {
          const link = h('a', { href: URL.createObjectURL(new Blob([text], { type: 'application/json' })), download: 'timetable.json' });
          document.body.append(link);
          link.click();
          link.remove();
          return false;
        },
      },
      { label: t('close') },
    ]);
  }

  /** Puts text on the clipboard: through the clipboard of the browser, or else by copying the text selected in area. */
  async function copyText(text, area) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      area.focus();
      area.select();
      try {
        return document.execCommand('copy');
      } catch (ignored) {
        return false;
      }
    }
  }

  /** The dialog to make a line, or to change one when line is given. */
  function lineDialog(line) {
    const editing = line !== undefined && line !== null;
    const chosen = editing ? line.stations.slice() : [];
    let idInput = null;
    let nameInput = null;
    let loopInput = null;
    let chosenList = null;
    let networkList = null;
    let error = null;

    function fillLists() {
      chosenList.replaceChildren();
      chosen.forEach((station, i) => chosenList.append(h('li', null, h('span', null, station), h('span', null,
        h('button', { type: 'button', title: t('up'), disabled: i === 0, onclick: () => { [chosen[i - 1], chosen[i]] = [chosen[i], chosen[i - 1]]; fillLists(); } }, '↑'),
        h('button', { type: 'button', title: t('down'), disabled: i === chosen.length - 1, onclick: () => { [chosen[i + 1], chosen[i]] = [chosen[i], chosen[i + 1]]; fillLists(); } }, '↓'),
        h('button', { type: 'button', title: t('remove'), onclick: () => { chosen.splice(i, 1); fillLists(); } }, '×')))));
      networkList.replaceChildren();
      if (state.network === null) {
        networkList.append(h('li', { class: 'note' }, t('noNetwork')));
        return;
      }
      for (const station of state.network.stations)
        if (!chosen.includes(station))
          networkList.append(h('li', null, h('span', null, station),
            h('button', { type: 'button', onclick: () => { chosen.push(station); fillLists(); } }, '+')));
    }

    const buttons = [
      {
        label: t('ok'),
        primary: true,
        action: () => {
          const id = idInput.value.trim();
          if (id === '') {
            error.textContent = t('lineIdEmpty');
            return false;
          }
          if (state.timetable.lines.some(other => other.id === id && (!editing || other.id !== line.id))) {
            error.textContent = t('lineIdTaken');
            return false;
          }
          const name = nameInput.value.trim() === '' ? id : nameInput.value.trim();
          state.lineId = id;
          change(rt.edit.putLine(state.timetable, editing ? line.id : null, { id, name, stations: chosen, loop: loopInput.checked }));
          return true;
        },
      },
    ];
    if (editing)
      buttons.push({
        label: t('removeLine'),
        action: () => {
          const count = state.timetable.trains.filter(train => train.line === line.id).length;
          if (!confirm(t('confirmRemoveLine', line.name || line.id, count)))
            return false;
          state.selection = null;
          change(rt.edit.removeLine(state.timetable, line.id));
          return true;
        },
      });
    buttons.push({ label: t('cancel') });

    dialog(editing ? t('editLine') : t('addLine'), body => {
      idInput = h('input', { type: 'text', value: editing ? line.id : '', spellcheck: 'false' });
      nameInput = h('input', { type: 'text', value: editing ? line.name : '', spellcheck: 'false' });
      loopInput = h('input', { type: 'checkbox', checked: editing && line.loop });
      body.append(h('div', { class: 'field' }, h('label', null, t('lineId')), idInput));
      body.append(h('div', { class: 'field' }, h('label', null, t('lineName')), nameInput));
      body.append(h('div', { class: 'field' }, h('label', null, t('loop')), h('span', null, loopInput)));
      chosenList = h('ul', { class: 'list picker' });
      networkList = h('ul', { class: 'list picker' });
      const typed = h('input', { type: 'text', placeholder: t('stationName'), spellcheck: 'false' });
      const addTyped = () => {
        const name = typed.value.trim();
        if (name !== '' && !chosen.includes(name)) {
          chosen.push(name);
          typed.value = '';
          fillLists();
        }
      };
      typed.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
          event.preventDefault();
          addTyped();
        }
      });
      body.append(h('div', { class: 'columns' },
        h('div', null, h('h2', null, t('stationsOfLine')), chosenList,
          h('div', { class: 'buttons' }, typed, h('button', { type: 'button', onclick: addTyped }, t('addStation')))),
        h('div', null, h('h2', null, t('networkStations')), networkList)));
      error = h('p', { class: 'error' });
      body.append(error);
      fillLists();
    }, buttons);
  }

  /**
   * The dialog of the stations' own times: every station of the lines, in the order the lines have
   * them, and after them any station given times that no line has any more, to clear.
   */
  function stationTimesDialog() {
    const names = [];
    for (const line of state.timetable.lines)
      names.push(...line.stations);
    for (const station of state.timetable.stations || [])
      names.push(station.name);
    const rows = [...new Set(names)].map(name => ({ station: rt.edit.stationTimes(state.timetable, name), dwell: null, minimum: null }));
    let error = null;
    const NONE = rt.edit.NONE;
    const most = rt.problems.MAX_STOP_SECONDS;

    dialog(t('stationTimes'), body => {
      body.append(h('p', { class: 'help' }, t('stationTimesHelp', state.settings.dwell)));
      if (rows.length === 0) {
        body.append(h('p', { class: 'note' }, t('noStationsYet')));
        return;
      }
      const box = (seconds, placeholder) => h('input', {
        type: 'text', inputmode: 'numeric', class: 'seconds', spellcheck: 'false', placeholder,
        value: seconds === NONE ? '' : String(seconds),
      });
      for (const row of rows) {
        row.dwell = box(row.station.dwell, String(state.settings.dwell));
        row.minimum = box(row.station.minimumStop, t('serverDefault'));
      }
      body.append(h('div', { class: 'scroll' }, h('table', { class: 'station-times' },
        h('thead', null, h('tr', null, h('th', null, t('station')), h('th', null, t('dwell')), h('th', null, t('minimumStop')))),
        h('tbody', null, rows.map(row => h('tr', null, h('td', null, row.station.name), h('td', null, row.dwell), h('td', null, row.minimum)))))));
      error = h('p', { class: 'error' });
      body.append(error);
    }, [
      {
        label: t('ok'),
        primary: true,
        action: () => {
          if (rows.length === 0)
            return true;
          const list = [];
          let bad = false;
          for (const row of rows) {
            const dwell = readSeconds(row.dwell.value, most);
            const minimum = readSeconds(row.minimum.value, most);
            row.dwell.classList.toggle('bad', dwell === null);
            row.minimum.classList.toggle('bad', minimum === null);
            if (dwell === null || minimum === null)
              bad = true;
            else
              list.push({ name: row.station.name, dwell, minimumStop: minimum });
          }
          if (bad) {
            error.textContent = t('badSeconds');
            return false;
          }
          change(rt.edit.setStations(state.timetable, list));
          return true;
        },
      },
      { label: t('cancel') },
    ]);
  }

  /**
   * The dialog of the types: each with its name and colour, picked from the samples or the palette or
   * written out, in the order they are listed. Until it is changed here, the timetable gives no type a
   * colour, and a type the trains have is drawn in the one picked from its name.
   */
  function typesDialog() {
    const before = rt.edit.typeList(state.timetable);
    const rows = before.map(type => ({ from: type.name, name: type.name, color: type.color, listed: type.listed }));
    let table = null;
    let error = null;

    function colourCell(row) {
      const picker = h('input', { type: 'color', value: row.color.toLowerCase(), title: t('typeColour') });
      const written = h('input', { type: 'text', class: 'colour', value: row.color, spellcheck: 'false' });
      const take = colour => {
        row.color = colour;
        row.listed = true;
        picker.value = colour;
        written.value = colour;
        written.classList.remove('bad');
      };
      picker.addEventListener('input', () => take(picker.value));
      written.addEventListener('input', () => {
        const colour = written.value.trim();
        if (rt.problems.isColour(colour)) {
          row.color = colour.toLowerCase();
          row.listed = true;
          picker.value = row.color;
        } else {
          row.color = colour;
        }
      });
      const samples = h('span', { class: 'samples' }, rt.timetable.TYPE_COLOURS.map(colour =>
        h('button', { type: 'button', class: 'sample', title: colour, style: 'background: ' + colour, onclick: () => take(colour) })));
      return h('td', { class: 'colours' }, samples, picker, written);
    }

    function fill() {
      table.replaceChildren();
      if (rows.length === 0) {
        table.append(h('tr', null, h('td', { class: 'note' }, t('noTypesYet'))));
        return;
      }
      table.append(h('tr', null, h('th', null, t('typeName')), h('th', null, t('typeColour')), h('th')));
      rows.forEach((row, i) => {
        const name = h('input', { type: 'text', value: row.name, spellcheck: 'false', oninput: event => { row.name = event.target.value; } });
        table.append(h('tr', { title: row.listed ? null : t('notListed') },
          h('td', null, name),
          colourCell(row),
          h('td', { class: 'order' },
            h('button', { type: 'button', title: t('up'), disabled: i === 0, onclick: () => { [rows[i - 1], rows[i]] = [rows[i], rows[i - 1]]; fill(); } }, '↑'),
            h('button', { type: 'button', title: t('down'), disabled: i === rows.length - 1, onclick: () => { [rows[i + 1], rows[i]] = [rows[i], rows[i + 1]]; fill(); } }, '↓'),
            h('button', { type: 'button', title: t('remove'), onclick: () => { rows.splice(i, 1); fill(); } }, '×'))));
      });
    }

    dialog(t('types'), body => {
      body.append(h('p', { class: 'help' }, t('typesHelp')));
      table = h('table', { class: 'types' });
      body.append(h('div', { class: 'scroll' }, table));
      body.append(h('div', { class: 'buttons' }, h('button', {
        type: 'button',
        onclick: () => {
          const colours = rt.timetable.TYPE_COLOURS;
          rows.push({ from: null, name: '', color: colours[rows.length % colours.length], listed: true });
          fill();
        },
      }, t('addType'))));
      error = h('p', { class: 'error' });
      body.append(error);
      fill();
    }, [
      {
        label: t('ok'),
        primary: true,
        action: () => {
          const list = rows.map(row => ({ name: row.name.trim(), color: row.color.trim() }));
          if (list.some(type => type.name === '')) {
            error.textContent = t('typeNameEmpty');
            return false;
          }
          if (new Set(list.map(type => type.name)).size !== list.length) {
            error.textContent = t('typeNameTaken');
            return false;
          }
          if (list.some(type => !rt.problems.isColour(type.color))) {
            error.textContent = t('badColour');
            return false;
          }
          const unchanged = list.length === before.length && rows.every((row, i) => row.from === before[i].name
            && list[i].name === before[i].name && list[i].color === before[i].color && row.listed === before[i].listed);
          if (unchanged)
            return true;
          const { renames, removed } = rt.edit.typeChanges(before.map(type => type.name),
            rows.map((row, i) => ({ from: row.from, name: list[i].name })));
          const used = removed.filter(name => rt.edit.trainsOfType(state.timetable, [name]) > 0);
          if (used.length > 0 && !confirm(t('confirmRemoveTypes', used.join(', '), rt.edit.trainsOfType(state.timetable, used))))
            return false;
          change(rt.edit.setTypes(state.timetable, list, renames));
          return true;
        },
      },
      { label: t('cancel') },
    ]);
  }

  // ------------------------------------------------------------------ starting

  function bind() {
    $('new').addEventListener('click', () => {
      if (!confirm(t('confirmNew')))
        return;
      state.selection = null;
      state.lineId = null;
      view = null;
      change(rt.timetable.empty());
    });
    $('open').addEventListener('click', openTimetable);
    $('load-network').addEventListener('click', loadNetwork);
    $('export').addEventListener('click', exportTimetable);
    $('undo').addEventListener('click', undo);
    $('redo').addEventListener('click', redo);
    $('add-line').addEventListener('click', () => lineDialog(null));
    $('station-times').addEventListener('click', stationTimesDialog);
    $('train-types').addEventListener('click', typesDialog);
    $('draw-train').addEventListener('click', () => (drawing === null ? startDrawing() : cancelDrawing()));
    $('add-duty').addEventListener('click', () => {
      const id = rt.duties.nextDutyId(state.timetable);
      const line = currentLine();
      state.selection = { kind: 'duty', id };
      picking = true;
      dutyNote = '';
      change(rt.duties.addDuty(state.timetable, id, line === null || line.stations.length === 0 ? '' : line.stations[0]));
    });
    $('timetable-name').addEventListener('change', event => {
      if (event.target.value !== state.timetable.name)
        change(rt.edit.setName(state.timetable, event.target.value));
    });
    $('language').addEventListener('change', event => {
      state.settings.language = event.target.value;
      renderWords();
      render();
    });
    document.addEventListener('keydown', event => {
      // Keys typed into a box or a list are the box's: Delete in a list of stations does not remove the train.
      if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement
        || event.target instanceof HTMLSelectElement || $('dialog').open)
        return;
      const key = event.key.toLowerCase();
      if (event.key === 'Enter' && drawing !== null) {
        event.preventDefault();
        finishDrawing();
      } else if (event.key === 'Escape') {
        if (drawing !== null)
          cancelDrawing();
        else if (state.selection !== null)
          select(null);
      } else if ((event.key === 'Delete' || event.key === 'Backspace') && drawing === null && selectedTrain() !== null) {
        event.preventDefault();
        const number = selectedTrain().number;
        state.selection = null;
        change(rt.edit.removeTrain(state.timetable, number));
      } else if ((event.ctrlKey || event.metaKey) && key === 'z' && !event.shiftKey) {
        event.preventDefault();
        undo();
      } else if ((event.ctrlKey || event.metaKey) && (key === 'y' || key === 'z' && event.shiftKey)) {
        event.preventDefault();
        redo();
      }
    });
    bindDiagram();
    bindMap();
  }

  restore();
  renderWords();
  bind();
  render();

  // For looking into the page from the browser's console.
  window.rtEditor = { state, view: () => view, drawing: () => drawing };
})();
