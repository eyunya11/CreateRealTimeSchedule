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
  const SVG = 'http://www.w3.org/2000/svg';
  const MAX_UNDO = 200;

  /** Colours of the types of train, as the game's diagram picks them, for a light and a dark page alike. */
  const TYPE_COLOURS = ['#3b7dd8', '#3a9d5d', '#d9822b', '#c94c4c', '#2f9e9e', '#b59a1c', '#a65fb0'];

  const state = {
    timetable: rt.timetable.empty(),
    networkText: null,
    network: null,
    lineId: null,
    selection: null,
    settings: { language: i18n.guess(navigator.language), dwell: 20, snap: 5 },
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

  function typeColour(type) {
    if (type === '')
      return 'var(--train)';
    let hash = 0;
    for (let i = 0; i < type.length; i++)
      hash = (hash * 31 + type.charCodeAt(i)) | 0;
    return TYPE_COLOURS[((hash % TYPE_COLOURS.length) + TYPE_COLOURS.length) % TYPE_COLOURS.length];
  }

  /** HH:MM of seconds from 00:00 of the service day, going on past 24:00 and back before 00:00. */
  function clockText(second) {
    const minutes = Math.round(second / 60);
    const hour = Math.floor(minutes / 60);
    const minute = ((minutes % 60) + 60) % 60;
    return String(hour).padStart(2, '0') + ':' + String(minute).padStart(2, '0');
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
        network: state.networkText,
        lineId: state.lineId,
        settings: state.settings,
      }));
    } catch (e) {
      // A browser that keeps nothing for a page opened from a file: the draft lasts as long as the page.
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
    if (stored.settings && typeof stored.settings === 'object')
      Object.assign(state.settings, stored.settings);
    if (typeof stored.timetable === 'string') {
      const read = rt.timetable.read(stored.timetable);
      if (read.timetable !== null)
        state.timetable = read.timetable;
    }
    if (typeof stored.network === 'string') {
      const read = rt.network.read(stored.network);
      if (read.network !== null) {
        state.network = read.network;
        state.networkText = stored.network;
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
    renderLines();
    renderTrains();
    renderDuties();
    renderDiagram();
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
      }, train.number, train.deadhead ? ' (' + t('deadhead') + ')' : '', ' ', h('span', { class: 'sub' }, route)));
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
    const match = /^(line|train|duty) "((?:[^"\\]|\\.)*)"/.exec(problem);
    if (match === null)
      return null;
    if (match[1] === 'line')
      return { kind: 'line', id: match[2] };
    return match[1] === 'train' ? { kind: 'train', number: match[2] } : { kind: 'duty', id: match[2] };
  }

  function renderProblems() {
    const found = rt.problems.problems(state.timetable);
    $('problems-title').textContent = found.length === 0 ? t('noProblems') : t('problemCount', found.length);
    const list = $('problems');
    list.replaceChildren();
    for (const problem of found)
      list.append(h('li', {
        onclick: () => {
          const target = problemTarget(problem);
          if (target === null)
            return;
          if (target.kind === 'station') {
            stationTimesDialog();
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

  /** A train being drawn: the stops so far, and the type and mark it will have. Null when none is. */
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

  function lineAxis(line) {
    return rt.axis.of(line, state.network === null ? [] : state.network.legs);
  }

  function shownTimetable() {
    return shown !== null ? shown : state.timetable;
  }

  /** The seconds a train takes from one station to the other, by the network; null if it does not say. */
  function running(from, to) {
    return state.network === null ? null : rt.network.seconds(state.network, from, to);
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

    // the time scale
    const step = view.step(56);
    for (let second = Math.ceil(view.start() / step) * step; second <= view.end(); second += step) {
      const x = view.x(second);
      const hour = second % 3600 === 0;
      svg.append(s('line', { class: hour ? 'grid strong' : 'grid', x1: x, y1: top, x2: x, y2: plotBottom }));
      const label = s('text', { class: 'label', x, y: plotBottom + 18, 'text-anchor': 'middle' });
      label.textContent = clockText(second);
      svg.append(label);
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
      const colour = planned.deadhead ? 'var(--deadhead)' : typeColour(planned.type);
      const marks = (planned.deadhead ? ' deadhead' : '') + (isSelected ? ' selected' : '')
        + (duty === null ? '' : inDuty.has(planned.number) ? ' in-duty' : ' dim');
      const group = s('g', { class: 'train' + marks, 'data-number': planned.number });
      group.append(s('path', { class: 'shown', d, stroke: colour }));
      const hit = s('path', { class: 'hit', d });
      hit.addEventListener('pointerdown', event => {
        if (drawing !== null || event.button !== 0)
          return; // a click while drawing goes to the row under it
        if (picking && duty !== null) {
          event.stopPropagation();
          dutyNote = '';
          change(rt.duties.appendTrain(state.timetable, duty.id, planned.number));
          return;
        }
        if (isSelected && (event.ctrlKey || event.metaKey)) {
          startDrag(planned.number, -1, event);
          return;
        }
        event.stopPropagation();
        select({ kind: 'train', number: planned.number });
      });
      group.append(hit);
      if (!Number.isNaN(planned.labelSecond)) {
        const number = s('text', { class: 'number', x: view.x(planned.labelSecond) + 3, y: view.y(planned.labelPosition, length) - 4, fill: colour });
        number.textContent = planned.number;
        group.append(number);
      }
      plot.append(group);
      if (isSelected && drawing === null)
        for (const point of planned.points)
          for (const [second, position] of [[point.arriveSecond, point.arrivePosition], [point.departSecond, point.departPosition]])
            if (!Number.isNaN(second) && !Number.isNaN(position)) {
              const handle = s('circle', { class: 'point', cx: view.x(second), cy: view.y(position, length), r: 5, fill: colour });
              handle.addEventListener('pointerdown', event => {
                if (event.button === 0)
                  startDrag(planned.number, point.stop, event);
              });
              plot.append(handle);
            }
    }

    plot.append(s('g', { id: 'preview' }));
    renderPreview();
  }

  /** The train being drawn, with the stop it would get where the pointer is. */
  function renderPreview() {
    const group = document.getElementById('preview');
    if (group === null || drawn === null)
      return;
    group.replaceChildren();
    if (drawing === null)
      return;
    let stops = drawing.stops;
    if (hover !== null) {
      const last = stops[stops.length - 1];
      stops = rt.edit.drawStop(stops, hover.station, hover.second, last === undefined ? null : running(last.station, hover.station),
        drawingDwellAt(hover.station), state.settings.snap);
    }
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
    const at = end.arrive !== rt.edit.NONE ? end.arrive : end.depart;
    const label = s('text', { class: 'label', x: view.x(at) + 6, y: view.y(drawn.axis.position(Math.max(0, drawn.axis.row(end.station))), drawn.length) + 14 });
    label.textContent = timeText(at);
    group.append(label);
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
    return { station: best.station, second: view.second(x) };
  }

  /** Keeps the pointer's events coming to the diagram while a button is held, if the browser still knows the pointer. */
  function capture(pointerId) {
    try {
      $('diagram').setPointerCapture(pointerId);
    } catch (e) {
      // A pointer already gone: the drag goes on while the pointer stays over the diagram.
    }
  }

  function startDrag(number, index, event) {
    event.stopPropagation();
    dragging = { number, index, x0: event.clientX, base: state.timetable, delta: 0, id: event.pointerId };
    capture(event.pointerId);
  }

  function startDrawing() {
    const line = currentLine();
    if (line === null || line.stations.length < 2)
      return;
    drawing = { stops: [], type: drawing !== null ? drawing.type : '', deadhead: false, dwell: drawing !== null ? drawing.dwell : rt.edit.NONE };
    hover = null;
    state.selection = null;
    render();
  }

  function addDrawnStop(point) {
    const last = drawing.stops[drawing.stops.length - 1];
    drawing.stops = rt.edit.drawStop(drawing.stops, point.station, point.second,
      last === undefined ? null : running(last.station, point.station), drawingDwellAt(point.station), state.settings.snap);
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
      view.zoom(event.deltaY > 0 ? 1.25 : 0.8, event.clientX - box.left);
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
        const raw = (event.clientX - dragging.x0) / view.width * view.span;
        const delta = rt.edit.snap(raw, state.settings.snap);
        if (delta !== dragging.delta) {
          dragging.delta = delta;
          shown = delta === 0 ? null : dragging.index < 0
            ? rt.edit.shiftTrain(dragging.base, dragging.number, delta)
            : rt.edit.moveStop(dragging.base, dragging.number, dragging.index, delta);
          renderDiagram();
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
        shown = null;
        dragging = null;
        if (next !== null && event.type === 'pointerup')
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
    new ResizeObserver(() => renderDiagram()).observe($('diagram-area'));
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
      type: 'text', value: drawing.type, spellcheck: 'false',
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
          if (seconds !== rt.edit.NONE)
            drawing.stops = rt.edit.setDrawnDwell(drawing.stops, i, seconds);
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

  /** The row under a stop's times: how long it stands there, and the least it stands when late. */
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
        seconds => (seconds === NONE ? render() : change(rt.edit.setDwell(state.timetable, number, i, seconds))));
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
    return h('tr', { class: 'stop-times' }, h('td', { colspan: '4' }, cells));
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
      type: 'text', value: train.type, spellcheck: 'false',
      onchange: event => change(rt.edit.setTrain(state.timetable, number, { type: event.target.value.trim() })),
    })));
    panel.append(field(t('deadhead'), h('span', null, h('input', {
      type: 'checkbox', checked: train.deadhead,
      onchange: event => change(rt.edit.setTrain(state.timetable, number, { deadhead: event.target.checked })),
    }))));
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
          seconds => change(rt.edit.setStopTime(state.timetable, number, i, 'arrive', seconds)))),
        h('td', null, timeInput(stop.depart, i === last && stop.depart === rt.edit.NONE,
          seconds => change(rt.edit.setStopTime(state.timetable, number, i, 'depart', seconds)))),
        h('td', null, h('button', {
          type: 'button', title: t('removeStop'), disabled: train.stops.length <= 2,
          onclick: () => change(rt.edit.removeStop(state.timetable, number, i)),
        }, '×'))), stopTimesRow(train, i)];
    });
    panel.append(h('table', null,
      h('thead', null, h('tr', null, h('th', null, t('station')), h('th', null, t('arrive')), h('th', null, t('depart')), h('th'))),
      h('tbody', null, rows.flat())));
    panel.append(h('p', { class: 'note' }, t('stopTimesHelp')));
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
    panel.append(field(t('snap'), h('select', {
      onchange: event => {
        state.settings.snap = Number(event.target.value);
        saveSoon();
      },
    }, [1, 5, 10, 15, 30, 60].map(step => h('option', { value: String(step), selected: step === state.settings.snap }, String(step))))));
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
      if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement || $('dialog').open)
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
  }

  restore();
  renderWords();
  bind();
  render();

  // For looking into the page from the browser's console.
  window.rtEditor = { state, view: () => view, drawing: () => drawing };
})();
