// NEKOPLAY 插件沙盒前导脚本：只注入 neko 对象，插件没有文件系统 / DOM / 其他原生能力。
(function (g) {
  'use strict';
  var seq = 0, pend = {}, store = {};

  g.__nekoResolve = function (id, ok, payload) {
    var p = pend[id];
    if (!p) return;
    delete pend[id];
    if (ok) p.res(payload === null || payload === undefined ? undefined : JSON.parse(payload));
    else p.rej(new Error(payload));
  };
  g.__nekoStoreInit = function (json) { try { store = JSON.parse(json) || {}; } catch (e) { store = {}; } };
  g.__nekoDone = function (id, ok, payload) {
    sendMessage('neko.done', JSON.stringify({ id: id, ok: ok, data: payload }));
  };
  function call(ch, data) {
    return new Promise(function (res, rej) {
      var id = ++seq;
      pend[id] = { res: res, rej: rej };
      sendMessage(ch, JSON.stringify({ id: id, data: data }));
    });
  }

  // ---------- 字节 / 编码 ----------
  function utf8(str) {
    var out = [];
    str = String(str);
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c >= 0xd800 && c < 0xdc00 && i + 1 < str.length) {
        var d = str.charCodeAt(i + 1);
        if (d >= 0xdc00 && d < 0xe000) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; }
      }
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return out;
  }
  function toBytes(x) {
    if (typeof x === 'string') return utf8(x);
    var a = [];
    for (var i = 0; i < x.length; i++) a.push(x[i] & 255);
    return a;
  }
  function hex(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    return s;
  }
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function b64enc(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i += 3) {
      var n = (bytes[i] << 16) | ((bytes[i + 1] || 0) << 8) | (bytes[i + 2] || 0);
      s += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] +
        (i + 1 < bytes.length ? B64[(n >> 6) & 63] : '=') +
        (i + 2 < bytes.length ? B64[n & 63] : '=');
    }
    return s;
  }
  function b64dec(str) {
    str = String(str).replace(/[^A-Za-z0-9+\/]/g, '');
    var out = [], buf = 0, bits = 0;
    for (var i = 0; i < str.length; i++) {
      buf = (buf << 6) | B64.indexOf(str[i]);
      bits += 6;
      if (bits >= 8) { bits -= 8; out.push((buf >> bits) & 255); }
    }
    return out;
  }
  function fromUtf8(bytes) {
    var s = '', i = 0;
    while (i < bytes.length) {
      var c = bytes[i++];
      if (c < 0x80) s += String.fromCharCode(c);
      else if (c < 0xe0) s += String.fromCharCode(((c & 31) << 6) | (bytes[i++] & 63));
      else if (c < 0xf0) { s += String.fromCharCode(((c & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63)); }
      else {
        var cp = ((c & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63);
        cp -= 0x10000;
        s += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 1023));
      }
    }
    return s;
  }

  // ---------- 摘要算法 ----------
  function rol(x, n) { return (x << n) | (x >>> (32 - n)); }
  function md5(bytes) {
    var K = [], S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
      4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
    for (var i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0;
    var m = bytes.slice(), l = bytes.length * 8;
    m.push(0x80);
    while (m.length % 64 !== 56) m.push(0);
    for (i = 0; i < 8; i++) m.push(i < 4 ? (l >>> (8 * i)) & 255 : 0);
    var a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;
    for (var o = 0; o < m.length; o += 64) {
      var M = [];
      for (i = 0; i < 16; i++) M[i] = m[o + 4 * i] | (m[o + 4 * i + 1] << 8) | (m[o + 4 * i + 2] << 16) | (m[o + 4 * i + 3] << 24);
      var A = a0, B = b0, C = c0, D = d0;
      for (i = 0; i < 64; i++) {
        var F, g;
        if (i < 16) { F = (B & C) | (~B & D); g = i; }
        else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
        else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
        else { F = C ^ (B | ~D); g = (7 * i) % 16; }
        F = (F + A + K[i] + M[g]) | 0;
        A = D; D = C; C = B;
        B = (B + rol(F, S[i])) | 0;
      }
      a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
    }
    var out = [];
    [a0, b0, c0, d0].forEach(function (v) { for (var j = 0; j < 4; j++) out.push((v >>> (8 * j)) & 255); });
    return out;
  }
  function padBE(bytes) {
    var m = bytes.slice(), l = bytes.length * 8;
    m.push(0x80);
    while (m.length % 64 !== 56) m.push(0);
    var hi = Math.floor(l / 4294967296), lo = l >>> 0;
    [hi, lo].forEach(function (v) { for (var j = 3; j >= 0; j--) m.push((v >>> (8 * j)) & 255); });
    return m;
  }
  function sha1(bytes) {
    var m = padBE(bytes), h0 = 0x67452301, h1 = 0xefcdab89 | 0, h2 = 0x98badcfe | 0, h3 = 0x10325476, h4 = 0xc3d2e1f0 | 0;
    for (var o = 0; o < m.length; o += 64) {
      var w = [], i;
      for (i = 0; i < 16; i++) w[i] = (m[o + 4 * i] << 24) | (m[o + 4 * i + 1] << 16) | (m[o + 4 * i + 2] << 8) | m[o + 4 * i + 3];
      for (i = 16; i < 80; i++) w[i] = rol(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
      var a = h0, b = h1, c = h2, d = h3, e = h4;
      for (i = 0; i < 80; i++) {
        var f, k;
        if (i < 20) { f = (b & c) | (~b & d); k = 0x5a827999; }
        else if (i < 40) { f = b ^ c ^ d; k = 0x6ed9eba1; }
        else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdc | 0; }
        else { f = b ^ c ^ d; k = 0xca62c1d6 | 0; }
        var t = (rol(a, 5) + f + e + k + w[i]) | 0;
        e = d; d = c; c = rol(b, 30); b = a; a = t;
      }
      h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0; h4 = (h4 + e) | 0;
    }
    var out = [];
    [h0, h1, h2, h3, h4].forEach(function (v) { for (var j = 3; j >= 0; j--) out.push((v >>> (8 * j)) & 255); });
    return out;
  }
  var K256 = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
    0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
    0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70,
    0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
  function ror(x, n) { return (x >>> n) | (x << (32 - n)); }
  function sha256(bytes) {
    var m = padBE(bytes);
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    for (var o = 0; o < m.length; o += 64) {
      var w = [], i;
      for (i = 0; i < 16; i++) w[i] = (m[o + 4 * i] << 24) | (m[o + 4 * i + 1] << 16) | (m[o + 4 * i + 2] << 8) | m[o + 4 * i + 3];
      for (i = 16; i < 64; i++) {
        var s0 = ror(w[i - 15], 7) ^ ror(w[i - 15], 18) ^ (w[i - 15] >>> 3);
        var s1 = ror(w[i - 2], 17) ^ ror(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (i = 0; i < 64; i++) {
        var S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25), ch = (e & f) ^ (~e & g);
        var t1 = (h + S1 + ch + K256[i] + w[i]) | 0;
        var S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22), maj = (a & b) ^ (a & c) ^ (b & c);
        var t2 = (S0 + maj) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    var out = [];
    H.forEach(function (v) { for (var j = 3; j >= 0; j--) out.push((v >>> (8 * j)) & 255); });
    return out;
  }
  var ALGO = { md5: md5, sha1: sha1, sha256: sha256 };
  function hmac(algo, key, msg) {
    var h = ALGO[algo];
    if (!h) throw new Error('不支持的算法：' + algo);
    var k = toBytes(key);
    if (k.length > 64) k = h(k);
    while (k.length < 64) k.push(0);
    var ip = k.map(function (b) { return b ^ 0x36; }), op = k.map(function (b) { return b ^ 0x5c; });
    return h(op.concat(h(ip.concat(toBytes(msg)))));
  }
  function out(bytes, enc) { return enc === 'base64' ? b64enc(bytes) : enc === 'bytes' ? bytes : hex(bytes); }
  function unsupported(name) {
    return function () { throw new Error('此版本暂不支持 ' + name); };
  }

  g.neko = {
    http: function (req) { return call('neko.http', req); },
    storage: {
      get: function (k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
      set: function (k, v) { store[k] = String(v); sendMessage('neko.storage', JSON.stringify(store)); },
      remove: function (k) { delete store[k]; sendMessage('neko.storage', JSON.stringify(store)); }
    },
    crypto: {
      md5: function (s, enc) { return out(md5(toBytes(s)), enc); },
      sha1: function (s, enc) { return out(sha1(toBytes(s)), enc); },
      sha256: function (s, enc) { return out(sha256(toBytes(s)), enc); },
      hmac: function (algo, key, msg, enc) { return out(hmac(algo, key, msg), enc); },
      base64: {
        encode: function (s) { return b64enc(toBytes(s)); },
        decode: function (s) { return fromUtf8(b64dec(s)); },
        decodeBytes: function (s) { return b64dec(s); }
      },
      aesEncrypt: unsupported('aesEncrypt'),
      aesDecrypt: unsupported('aesDecrypt'),
      rsaEncrypt: unsupported('rsaEncrypt')
    },
    log: function () {
      var a = [];
      for (var i = 0; i < arguments.length; i++) {
        var v = arguments[i];
        try { a.push(typeof v === 'string' ? v : JSON.stringify(v)); } catch (e) { a.push(String(v)); }
      }
      sendMessage('neko.log', JSON.stringify(a));
    },
    env: { appVersion: '1.0.0', platform: 'android', lang: 'zh-CN' }
  };

  // 登录上下文（login(ctx) 使用）
  g.__nekoCtx = {
    prompt: function (fields) { return call('neko.prompt', fields); },
    // 单选：{ title, options:[...] } → 选中的序号，取消返回 null
    choose: function (req) { return call('neko.ui', Object.assign({ kind: 'choose' }, req)); },
    // 二维码：{ title, text, hint, confirm } → 用户点「确认」返回 true，取消返回 false
    qr: function (req) { return call('neko.ui', Object.assign({ kind: 'qr' }, req)); },
    openWebView: function () { return Promise.reject(new Error('此版本暂不支持内置网页登录，请使用 prompt 表单')); }
  };

  // 导出：export default {...} 会被改写为 __nekoExport = {...}
  g.module = { exports: {} };
  g.__nekoInstance = function () {
    var e = g.__nekoExport || (g.module.exports && (g.module.exports.default || g.module.exports));
    return e;
  };
  // 阻断常见的越权入口
  g.fetch = undefined;
  g.XMLHttpRequest = undefined;
})(globalThis);
