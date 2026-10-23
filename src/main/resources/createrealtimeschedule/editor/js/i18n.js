/*
 * The words of the editor's page, in Japanese and English. The language is the browser's at first,
 * and the page remembers the one picked. The problems of a timetable are in English in both, as the
 * mod says them.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports)
    module.exports = factory();
  else
    (root.rt = root.rt || {}).i18n = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const WORDS = {
    ja: {
      title: 'ダイヤの編集',
      timetableName: 'ダイヤの名前',
      new: '新しいダイヤ',
      open: 'ダイヤを開く',
      network: '線路を読み込む',
      export: '書き出す',
      undo: '元に戻す',
      redo: 'やり直す',
      lines: '路線',
      addLine: '路線を足す',
      editLine: '路線を直す',
      trains: '列車',
      duties: '運用',
      problems: '誤り',
      noProblems: '誤りはありません',
      problemCount: '誤り {0} 件',
      confirmNew: 'いまのダイヤを消して、新しいダイヤを始めますか？',
      openTitle: 'ダイヤを開く',
      openHelp: 'ゲームで /rttimetable copy を実行してから、ここに貼り付けてください。timetable.json のファイルも開けます。',
      networkTitle: '線路を読み込む',
      networkHelp: 'ゲームで /rtnetwork copy を実行してから、ここに貼り付けてください。network.json のファイルも開けます。',
      read: '読み込む',
      cancel: 'やめる',
      close: '閉じる',
      notRead: '読み込めませんでした。',
      exportTitle: '書き出す',
      exportHelp: 'クリップボードに写してから、ゲームで /rttimetable paste を実行してください。ファイルに保存したときは、ワールドフォルダの createrealtimeschedule/timetable.json に置いて /rttimetable load を実行します。',
      exportProblems: '誤りが {0} 件あります。このままでは、ゲームは読み込みません。',
      copy: 'クリップボードに写す',
      copied: '写しました',
      copyFailed: '写せませんでした。上の文字を選んで、手で写してください。',
      save: 'ファイルに保存',
      lineId: '路線の ID',
      lineName: '路線の名前',
      loop: '環状線',
      stationsOfLine: '路線の駅(上から順)',
      networkStations: '線路の駅',
      stationName: '駅の名前',
      addStation: '足す',
      noNetwork: '線路を読み込むと、ここから駅を選べます。',
      ok: '決める',
      removeLine: '路線を消す',
      confirmRemoveLine: '路線「{0}」と、その列車 {1} 本を消しますか？',
      lineIdTaken: 'その ID の路線は、もうあります。',
      lineIdEmpty: 'ID を入れてください。',
      noLine: '左の「路線を足す」から、路線を作ってください。',
      noStations: '路線に駅を2つ以上入れてください。',
      status: '{0}: 路線 {1}、列車 {2} 本、運用 {3}',
      statusNetwork: '線路: 駅 {0}、区間 {1}',
      statusNoNetwork: '線路: まだ読み込んでいません',
      versions: '読める版: ダイヤは第{0}版、線路は第{1}版まで',
      untitled: '(名前なし)',
      up: '上へ',
      down: '下へ',
      remove: '外す',
      deadhead: '回送',
      language: '言語',
    },
    en: {
      title: 'Timetable Editor',
      timetableName: 'Timetable name',
      new: 'New',
      open: 'Open timetable',
      network: 'Load network',
      export: 'Export',
      undo: 'Undo',
      redo: 'Redo',
      lines: 'Lines',
      addLine: 'Add line',
      editLine: 'Edit line',
      trains: 'Trains',
      duties: 'Duties',
      problems: 'Problems',
      noProblems: 'No problems',
      problemCount: '{0} problems',
      confirmNew: 'Throw this timetable away and start a new one?',
      openTitle: 'Open a timetable',
      openHelp: 'Run /rttimetable copy in the game, then paste it here. A timetable.json file can be opened too.',
      networkTitle: 'Load the track network',
      networkHelp: 'Run /rtnetwork copy in the game, then paste it here. A network.json file can be opened too.',
      read: 'Load',
      cancel: 'Cancel',
      close: 'Close',
      notRead: 'It could not be loaded.',
      exportTitle: 'Export',
      exportHelp: 'Copy it to the clipboard, then run /rttimetable paste in the game. A saved file goes in createrealtimeschedule/timetable.json in the world folder, for /rttimetable load.',
      exportProblems: 'There are {0} problems; the game will not load the timetable as it is.',
      copy: 'Copy to clipboard',
      copied: 'Copied',
      copyFailed: 'It could not be copied. Select the text above and copy it by hand.',
      save: 'Save as a file',
      lineId: 'Line id',
      lineName: 'Line name',
      loop: 'Loop line',
      stationsOfLine: 'Stations of the line, top down',
      networkStations: 'Stations of the network',
      stationName: 'Station name',
      addStation: 'Add',
      noNetwork: 'Load the network to pick its stations here.',
      ok: 'OK',
      removeLine: 'Remove line',
      confirmRemoveLine: 'Remove the line "{0}" and its {1} trains?',
      lineIdTaken: 'Another line has that id.',
      lineIdEmpty: 'Give the line an id.',
      noLine: 'Add a line on the left to begin.',
      noStations: 'Give the line two stations or more.',
      status: '{0}: {1} lines, {2} trains, {3} duties',
      statusNetwork: 'Network: {0} stations, {1} legs',
      statusNoNetwork: 'Network: not loaded yet',
      versions: 'Reads timetables up to version {0}, networks up to version {1}',
      untitled: '(untitled)',
      up: 'Up',
      down: 'Down',
      remove: 'Remove',
      deadhead: 'Deadhead',
      language: 'Language',
    },
  };

  let language = 'en';

  function setLanguage(name) {
    language = WORDS[name] ? name : 'en';
  }

  function current() {
    return language;
  }

  /** The words of key, with {0}, {1}... filled in. */
  function t(key, ...values) {
    const words = WORDS[language][key] !== undefined ? WORDS[language][key] : WORDS.en[key];
    if (words === undefined)
      return key;
    return words.replace(/\{(\d+)\}/g, (all, i) => String(values[Number(i)]));
  }

  /** The language to start in, from the browser's. */
  function guess(browserLanguage) {
    return typeof browserLanguage === 'string' && browserLanguage.toLowerCase().startsWith('ja') ? 'ja' : 'en';
  }

  return { WORDS, setLanguage, current, t, guess };
});
