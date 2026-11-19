// ==NekoPlugin==
// @id          ncm-enhanced
// @name        网易云音乐（API Enhanced）
// @version     1.3.0
// @author      NEKOPLAY
// @api         1
// @hosts       *
// @caps        search, stream, lyric, recommend, liked, playlist, login
// ==/NekoPlugin==
//
// 对接你自己部署的 NeteaseCloudMusicApiEnhanced/api-enhanced 服务。
// 首次使用：在「音源插件」页点本插件的「设置」，填写你的服务地址（例如 http://192.168.1.10:3000 或 https://api.example.com），
// 登录支持：扫码、手机验证码、粘贴 Cookie（用于 VIP 曲目和每日推荐）。
// @hosts 写 *，是因为服务地址由你自己决定；插件只会请求你填的那个地址。

const store = {
  get base() { return (neko.storage.get('base') || '').replace(/\/+$/, ''); },
  get cookie() { return neko.storage.get('cookie') || ''; },
};

function need() {
  if (!store.base) throw new Error('请先点「设置」填写 API 服务地址');
  return store.base;
}

function qs(params) {
  const parts = [];
  for (const k in params) {
    const v = params[k];
    if (v === undefined || v === null || v === '') continue;
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
  }
  return parts.join('&');
}

async function api(path, params, loose) {
  const base = need();
  const p = Object.assign({}, params || {});
  if (store.cookie) p.cookie = store.cookie;
  p.timestamp = Date.now(); // 避免服务端缓存
  const res = await neko.http({ url: base + path + '?' + qs(p), method: 'GET', timeout: 10000 });
  if (res.status < 200 || res.status >= 300) throw new Error('服务返回 ' + res.status);
  let j;
  try { j = JSON.parse(res.body); } catch (e) { throw new Error('服务返回的不是 JSON，请检查地址'); }
  if (!loose && j && j.code && j.code !== 200) throw new Error((j.message || j.msg || '接口错误') + '（' + j.code + '）');
  return j;
}

function pic(u, size) {
  if (!u) return undefined;
  return String(u).replace(/^http:/, 'https:') + '?param=' + (size || 300) + 'y' + (size || 300);
}

function fromSong(s) {
  const ar = s.ar || s.artists || [];
  const al = s.al || s.album || {};
  return {
    id: String(s.id),
    title: s.name,
    artist: ar.map(function (a) { return a.name; }).join(' / ') || '未知艺人',
    album: al.name || '',
    duration: Math.round((s.dt || s.duration || s.mDuration || 0) / 1000),
    cover: pic(al.picUrl || (s.al && s.al.picUrl)),
  };
}

const LEVELS = { lossless: ['lossless', 'exhigh', 'standard'], high: ['exhigh', 'standard'], standard: ['standard'] };

export default {
  async ping() {
    const t = Date.now();
    await api('/cloudsearch', { keywords: 'a', limit: 1 });
    return { ok: true, ms: Date.now() - t };
  },

  async search(query, opt) {
    const page = (opt && opt.page) || 1;
    const size = (opt && opt.pageSize) || 20;
    const j = await api('/cloudsearch', { keywords: query, limit: size, offset: (page - 1) * size, type: 1 });
    const songs = (j.result && j.result.songs) || [];
    return { items: songs.map(fromSong), hasMore: songs.length >= size };
  },

  async getStream(track, opt) {
    const want = (opt && opt.quality) || 'lossless';
    const levels = LEVELS[want] || LEVELS.standard;
    let lastErr = '没有可用的播放地址（可能是版权限制或服务端未开启解灰）';
    for (const level of levels) {
      try {
        const j = await api('/song/url/v1', { id: track.id, level: level });
        const d = j.data && j.data[0];
        if (d && d.url) {
          const type = (d.type || '').toLowerCase();
          return {
            url: String(d.url).replace(/^http:/, 'https:'),
            format: type === 'flac' ? 'flac' : (type === 'mp4' || type === 'm4a' ? 'm4a' : 'mp3'),
            quality: type === 'flac' ? 'lossless' : (level === 'standard' ? 'standard' : 'high'),
            bitrate: d.br ? Math.round(d.br / 1000) : undefined,
            expiresAt: Date.now() + 15 * 60 * 1000,
          };
        }
      } catch (e) { lastErr = String(e.message || e); }
    }
    // api-enhanced 的解灰接口：服务端按已配置的音源匹配
    try {
      const j = await api('/song/url/match', { id: track.id });
      const u = typeof j.data === 'string' ? j.data : (j.data && (j.data.url || (j.data[0] && j.data[0].url)));
      if (u) return { url: String(u), format: 'mp3', quality: 'standard', expiresAt: Date.now() + 10 * 60 * 1000 };
    } catch (e) { /* 忽略，沿用上面的错误 */ }
    throw new Error(lastErr);
  },

  async getLyric(track) {
    const j = await api('/lyric', { id: track.id });
    const lrc = (j.lrc && j.lrc.lyric) || '';
    const tr = (j.tlyric && j.tlyric.lyric) || '';
    if (!lrc) throw new Error('暂无歌词');
    return { lrc: lrc, translation: tr || undefined };
  },

  // 首页「推荐」：有 Cookie 时用每日推荐，否则用新歌速递
  async recommend() {
    if (store.cookie) {
      try {
        const j = await api('/recommend/songs');
        const list = (j.data && j.data.dailySongs) || [];
        if (list.length) return { items: list.slice(0, 30).map(fromSong) };
      } catch (e) { /* 回落到新歌速递 */ }
    }
    const j = await api('/personalized/newsong', { limit: 20 });
    const list = (j.result || []).map(function (r) { return r.song; }).filter(Boolean);
    return { items: list.map(fromSong) };
  },

  // 我喜欢：分页返回
  async getLiked(opt) {
    const page = (opt && opt.page) || 1;
    const size = (opt && opt.pageSize) || 100;
    if (!store.cookie) throw new Error('请先登录（点「设置」）');
    let uid = neko.storage.get('uid');
    if (!uid) {
      const a = await api('/user/account');
      uid = a.profile && a.profile.userId;
      if (!uid) throw new Error('登录已失效，请重新登录');
      neko.storage.set('uid', String(uid));
    }
    const pl = await api('/user/playlist', { uid: uid, limit: 30 });
    const first = pl.playlist && pl.playlist[0];
    if (!first) throw new Error('没有找到「我喜欢的音乐」');
    const j = await api('/playlist/track/all', { id: first.id, limit: size, offset: (page - 1) * size });
    const songs = j.songs || [];
    return { items: songs.map(fromSong), total: first.trackCount || 0, hasMore: page * size < (first.trackCount || 0) };
  },

  async getPlaylists() {
    if (!store.cookie) throw new Error('请先登录（点「设置」）');
    let uid = neko.storage.get('uid');
    if (!uid) {
      const a = await api('/user/account');
      uid = a.profile && a.profile.userId;
      if (!uid) throw new Error('登录已失效，请重新登录');
      neko.storage.set('uid', String(uid));
    }
    const pl = await api('/user/playlist', { uid: uid, limit: 100 });
    return (pl.playlist || []).filter(function (p) { return p.specialType !== 5; }).map(function (p) {
      return { id: String(p.id), name: p.name, count: p.trackCount || 0, cover: p.coverImgUrl ? p.coverImgUrl + '?param=300y300' : '', mine: String(p.userId) === String(uid) };
    });
  },

  async getPlaylist(id, opt) {
    const page = (opt && opt.page) || 1;
    const size = (opt && opt.pageSize) || 100;
    const j = await api('/playlist/track/all', { id: id, limit: size, offset: (page - 1) * size });
    const songs = j.songs || [];
    return { items: songs.map(fromSong), hasMore: songs.length >= size };
  },

  async setLike(track, like) {
    if (!store.cookie) throw new Error('请先登录');
    await api('/like', { id: track.id, like: like ? 'true' : 'false' });
    return { ok: true };
  },

  async login(ctx) {
    // 没填过服务地址时，先填地址
    if (!store.base) await askBase(ctx);
    if (!store.base) return;
    const hasCookie = !!store.cookie;
    const options = ['扫码登录（推荐）', '手机验证码登录', '粘贴 Cookie', '修改 API 服务地址'];
    if (hasCookie) options.push('退出登录');
    const pick = await ctx.choose({ title: '网易云音乐 · ' + (hasCookie ? '已有登录信息' : '未登录'), options: options });
    if (pick === null || pick === undefined) return;
    if (pick === 0) return { message: await qrLogin(ctx) };
    if (pick === 1) return { message: await smsLogin(ctx) };
    if (pick === 2) return { message: await pasteCookie(ctx) };
    if (pick === 3) { await askBase(ctx); return { message: '服务地址已保存：' + store.base }; }
    neko.storage.remove('cookie');
    return { message: '已退出登录' };
  },
};

async function askBase(ctx) {
  const r = await ctx.prompt([{ key: 'base', label: 'API 服务地址（如 http://192.168.1.10:3000）', type: 'text' }]);
  if (!r) return;
  const base = String(r.base || '').trim();
  if (!/^https?:\/\//i.test(base)) throw new Error('地址要以 http:// 或 https:// 开头');
  neko.storage.set('base', base);
}

// 接口返回的 cookie 带有 Path / Max-Age 等属性，只保留真正的键值
function cleanCookie(raw) {
  const skip = ['max-age', 'expires', 'path', 'domain', 'httponly', 'secure', 'samesite'];
  const out = [];
  String(raw || '').split(/;\s*/).forEach(function (kv) {
    const i = kv.indexOf('=');
    if (i <= 0) return;
    const k = kv.slice(0, i).trim();
    if (skip.indexOf(k.toLowerCase()) >= 0) return;
    out.push(k + '=' + kv.slice(i + 1).trim());
  });
  return out.join('; ');
}

async function finishLogin(rawCookie) {
  const ck = cleanCookie(rawCookie);
  if (!ck || ck.indexOf('MUSIC_U') < 0) throw new Error('登录成功但没有拿到 MUSIC_U，请改用粘贴 Cookie');
  neko.storage.set('cookie', ck);
  neko.storage.remove('uid');
  try {
    const j = await api('/login/status', {}, true);
    const p = j && j.data && j.data.profile;
    if (p && p.nickname) return '登录成功：' + p.nickname;
  } catch (e) { /* 拿不到昵称不影响登录 */ }
  return '登录成功';
}

async function qrLogin(ctx) {
  const k = await api('/login/qr/key', {}, false);
  const key = k.data && (k.data.unikey || (k.data.data && k.data.data.unikey));
  if (!key) throw new Error('获取二维码 key 失败');
  const c = await api('/login/qr/create', { key: key, qrimg: 'false' }, false);
  const url = c.data && (c.data.qrurl || (c.data.data && c.data.data.qrurl));
  if (!url) throw new Error('生成二维码失败');
  let hint = '请用另一台设备上的网易云音乐 App 扫码，并在手机上点确认，然后点下面的按钮。';
  for (let i = 0; i < 6; i++) {
    const ok = await ctx.qr({ title: '扫码登录', text: url, hint: hint, confirm: '我已确认' });
    if (!ok) return '已取消登录';
    const r = await api('/login/qr/check', { key: key }, true);
    if (r.code === 803) return await finishLogin(r.cookie);
    if (r.code === 800) throw new Error('二维码已过期，请重新登录');
    hint = r.code === 802 ? '已扫码，请在手机上点「确认登录」，然后再点按钮。' : '还没有扫码，请先扫码并确认。';
  }
  throw new Error('等待扫码超时');
}

async function smsLogin(ctx) {
  const a = await ctx.prompt([{ key: 'phone', label: '手机号', type: 'text' }]);
  if (!a || !String(a.phone || '').trim()) return '已取消';
  const phone = String(a.phone).trim();
  await api('/captcha/sent', { phone: phone }, false);
  const b = await ctx.prompt([{ key: 'code', label: '短信验证码（已发送到 ' + phone + '）', type: 'text' }]);
  if (!b || !String(b.code || '').trim()) return '已取消';
  const j = await api('/login/cellphone', { phone: phone, captcha: String(b.code).trim() }, false);
  return await finishLogin(j.cookie);
}

async function pasteCookie(ctx) {
  const r = await ctx.prompt([{ key: 'cookie', label: 'Cookie（如 MUSIC_U=xxxx）', type: 'text' }]);
  const ck = r && String(r.cookie || '').trim();
  if (!ck) return '已取消';
  neko.storage.set('cookie', ck);
  return 'Cookie 已保存';
}
