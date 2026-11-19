// ==NekoPlugin==
// @id          ncm-direct
// @name        网易云音乐
// @version     2.0.0
// @author      NEKOPLAY
// @api         1
// @hosts       music.163.com, *.music.163.com
// @caps        search, stream, lyric, recommend, liked, playlist, login
// ==/NekoPlugin==
//
// 无版权、VIP 或付费专辑的歌曲会播放失败，登录有 VIP 的账号后可播放会员曲目。

// ---------------- 加密：AES-128-CBC（纯 JS） ----------------
const SBOX = (function () {
  const s = new Array(256), inv = new Array(256);
  let p = 1, q = 1;
  do {
    p = p ^ ((p << 1) & 0xff) ^ (p & 0x80 ? 0x1b : 0);
    q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xff; if (q & 0x80) q ^= 0x09;
    const x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4));
    s[p] = (x ^ 0x63) & 0xff; inv[s[p]] = p;
  } while (p !== 1);
  s[0] = 0x63;
  return s;
})();

function xtime(a) { return ((a << 1) ^ (a & 0x80 ? 0x1b : 0)) & 0xff; }

function expandKey(key) {
  const w = [];
  for (let i = 0; i < 16; i++) w.push(key[i]);
  let rcon = 1;
  for (let i = 16; i < 176; i += 4) {
    let t = [w[i - 4], w[i - 3], w[i - 2], w[i - 1]];
    if (i % 16 === 0) {
      t = [SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]];
      rcon = xtime(rcon);
    }
    for (let j = 0; j < 4; j++) w.push(w[i - 16 + j] ^ t[j]);
  }
  return w;
}

function encryptBlock(b, w) {
  const s = b.slice();
  for (let i = 0; i < 16; i++) s[i] ^= w[i];
  for (let r = 1; r <= 10; r++) {
    for (let i = 0; i < 16; i++) s[i] = SBOX[s[i]];
    // ShiftRows
    let t = s[1]; s[1] = s[5]; s[5] = s[9]; s[9] = s[13]; s[13] = t;
    t = s[2]; s[2] = s[10]; s[10] = t; t = s[6]; s[6] = s[14]; s[14] = t;
    t = s[15]; s[15] = s[11]; s[11] = s[7]; s[7] = s[3]; s[3] = t;
    if (r < 10) {
      for (let c = 0; c < 4; c++) {
        const a0 = s[c * 4], a1 = s[c * 4 + 1], a2 = s[c * 4 + 2], a3 = s[c * 4 + 3];
        const all = a0 ^ a1 ^ a2 ^ a3;
        s[c * 4] = a0 ^ all ^ xtime(a0 ^ a1);
        s[c * 4 + 1] = a1 ^ all ^ xtime(a1 ^ a2);
        s[c * 4 + 2] = a2 ^ all ^ xtime(a2 ^ a3);
        s[c * 4 + 3] = a3 ^ all ^ xtime(a3 ^ a0);
      }
    }
    for (let i = 0; i < 16; i++) s[i] ^= w[r * 16 + i];
  }
  return s;
}

function utf8(str) {
  const e = unescape(encodeURIComponent(str)), out = [];
  for (let i = 0; i < e.length; i++) out.push(e.charCodeAt(i));
  return out;
}

function aesCbc(bytes, keyStr, ivStr) {
  const w = expandKey(utf8(keyStr));
  let prev = utf8(ivStr);
  const pad = 16 - (bytes.length % 16);
  const data = bytes.concat(new Array(pad).fill(pad));
  const out = [];
  for (let i = 0; i < data.length; i += 16) {
    const blk = [];
    for (let j = 0; j < 16; j++) blk.push(data[i + j] ^ prev[j]);
    prev = encryptBlock(blk, w);
    for (let j = 0; j < 16; j++) out.push(prev[j]);
  }
  return out;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function b64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i], b = bytes[i + 1], c = bytes[i + 2];
    s += B64[a >> 2] + B64[((a & 3) << 4) | ((b || 0) >> 4)] +
      (b === undefined ? '=' : B64[((b & 15) << 2) | ((c || 0) >> 6)]) +
      (c === undefined ? '=' : B64[c & 63]);
  }
  return s;
}

const NONCE = '0CoJUm6Qyw8W8jud';
const IV = '0102030405060708';
// 固定会话密钥：网易云只要求 encSecKey 与 AES 密钥匹配，用固定值可省掉每次的 RSA 运算
const SEC_KEY = 'nK7qPz3Wm9XvL2tR';
const ENC_SEC_KEY = '1b217eb9ffc597dcfbaae9a3c0fd2d56f1fa7fdd06f386c789714b426cd102041d4c883ea4ea82439e1aa9d453326b835904a61fbfab07b1266ec5dbda413220e8fe269c0035d839f02187d83dd4803437bf0af13ead1eba669df755a143fbfdd1cb9c1ba709885fd4d388ae586dac2108c6cbeae6b25098afaa44cd6e82eed2';

function weapiBody(data) {
  const first = b64(aesCbc(utf8(JSON.stringify(data)), NONCE, IV));
  const params = b64(aesCbc(utf8(first), SEC_KEY, IV));
  return 'params=' + encodeURIComponent(params) + '&encSecKey=' + ENC_SEC_KEY;
}

// ---------------- 请求 ----------------
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function stored() { return neko.storage.get('cookie') || ''; }
function csrf() { const m = /__csrf=([^;\s]+)/.exec(stored()); return m ? m[1] : ''; }

async function raw(path, data) {
  const d = Object.assign({}, data || {});
  d.csrf_token = csrf();
  const res = await neko.http({
    url: 'https://music.163.com' + path + '?csrf_token=' + encodeURIComponent(csrf()),
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Referer': 'https://music.163.com/',
      'Origin': 'https://music.163.com',
      'User-Agent': UA,
      'Cookie': 'os=pc; appver=2.7.1.198277; ' + stored(),
    },
    body: weapiBody(d),
    timeout: 10000,
  });
  if (res.status < 200 || res.status >= 300) throw new Error('服务返回 ' + res.status);
  let j;
  try { j = JSON.parse(res.body); } catch (e) { throw new Error('返回的不是 JSON（可能被风控）'); }
  return { j: j, headers: res.headers || {} };
}

async function api(path, data) {
  const r = await raw(path, data);
  const c = r.j && r.j.code;
  if (c === -460 || c === -462) throw new Error('被网易云风控拦截（' + c + '），稍后再试或换网络');
  if (c && c !== 200) throw new Error((r.j.message || r.j.msg || '接口错误') + '（' + c + '）');
  return r.j;
}

function pic(u) { return u ? String(u).replace(/^http:/, 'https:') + '?param=300y300' : undefined; }

function fromSong(s) {
  const ar = s.ar || s.artists || [];
  const al = s.al || s.album || {};
  return {
    id: String(s.id),
    title: s.name,
    artist: ar.map(function (a) { return a.name; }).join(' / ') || '未知艺人',
    album: al.name || '',
    duration: Math.round((s.dt || s.duration || s.mDuration || 0) / 1000),
    cover: pic(al.picUrl),
  };
}

// 搜索结果里没有封面，用歌曲详情补上封面和时长
async function fillDetail(items) {
  if (!items.length) return;
  try {
    const j = await api('/weapi/v3/song/detail', { c: JSON.stringify(items.map(function (t) { return { id: Number(t.id) }; })) });
    const by = {};
    (j.songs || []).forEach(function (s) { by[String(s.id)] = s; });
    items.forEach(function (t) {
      const s = by[t.id];
      if (!s) return;
      if (s.al && s.al.picUrl) t.cover = pic(s.al.picUrl);
      if (!t.duration && s.dt) t.duration = Math.round(s.dt / 1000);
    });
  } catch (e) { /* 没有封面也能用 */ }
}

const LEVELS = { lossless: ['lossless', 'exhigh', 'standard'], high: ['exhigh', 'standard'], standard: ['standard'] };

export default {
  async ping() {
    const t = Date.now();
    await api('/weapi/search/get', { s: 'a', type: 1, limit: 1, offset: 0 });
    return { ok: true, ms: Date.now() - t };
  },

  async search(query, opt) {
    const size = (opt && opt.pageSize) || 20;
    const page = (opt && opt.page) || 1;
    const j = await api('/weapi/search/get', { s: query, type: 1, limit: size, offset: (page - 1) * size });
    const songs = (j.result && j.result.songs) || [];
    const items = songs.map(fromSong);
    await fillDetail(items);
    return { items: items, hasMore: songs.length >= size };
  },

  async getStream(track, opt) {
    const levels = LEVELS[(opt && opt.quality) || 'lossless'] || LEVELS.standard;
    let err = '没有可用的播放地址（无版权或需要 VIP，本插件没有解灰）';
    for (const level of levels) {
      try {
        const j = await api('/weapi/song/enhance/player/url/v1', { ids: '[' + track.id + ']', level: level, encodeType: 'flac' });
        const d = j.data && j.data[0];
        if (d && d.url) {
          if (d.freeTrialInfo) { err = '这首歌只有试听片段，需要 VIP'; continue; }
          const type = String(d.type || '').toLowerCase();
          return {
            url: String(d.url).replace(/^http:/, 'https:'),
            format: type === 'flac' ? 'flac' : (type === 'm4a' ? 'm4a' : 'mp3'),
            quality: type === 'flac' ? 'lossless' : (level === 'standard' ? 'standard' : 'high'),
            bitrate: d.br ? Math.round(d.br / 1000) : undefined,
            expiresAt: Date.now() + 15 * 60 * 1000,
          };
        }
      } catch (e) { err = String(e.message || e); }
    }
    throw new Error(err);
  },

  async getLyric(track) {
    const j = await api('/weapi/song/lyric', { id: track.id, lv: -1, tv: -1, rv: -1, kv: -1, _nmclfl: 1 });
    const lrc = (j.lrc && j.lrc.lyric) || '';
    if (!lrc) throw new Error('暂无歌词');
    return { lrc: lrc, translation: (j.tlyric && j.tlyric.lyric) || undefined };
  },

  async recommend() {
    if (stored()) {
      try {
        const j = await api('/weapi/v3/discovery/recommend/songs', {});
        const list = (j.data && j.data.dailySongs) || j.recommend || [];
        if (list.length) return { items: list.slice(0, 30).map(fromSong) };
      } catch (e) { /* 回落到新歌速递 */ }
    }
    const j = await api('/weapi/v1/discovery/new/songs', { areaId: 0, total: true });
    const items = (j.data || []).slice(0, 20).map(fromSong);
    await fillDetail(items);
    return { items: items };
  },

  // 我喜欢：分页返回
  async getLiked(opt) {
    const ids = await likedIds();
    return await pageOf(ids, opt);
  },

  // 账号下的全部歌单（自建 + 收藏），不含「我喜欢的音乐」
  async getPlaylists() {
    if (!stored()) throw new Error('请先登录');
    const u = await uid();
    const pl = await api('/weapi/user/playlist', { uid: u, limit: 100, offset: 0, includeVideo: true });
    return (pl.playlist || []).filter(function (p) { return p.specialType !== 5; }).map(function (p) {
      return { id: String(p.id), name: p.name, count: p.trackCount || 0, cover: pic(p.coverImgUrl), mine: String(p.userId) === u };
    });
  },

  async getPlaylist(id, opt) {
    if (!stored()) throw new Error('请先登录');
    const ids = await playlistIds(id);
    return await pageOf(ids, opt);
  },

  // 登录账号的昵称和头像
  async getProfile() {
    if (!stored()) return null;
    const j = await api('/weapi/w/nuser/account/get', {});
    const pr = j.profile;
    if (!pr) throw new Error('登录已失效，请重新登录');
    neko.storage.set('uid', String(pr.userId));
    return { name: pr.nickname || '', avatar: pr.avatarUrl ? String(pr.avatarUrl).replace(/^http:/, 'https:') + '?param=200y200' : '' };
  },

  async logout() { return doLogout(); },

  async setLike(track, like) {
    if (!stored()) throw new Error('请先登录');
    await api('/weapi/radio/like', { alg: 'itembased', trackId: track.id, like: !!like, time: '3' });
    likedCache = null;
    idsCache = {};
    return { ok: true };
  },

  async login(ctx) {
    const has = !!stored();
    const options = ['扫码登录（推荐）', '手机验证码登录', '粘贴 Cookie'];
    if (has) options.push('退出登录');
    const pick = await ctx.choose({ title: '网易云音乐 · ' + (has ? '已有登录信息' : '未登录'), options: options });
    if (pick === null || pick === undefined) return;
    if (pick === 0) return { message: await qrLogin(ctx) };
    if (pick === 1) return { message: await smsLogin(ctx) };
    if (pick === 2) return { message: await pasteCookie(ctx) };
    doLogout();
    return { message: '已退出登录' };
  },
};

let likedCache = null;
async function uid() {
  let u = neko.storage.get('uid');
  if (u) return u;
  const j = await api('/weapi/w/nuser/account/get', {});
  u = j.account && j.account.id;
  if (!u) throw new Error('登录已失效，请重新登录');
  neko.storage.set('uid', String(u));
  return String(u);
}
// 喜欢的音乐 = 账号下第一个歌单；缓存 60 秒
async function likedIds() {
  if (!stored()) throw new Error('请先登录（点「设置」）');
  if (likedCache && Date.now() - likedCache.t < 60000) return likedCache.ids;
  const u = await uid();
  const pl = await api('/weapi/user/playlist', { uid: u, limit: 30, offset: 0, includeVideo: true });
  const first = pl.playlist && pl.playlist[0];
  if (!first) throw new Error('没有找到「我喜欢的音乐」');
  const d = await api('/weapi/v6/playlist/detail', { id: first.id, n: 0, s: 0 });
  const ids = ((d.playlist && d.playlist.trackIds) || []).map(function (t) { return String(t.id); });
  likedCache = { t: Date.now(), ids: ids };
  return ids;
}

// 歌单里的全部歌曲 id（缓存 2 分钟，分页时不重复请求）
let idsCache = {};
function doLogout() {
  neko.storage.remove('cookie');
  neko.storage.remove('uid');
  idsCache = {};
  likedCache = null;
  return { ok: true };
}
async function playlistIds(id) {
  const c = idsCache[id];
  if (c && Date.now() - c.t < 120000) return c.ids;
  const d = await api('/weapi/v6/playlist/detail', { id: id, n: 0, s: 0 });
  const ids = ((d.playlist && d.playlist.trackIds) || []).map(function (t) { return String(t.id); });
  idsCache[id] = { t: Date.now(), ids: ids };
  return ids;
}

// 取一页：每 100 首一个请求，并行发出
async function pageOf(ids, opt) {
  const page = (opt && opt.page) || 1;
  const size = (opt && opt.pageSize) || 200;
  const slice = ids.slice((page - 1) * size, page * size);
  const parts = [];
  for (let i = 0; i < slice.length; i += 100) parts.push(slice.slice(i, i + 100));
  const got = await Promise.all(parts.map(function (part) {
    return api('/weapi/v3/song/detail', { c: JSON.stringify(part.map(function (x) { return { id: Number(x) }; })) });
  }));
  const by = {};
  got.forEach(function (j) { (j.songs || []).forEach(function (s) { by[String(s.id)] = s; }); });
  const items = [];
  slice.forEach(function (x) { if (by[x]) items.push(fromSong(by[x])); });
  return { items: items, total: ids.length, hasMore: page * size < ids.length };
}

// 从响应头和响应体里取出登录 Cookie
function pickCookie(headers, body) {
  const text = String(headers['set-cookie'] || '') + ';' + String((body && body.cookie) || '');
  const out = {};
  const re = /(MUSIC_U|__csrf|MUSIC_A_T|MUSIC_R_T|MUSIC_A|NMTID)=([^;,\s]+)/g;
  let m;
  while ((m = re.exec(text))) out[m[1]] = m[2];
  return Object.keys(out).map(function (k) { return k + '=' + out[k]; }).join('; ');
}

async function finish(ck) {
  if (ck.indexOf('MUSIC_U') < 0) throw new Error('登录成功但没有拿到 MUSIC_U，请改用粘贴 Cookie');
  neko.storage.set('cookie', ck);
  neko.storage.remove('uid');
  try {
    const j = await api('/weapi/w/nuser/account/get', {});
    if (j.profile && j.profile.nickname) return '登录成功：' + j.profile.nickname;
  } catch (e) { /* 拿不到昵称不影响登录 */ }
  return '登录成功';
}

async function qrLogin(ctx) {
  const k = await api('/weapi/login/qrcode/unikey', { type: 1 });
  const key = k.unikey;
  if (!key) throw new Error('获取二维码失败');
  const url = 'https://music.163.com/login?codekey=' + key;
  let hint = '请用另一台设备上的网易云音乐 App 扫码，并在手机上点确认，然后点下面的按钮。';
  for (let i = 0; i < 6; i++) {
    const ok = await ctx.qr({ title: '扫码登录', text: url, hint: hint, confirm: '我已确认' });
    if (!ok) return '已取消登录';
    const r = await raw('/weapi/login/qrcode/client/login', { key: key, type: 1 });
    const code = r.j && r.j.code;
    if (code === 803) return await finish(pickCookie(r.headers, r.j));
    if (code === 800) throw new Error('二维码已过期，请重新登录');
    hint = code === 802 ? '已扫码，请在手机上点「确认登录」，然后再点按钮。' : '还没有扫码，请先扫码并确认。';
  }
  throw new Error('等待扫码超时');
}

async function smsLogin(ctx) {
  const a = await ctx.prompt([{ key: 'phone', label: '手机号（中国大陆）', type: 'text' }]);
  const phone = a && String(a.phone || '').trim();
  if (!phone) return '已取消';
  await api('/weapi/sms/captcha/sent', { cellphone: phone, ctcode: '86' });
  const b = await ctx.prompt([{ key: 'code', label: '短信验证码（已发送到 ' + phone + '）', type: 'text' }]);
  const code = b && String(b.code || '').trim();
  if (!code) return '已取消';
  const r = await raw('/weapi/login/cellphone', { phone: phone, countrycode: '86', captcha: code, rememberLogin: 'true' });
  if (r.j.code !== 200) throw new Error((r.j.message || r.j.msg || '登录失败') + '（' + r.j.code + '）');
  return await finish(pickCookie(r.headers, r.j));
}

async function pasteCookie(ctx) {
  const r = await ctx.prompt([{ key: 'cookie', label: 'Cookie（如 MUSIC_U=xxxx）', type: 'text' }]);
  const ck = r && String(r.cookie || '').trim();
  if (!ck) return '已取消';
  neko.storage.set('cookie', ck);
  neko.storage.remove('uid');
  return 'Cookie 已保存';
}
