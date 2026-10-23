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
    state.selection = selection;
    if (selection !== null && selection.kind === 'train') {
      const train = state.timetable.trains.find(each => each.number === selection.number);
      if (train !== undefined && state.timetable.lines.some(line => line.id === train.line))
        state.lineId = train.line;
    }
    render();
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

  /** The train, duty or line a problem is about, from the way the mod words it. */
  function problemTarget(problem) {
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
          if (target.kind === 'line') {
            state.lineId = target.id;
            render();
          } else {
            select(target);
          }
        },
      }, problem));
  }

  // ------------------------------------------------------------------ the diagram

  /** The diagram's layout of the line chosen: its axis, and the margins around the plot. */
  function lineAxis(line) {
    return rt.axis.of(line, state.network === null ? [] : state.network.legs);
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

  function renderDiagram() {
    const svg = $('diagram');
    svg.replaceChildren();
    const line = currentLine();
    $('empty-message').textContent = line === null ? t('noLine') : line.stations.length < 2 ? t('noStations') : '';
    if (line === null || line.stations.length < 2)
      return;
    const area = $('diagram-area');
    const width = area.clientWidth;
    const height = area.clientHeight;
    const lineAxisOf = lineAxis(line);
    const length = lineAxisOf.length();

    let labelWidth = 0;
    for (const station of lineAxisOf.stations)
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
    lineAxisOf.stations.forEach((station, row) => {
      const y = view.y(lineAxisOf.position(row), length);
      svg.append(s('line', { class: 'grid strong', x1: left, y1: y, x2: plotRight, y2: y }));
      const label = s('text', { class: 'station', x: left - 8, y: y + 4, 'text-anchor': 'end' });
      label.textContent = station;
      svg.append(label);
    });

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
    for (const planned of rt.layout.plannedTrains(state.timetable, lineAxisOf)) {
      const d = planned.segments.map(segment => 'M' + view.x(segment.fromSecond).toFixed(1) + ',' + view.y(segment.fromPosition, length).toFixed(1)
        + 'L' + view.x(segment.toSecond).toFixed(1) + ',' + view.y(segment.toPosition, length).toFixed(1)).join('');
      const isSelected = selected !== null && selected.number === planned.number;
      const colour = planned.deadhead ? 'var(--deadhead)' : typeColour(planned.type);
      const group = s('g', { class: 'train' + (planned.deadhead ? ' deadhead' : '') + (isSelected ? ' selected' : ''), 'data-number': planned.number });
      group.append(s('path', { class: 'shown', d, stroke: colour }));
      const hit = s('path', { class: 'hit', d });
      hit.addEventListener('pointerdown', event => {
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
      if (isSelected)
        for (const point of planned.points)
          for (const [second, position] of [[point.arriveSecond, point.arrivePosition], [point.departSecond, point.departPosition]])
            if (!Number.isNaN(second) && !Number.isNaN(position))
              plot.append(s('circle', { class: 'point', cx: view.x(second), cy: view.y(position, length), r: 4, fill: colour }));
    }
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

    let panning = null;
    svg.addEventListener('pointerdown', event => {
      if (view === null || event.button !== 0)
        return;
      panning = { x: event.clientX, id: event.pointerId, moved: false };
      svg.setPointerCapture(event.pointerId);
    });
    svg.addEventListener('pointermove', event => {
      if (panning === null || event.pointerId !== panning.id)
        return;
      const dx = event.clientX - panning.x;
      if (Math.abs(dx) > 0) {
        view.pan(dx);
        panning.x = event.clientX;
        panning.moved = true;
        renderDiagram();
      }
    });
    const end = event => {
      if (panning === null || event.pointerId !== panning.id)
        return;
      if (!panning.moved && state.selection !== null)
        select(null);
      panning = null;
    };
    svg.addEventListener('pointerup', end);
    svg.addEventListener('pointercancel', end);
    new ResizeObserver(() => renderDiagram()).observe($('diagram-area'));
  }

  // ------------------------------------------------------------------ the inspector

  function renderInspector() {
    const panel = $('inspector');
    panel.replaceChildren();
    const train = selectedTrain();
    if (train !== null) {
      panel.append(h('h2', null, t('trains') + ' ' + train.number + (train.deadhead ? ' (' + t('deadhead') + ')' : '')));
      const rows = train.stops.map(stop => h('tr', null, h('td', null, stop.station), h('td', null, timeText(stop.arrive)), h('td', null, timeText(stop.depart))));
      panel.append(h('table', null, h('tbody', null, rows)));
      return;
    }
    const selection = state.selection;
    if (selection !== null && selection.kind === 'duty') {
      const duty = state.timetable.duties.find(each => each.id === selection.id);
      if (duty !== undefined) {
        panel.append(h('h2', null, t('duties') + ' ' + duty.id));
        panel.append(h('p', null, duty.depot));
        panel.append(h('ol', null, duty.trains.map(number => h('li', null, number))));
      }
    }
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
      if ((event.ctrlKey || event.metaKey) && key === 'z' && !event.shiftKey) {
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
})();
