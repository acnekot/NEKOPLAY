import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_js/flutter_js.dart';

import 'plugin_models.dart';

/// 一个插件 = 一个独立 JS 引擎（Android: QuickJS，iOS: JavaScriptCore）。
/// 沙盒规则（SPEC_PLUGIN 第 3、8 节）：
/// - 只注入 neko 对象；fetch / XMLHttpRequest 被移除；
/// - neko.http 只能访问 @hosts 声明的域名，重定向到其他域名同样拦截；
/// - 单次调用 15 秒超时，同时最多 4 个请求，默认请求超时 10 秒；
/// - 私有存储单插件 1 MB，互相隔离。
class PluginSandbox {
  PluginSandbox(this.meta, this.code, this.storageFile, {this.onLog, this.onPrompt, this.onUi});

  final PluginMeta meta;
  final String code;
  final File storageFile;
  final void Function(String plugin, String line)? onLog;
  final Future<Map<String, String>?> Function(String plugin, List<Map<String, dynamic>> fields)? onPrompt;
  final Future<dynamic> Function(String plugin, Map<String, dynamic> req)? onUi;

  static String? _prelude;
  JavascriptRuntime? _rt;
  Future<void>? _ready;
  final Map<int, Completer<dynamic>> _calls = {};
  int _seq = 0;
  int _inflight = 0;
  final List<Completer<void>> _waiters = [];
  Map<String, String> _store = {};
  bool disposed = false;

  Future<void> _ensure() => _ready ??= _init();

  Future<void> _init() async {
    _prelude ??= await rootBundle.loadString('assets/plugin/prelude.js');
    final rt = getJavascriptRuntime(xhr: false);
    _rt = rt;

    rt.onMessage('neko.http', (dynamic a) => _onHttp(a));
    rt.onMessage('neko.done', (dynamic a) {
      final m = _asMap(a);
      final c = _calls[m['id']];
      if (c == null || c.isCompleted) return;
      if (m['ok'] == true) {
        final d = m['data'];
        try {
          c.complete(d == null || d == 'null' ? null : jsonDecode(d as String));
        } catch (e) {
          c.completeError('插件返回了无法解析的数据');
        }
      } else {
        c.completeError('${m['data']}');
      }
    });
    rt.onMessage('neko.log', (dynamic a) {
      final m = a is List ? a : _asMap(a).values.toList();
      onLog?.call(meta.id, m.join(' '));
    });
    rt.onMessage('neko.storage', (dynamic a) => _persistStore(a));
    rt.onMessage('neko.prompt', (dynamic a) => _onPrompt(a));
    rt.onMessage('neko.ui', (dynamic a) => _onUi(a));

    _check(rt.evaluate(_prelude!));
    _check(rt.evaluate(
        "neko.env.platform='${Platform.isIOS ? 'ios' : 'android'}'; neko.env.lang='zh-CN';"));
    try {
      if (await storageFile.exists()) {
        final j = jsonDecode(await storageFile.readAsString()) as Map;
        _store = j.map((k, v) => MapEntry('$k', '$v'));
      }
    } catch (_) {}
    _check(rt.evaluate('__nekoStoreInit(${jsonEncode(jsonEncode(_store))});'));

    var src = code;
    final re = RegExp(r'export\s+default');
    if (re.hasMatch(src)) {
      src = src.replaceFirst(re, 'globalThis.__nekoExport =');
    }
    _check(rt.evaluate(src));
    final ok = rt.evaluate("(function(){var p=__nekoInstance();return p&&typeof p==='object'?'1':'0'})()");
    if (ok.stringResult != '1') throw '插件没有导出对象（export default {...}）';
  }

  void _check(JsEvalResult r) {
    if (r.isError) {
      var s = r.stringResult;
      if (s.length > 160) s = s.substring(0, 160);
      throw '插件脚本出错：$s';
    }
  }

  Map<String, dynamic> _asMap(dynamic a) {
    if (a is String) a = jsonDecode(a);
    return Map<String, dynamic>.from(a as Map);
  }

  /// 调用插件函数，返回 JSON 解码后的结果。
  Future<dynamic> invoke(String fn, List<dynamic> args, {Duration timeout = const Duration(seconds: 15)}) async {
    await _ensure();
    final rt = _rt!;
    final id = ++_seq;
    final c = Completer<dynamic>();
    _calls[id] = c;
    final argJs = fn == 'login' ? '__nekoCtx' : null;
    final callArgs = argJs ?? jsonEncode(args);
    final js = '''
(function(){
  try {
    var p = __nekoInstance();
    if (!p || typeof p.$fn !== 'function') { __nekoDone($id, false, '插件没有实现 $fn'); return; }
    var r = ${argJs != null ? 'p.$fn.call(p, __nekoCtx)' : 'p.$fn.apply(p, $callArgs)'};
    Promise.resolve(r).then(function(v){ __nekoDone($id, true, v === undefined ? 'null' : JSON.stringify(v)); },
      function(e){ __nekoDone($id, false, String((e && e.message) || e)); });
  } catch (e) { __nekoDone($id, false, String((e && e.message) || e)); }
})();''';
    final pump = Timer.periodic(const Duration(milliseconds: 25), (_) {
      try {
        rt.executePendingJob();
      } catch (_) {}
    });
    try {
      _check(rt.evaluate(js));
      rt.executePendingJob();
      return await c.future.timeout(timeout, onTimeout: () => throw '插件响应超时');
    } finally {
      pump.cancel();
      _calls.remove(id);
    }
  }

  // ---------- neko.http ----------
  Future<void> _acquire() async {
    if (_inflight < 4) {
      _inflight++;
      return;
    }
    final w = Completer<void>();
    _waiters.add(w);
    await w.future;
  }

  void _release() {
    if (_waiters.isNotEmpty) {
      _waiters.removeAt(0).complete();
    } else {
      _inflight--;
    }
  }

  Future<void> _onHttp(dynamic a) async {
    final m = _asMap(a);
    final id = m['id'];
    try {
      await _acquire();
      try {
        final res = await _http(Map<String, dynamic>.from(m['data'] as Map));
        _resolve(id, true, jsonEncode(res));
      } finally {
        _release();
      }
    } catch (e) {
      _resolve(id, false, _why(e));
    }
  }

  String _why(Object e) {
    if (e is TimeoutException) return '请求超时';
    if (e is SocketException) return '网络连接失败';
    if (e is HandshakeException) return '证书校验失败';
    return '$e';
  }

  void _resolve(dynamic id, bool ok, String payload) {
    if (disposed) return;
    final rt = _rt;
    if (rt == null) return;
    rt.evaluate('__nekoResolve($id, $ok, ${jsonEncode(payload)});');
    try {
      rt.executePendingJob();
    } catch (_) {}
  }

  void _checkHost(Uri u) {
    if (u.scheme != 'http' && u.scheme != 'https') throw '只允许 http / https 请求';
    if (!meta.allowsHost(u.host)) throw '域名 ${u.host} 不在插件声明的 @hosts 内，已拦截';
  }

  Future<Map<String, dynamic>> _http(Map<String, dynamic> req) async {
    var url = Uri.parse('${req['url']}');
    var method = '${req['method'] ?? 'GET'}'.toUpperCase();
    final headers = <String, String>{
      for (final e in ((req['headers'] as Map?) ?? const {}).entries) '${e.key}': '${e.value}',
    };
    Object? body = req['body'];
    final timeoutMs = (req['timeout'] as num?)?.toInt() ?? 10000;
    final deadline = Duration(milliseconds: timeoutMs.clamp(1000, 15000));
    final sw = Stopwatch()..start();

    for (var hop = 0; hop < 6; hop++) {
      _checkHost(url);
      final client = HttpClient()..connectionTimeout = deadline;
      try {
        final r = await client.openUrl(method, url).timeout(deadline);
        r.followRedirects = false;
        headers.forEach(r.headers.set);
        if (body != null && method != 'GET' && method != 'HEAD') {
          final bytes = utf8.encode(body is String ? body : jsonEncode(body));
          r.headers.contentLength = bytes.length;
          r.add(bytes);
        }
        final left = deadline - sw.elapsed;
        final resp = await r.close().timeout(left.isNegative ? const Duration(milliseconds: 500) : left);
        if (resp.isRedirect) {
          final loc = resp.headers.value('location');
          await resp.drain<void>();
          if (loc == null) throw '重定向缺少 Location';
          url = url.resolve(loc);
          if (resp.statusCode == 303 || ((resp.statusCode == 301 || resp.statusCode == 302) && method == 'POST')) {
            method = 'GET';
            body = null;
          }
          continue;
        }
        final data = <int>[];
        await for (final chunk in resp.timeout(deadline)) {
          data.addAll(chunk);
          if (data.length > 6 * 1024 * 1024) throw '响应超过 6 MB';
        }
        final hs = <String, String>{};
        resp.headers.forEach((k, v) => hs[k] = v.join(', '));
        return {'status': resp.statusCode, 'headers': hs, 'body': utf8.decode(data, allowMalformed: true)};
      } finally {
        client.close(force: true);
      }
    }
    throw '重定向次数过多';
  }

  // ---------- 私有存储 ----------
  void _persistStore(dynamic a) {
    try {
      final raw = a is String ? a : jsonEncode(a);
      if (raw.length > 1024 * 1024) {
        onLog?.call(meta.id, '[存储] 超过 1 MB 上限，本次写入被丢弃');
        return;
      }
      storageFile.parent.createSync(recursive: true);
      storageFile.writeAsStringSync(raw);
    } catch (_) {}
  }

  // ---------- 登录表单 ----------
  Future<void> _onUi(dynamic a) async {
    final m = _asMap(a);
    final id = m['id'];
    try {
      final req = Map<String, dynamic>.from(m['data'] as Map);
      final r = await onUi?.call(meta.name, req);
      _resolve(id, true, jsonEncode(r));
    } catch (e) {
      _resolve(id, false, '$e');
    }
  }

  Future<void> _onPrompt(dynamic a) async {
    final m = _asMap(a);
    final id = m['id'];
    try {
      final fields = [for (final f in (m['data'] as List)) Map<String, dynamic>.from(f as Map)];
      final r = await onPrompt?.call(meta.name, fields);
      if (r == null) {
        _resolve(id, false, '已取消');
      } else {
        _resolve(id, true, jsonEncode(r));
      }
    } catch (e) {
      _resolve(id, false, '$e');
    }
  }

  void dispose() {
    disposed = true;
    for (final c in _calls.values) {
      if (!c.isCompleted) c.completeError('插件已停用');
    }
    _calls.clear();
    try {
      _rt?.dispose();
    } catch (_) {}
    _rt = null;
    _ready = null;
  }
}
