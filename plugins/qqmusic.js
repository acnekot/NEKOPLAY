// @id qqmusic
// @name QQ 音乐
// @version 1.2.1
// @author NEKOPLAY
// @desc QQ 音乐音源：搜索、播放、歌词、歌单、专辑、歌手、排行榜，QQ 扫码登录后看自己的歌单
// @api 1
//
// 接口说明见 docs/plugin-api.md。协议笔记见 docs/qq-protocol.md（只记事实，不含第三方代码）。

const GATE = 'https://u.y.qq.com/cgi-bin/musicu.fcg';
const CDN = 'https://isure.stream.qqmusic.qq.com/';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

// ---------- 工具 ----------
// 用 BigInt：普通数字乘 33 会超过 2^53 丢精度，算出的 ptqrtoken 不对，QQ 会直接回 403
function hash33(s, h0) {
  const M = (1n << 48n) - 1n;
  let h = BigInt(h0 || 0);
  for (let i = 0; i < s.length; i++) h = ((h << 5n) + h + BigInt(s.charCodeAt(i))) & M;
  return Number(h & 2147483647n);
}
function guid() {
  let g = nk.store.get('guid');
  if (!g) { g = Array.from({ length: 32 }, () => '0123456789abcdef'[Math.floor(Math.random() * 16)]).join(''); nk.store.set('guid', g); }
  return g;
}
function cred() { return nk.store.get('cred'); }
const https = (u) => (u || '').replace(/^http:/, 'https:');
const albumPic = (mid, size) => mid ? `https://y.gtimg.cn/music/photo_new/T002R${size || 500}x${size || 500}M000${mid}.jpg` : null;
const singerPic = (mid) => mid ? `https://y.gtimg.cn/music/photo_new/T001R500x500M000${mid}.jpg` : null;
const b64 = (s) => { const t = (s || '').trim(); if (!t) return ''; if (t.startsWith('[')) return t; try { return nk.base64.decode(t); } catch (e) { return ''; } };

/** 调网关：返回 req_0.data */
async function call(module, method, param, extraComm) {
  const c = cred();
  const comm = { ct: 19, cv: 2201, platform: '', chid: '0', guid: guid().toUpperCase(), g_tk: c ? hash33(c.key, 5381) : 5381 };
  if (c) Object.assign(comm, { uin: c.id, qq: String(c.id), authst: c.key, tmeLoginType: c.type });
  Object.assign(comm, extraComm || {});
  const headers = { 'User-Agent': UA, Referer: 'https://y.qq.com/', Origin: 'https://y.qq.com' };
  if (c) headers.Cookie = `uin=${c.id}; qqmusic_uin=${c.id}; qm_keyst=${c.key}; qqmusic_key=${c.key}; tmeLoginType=${c.type}`;
  const r = await nk.http({ url: GATE, json: { comm, req_0: { module, method, param } }, headers });
  if (r.status !== 200) throw new Error('QQ 音乐服务返回 ' + r.status);
  const r0 = r.json().req_0 || {};
  const code = r0.code || 0;
  if (code === 1000) throw new Error('QQ 音乐登录已过期，请重新登录');
  if (code === 2001) throw new Error('触发 QQ 音乐风控，稍后再试或先登录');
  if (code !== 0) throw new Error('QQ 音乐接口错误（' + code + '）');
  return r0.data || {};
}

// 歌曲 mid → 文件信息 / 试听 mid / 是否会员歌（播放时要用）
const files = {}, trials = {}, vip = {}, ids = {};
function song(s0) {
  const s = s0.songInfo || s0;
  const mid = s.mid || s.songmid;
  if (s.file) files[mid] = s.file;
  if (s.id) ids[mid] = s.id;
  if (s.vs && s.vs[0]) trials[mid] = s.vs[0];
  vip[mid] = !!(s.pay && s.pay.pay_play === 1);
  const singers = s.singer || [];
  const al = s.album || {};
  const alMid = al.mid || s.albummid || '';
  return {
    id: mid, title: s.name || s.title || s.songname, artist: singers.map(x => x.name).join(' / ') || '未知艺人',
    album: al.name || al.title || '', duration: (s.interval || 0) * 1000,
    cover: albumPic(alMid) || singerPic(singers[0] && singers[0].mid),
    artists: singers.map(x => ({ id: 'ar' + x.mid, name: x.name })), albumId: alMid ? 'al' + alMid : '', vip: vip[mid],
  };
}

// ---------- 搜索 ----------
function searchId() { return String(Math.floor(Math.random() * 20 + 1) * 18014398509481984 + Math.floor(Math.random() * 4194305) * 4294967296 + Date.now() % 86400000); }
async function searchRaw(q, type, page) {
  const d = await call('music.search.SearchCgiService', 'DoSearchForQQMusicDesktop', { searchid: searchId(), query: q, search_type: type, num_per_page: 30, page_num: page || 1, highlight: 0, grp: 1 });
  return d.body || {};
}

// ---------- 播放 ----------
const FMTS = [
  { p: 'M500', ext: 'mp3', size: 'size_128mp3', kbps: 128, level: 'standard' },
  { p: 'C600', ext: 'm4a', size: 'size_192aac', kbps: 192, level: 'higher' },
  { p: 'M800', ext: 'mp3', size: 'size_320mp3', kbps: 320, level: 'exhigh' },
  { p: 'F000', ext: 'flac', size: 'size_flac', kbps: 0, level: 'lossless' },
  { p: 'RS01', ext: 'flac', size: 'size_hires', kbps: 0, level: 'hires' },
];
async function details(mids) {
  const d = await call('music.trackInfo.UniformRuleCtrl', 'CgiGetTrackInfo', { mids, types: mids.map(() => 0), modify_stamp: mids.map(() => 0), ctx: 0, client: 1 });
  return (d.tracks || []).map(song);
}
async function vkey(mid, filename) {
  const c = cred();
  const d = await call('music.vkey.GetVkey', 'UrlGetVkey', { uin: c ? String(c.id) : '0', filename: [filename], guid: guid(), songmid: [mid], songtype: [0], ctx: 0 });
  return ((d.midurlinfo || [])[0]) || {};
}

// ---------- QQ 扫码登录（照官方电脑版登录框的流程，cookie 按域名保存） ----------
const U1 = 'https://graph.qq.com/oauth2.0/login_jump';
let jar = {};
function keep(r, host) {
  (r.headers['set-cookie'] || []).forEach(c => {
    const parts = c.split(';'); const kv = parts[0]; const i = kv.indexOf('='); if (i < 0) return;
    const d = parts.slice(1).map(x => x.trim()).find(x => /^domain=/i.test(x));
    const dom = (d ? d.slice(7) : host).replace(/^\./, '').toLowerCase();
    const k = kv.slice(0, i).trim() + '@' + dom, v = kv.slice(i + 1).trim();
    if (v) jar[k] = v; else delete jar[k];
  });
}
function cookieFor(host) { return Object.keys(jar).filter(k => { const d = k.split('@')[1]; return host === d || host.endsWith('.' + d); }).map(k => k.split('@')[0] + '=' + jar[k]).join('; '); }
function jarGet(name, dom) { const k = Object.keys(jar).find(k => k.split('@')[0] === name && (!dom || k.split('@')[1] === dom)); return k ? jar[k] : null; }
async function req(url, o) {
  const host = url.split('://')[1].split(/[/?]/)[0].toLowerCase();
  const headers = { 'User-Agent': UA, Referer: (o && o.referer) || 'https://xui.ptlogin2.qq.com/' };
  const ck = cookieFor(host); if (ck) headers.Cookie = ck;
  const r = await nk.http(Object.assign({ url, headers, redirect: false }, o && o.form ? { form: o.form } : {}, o && o.binary ? { binary: true } : {}, o && o.timeout ? { timeout: o.timeout } : {}));
  keep(r, host);
  return r;
}
const qs = (o) => Object.keys(o).map(k => k + '=' + encodeURIComponent(o[k])).join('&');
const REDIRECT = 'https://y.qq.com/portal/wx_redirect.html';
const WX_APPID = 'wx48db31d50e334801';
const qrState = (c) => [0, 405].includes(c) ? 'done' : [67, 404].includes(c) ? 'scanned' : [65, 402].includes(c) ? 'expired' : [68, 403].includes(c) ? 'refused' : 'wait';
/** 换到的凭证存起来，顺便取昵称头像 */
async function finish(d, type) {
  const id = Number(d.musicid || d.str_musicid || 0);
  if (!d.musickey || !id) throw new Error('登录失败：没有拿到凭证');
  nk.store.set('cred', { id, key: d.musickey, type });
  try { const h = await call('music.UnifiedHomepage.UnifiedHomepageSrv', 'GetHomepageHeader', { uin: String(id), IsQueryTabDetail: 1 }); const b = h.Info && h.Info.BaseInfo; if (b) nk.store.set('cred', { id, key: d.musickey, type, nick: b.Name, avatar: https(b.Avatar) }); } catch (e) {}
}

nk.register({
  // ---------- 搜索 ----------
  async search(q, type, page) {
    if (type === 'album') {
      const b = await searchRaw(q, 2, page);
      return ((b.album && b.album.list) || b.item_album || []).map(a => {
        const mid = a.albumMID || a.albummid || a.mid;
        return { id: 'al' + mid, name: a.albumName || a.name, cover: https(a.albumPic) || albumPic(mid), count: a.song_count, year: (a.publicTime || '').slice(0, 4), artist: a.singerName || (a.singer_list || []).map(x => x.name).join(' / ') };
      });
    }
    if (type === 'playlist') {
      const b = await searchRaw(q, 3, page);
      return ((b.songlist && b.songlist.list) || b.item_songlist || []).map(p => ({ id: String(p.dissid), name: p.dissname, count: p.song_count, cover: https(p.imgurl) }));
    }
    const b = await searchRaw(q, 0, page);
    return ((b.song && b.song.list) || b.item_song || []).map(song);
  },

  // ---------- 播放地址：从想要的音质往下试，整首拿不到时放试听 ----------
  async stream(mid, quality) {
    if (!files[mid]) await details([mid]);
    const f = files[mid] || {};
    const media = f.media_mid || mid + mid;
    for (let i = Math.min(Math.max(quality, 0), FMTS.length - 1); i >= 0; i--) {
      const m = FMTS[i];
      if (m.size in f && !(f[m.size] > 0) && i > 0) continue;
      const info = await vkey(mid, `${m.p}${media}.${m.ext}`);
      if (info.purl) return { url: CDN + info.purl, ext: m.ext, kbps: m.kbps, level: m.level };
    }
    if (trials[mid]) {
      const info = await vkey(mid, `RS02${trials[mid]}.mp3`);
      if (info.purl) return { url: CDN + info.purl, ext: 'mp3', kbps: 128, level: 'standard', trial: vip[mid], message: '这首是会员歌曲，当前账号只能放试听片段' };
    }
    return { error: cred() ? '没有可用的播放地址（这首歌需要 QQ 音乐会员或单独购买）' : '这首歌需要登录 QQ 音乐才能播放' };
  },

  async lyric(mid) {
    const d = await call('music.musichallSong.PlayLyricInfo', 'GetPlayLyricInfo', { songMid: mid, crypt: 0, ct: 19, cv: 2201, lrc_t: 0, qrc: 0, qrc_t: 0, roma: 0, roma_t: 0, trans: 1, trans_t: 0, type: 1 });
    return { lrc: b64(d.lyric), tr: b64(d.trans) || null };
  },

  // ---------- 歌单 / 专辑 / 歌手 ----------
  async playlist(id) {
    if (id.startsWith('top')) {
      const d = await call('music.musicToplist.Toplist', 'GetDetail', { topid: Number(id.slice(3)), offset: 0, num: 100, period: '' });
      return (d.songInfoList || []).map(song);
    }
    const liked = id === 'liked', c = cred();
    const out = [];
    for (let begin = 0; out.length < 3000;) {
      const p = { disstid: liked ? 0 : Number(id), dirid: liked ? 201 : 0, tag: 1, song_begin: begin, song_num: 200, userinfo: 1, orderlist: 1, onlysonglist: 0 };
      if (liked) Object.assign(p, { enc_host_uin: '', uin: c ? c.id : 0 });
      const d = await call('music.srfDissInfo.DissInfo', 'CgiGetDiss', p);
      const l = (d.songlist || []).map(song);
      out.push(...l);
      if (!l.length || out.length >= (d.total_song_num || out.length)) break;
      begin += l.length;
    }
    return out;
  },
  async album(id) {
    const mid = id.replace(/^al/, '');
    let info = null; try { info = await call('music.musichallAlbum.AlbumInfoServer', 'GetAlbumDetail', { albumMid: mid }); } catch (e) {}
    const songs = await call('music.musichallAlbum.AlbumSongList', 'GetAlbumSongList', { albumMid: mid, begin: 0, num: 200, order: 2 });
    const tracks = (songs.songList || []).map(song);
    const b = (info && info.basicInfo) || {};
    const singers = (info && info.singer && info.singer.singerList) || [];
    return { album: { id, name: b.albumName || (tracks[0] && tracks[0].album) || '', cover: albumPic(mid), count: songs.totalNum || tracks.length, year: (b.publishDate || '').slice(0, 4), artist: singers.map(x => x.name).join(' / ') }, tracks };
  },
  async artist(id) {
    const mid = id.replace(/^ar/, '');
    const songs = await call('musichall.song_list_server', 'GetSingerSongList', { singerMid: mid, order: 1, number: 50, begin: 0 });
    const tracks = (songs.songList || []).map(song);
    let s = null; try { s = ((await call('music.musichallSinger.SingerInfoInter', 'GetSingerDetail', { singer_mids: [mid], groups: 1, wikis: 1 })).singer_list || [])[0]; } catch (e) {}
    const name = (s && s.basic_info && s.basic_info.name) || ((tracks[0] && tracks[0].artists.find(a => a.id === id)) || {}).name || '';
    return { name, cover: singerPic(mid), desc: (s && s.ex_info && s.ex_info.desc) || '', tracks };
  },
  async toplists() {
    const d = await call('music.musicToplist.Toplist', 'GetAll', {});
    const out = [];
    (d.group || []).forEach(g => (g.toplist || []).forEach(t => out.push({ id: 'top' + t.topId, name: t.title, count: t.totalNum, cover: https(t.frontPicUrl || t.headPicUrl) })));
    return out;
  },

  // ---------- 账号 ----------
  async account() { const c = cred(); return c ? { name: c.nick || String(c.id), avatar: c.avatar || null } : null; },
  async myPlaylists() {
    const c = cred(); if (!c) return [];
    const pic = (p) => https(['picUrl', 'bigpicUrl', 'bigPicUrl', 'coverUrl', 'logo'].map(k => p[k]).find(x => x && x.startsWith('http')));
    const d = await call('music.musicasset.PlaylistBaseRead', 'GetPlaylistByUin', { uin: String(c.id) });
    const mine = (d.v_playlist || []).map(p => p.dirId === 201 ? { id: 'liked', name: '我喜欢', count: p.songNum, cover: pic(p), liked: true, mine: true } : { id: String(p.tid), name: p.dirName, count: p.songNum, cover: pic(p), mine: true });
    let fav = [];
    try { fav = ((await call('music.musicasset.PlaylistFavRead', 'CgiGetPlaylistFavInfo', { uin: String(c.id), offset: 0, size: 100 })).v_list || []).map(p => ({ id: String(p.tid), name: p.name, count: p.songnum, cover: pic(p) })); } catch (e) {}
    return mine.sort((a, b) => (b.liked ? 1 : 0) - (a.liked ? 1 : 0)).concat(fav);
  },
  async like(mid, on) {
    if (!cred()) return;
    if (!ids[mid]) await details([mid]);
    const d = await call('music.musicasset.PlaylistDetailWrite', on ? 'AddSonglist' : 'DelSonglist', { dirId: 201, tid: 0, bFmtUtf8: true, v_songInfo: [{ songId: ids[mid], songType: 0 }] });
    if (d.retCode && d.retCode !== 80092) throw new Error('QQ 音乐收藏失败（' + d.retCode + '）');
  },
  async logout() { nk.store.set('cred', null); },

  /** 登录方式：网页登录（内嵌 QQ 互联页，手机上能一键拉起手机 QQ）、QQ 扫码、微信扫码 */
  loginModes() { return [{ id: 'web', name: 'QQ 登录', web: true }, { id: 'qq', name: 'QQ 扫码' }, { id: 'wx', name: '微信扫码' }]; },

  /** 扫码登录第一步：返回二维码图片（base64）和查询用的 key */
  async loginQr(mode) {
    if (mode === 'wx') {
      const page = await req('https://open.weixin.qq.com/connect/qrconnect?' + qs({ appid: WX_APPID, redirect_uri: REDIRECT + '?login_type=2&surl=https://y.qq.com/', response_type: 'code', scope: 'snsapi_login', state: 'STATE', href: 'https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect' }));
      const m = /uuid=(.+?)"/.exec(page.body); if (!m) throw new Error('获取微信二维码失败');
      const img = await req('https://open.weixin.qq.com/connect/qrcode/' + m[1], { binary: true, referer: 'https://open.weixin.qq.com/connect/qrconnect' });
      return { image: img.bodyBase64, key: 'wx:' + m[1], hint: '用微信扫码，并在手机上点确认', poll: 300, saveApp: 'weixin://dl/scan', saveAppName: '微信' };
    }
    jar = {};
    await req('https://xui.ptlogin2.qq.com/cgi-bin/xlogin?' + qs({ appid: '716027609', daid: '383', style: '33', login_text: '登录', hide_title_bar: '1', hide_border: '1', target: 'self', s_url: U1, pt_3rd_aid: '100497308', theme: '2' }), { referer: 'https://graph.qq.com/' });
    const r = await req('https://xui.ptlogin2.qq.com/ssl/ptqrshow?' + qs({ appid: '716027609', e: '2', l: 'M', s: '3', d: '72', v: '4', t: String(Math.random()), daid: '383', pt_3rd_aid: '100497308', u1: U1 }), { binary: true });
    const key = jarGet('qrsig'); if (!key) throw new Error('获取 QQ 二维码失败');
    return { image: r.bodyBase64, key, hint: '用手机 QQ 扫码，并在手机上点确认' };
  },
  /** 扫码登录第二步：轮询状态 wait / scanned / done / expired / refused */
  async loginCheck(key) {
    if (key.startsWith('wx:')) {
      // 微信是长轮询：一次最多挂 30 秒
      let t = '';
      try { t = (await req('https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=' + key.slice(3) + '&_=' + Date.now(), { referer: 'https://open.weixin.qq.com/', timeout: 35 })).body; } catch (e) { return { state: 'wait' }; }
      const m = /window\.wx_errcode=(\d+);window\.wx_code='([^']*)'/.exec(t); if (!m) return { state: 'wait' };
      const st = qrState(Number(m[1])); if (st !== 'done') return { state: st };
      if (!m[2]) throw new Error('微信登录：获取 code 失败');
      await finish(await call('music.login.LoginServer', 'Login', { code: m[2], strAppid: WX_APPID }, { tmeLoginType: 1 }), 1);
      return { state: 'done' };
    }
    const pr = (await req('https://xui.ptlogin2.qq.com/ssl/ptqrlogin?' + qs({ u1: U1, ptqrtoken: hash33(key), ptredirect: '0', h: '1', t: '1', g: '1', from_ui: '1', ptlang: '2052', action: '0-0-' + Date.now(), js_ver: '25100115', js_type: '1', login_sig: jarGet('pt_login_sig') || '', pt_uistyle: '40', aid: '716027609', daid: '383', pt_3rd_aid: '100497308', o1vId: '', pt_js_version: 'v1.0.0' })));
    const t = pr.body;
    const m = /ptuiCB\((.*?)\)/.exec(t); if (!m) return { state: 'wait', message: pr.status !== 200 ? 'QQ 返回 HTTP ' + pr.status + '，还在重试' : undefined };
    const args = (m[1].match(/'((?:\\.|[^'])*)'/g) || []).map(x => x.slice(1, -1));
    const code = Number(args[0]);
    const st = qrState(code); if (st !== 'done') return { state: st };
    // 确认了：check_sig 拿 p_skey → authorize 拿 code → 换 QQ 音乐凭证
    let r = await req(args[2]);
    for (let i = 0; !jarGet('p_skey') && r.header('location') && i < 4; i++) r = await req(r.header('location'));
    const pskey = jarGet('p_skey', 'graph.qq.com') || jarGet('p_skey');
    if (!pskey) throw new Error('QQ 登录：获取 p_skey 失败');
    const a = await req('https://graph.qq.com/oauth2.0/authorize', { referer: 'https://graph.qq.com/', form: {
      response_type: 'code', client_id: '100497308', redirect_uri: REDIRECT + '?login_type=1&surl=https://y.qq.com/', scope: '', state: 'state', switch: '',
      from_ptlogin: '1', src: '1', update_auth: '1', openapi: '1010', g_tk: String(hash33(pskey, 5381)), auth_time: String(Date.now()),
      ui: Array.from({ length: 32 }, () => '0123456789ABCDEF'[Math.floor(Math.random() * 16)]).join('') } });
    const cm = /[?&]code=([^&]+)/.exec(a.header('location') || ''); if (!cm) throw new Error('QQ 登录：获取 code 失败');
    jar = {};
    await finish(await call('QQConnectLogin.LoginServer', 'QQLogin', { code: cm[1] }, { tmeLoginType: 2 }), 2);
    return { state: 'done' };
  },

  // ---------- 网页登录 ----------
  /** done：网页跳到这里就算登录完成；open：这些地址交给系统打开（拉起手机 QQ） */
  loginWeb(env) {
    const pc = !!(env && env.desktop);
    const q = qs({ response_type: 'code', client_id: '100497308', redirect_uri: REDIRECT + '?login_type=1&surl=https://y.qq.com/', state: 'state', display: pc ? 'pc' : 'mobile' });
    return { url: 'https://graph.qq.com/oauth2.0/' + (pc ? 'show?which=Login&' : 'authorize?') + q, pc,
      done: '^https://y\\.qq\\.com/portal/wx_redirect\\.html', open: '^wtloginmqq',
      hint: pc ? '用手机 QQ 扫码，或者电脑上已经登录了 QQ 时直接点头像快捷登录' : '在下面登录 QQ；点「一键登录」会跳到手机 QQ，确认后自动回来' };
  },
  async loginWebDone(url) {
    const m = /[?&]code=([^&#]+)/.exec(url); if (!m) throw new Error('没有拿到登录 code');
    await finish(await call('QQConnectLogin.LoginServer', 'QQLogin', { code: m[1] }, { tmeLoginType: 2 }), 2);
    return { state: 'done' };
  },
  /** 拉起手机 QQ 前改写地址：带上本 App 的包名和回跳 scheme */
  loginWebOpen(u, app) {
    let r = u;
    const m = /([?&]p=)([^&]*)/.exec(r);
    if (m) {
      let inner = decodeURIComponent(m[2]);
      const key = app.ios ? 'pt_bundleid' : 'pt_package';
      if (inner.indexOf(key + '=') < 0) inner += '&' + key + '=' + app.id;
      r = r.slice(0, m.index + m[1].length) + encodeURIComponent(inner) + r.slice(m.index + m[0].length);
    }
    const cb = 'schemacallback=' + encodeURIComponent(app.scheme);
    return /[?&]schemacallback=/.test(r) ? r.replace(/schemacallback=[^&]*/, cb) : r + '&' + cb;
  },
  /** 手机 QQ 跳回来的 nekoplay://… 里取出要继续加载的网页 */
  loginWebBack(cb) {
    const r = cb.replace(/^[a-z]+:\/\//, '').replace(/\(null\)/g, '');
    if (/^https?:\/\//.test(r)) return r;
    const i = r.indexOf('http'); if (i < 0) return null;
    let u = r.slice(i);
    if (/^https?%3A/i.test(u)) u = decodeURIComponent(u);
    return u;
  },
});
