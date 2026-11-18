/* =============================================================
   NEKOPLAY — 原型逻辑
   状态集中在一个 state 对象里，渲染函数按区域更新 DOM。
   ============================================================= */
'use strict';

/* ---------- 示例数据 ---------- */

const SAMPLE_LRC = `[ti:Low Tide Index]
[ar:Lumen Field]
[00:00.00]
[01:02.00]街灯が水に滲んでいく
[01:02.00]Machiakari ga mizu ni nijinde iku
[01:02.00]街灯在水面上晕开
[01:14.00]数字だけが静かに並ぶ
[01:14.00]Sūji dake ga shizuka ni narabu
[01:14.00]只有数字静静排成一列
[01:26.00]君は大丈夫だと言った
[01:26.00]Kimi wa daijōbu da to itta
[01:26.00]你说过没关系
[01:34.00]引き潮の合図をもう一度
[01:34.00]Hikishio no aizu o mō ichido
[01:34.00]退潮的信号，再读一次
[01:49.00]桟橋の上で歩数を数える
[01:49.00]Sanbashi no ue de hosū o kazoeru
[01:49.00]在栈桥上数着脚步
[02:03.00]ここでは何も急がない
[02:03.00]Koko de wa nani mo isoganai
[02:03.00]这里没有什么需要着急
[02:18.00]線だけが残っていく
[02:18.00]Sen dake ga nokotte iku
[02:18.00]只有那条线留了下来
[02:31.00]音が消えるまで待っていて
[02:31.00]Oto ga kieru made matte ite
[02:31.00]等到声音消失为止`;

const SAMPLE_TRACKS = [
  { id: 1, title: 'Afterglow Protocol', artist: 'Lumen Field',  durationSec: 222, format: 'FLAC', sampleRate: '44.1 kHz' },
  { id: 2, title: 'Signal Loss',        artist: 'Halftone',     durationSec: 258, format: 'FLAC', sampleRate: '96 kHz' },
  { id: 3, title: 'Paper Satellites',   artist: 'Marlow & Oda', durationSec: 185, format: 'WAV',  sampleRate: '48 kHz' },
  { id: 4, title: 'Low Tide Index',     artist: 'Lumen Field',  durationSec: 311, format: 'FLAC', sampleRate: '44.1 kHz', lyricsText: SAMPLE_LRC },
  { id: 5, title: 'Cold Open',          artist: 'Quiet Harbor', durationSec: 168, format: 'FLAC', sampleRate: '44.1 kHz' },
  { id: 6, title: 'Gridlines',          artist: 'Halftone',     durationSec: 242, format: 'WAV',  sampleRate: '48 kHz' },
  { id: 7, title: 'Slow Static',        artist: 'Quiet Harbor', durationSec: 216, format: 'FLAC', sampleRate: '96 kHz' },
  { id: 8, title: 'Reference Tone',     artist: 'Marlow & Oda', durationSec: 384, format: 'FLAC', sampleRate: '44.1 kHz' }
];

const ICONS = {
  play: '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><path d="M8 5.5v13l10-6.5z" fill="currentColor"/></svg>',
  pause: '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><rect x="7" y="5.5" width="3.6" height="13" fill="currentColor"/><rect x="13.4" y="5.5" width="3.6" height="13" fill="currentColor"/></svg>',
  prev: '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false"><path d="M7 5h2v14H7zM19 5v14L9.5 12z" fill="currentColor"/></svg>',
  next: '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false"><path d="M17 5h-2v14h2zM5 5v14l9.5-7z" fill="currentColor"/></svg>'
};

const CONTROLS_HTML = `
  <button class="btn-square js-prev" type="button" data-act="prev" aria-label="上一首">${ICONS.prev}</button>
  <button class="btn-play js-play" type="button" data-act="play" aria-label="播放">
    <span class="js-playtext">播放</span>
    <span class="js-playicon">${ICONS.play}</span>
  </button>
  <button class="btn-square js-next" type="button" data-act="next" aria-label="下一首">${ICONS.next}</button>`;

const PROGRESS_HTML = `
  <span class="time cur mono js-cur">00:00</span>
  <input class="slider js-seek" type="range" min="0" max="100" step="0.1" value="0" aria-label="播放进度">
  <span class="time dur mono js-dur">00:00</span>`;

const MODE_HTML = `
  <button type="button" data-mode="sequence" aria-pressed="true">顺序</button>
  <button type="button" data-mode="shuffle" aria-pressed="false">随机</button>
  <button type="button" data-mode="one" aria-pressed="false">单曲</button>`;

const COVER_CELLS = '<span class="cell c1"></span><span class="cell c2"></span><span class="cell c3"></span><span class="cell c4"></span>';

const SPEC_COLORS = ['#1A4BE0', '#0F3FC7', '#2457F5', '#0B2E9E', '#5B3FD1', '#0F7B6C', '#B4441B', '#0E1117'];
const SPEC_DEFAULT = { on: true, style: 'bars', position: 'bottom', count: 48, speed: 1, opacity: 16, height: 200, color: '#1A4BE0' };

const SPECTRUM_KEY = 'nekoplay.spectrum';
const FILTER_KEY = 'nekoplay.lyrics';
const THEME_KEY = 'nekoplay.theme';
const THEMES = ['classic', 'glass', 'bigtype', 'bignum', 'original'];

/* ---------- 工具 ---------- */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const pad2 = (n) => String(n).padStart(2, '0');
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

function fmt(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  return `${pad2(Math.floor(sec / 60))}:${pad2(sec % 60)}`;
}

function fmtLong(sec) {
  sec = Math.max(0, Math.round(sec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  return h ? `${h}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`;
}

const mqMobile = window.matchMedia('(max-width: 999px)');
const isMobile = () => mqMobile.matches;

/* ---------- 持久化 ---------- */

function loadTheme() {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    return THEMES.includes(saved) ? saved : 'classic';
  } catch (err) {
    return 'classic';
  }
}

function loadFilters() {
  const fallback = { orig: true, romaji: true, trans: true };
  try {
    const raw = JSON.parse(localStorage.getItem(FILTER_KEY) || 'null');
    if (raw && typeof raw === 'object') {
      const f = { orig: !!raw.orig, romaji: !!raw.romaji, trans: !!raw.trans };
      if (f.orig || f.romaji || f.trans) return f;
    }
  } catch (err) { /* ignore */ }
  return fallback;
}

function saveFilters() {
  try { localStorage.setItem(FILTER_KEY, JSON.stringify(state.filters)); } catch (err) { /* ignore */ }
}

function loadSpectrum() {
  const base = { ...SPEC_DEFAULT };
  try {
    const raw = JSON.parse(localStorage.getItem(SPECTRUM_KEY) || 'null');
    if (raw && typeof raw === 'object') {
      if (typeof raw.on === 'boolean') base.on = raw.on;
      if (['bars', 'thin', 'mirror'].includes(raw.style)) base.style = raw.style;
      if (['bottom', 'fill'].includes(raw.position)) base.position = raw.position;
      if ([24, 48, 96].includes(raw.count)) base.count = raw.count;
      if ([0.5, 1, 2].includes(raw.speed)) base.speed = raw.speed;
      if (typeof raw.opacity === 'number') base.opacity = clamp(raw.opacity, 4, 45);
      if (typeof raw.height === 'number') base.height = clamp(raw.height, 80, 320);
      if (SPEC_COLORS.includes(raw.color)) base.color = raw.color;
    }
  } catch (err) { /* ignore */ }
  return base;
}

function saveSpectrum() {
  try {
    const out = {};
    Object.keys(SPEC_DEFAULT).forEach((k) => { out[k] = state.spectrum[k]; });
    localStorage.setItem(SPECTRUM_KEY, JSON.stringify(out));
  } catch (err) { /* ignore */ }
}

/* ---------- 状态 ---------- */

const state = {
  tracks: SAMPLE_TRACKS.map((t) => ({ ...t })),
  currentId: 4,
  playing: false,
  time: 0,
  duration: 311,
  volume: 0.8,
  mode: 'sequence',
  query: '',
  view: isMobile() ? 'player' : 'queue',
  nav: 'now',
  filters: loadFilters(),
  spectrum: loadSpectrum(),
  theme: loadTheme()
};

let lyricIdx = -2;
let userScrollAt = 0;
let toastTimer = 0;

let actx = null;
let analyser = null;
let freqData = null;
let desktopWindow = null;

const audio = $('#audio');

/* ---------- 数据访问 ---------- */

function current() {
  return state.tracks.find((t) => t.id === state.currentId) || state.tracks[0];
}

function trackIndex(t) {
  return state.tracks.indexOf(t);
}

function visibleTracks() {
  const q = state.query.trim().toLowerCase();
  if (!q) return state.tracks;
  return state.tracks.filter((t) => t.title.toLowerCase().includes(q) || t.artist.toLowerCase().includes(q));
}

function countText(list) {
  const total = list.reduce((sum, t) => sum + (t.durationSec || 0), 0);
  return `${pad2(list.length)} 首 · ${fmtLong(total)}`;
}

/* ---------- LRC 解析 ---------- */

function hasCJK(s) {
  return /[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff]/.test(s);
}

function parseLrc(text) {
  const re = /^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]\s*(.*)$/;
  const entries = [];

  String(text).split(/\r?\n/).forEach((raw) => {
    const m = raw.trim().match(re);
    if (!m) return;
    const frac = m[3] ? Number('0.' + m[3]) : 0;
    const time = Number(m[1]) * 60 + Number(m[2]) + frac;
    const txt = m[4].trim();
    if (!txt) return;
    entries.push({ time, text: txt });
  });

  const groups = [];
  entries.forEach((e) => {
    const last = groups[groups.length - 1];
    if (last && Math.abs(last.time - e.time) < 0.001) last.texts.push(e.text);
    else groups.push({ time: e.time, texts: [e.text] });
  });

  return groups
    .map((g) => {
      const line = { time: g.time, orig: '', romaji: '', trans: '' };
      if (g.texts.length >= 3) {
        line.orig = g.texts[0];
        line.romaji = g.texts[1];
        line.trans = g.texts[2];
      } else if (g.texts.length === 2) {
        line.orig = g.texts[0];
        if (hasCJK(g.texts[1])) line.trans = g.texts[1];
        else line.romaji = g.texts[1];
      } else {
        line.orig = g.texts[0];
      }
      return line;
    })
    .sort((a, b) => a.time - b.time);
}

function getLyrics(t) {
  if (!t) return null;
  if (Array.isArray(t._lines)) return t._lines;
  if (t.lyricsText) {
    t._lines = parseLrc(t.lyricsText);
    return t._lines;
  }
  return null;
}

function layerMeta(lines) {
  return {
    orig: true,
    romaji: !!(lines && lines.some((l) => l.romaji)),
    trans: !!(lines && lines.some((l) => l.trans))
  };
}

function currentLyricIndex(lines, time) {
  if (!lines || !lines.length) return -1;
  let idx = -1;
  for (let i = 0; i < lines.length; i += 1) {
    if (lines[i].time <= time + 0.05) idx = i;
    else break;
  }
  return idx;
}

/* ---------- 封面 ---------- */

function paintCover(el, idx, artUrl) {
  if (!el) return;
  ['cover-s0', 'cover-s1', 'cover-s2', 'cover-s3'].forEach((c) => el.classList.remove(c));
  el.classList.toggle('has-art', !!artUrl);
  if (artUrl) {
    el.innerHTML = `<img src="${esc(artUrl)}" alt="">`;
    return;
  }
  const n = ((idx % 4) + 4) % 4;
  el.classList.add('cover-s' + n);
  el.innerHTML = COVER_CELLS;
}

/* ---------- 渲染：正在播放 ---------- */

function renderNow() {
  const t = current();
  const idx = Math.max(0, trackIndex(t));

  $('#track-title').textContent = t.title;
  $('#track-artist').textContent = t.artist;
  $('#track-meta').textContent =
    `${pad2(idx + 1)} / ${pad2(state.tracks.length)} · ${t.format} · ${t.sampleRate}`;

  paintCover($('#cover'), idx, t.coverUrl);
  paintCover($('#mini-cover'), idx, t.coverUrl);

  $('#pb-title').textContent = t.title;
  $('#pb-artist').textContent = t.artist;

  const nt = pickNextTrack(true);
  $('#next-title').textContent = nt ? nt.title : '—';
  $('#next-artist').textContent = nt ? nt.artist : '队列末尾';

  updateThemeTexts();
  document.title = `${t.title} · NEKOPLAY`;
}

function updateThemeTexts() {
  const t = current();
  const idx = Math.max(0, trackIndex(t));
  const dur = state.duration || t.durationSec || 0;
  const set = (sel, val) => {
    const el = $(sel);
    if (el) el.textContent = val;
  };

  set('#big-num', pad2(idx + 1));
  set('#big-num-total', `/ ${pad2(state.tracks.length)}`);
  set('#big-time', fmt(state.time));
  set('#big-dur', `/ ${fmt(dur)}`);
  set('#orig-format', t.format);
  set('#orig-rate', t.sampleRate);
  set('#orig-remain', `-${fmt(Math.max(0, dur - state.time))}`);
}

/* ---------- 渲染：队列 / 曲库 / 播放列表 ---------- */

function queueRow(t) {
  const isCur = t.id === state.currentId;
  const num = pad2(Math.max(0, trackIndex(t)) + 1);
  const idxHtml = `<span class="qnum mono">${num}</span>`
    + (isCur ? '<span class="eq" aria-hidden="true"><i></i><i></i><i></i></span>' : '');

  return `<button class="qrow${isCur ? ' is-current' : ''}" type="button" data-id="${t.id}"
      aria-label="${esc(t.title)} — ${esc(t.artist)}"${isCur ? ' aria-current="true"' : ''}>
    <span class="qidx">${idxHtml}</span>
    <span class="qmeta">
      <span class="qt">${esc(t.title)}</span>
      <span class="qa">${esc(t.artist)}</span>
    </span>
    <span class="qdur mono">${fmtLong(t.durationSec)}</span>
  </button>`;
}

function emptyHTML() {
  return `<div class="empty">
    <p class="empty-title">没有找到「${esc(state.query)}」相关的曲目。</p>
    <p class="empty-note">换个关键词试试，或者清空搜索框查看全部。</p>
  </div>`;
}

function queueHTML() {
  const list = visibleTracks();
  if (!list.length) return emptyHTML();

  const rows = `<div class="qlist" role="listbox" aria-label="播放队列">${list.map(queueRow).join('')}</div>`;
  if (state.theme !== 'original') return rows;

  const total = list.reduce((sum, t) => sum + (t.durationSec || 0), 0);
  return `<div class="qhead">
      <div class="qhead-top">
        <span class="label accent">二 · 播放队列</span>
        <span class="qhead-count mono">${countText(list)}</span>
      </div>
      <div class="qhead-cols" aria-hidden="true">
        <span>#</span><span>标题</span><span>艺人</span><span>时长</span>
      </div>
    </div>
    ${rows}
    <div class="qfoot">
      <span>合计 ${pad2(list.length)} 首</span>
      <span class="mono">${fmtLong(total)}</span>
    </div>`;
}

function libraryHTML() {
  const list = visibleTracks();
  const head = `<div class="panel-head">
    <div><div class="label">LIBRARY</div><h2 class="panel-title">曲库</h2></div>
    <div class="panel-meta mono">${countText(list)}</div>
  </div>`;
  if (!list.length) return head + emptyHTML();
  return head + `<div class="qlist" role="listbox" aria-label="曲库">${list.map(queueRow).join('')}</div>`;
}

function playlistHTML() {
  const list = visibleTracks();
  const head = `<div class="panel-head">
    <div><div class="label">PLAYLISTS</div><h2 class="panel-title">播放列表</h2></div>
    <div class="panel-meta mono">${countText(list)}</div>
  </div>`;
  if (!list.length) return head + emptyHTML();

  const base = list.filter((t) => !t.isLocal);
  const local = list.filter((t) => t.isLocal);

  let html = head;
  if (base.length) {
    html += `<div class="group-head"><span class="label">默认列表</span><span class="mono">${pad2(base.length)} 首</span></div>`;
    html += `<div class="qlist" role="listbox" aria-label="默认列表">${base.map(queueRow).join('')}</div>`;
  }
  if (local.length) {
    html += `<div class="group-head"><span class="label">本地导入</span><span class="mono">${pad2(local.length)} 首</span></div>`;
    html += `<div class="qlist" role="listbox" aria-label="本地导入">${local.map(queueRow).join('')}</div>`;
  } else {
    html += `<div class="group-head"><span class="label">本地导入</span><span class="mono">00 首</span></div>
      <p class="empty-note" style="padding: 0 4px 8px;">还没有导入本地文件，点顶栏「导入音乐」选择音频。</p>`;
  }
  return html;
}

/* ---------- 渲染：歌词 ---------- */

function lyricsHTML() {
  const t = current();
  const lines = getLyrics(t);
  const idx = currentLyricIndex(lines, state.time);

  const mobileHead = `<div class="lyr-mobilehead">
    <div class="cover mcover" data-cover-idx="${Math.max(0, trackIndex(t))}" aria-hidden="true">${COVER_CELLS}</div>
    <div class="lyr-mh-meta">
      <div class="lyr-mh-title">${esc(t.title)}</div>
      <div class="lyr-mh-artist">${esc(t.artist)}</div>
    </div>
    <div class="lyr-mh-time mono js-lyr-elapsed">${fmt(state.time)}</div>
  </div>`;

  if (!lines || !lines.length) {
    return `<div class="lyr">${mobileHead}
      <div class="lyr-empty">
        <p class="empty-title">这首歌还没有歌词。</p>
        <p class="empty-note">导入 .lrc 文件后，歌词会随播放进度逐行高亮。</p>
        <button class="linkbtn" type="button" data-act="import-lrc">导入歌词</button>
      </div>
    </div>`;
  }

  const meta = layerMeta(lines);
  const f = state.filters;
  const chipDefs = [
    ['orig', '原文'],
    ['romaji', '罗马音'],
    ['trans', '翻译']
  ];
  const chips = chipDefs.map(([key, label]) => {
    const avail = key === 'orig' ? true : meta[key];
    const on = avail && f[key];
    const title = avail ? '' : ` title="这首歌没有${label}"`;
    return `<button class="chip" type="button" data-layer="${key}" aria-pressed="${on}"${avail ? '' : ' disabled'}${title}>${label}</button>`;
  }).join('');

  const rows = lines.map((line, i) => {
    const parts = [];
    if (f.orig) parts.push(`<span class="l-orig">${esc(line.orig)}</span>`);
    if (f.romaji && line.romaji) parts.push(`<span class="l-romaji">${esc(line.romaji)}</span>`);
    if (f.trans && line.trans) parts.push(`<span class="l-trans">${esc(line.trans)}</span>`);
    return `<button class="lrow${i === idx ? ' is-current' : ''}" type="button"
      data-act="seek-lyric" data-time="${line.time}" data-i="${i}"
      aria-label="跳转到 ${fmt(line.time)}：${esc(line.orig)}">${parts.join('')}</button>`;
  }).join('');

  return `<div class="lyr">${mobileHead}
    <div class="lyr-tools" role="group" aria-label="歌词显示层">${chips}</div>
    <div class="lyr-list" role="list">${rows}</div>
  </div>`;
}

function updateLyricsCurrent(force = false) {
  const list = $('.lyr-list');
  if (!list) { lyricIdx = -2; return; }

  const lines = getLyrics(current()) || [];
  const idx = currentLyricIndex(lines, state.time);
  const changed = idx !== lyricIdx;

  $$('.js-lyr-elapsed').forEach((e) => { e.textContent = fmt(state.time); });

  if (!force && !changed) return;
  lyricIdx = idx;

  const rows = $$('.lrow', list);
  const winStart = idx < 0 ? 0 : idx - 1;
  const winEnd = idx < 0 ? 5 : idx + 4;
  rows.forEach((row, i) => {
    row.classList.toggle('is-current', i === idx);
    row.classList.toggle('is-out', isMobile() && (i < winStart || i > winEnd));
    if (i === idx) row.setAttribute('aria-current', 'true');
    else row.removeAttribute('aria-current');
  });

  if (changed && !force && state.playing && idx >= 0 && isLyricsVisible() && !isMobile()) {
    if (performance.now() - userScrollAt > 4000) {
      const row = rows[idx];
      if (row) {
        const top = row.offsetTop - list.clientHeight / 2 + row.offsetHeight / 2;
        list.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
      }
    }
  }
}

function isLyricsVisible() {
  return state.nav === 'now' && state.view === 'lyrics';
}

/* ---------- 渲染：面板与标签 ---------- */

function renderPanel() {
  const panel = $('#rpanel');
  const nav = state.nav;

  $('#rtabs').hidden = nav !== 'now';

  let html;
  if (nav === 'library') html = libraryHTML();
  else if (nav === 'playlist') html = playlistHTML();
  else if (state.view === 'lyrics') html = lyricsHTML();
  else html = queueHTML();

  panel.innerHTML = html;

  // 手机端歌词迷你封面
  $$('.mcover', panel).forEach((el) => {
    const ci = Number(el.dataset.coverIdx) || 0;
    paintCover(el, ci, state.tracks[ci] ? state.tracks[ci].coverUrl : null);
  });

  updateTabs();
  lyricIdx = -2;
  updateLyricsCurrent(true);

  const list = $('.lyr-list');
  if (list) {
    requestAnimationFrame(() => {
      const row = $('.lrow.is-current', list);
      if (row) list.scrollTop = Math.max(0, row.offsetTop - list.clientHeight / 2 + row.offsetHeight / 2);
    });
  }
}

function updateTabs() {
  $$('.rtab').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.view === state.view)));
  $$('.mtab').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.view === state.view)));
  $$('.nav-item').forEach((b) => b.classList.toggle('is-active', b.dataset.nav === state.nav));

  $('#main').dataset.view = state.view;
  document.body.dataset.view = state.view;

  $('#rtabs-meta').textContent = countText(visibleTracks());
}

/* ---------- 渲染：播放控制 ---------- */

function paintSlider(el) {
  const min = Number(el.min) || 0;
  const max = Number(el.max) || 0;
  const v = Number(el.value);
  const pct = max > min ? ((v - min) / (max - min)) * 100 : 0;
  el.style.setProperty('--pct', pct.toFixed(2) + '%');
}

function renderTransport() {
  const t = current();

  $$('.js-playtext').forEach((e) => { e.textContent = state.playing ? '暂停' : '播放'; });
  $$('.js-playicon').forEach((e) => { e.innerHTML = state.playing ? ICONS.pause : ICONS.play; });
  $$('.js-play').forEach((e) => e.setAttribute('aria-label', state.playing ? '暂停' : '播放'));

  $$('[data-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mode === state.mode)));

  $$('.js-vol').forEach((v) => {
    v.value = String(Math.round(state.volume * 100));
    paintSlider(v);
  });

  document.body.classList.toggle('is-playing', state.playing);

  // 队列里当前行的音柱
  $$('.qrow').forEach((row) => {
    row.classList.toggle('is-current', Number(row.dataset.id) === t.id);
  });

  renderTransportTime();
  updateBars();
}

function renderTransportTime() {
  const dur = state.duration || current().durationSec || 0;

  $$('.js-cur').forEach((e) => { e.textContent = fmt(state.time); });
  $$('.js-dur').forEach((e) => { e.textContent = fmt(dur); });

  $$('.js-seek').forEach((el) => {
    el.max = String(dur || 0);
    if (document.activeElement !== el) el.value = String(Math.min(state.time, dur));
    paintSlider(el);
  });

  updateLyricsCurrent(false);
  updateThemeTexts();
}

/* ---------- 播放核心 ---------- */

function ensureAudioGraph() {
  if (actx) {
    if (actx.state === 'suspended') actx.resume().catch(() => {});
    return;
  }
  try {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    actx = new AC();
    const src = actx.createMediaElementSource(audio);
    analyser = actx.createAnalyser();
    analyser.fftSize = 2048;
    analyser.smoothingTimeConstant = 0.8;
    src.connect(analyser);
    analyser.connect(actx.destination);
    freqData = new Uint8Array(analyser.frequencyBinCount);
  } catch (err) {
    console.warn('[NEKOPLAY] Web Audio 初始化失败：', err);
    actx = null;
    analyser = null;
  }
}

function play() {
  const t = current();
  if (t.isLocal) {
    ensureAudioGraph();
    if (actx && actx.state === 'suspended') actx.resume().catch(() => {});
    if (audio.getAttribute('src') !== t.src) audio.src = t.src;
    audio.volume = state.volume;
    if (Number.isFinite(audio.duration) && Math.abs(audio.currentTime - state.time) > 0.25) {
      try { audio.currentTime = state.time; } catch (err) { /* ignore */ }
    }
    audio.play().then(() => {
      if (actx && actx.state === 'suspended') actx.resume().catch(() => {});
    }).catch(() => toast('浏览器拦截了播放，请再点一次播放按钮'));
  }
  state.playing = true;
  renderTransport();
}

function pause() {
  audio.pause();
  state.playing = false;
  renderTransport();
}

function togglePlay() {
  if (state.playing) pause();
  else play();
}

function seekTo(sec) {
  const dur = state.duration || 0;
  state.time = clamp(Number(sec) || 0, 0, dur);
  if (current().isLocal) {
    try { audio.currentTime = state.time; } catch (err) { /* ignore */ }
  }
  renderTransportTime();
}

function pickNextTrack(displayOnly = false) {
  const list = state.tracks;
  if (!list.length) return null;
  if (!displayOnly && state.mode === 'shuffle' && list.length > 1) {
    let t;
    do { t = list[Math.floor(Math.random() * list.length)]; } while (t.id === state.currentId);
    return t;
  }
  const i = trackIndex(current());
  return list[(i + 1) % list.length];
}

function setTrack(id, playNow = true) {
  const t = state.tracks.find((x) => x.id === id);
  if (!t) return;

  audio.pause();
  state.playing = false;
  state.currentId = t.id;
  state.time = 0;
  state.duration = t.durationSec || 0;
  lyricIdx = -2;

  renderNow();
  renderPanel();
  renderTransport();

  if (playNow) play();
}

function nextTrack() {
  const t = pickNextTrack();
  if (t) setTrack(t.id, true);
}

function prevTrack() {
  if (state.time > 3) {
    seekTo(0);
    return;
  }
  const i = trackIndex(current());
  const list = state.tracks;
  const t = list[(i - 1 + list.length) % list.length];
  if (t) setTrack(t.id, true);
}

function onEnded() {
  if (state.mode === 'one') {
    seekTo(0);
    play();
    return;
  }
  const t = pickNextTrack();
  if (t) setTrack(t.id, true);
}

audio.addEventListener('ended', () => {
  if (state.playing) onEnded();
});

audio.addEventListener('loadedmetadata', () => {
  const t = current();
  if (t.isLocal && Number.isFinite(audio.duration)) {
    t.durationSec = audio.duration;
    state.duration = audio.duration;
    renderTransportTime();
  }
});

audio.addEventListener('timeupdate', () => {
  const t = current();
  if (state.playing && t.isLocal) state.time = audio.currentTime || 0;
});

/* ---------- 频谱 ---------- */

const barsEl = $('#bars');
const spectrumEl = $('#spectrum');

function buildBars() {
  const count = state.spectrum.count;
  barsEl.innerHTML = '';
  for (let i = 0; i < count; i += 1) {
    const bar = document.createElement('div');
    bar.className = 'bar';
    const base = 0.45 + Math.random() * 0.5; // 0.45–0.95s
    bar.dataset.dur = base.toFixed(2);
    bar.style.animationDuration = base.toFixed(2) + 's';
    bar.style.animationDelay = (-Math.random() * 1.6).toFixed(2) + 's';
    bar.style.setProperty('--m', (0.25 + Math.random() * 0.75).toFixed(2));
    bar.style.setProperty('--s', '.12');
    barsEl.appendChild(bar);
  }
}

function freezeBars() {
  $$('.bar', barsEl).forEach((bar) => {
    let scale = 0.12;
    const tr = getComputedStyle(bar).transform;
    const m = tr && tr.match(/matrix\(([^)]+)\)/);
    if (m) {
      const parts = m[1].split(',').map(Number);
      if (Number.isFinite(parts[3])) scale = Math.abs(parts[3]);
    }
    bar.style.setProperty('--s', scale.toFixed(3));
  });
  barsEl.classList.remove('sim', 'live');
}

function updateBars() {
  const s = state.spectrum;
  const playing = state.playing && s.on;

  if (!playing) {
    if (barsEl.classList.contains('sim') || barsEl.classList.contains('live')) {
      freezeBars();
      requestAnimationFrame(() => {
        $$('.bar', barsEl).forEach((b) => b.style.setProperty('--s', '.06'));
      });
    } else {
      $$('.bar', barsEl).forEach((b) => b.style.setProperty('--s', '.06'));
    }
    return;
  }

  const real = current().isLocal;
  barsEl.classList.toggle('sim', !real);
  barsEl.classList.toggle('live', real);

  $$('.bar', barsEl).forEach((b) => {
    b.style.animationDuration = (Number(b.dataset.dur) / (s.speed || 1)).toFixed(3) + 's';
  });
}

function applySpectrum() {
  const s = state.spectrum;

  spectrumEl.style.opacity = s.on ? String(s.opacity / 100) : '0';
  spectrumEl.style.setProperty('--sh', (isMobile() ? 220 : s.height) + 'px');
  spectrumEl.style.setProperty('--sc', s.color);

  spectrumEl.classList.toggle('is-fill', s.position === 'fill' && !isMobile());
  spectrumEl.classList.toggle('is-mirror', s.style === 'mirror');
  spectrumEl.classList.toggle('is-thin', s.style === 'thin');

  if (barsEl.children.length !== s.count) buildBars();

  updateBars();
  renderSettings();
}

function spectrumFrame() {
  const s = state.spectrum;
  if (!s.on || !state.playing) return;
  if (!current().isLocal || !analyser || !freqData || !actx) return;

  analyser.getByteFrequencyData(freqData);

  const bars = barsEl.children;
  const n = bars.length;
  if (!n) return;

  const nyquist = actx.sampleRate / 2;
  const fmin = 30;
  const fmax = 16000;
  const ratio = fmax / fmin;

  for (let i = 0; i < n; i += 1) {
    const f0 = fmin * Math.pow(ratio, i / n);
    const f1 = fmin * Math.pow(ratio, (i + 1) / n);
    let b0 = Math.floor((f0 / nyquist) * freqData.length);
    let b1 = Math.ceil((f1 / nyquist) * freqData.length);
    b0 = clamp(b0, 0, freqData.length - 1);
    b1 = clamp(Math.max(b1, b0 + 1), 0, freqData.length);
    let sum = 0;
    let c = 0;
    for (let b = b0; b < b1; b += 1) {
      sum += freqData[b];
      c += 1;
    }
    const v = c ? sum / c / 255 : 0;
    const val = clamp(Math.pow(v, 1.25) * 1.65, 0.06, 1);
    bars[i].style.setProperty('--s', val.toFixed(3));
  }
}

/* ---------- 频谱设置面板 ---------- */

function renderSettings() {
  const s = state.spectrum;

  const visible = $('#set-visible');
  if (visible) visible.checked = s.on;

  $$('[data-theme-set] button').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.v === state.theme));
  });

  $$('[data-set]').forEach((seg) => {
    const key = seg.dataset.set;
    $$('button', seg).forEach((b) => {
      b.setAttribute('aria-pressed', String(String(s[key]) === b.dataset.v));
    });
  });

  const op = $('#set-opacity');
  if (op) op.value = String(s.opacity);
  const opv = $('#val-opacity');
  if (opv) opv.textContent = s.opacity + '%';

  const hg = $('#set-height');
  if (hg) {
    hg.value = String(s.height);
    hg.disabled = s.position === 'fill';
  }
  const hgv = $('#val-height');
  if (hgv) hgv.textContent = s.height + 'px';

  $$('#set-colors .swatch').forEach((b) => {
    b.setAttribute('aria-pressed', String(b.dataset.c === s.color));
  });
}

function setSpec(key, value) {
  state.spectrum[key] = value;
  saveSpectrum();
  applySpectrum();
}

function setTheme(theme) {
  if (!THEMES.includes(theme)) return;

  state.theme = theme;
  try { localStorage.setItem(THEME_KEY, theme); } catch (err) { /* ignore */ }

  document.documentElement.dataset.theme = theme;

  // 初版 / 大字 / 数字主题没有队列与歌词标签，回到队列避免卡在不可切换的视图
  if (['original', 'bigtype', 'bignum'].includes(theme) && state.nav === 'now' && state.view === 'lyrics') {
    state.view = 'queue';
  }

  updateThemeTexts();
  renderSettings();
  renderPanel();
  renderTransport();
}

function openSettings() {
  const panel = $('#spectrum-panel');
  panel.hidden = false;
  closeAvatarMenu();
  renderSettings();
  const selectedTheme = $('[data-theme-set] button[aria-pressed="true"]');
  (selectedTheme || $('#set-visible')).focus();
}

function closeSettings() {
  const panel = $('#spectrum-panel');
  if (panel.hidden) return;
  panel.hidden = true;
  const trigger = $('#btn-avatar');
  if (trigger) trigger.focus();
}

/* ---------- 头像菜单 ---------- */

function closeAvatarMenu() {
  const menu = $('#avatar-menu');
  const btn = $('#btn-avatar');
  if (!menu || menu.hidden) return;
  menu.hidden = true;
  if (btn) btn.setAttribute('aria-expanded', 'false');
}

function toggleAvatarMenu() {
  const menu = $('#avatar-menu');
  const btn = $('#btn-avatar');
  if (!menu || !btn) return;
  const open = menu.hidden;
  menu.hidden = !open;
  btn.setAttribute('aria-expanded', String(open));
  if (open) {
    const first = $('.menu-item', menu);
    if (first) first.focus();
  }
}

/* ---------- 提示 ---------- */

function toast(msg) {
  const el = $('#toast');
  el.textContent = msg;
  el.classList.add('show');
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => el.classList.remove('show'), 2400);
}

/* ---------- 导入 ---------- */

/* 解析内嵌封面：FLAC PICTURE / MP3 ID3v2 APIC / M4A covr */

const COVER_SCAN_BYTES = 8 * 1024 * 1024;

function bytesAt(u8, off, text) {
  if (off < 0 || off + text.length > u8.length) return false;
  for (let i = 0; i < text.length; i += 1) {
    if (u8[off + i] !== text.charCodeAt(i)) return false;
  }
  return true;
}

function findBytes(u8, text, from = 0) {
  const last = u8.length - text.length;
  for (let i = Math.max(0, from); i <= last; i += 1) {
    if (bytesAt(u8, i, text)) return i;
  }
  return -1;
}

function ascii(u8, start, len) {
  let out = '';
  for (let i = 0; i < len; i += 1) out += String.fromCharCode(u8[start + i]);
  return out;
}

function be32(u8, p) {
  return (((u8[p] << 24) >>> 0) + (u8[p + 1] << 16) + (u8[p + 2] << 8) + u8[p + 3]) >>> 0;
}

function be24(u8, p) {
  return (u8[p] << 16) + (u8[p + 1] << 8) + u8[p + 2];
}

function syncsafe32(u8, p) {
  return ((u8[p] & 0x7f) << 21) | ((u8[p + 1] & 0x7f) << 14) | ((u8[p + 2] & 0x7f) << 7) | (u8[p + 3] & 0x7f);
}

function sniffImageMime(data) {
  if (!data || data.length < 12) return null;
  if (data[0] === 0xff && data[1] === 0xd8) return 'image/jpeg';
  if (data[0] === 0x89 && data[1] === 0x50 && data[2] === 0x4e && data[3] === 0x47) return 'image/png';
  if (data[0] === 0x47 && data[1] === 0x49 && data[2] === 0x46) return 'image/gif';
  if (data[0] === 0x42 && data[1] === 0x4d) return 'image/bmp';
  if (data[8] === 0x57 && data[9] === 0x45 && data[10] === 0x42 && data[11] === 0x50) return 'image/webp';
  return null;
}

function parseApicFrame(b) {
  if (b.length < 4) return null;
  const enc = b[0];
  let p = 1;
  const mimeStart = p;
  while (p < b.length && b[p] !== 0) p += 1;
  const mime = ascii(b, mimeStart, p - mimeStart).toLowerCase();
  p += 1;
  if (p >= b.length) return null;
  p += 1; // picture type
  if (enc === 1 || enc === 2) {
    while (p + 1 < b.length && !(b[p] === 0 && b[p + 1] === 0)) p += 2;
    p += 2;
  } else {
    while (p < b.length && b[p] !== 0) p += 1;
    p += 1;
  }
  if (p >= b.length) return null;
  const data = b.subarray(p);
  const sniffed = sniffImageMime(data);
  if (!sniffed) return null;
  return { mime: sniffed || mime, data };
}

function parsePicFrame(b) {
  if (b.length < 6) return null;
  const enc = b[0];
  const fmt = ascii(b, 1, 3).toUpperCase();
  let p = 5; // 编码 + 3 字符格式 + 图片类型
  if (enc === 1 || enc === 2) {
    while (p + 1 < b.length && !(b[p] === 0 && b[p + 1] === 0)) p += 2;
    p += 2;
  } else {
    while (p < b.length && b[p] !== 0) p += 1;
    p += 1;
  }
  if (p >= b.length) return null;
  const data = b.subarray(p);
  const sniffed = sniffImageMime(data);
  if (!sniffed) return null;
  const mime = sniffed || (fmt === 'PNG' ? 'image/png' : 'image/jpeg');
  return { mime, data };
}

function parseId3Cover(u8) {
  if (!bytesAt(u8, 0, 'ID3') || u8.length < 10) return null;
  const ver = u8[3];
  const flags = u8[5];
  const tagSize = syncsafe32(u8, 6);
  const end = Math.min(u8.length, 10 + tagSize);
  let p = 10;

  if (ver === 2) {
    while (p + 6 <= end) {
      const id = ascii(u8, p, 3);
      if (!/^[A-Z0-9]{3}$/.test(id)) break;
      const size = be24(u8, p + 3);
      p += 6;
      if (size <= 0 || p + size > end) break;
      if (id === 'PIC') return parsePicFrame(u8.subarray(p, p + size));
      p += size;
    }
    return null;
  }

  if (ver !== 3 && ver !== 4) return null;

  if (flags & 0x40) {
    if (ver === 4) {
      p = 10 + syncsafe32(u8, 10);
    } else {
      p = 10 + 4 + be32(u8, 10);
    }
    if (p >= end) return null;
  }

  while (p + 10 <= end) {
    const id = ascii(u8, p, 4);
    if (!/^[A-Z0-9]{4}$/.test(id)) break;
    const size = ver === 4 ? syncsafe32(u8, p + 4) : be32(u8, p + 4);
    p += 10;
    if (size <= 0 || p + size > end) break;
    if (id === 'APIC') {
      const art = parseApicFrame(u8.subarray(p, p + size));
      if (art) return art;
    }
    p += size;
  }
  return null;
}

function parseFlacPicture(b) {
  if (b.length < 32) return null;
  let p = 4; // picture type
  const mimeLen = be32(b, p);
  p += 4;
  if (p + mimeLen > b.length) return null;
  const mime = ascii(b, p, mimeLen).toLowerCase();
  p += mimeLen;
  const descLen = be32(b, p);
  p += 4 + descLen + 16;
  if (p + 4 > b.length) return null;
  const dataLen = be32(b, p);
  p += 4;
  if (!dataLen || p + dataLen > b.length) return null;
  const data = b.subarray(p, p + dataLen);
  const sniffed = sniffImageMime(data);
  if (!sniffed) return null;
  return { mime: sniffed || mime, data };
}

function parseFlacCover(u8, start) {
  if (!bytesAt(u8, start, 'fLaC')) return null;
  let p = start + 4;
  while (p + 4 <= u8.length) {
    const header = u8[p];
    const last = !!(header & 0x80);
    const type = header & 0x7f;
    const len = be24(u8, p + 1);
    p += 4;
    if (p + len > u8.length) return null;
    if (type === 6) {
      const art = parseFlacPicture(u8.subarray(p, p + len));
      if (art) return art;
    }
    p += len;
    if (last) break;
  }
  return null;
}

function parseMp4Cover(u8) {
  let from = 0;
  for (let tries = 0; tries < 4; tries += 1) {
    const idx = findBytes(u8, 'covr', from);
    if (idx < 0) return null;
    from = idx + 4;
    let p = idx + 4;
    while (p + 8 <= u8.length) {
      const size = be32(u8, p);
      if (size < 8) break;
      const type = ascii(u8, p + 4, 4);
      if (type === 'data') {
        if (p + 16 > u8.length) break;
        const flag = be32(u8, p + 8) & 0xffffff;
        const end = Math.min(p + size, u8.length);
        if (end <= p + 16) break;
        const data = u8.subarray(p + 16, end);
        const sniffed = sniffImageMime(data);
        if (!sniffed) break;
        return { mime: sniffed || (flag === 14 ? 'image/png' : 'image/jpeg'), data };
      }
      p += size;
    }
  }
  return null;
}

async function extractCover(file) {
  if (!file || !file.size) return null;
  try {
    const headLen = Math.min(file.size, COVER_SCAN_BYTES);
    const head = new Uint8Array(await file.slice(0, headLen).arrayBuffer());
    let art = null;

    if (bytesAt(head, 0, 'fLaC')) {
      art = parseFlacCover(head, 0);
    } else if (bytesAt(head, 0, 'ID3')) {
      const tagSize = 10 + syncsafe32(head, 6);
      if (tagSize < head.length && bytesAt(head, tagSize, 'fLaC')) {
        art = parseFlacCover(head, tagSize);
      }
      if (!art) art = parseId3Cover(head);
    }

    const name = (file.name || '').toLowerCase();
    const isMp4 = bytesAt(head, 4, 'ftyp') || /\.(m4a|mp4|m4b)$/.test(name);
    if (!art && isMp4) {
      art = parseMp4Cover(head);
      if (!art && file.size > headLen) {
        const tailLen = Math.min(file.size, COVER_SCAN_BYTES);
        const tail = new Uint8Array(await file.slice(file.size - tailLen).arrayBuffer());
        art = parseMp4Cover(tail);
      }
    }

    if (!art || !art.data || !art.data.length) return null;
    return URL.createObjectURL(new Blob([art.data], { type: art.mime }));
  } catch (err) {
    return null;
  }
}

function handleAudioFiles(files) {
  if (!files || !files.length) return;

  ensureAudioGraph();
  const sr = actx ? actx.sampleRate : 44100;
  const srText = (sr / 1000).toFixed(1) + ' kHz';

  let maxId = 0;
  state.tracks.forEach((t) => { if (t.id > maxId) maxId = t.id; });

  const added = [];
  files.forEach((file) => {
    const src = URL.createObjectURL(file);
    const ext = (file.name.split('.').pop() || '').toUpperCase();
    const track = {
      id: ++maxId,
      title: file.name.replace(/\.[^.]+$/, ''),
      artist: '本地文件',
      durationSec: 0,
      format: ext || 'AUDIO',
      sampleRate: srText,
      isLocal: true,
      src,
      file
    };
    state.tracks.push(track);
    added.push(track);

    const probe = new Audio(src);
    probe.addEventListener('loadedmetadata', () => {
      track.durationSec = Number.isFinite(probe.duration) ? probe.duration : 0;
      if (track.id === state.currentId) {
        state.duration = track.durationSec;
        renderTransport();
      }
      renderPanel();
    }, { once: true });
  });

  if (added.length) {
    setTrack(added[0].id, true);
    toast(`已导入 ${added.length} 首本地音乐`);

    // 后台逐个读取内嵌封面，读到后替换几何封面
    (async () => {
      for (const track of added) {
        const url = await extractCover(track.file);
        if (!url) continue;
        track.coverUrl = url;
        if (track.id === state.currentId) renderNow();
        renderPanel();
      }
    })();
  }
}

function handleLrcFile(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    const lines = parseLrc(String(reader.result || ''));
    if (!lines.length) {
      toast('这个文件里没有可用的歌词');
      return;
    }
    const t = current();
    t.lyricsText = String(reader.result || '');
    t._lines = lines;
    state.filters = { orig: true, romaji: true, trans: true };
    saveFilters();
    renderPanel();
    toast('歌词已导入');
  };
  reader.onerror = () => toast('读取歌词文件失败');
  reader.readAsText(file, 'utf-8');
}

/* ---------- 视图切换 ---------- */

function switchView(view) {
  state.view = view;
  renderPanel();
  updateTabs();
  updateLyricsCurrent(true);
}

function switchNav(nav) {
  state.nav = nav;
  renderPanel();
  updateTabs();
}

/* ---------- 初始化 DOM ---------- */

function initDom() {
  $$('[data-controls]').forEach((el) => { el.innerHTML = CONTROLS_HTML; });
  $$('[data-progress]').forEach((el) => { el.innerHTML = PROGRESS_HTML; });
  $$('[data-mode-seg]').forEach((el) => { el.innerHTML = MODE_HTML; });

  const swatches = $('#set-colors');
  swatches.innerHTML = SPEC_COLORS.map((c) =>
    `<button class="swatch" type="button" data-c="${c}" aria-pressed="false" aria-label="频谱颜色 ${c}" style="background:${c}"></button>`
  ).join('');

  document.body.dataset.view = state.view;
}

/* ---------- 事件 ---------- */

function bindEvents() {
  /* 搜索 */
  $('#search').addEventListener('input', (e) => {
    state.query = e.target.value.trim();
    const box = e.target.closest('.search');
    if (box) box.classList.toggle('has-value', !!e.target.value);
    if (isMobile() && state.query && state.view === 'player') state.view = 'queue';
    renderPanel();
    updateTabs();
  });

  $('#search').addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    e.stopPropagation();
    const input = e.currentTarget;
    if (input.value) {
      input.value = '';
      const box = input.closest('.search');
      if (box) box.classList.remove('has-value');
      state.query = '';
      renderPanel();
      updateTabs();
    } else {
      input.blur();
    }
  });

  /* 顶栏按钮 */
  $('#btn-msearch').addEventListener('click', () => {
    const open = document.body.classList.toggle('search-open');
    $('#btn-msearch').setAttribute('aria-expanded', String(open));
    if (open) window.setTimeout(() => $('#search').focus(), 30);
  });

  $('#btn-avatar').addEventListener('click', (e) => {
    e.stopPropagation();
    toggleAvatarMenu();
  });
  $('#btn-settings').addEventListener('click', () => {
    closeAvatarMenu();
    openSettings();
  });
  $('#btn-quit').addEventListener('click', () => {
    closeAvatarMenu();
    if (desktopWindow) {
      desktopWindow.close().catch(() => toast('退出失败，请用 Alt+F4'));
    } else {
      toast('浏览器预览中无法退出应用');
    }
  });

  $('#btn-import').addEventListener('click', () => {
    closeAvatarMenu();
    $('#file-audio').click();
  });
  $('#file-audio').addEventListener('change', (e) => {
    const files = Array.from(e.target.files || []);
    e.target.value = '';
    handleAudioFiles(files);
  });

  $('#file-lrc').addEventListener('change', (e) => {
    const file = (e.target.files || [])[0];
    e.target.value = '';
    handleLrcFile(file);
  });

  $('#next-track').addEventListener('click', nextTrack);

  /* 设置面板 */
  $('#settings-done').addEventListener('click', closeSettings);
  $('#settings-reset').addEventListener('click', () => {
    state.spectrum = { ...SPEC_DEFAULT };
    saveSpectrum();
    applySpectrum();
    setTheme('classic');
    toast('设置已恢复默认');
  });

  $$('[data-theme-set]').forEach((seg) => {
    seg.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-v]');
      if (btn) setTheme(btn.dataset.v);
    });
  });

  $('#set-visible').addEventListener('change', (e) => setSpec('on', e.target.checked));
  $('#set-opacity').addEventListener('input', (e) => setSpec('opacity', Number(e.target.value)));
  $('#set-height').addEventListener('input', (e) => setSpec('height', Number(e.target.value)));

  $$('[data-set]').forEach((seg) => {
    seg.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-v]');
      if (!btn) return;
      const key = seg.dataset.set;
      const raw = btn.dataset.v;
      const value = key === 'count' ? Number(raw) : (key === 'speed' ? Number(raw) : raw);
      setSpec(key, value);
    });
  });

  $('#set-colors').addEventListener('click', (e) => {
    const sw = e.target.closest('.swatch');
    if (sw) setSpec('color', sw.dataset.c);
  });

  /* 点击外部关闭菜单 / 设置面板 */
  document.addEventListener('pointerdown', (e) => {
    const menu = $('#avatar-menu');
    if (menu && !menu.hidden && !e.target.closest('.avatar-wrap')) closeAvatarMenu();

    const panel = $('#spectrum-panel');
    if (panel.hidden) return;
    if (panel.contains(e.target)) return;
    closeSettings();
  });

  /* 全局点击委托 */
  document.addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (chip) {
      const layer = chip.dataset.layer;
      if (chip.disabled) {
        toast(`这首歌没有${chip.textContent.trim()}`);
        return;
      }
      const next = { ...state.filters };
      next[layer] = !next[layer];
      if (!next.orig && !next.romaji && !next.trans) {
        toast('至少保留一项歌词显示');
        return;
      }
      state.filters = next;
      saveFilters();
      renderPanel();
      return;
    }

    const act = e.target.closest('[data-act]');
    if (act) {
      const a = act.dataset.act;
      if (a === 'play') togglePlay();
      else if (a === 'prev') prevTrack();
      else if (a === 'next') nextTrack();
      else if (a === 'import-lrc') $('#file-lrc').click();
      else if (a === 'seek-lyric') {
        seekTo(Number(act.dataset.time));
        if (!state.playing) play();
      }
      return;
    }

    const row = e.target.closest('.qrow');
    if (row) {
      const id = Number(row.dataset.id);
      if (id === state.currentId) togglePlay();
      else setTrack(id, true);
      return;
    }

    const viewBtn = e.target.closest('button[data-view]');
    if (viewBtn) { switchView(viewBtn.dataset.view); return; }

    const navBtn = e.target.closest('[data-nav]');
    if (navBtn) { switchNav(navBtn.dataset.nav); return; }

    const modeBtn = e.target.closest('[data-mode]');
    if (modeBtn) {
      state.mode = modeBtn.dataset.mode;
      renderTransport();
      return;
    }
  });

  /* 播放进度 / 音量 */
  document.addEventListener('input', (e) => {
    if (e.target.matches('.js-vol')) {
      state.volume = Number(e.target.value) / 100;
      audio.volume = state.volume;
      $$('.js-vol').forEach((v) => {
        if (v !== e.target) v.value = e.target.value;
        paintSlider(v);
      });
      return;
    }
    if (e.target.matches('.js-seek')) {
      seekTo(Number(e.target.value));
      paintSlider(e.target);
    }
  });

  /* 用户手动滚动歌词后 4 秒内不自动跟随 */
  document.addEventListener('wheel', (e) => {
    if (e.target.closest('.lyr-list')) userScrollAt = performance.now();
  }, { passive: true });
  document.addEventListener('touchmove', (e) => {
    if (e.target.closest('.lyr-list')) userScrollAt = performance.now();
  }, { passive: true });
  document.addEventListener('pointerdown', (e) => {
    if (e.target.closest('.lyr-list')) userScrollAt = performance.now();
  });

  /* 首次手势后恢复 AudioContext */
  document.addEventListener('pointerdown', () => {
    if (actx && actx.state === 'suspended') actx.resume().catch(() => {});
  }, { passive: true });

  /* 键盘 */
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const menu = $('#avatar-menu');
      if (menu && !menu.hidden) {
        closeAvatarMenu();
        $('#btn-avatar').focus();
        return;
      }
      if (!$('#spectrum-panel').hidden) {
        closeSettings();
        return;
      }
      if (document.body.classList.contains('search-open')) {
        document.body.classList.remove('search-open');
        $('#btn-msearch').setAttribute('aria-expanded', 'false');
        return;
      }
    }
    if (!$('#spectrum-panel').hidden) return;

    const el = e.target;
    const tag = (el.tagName || '').toLowerCase();
    const typing = tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable;
    if (typing) return;

    if (e.code === 'Space') {
      if (el.closest('button, a')) return;
      e.preventDefault();
      togglePlay();
      return;
    }
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      prevTrack();
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      nextTrack();
    }
  });

  /* 响应式切换 */
  mqMobile.addEventListener('change', () => {
    if (!isMobile() && state.view === 'player') state.view = 'queue';
    if (isMobile()) {
      state.nav = 'now';
      closeSettings();
    }
    applySpectrum();
    renderPanel();
    updateTabs();
    renderTransport();
  });
}

/* ---------- 主循环 ---------- */

let lastTs = performance.now();
let uiAcc = 0;

function loop(ts) {
  const dt = Math.min(0.25, (ts - lastTs) / 1000);
  lastTs = ts;

  if (state.playing) {
    if (current().isLocal) {
      state.time = audio.currentTime || 0;
      if (Number.isFinite(audio.duration) && audio.duration > 0) state.duration = audio.duration;
    } else {
      state.time += dt;
      if (state.time >= state.duration) {
        state.time = state.duration;
        state.playing = false;
        onEnded();
      }
    }
  }

  uiAcc += dt;
  if (uiAcc >= 0.1) {
    uiAcc = 0;
    renderTransportTime();
  }

  spectrumFrame();
  requestAnimationFrame(loop);
}

/* ---------- Tauri 无边框窗口 ---------- */

function initDesktopWindow() {
  const api = window.__TAURI__;
  if (!api || !api.window) return;

  const getCurrent = api.window.getCurrentWindow || api.window.getCurrent;
  const appWindow = typeof getCurrent === 'function' ? getCurrent() : null;
  if (!appWindow) return;

  desktopWindow = appWindow;
  document.documentElement.classList.add('is-tauri');

  const maxBtn = $('[data-win="max"]');

  async function refreshMaxState() {
    try {
      const max = await appWindow.isMaximized();
      document.documentElement.classList.toggle('win-max', !!max);
      if (maxBtn) maxBtn.setAttribute('aria-label', max ? '还原' : '最大化');
    } catch (err) { /* ignore */ }
  }

  $$('[data-win]').forEach((btn) => {
    btn.addEventListener('click', async () => {
      try {
        if (btn.dataset.win === 'min') await appWindow.minimize();
        else if (btn.dataset.win === 'max') {
          await appWindow.toggleMaximize();
          await refreshMaxState();
        } else if (btn.dataset.win === 'close') {
          await appWindow.close();
        }
      } catch (err) {
        console.warn('[NEKOPLAY] window action failed:', err);
        if (btn.dataset.win === 'close') {
          try { window.close(); } catch (e) { /* ignore */ }
        }
      }
    });
  });

  const topbar = $('.topbar');
  if (topbar) {
    topbar.addEventListener('dblclick', (e) => {
      if (e.target.closest('button, a, input, textarea, select, label')) return;
      appWindow.toggleMaximize().then(refreshMaxState).catch(() => {});
    });
  }

  refreshMaxState();
}

/* ---------- 启动 ---------- */

initDom();
document.documentElement.dataset.theme = state.theme;
initDesktopWindow();
bindEvents();
renderNow();
renderPanel();
renderTransport();
applySpectrum();
requestAnimationFrame(loop);
