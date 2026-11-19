import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:flutter/services.dart' show rootBundle;

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../audio/engine.dart';
import '../data/library.dart';
import '../data/lyrics.dart';
import '../data/settings.dart';
import '../data/track.dart';
import '../nav/nav.dart';
import '../ui/widgets.dart' show imageCacheManager, imageHeaders;
import 'plugin_models.dart';
import 'sandbox.dart';

class PluginsState {
  const PluginsState({this.items = const [], this.logs = const [], this.loaded = false});
  final List<PluginInfo> items;
  final List<String> logs;
  final bool loaded;

  PluginsState copyWith({List<PluginInfo>? items, List<String>? logs, bool? loaded}) =>
      PluginsState(items: items ?? this.items, logs: logs ?? this.logs, loaded: loaded ?? this.loaded);

  int get enabledCount => items.where((e) => e.enabled).length;
  List<PluginInfo> get activeOnline =>
      items.where((e) => e.enabled && !e.builtin && e.status != PStatus.fail).toList();
}

class SearchBundle {
  SearchBundle({required this.bySource, this.blockedByWifi = false, this.errors = const {}});
  final Map<String, List<Track>> bySource;
  final bool blockedByWifi;
  final Map<String, String> errors;
}

final pluginsProvider = NotifierProvider<PluginsNotifier, PluginsState>(PluginsNotifier.new);

class PluginsNotifier extends Notifier<PluginsState> {
  late Directory _dir;
  final Map<String, String> _code = {};
  final Map<String, PluginSandbox> _boxes = {};
  final Map<String, (int, SearchBundle)> _searchCache = {};
  final Map<String, (int, ResolvedStream)> _streamCache = {};
  final Map<String, String> _lyricCache = {};

  /// 登录表单由界面提供。
  Future<Map<String, String>?> Function(String plugin, List<Map<String, dynamic>> fields)? promptUi;
  Future<dynamic> Function(String plugin, Map<String, dynamic> req)? uiHandler;

  @override
  PluginsState build() {
    ref.onDispose(() {
      for (final b in _boxes.values) {
        b.dispose();
      }
    });
    return PluginsState(items: [PluginInfo(meta: PluginMeta.local, enabled: true, status: PStatus.ok)]);
  }

  // ---------- 持久化 ----------
  Future<void> load() async {
    final docs = await getApplicationDocumentsDirectory();
    _dir = Directory(p.join(docs.path, 'plugins'))..createSync(recursive: true);
    final f = File(p.join(_dir.path, 'plugins.json'));
    final infos = <String, PluginInfo>{};
    var order = <String>['local'];
    var localOn = true;
    _bundledSeen = {};
    if (await f.exists()) {
      try {
        final j = jsonDecode(await f.readAsString()) as Map;
        order = [for (final e in (j['order'] as List? ?? const ['local'])) '$e'];
        localOn = (j['localOn'] as bool?) ?? true;
        _bundledSeen = {for (final e in (j['bundledSeen'] as List? ?? const [])) '$e'};
        for (final e in (j['plugins'] as List? ?? const [])) {
          final m = Map<String, dynamic>.from(e as Map);
          final id = m['id'] as String;
          final cf = File(p.join(_dir.path, '$id.js'));
          if (!await cf.exists()) continue;
          final code = await cf.readAsString();
          try {
            final meta = PluginMeta.parse(code);
            _code[id] = code;
            infos[id] = PluginInfo(
              meta: meta,
              enabled: (m['enabled'] as bool?) ?? false,
              status: (m['enabled'] as bool?) ?? false ? PStatus.ok : PStatus.unverified,
              ms: (m['ms'] as num?)?.toInt() ?? 0,
            );
          } catch (_) {}
        }
      } catch (_) {}
    }
    final list = <PluginInfo>[];
    for (final id in order) {
      if (id == 'local') {
        list.add(PluginInfo(meta: PluginMeta.local, enabled: localOn, status: PStatus.ok));
      } else if (infos.containsKey(id)) {
        list.add(infos.remove(id)!);
      }
    }
    if (!list.any((e) => e.builtin)) {
      list.insert(0, PluginInfo(meta: PluginMeta.local, enabled: true, status: PStatus.ok));
    }
    list.addAll(infos.values);
    await _syncBundled(list);
    state = state.copyWith(items: list, loaded: true);
    _save();
  }

  Set<String> _bundledSeen = {};

  /// 随 App 内置的插件：首次启动自动安装并启用；用户删除后不再装回；App 升级带来新版本时自动覆盖代码（登录信息保留）。
  Future<void> _syncBundled(List<PluginInfo> list) async {
    for (final asset in const ['assets/plugin/ncm-direct.js']) {
      try {
        final code = await rootBundle.loadString(asset);
        final meta = PluginMeta.parse(code);
        final i = list.indexWhere((e) => e.meta.id == meta.id);
        if (i >= 0) {
          if (versionGreater(meta.version, list[i].meta.version)) {
            await File(p.join(_dir.path, '${meta.id}.js')).writeAsString(code);
            _code[meta.id] = code;
            list[i] = PluginInfo(meta: meta, enabled: list[i].enabled, status: PStatus.unverified, ms: list[i].ms);
          }
        } else if (!_bundledSeen.contains(meta.id)) {
          await File(p.join(_dir.path, '${meta.id}.js')).writeAsString(code);
          _code[meta.id] = code;
          list.add(PluginInfo(meta: meta, enabled: true, status: PStatus.unverified));
        }
        _bundledSeen.add(meta.id);
      } catch (_) {}
    }
  }

  Future<void> _save() async {
    final data = {
      'order': [for (final e in state.items) e.meta.id],
      'localOn': state.items.firstWhere((e) => e.builtin).enabled,
      'bundledSeen': _bundledSeen.toList(),
      'plugins': [
        for (final e in state.items.where((e) => !e.builtin))
          {'id': e.meta.id, 'enabled': e.enabled, 'ms': e.ms},
      ],
    };
    try {
      await File(p.join(_dir.path, 'plugins.json')).writeAsString(jsonEncode(data));
    } catch (_) {}
  }

  void _setItems(List<PluginInfo> l) {
    state = state.copyWith(items: l);
    _save();
  }

  void _patch(String id, void Function(PluginInfo i) f) {
    state = state.copyWith(items: [
      for (final e in state.items)
        if (e.meta.id == id) (e.copy()..also(f)) else e,
    ]);
  }

  void log(String plugin, String line) {
    final t = DateTime.now();
    final s = '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:${t.second.toString().padLeft(2, '0')} [$plugin] $line';
    final logs = [...state.logs, s];
    state = state.copyWith(logs: logs.length > 200 ? logs.sublist(logs.length - 200) : logs);
  }

  PluginSandbox _box(String id) => _boxes.putIfAbsent(
        id,
        () => PluginSandbox(
          state.items.firstWhere((e) => e.meta.id == id).meta,
          _code[id]!,
          File(p.join(_dir.path, '$id.store.json')),
          onLog: log,
          onPrompt: (name, fields) => promptUi?.call(name, fields) ?? Future.value(null),
          onUi: (name, req) => uiHandler?.call(name, req) ?? Future.value(null),
        ),
      );

  // ---------- 安装 / 更新 / 卸载 ----------
  Future<String> download(String url) async {
    final u = Uri.tryParse(url.trim());
    if (u == null || !(u.scheme == 'http' || u.scheme == 'https')) throw '地址要以 http:// 或 https:// 开头';
    final c = HttpClient()..connectionTimeout = const Duration(seconds: 10);
    try {
      final r = await (await c.getUrl(u)).close().timeout(const Duration(seconds: 15));
      if (r.statusCode != 200) throw '下载失败（HTTP ${r.statusCode}）';
      final bytes = <int>[];
      await for (final ch in r) {
        bytes.addAll(ch);
        if (bytes.length > 512 * 1024) throw '插件文件超过 512 KB';
      }
      return utf8.decode(bytes);
    } on SocketException {
      throw '网络连接失败';
    } on TimeoutException {
      throw '下载超时';
    } finally {
      c.close(force: true);
    }
  }

  /// 解析并校验，返回元数据供确认框展示（尚未安装）。
  PluginMeta inspect(String code) {
    final meta = PluginMeta.parse(code);
    if (code.length > 512 * 1024) throw '插件文件超过 512 KB';
    return meta;
  }

  Future<void> install(String code) async {
    final meta = inspect(code);
    if (state.items.any((e) => e.meta.id == meta.id)) throw '已安装同 ID 的插件（${meta.id}），请先删除或使用「检查更新」';
    await File(p.join(_dir.path, '${meta.id}.js')).writeAsString(code);
    _code[meta.id] = code;
    _setItems([...state.items, PluginInfo(meta: meta)]); // 默认停用
  }

  Future<void> remove(String id) async {
    _boxes.remove(id)?.dispose();
    _code.remove(id);
    for (final n in ['$id.js', '$id.store.json']) {
      try {
        await File(p.join(_dir.path, n)).delete();
      } catch (_) {}
    }
    _setItems(state.items.where((e) => e.meta.id != id).toList());
  }

  void moveUp(String id) {
    final l = [...state.items];
    final i = l.indexWhere((e) => e.meta.id == id);
    if (i <= 0) return;
    final t = l[i - 1];
    l[i - 1] = l[i];
    l[i] = t;
    _setItems(l);
  }

  /// 检查更新：返回 null 表示已是最新。
  Future<UpdateOffer?> checkUpdate(String id) async {
    final info = state.items.firstWhere((e) => e.meta.id == id);
    final url = info.meta.update;
    if (url == null || url.isEmpty) throw '这个插件没有声明 @update 地址';
    final code = await download(url);
    final meta = inspect(code);
    if (meta.id != id) throw '更新文件的 @id（${meta.id}）与当前插件不一致';
    if (!versionGreater(meta.version, info.meta.version)) return null;
    final newHosts = meta.hosts.where((h) => !info.meta.hosts.contains(h)).toList();
    return UpdateOffer(code, meta, newHosts);
  }

  Future<void> applyUpdate(UpdateOffer o) async {
    final id = o.meta.id;
    _boxes.remove(id)?.dispose();
    await File(p.join(_dir.path, '$id.js')).writeAsString(o.code);
    _code[id] = o.code;
    _setItems([
      for (final e in state.items)
        if (e.meta.id == id) (e.copy()..meta = o.meta..fails = 0..status = e.enabled ? PStatus.ok : PStatus.unverified) else e,
    ]);
    if (state.items.firstWhere((e) => e.meta.id == id).enabled) await verify(id);
  }

  // ---------- 开关 / 连通检查 ----------
  Future<void> toggle(String id) async {
    final info = state.items.firstWhere((e) => e.meta.id == id);
    if (info.builtin) {
      _patch(id, (i) => i.enabled = !i.enabled);
      _save();
      return;
    }
    if (info.enabled) {
      _boxes.remove(id)?.dispose();
      _patch(id, (i) {
        i.enabled = false;
      });
      _save();
      return;
    }
    // 首次（或失败后）打开：先 ping，通过才启用
    _patch(id, (i) {
      i.enabled = true;
      i.status = PStatus.checking;
    });
    await verify(id);
  }

  Future<void> verify(String id) async {
    _patch(id, (i) => i.status = PStatus.checking);
    final started = DateTime.now();
    try {
      final r = await _box(id).invoke('ping', const [], timeout: const Duration(seconds: 10));
      final ok = r is Map ? (r['ok'] == true) : true;
      if (!ok) throw '连通检查未通过';
      final ms = r is Map && r['ms'] is num ? (r['ms'] as num).toInt() : DateTime.now().difference(started).inMilliseconds;
      _patch(id, (i) {
        i.status = PStatus.ok;
        i.ms = ms;
        i.fails = 0;
        i.error = '';
      });
    } catch (e) {
      _boxes.remove(id)?.dispose();
      log(id, 'ping 失败：$e');
      _patch(id, (i) {
        i.status = PStatus.fail;
        i.enabled = false; // 开关自动退回关闭
        i.error = _short('$e');
      });
    }
    _save();
  }

  String _short(String s) {
    s = s.replaceAll('Exception: ', '');
    return s.length > 40 ? '${s.substring(0, 40)}…' : s;
  }

  /// 返回 (错误, 提示)；成功时错误为 null，提示取自插件 login 的返回值 { message }。
  Future<({String? error, String? message})> login(String id) async {
    try {
      final r = await _box(id).invoke('login', const [], timeout: const Duration(minutes: 5));
      if (r is Map) unawaited(accountChanged(id));
      return (error: null, message: r is Map ? r['message'] as String? : null);
    } catch (e) {
      return (error: '$e', message: null);
    }
  }

  // ---------- 调用 ----------
  void _recordFail(String id, String why) {
    log(id, '失败：$why');
    final info = state.items.where((e) => e.meta.id == id).firstOrNull;
    if (info == null || info.builtin) return;
    final n = info.fails + 1;
    _patch(id, (i) {
      i.fails = n;
      i.error = _short(why);
      if (n >= 3) {
        i.enabled = false;
        i.status = PStatus.fail;
      }
    });
    if (n >= 3) {
      _boxes.remove(id)?.dispose();
      log(id, '连续失败 3 次，已自动停用');
      _save();
    }
  }

  void _recordOk(String id) {
    final info = state.items.where((e) => e.meta.id == id).firstOrNull;
    if (info != null && info.fails > 0) _patch(id, (i) => i.fails = 0);
  }

  Future<bool> _onWifiOrAllowed() async {
    if (!ref.read(settingsProvider).wifiOnly) return true;
    try {
      final r = await Connectivity().checkConnectivity();
      return r.contains(ConnectivityResult.wifi) || r.contains(ConnectivityResult.ethernet);
    } catch (_) {
      return true;
    }
  }

  Track _toTrack(String pluginId, Map<String, dynamic> m) {
    final extra = <String, String>{
      for (final e in ((m['extra'] as Map?) ?? const {}).entries) '${e.key}': '${e.value}',
    };
    final oid = '${m['id']}';
    return Track(
      id: '$pluginId:$oid',
      source: pluginId,
      title: '${m['title'] ?? '未知曲目'}',
      artist: '${m['artist'] ?? '未知艺人'}',
      album: '${m['album'] ?? ''}',
      durationMs: m['duration'] is num ? ((m['duration'] as num) * 1000).round() : 0,
      coverUrl: m['cover'] as String?,
      extra: extra,
    );
  }

  Map<String, dynamic> _fromTrack(Track t) => {
        'id': t.id.startsWith('${t.source}:') ? t.id.substring(t.source.length + 1) : t.id,
        'source': t.source,
        'title': t.title,
        'artist': t.artist,
        'album': t.album,
        'duration': t.durationMs / 1000,
        'cover': t.coverUrl,
        'extra': t.extra,
      };

  /// 并行搜索所有已启用且声明了 search 的在线插件，每个最多等 6 秒；结果缓存 10 分钟。
  Future<SearchBundle> search(String query, {String type = 'track'}) async {
    final q = query.trim();
    final active = state.activeOnline.where((e) => e.meta.caps.contains('search')).toList();
    if (q.isEmpty || active.isEmpty) return SearchBundle(bySource: {});
    if (!await _onWifiOrAllowed()) return SearchBundle(bySource: {}, blockedByWifi: true);
    final key = '$type|$q|${active.map((e) => e.meta.id).join(',')}';
    final hit = _searchCache[key];
    final now = DateTime.now().millisecondsSinceEpoch;
    if (hit != null && now - hit.$1 < 10 * 60 * 1000) return hit.$2;

    final by = <String, List<Track>>{};
    final errs = <String, String>{};
    await Future.wait(active.map((info) async {
      final id = info.meta.id;
      try {
        final r = await _box(id).invoke('search', [
          q,
          {'type': type, 'page': 1, 'pageSize': 20},
        ], timeout: const Duration(seconds: 6));
        final items = (r is Map ? r['items'] : null) as List? ?? const [];
        by[id] = [for (final e in items) _toTrack(id, Map<String, dynamic>.from(e as Map))];
        _recordOk(id);
      } catch (e) {
        errs[id] = '$e';
        _recordFail(id, '$e');
      }
    }));
    final b = SearchBundle(bySource: by, errors: errs);
    if (errs.isEmpty) _searchCache[key] = (now, b);
    return b;
  }

  String _quality() => ref.read(settingsProvider).qualityKey;

  /// 播放地址：先用曲目所属插件；失败且开启「自动换源」时，用「曲名 + 艺人」到下一个启用的音源重试。
  Future<ResolvedStream?> resolveStream(Track t) async {
    final cached = _streamCache[t.id];
    final now = DateTime.now().millisecondsSinceEpoch;
    if (cached != null && cached.$1 > now) return cached.$2;
    if (!await _onWifiOrAllowed()) throw '已开启「仅 Wi-Fi 下使用在线音源」，当前不是 Wi-Fi 网络';

    final infoList = state.items;
    final own = infoList.where((e) => e.meta.id == t.source && e.enabled && !e.builtin).firstOrNull;
    String? firstError;
    if (own != null) {
      try {
        return await _getStream(own.meta.id, _fromTrack(t), t.id);
      } catch (e) {
        firstError = '$e';
        _recordFail(own.meta.id, firstError);
      }
    } else {
      firstError = '音源「${t.source}」未启用';
    }
    if (!ref.read(settingsProvider).fallback) throw firstError;

    // 自动换源
    final startIdx = infoList.indexWhere((e) => e.meta.id == t.source);
    final others = [
      for (var i = 0; i < infoList.length; i++)
        if (infoList[i].enabled && infoList[i].meta.id != t.source && (infoList[i].builtin || infoList[i].status != PStatus.fail))
          infoList[i],
    ]..sort((a, b) {
        // 排在原音源后面的优先，其次按列表顺序
        final ia = infoList.indexOf(a), ib = infoList.indexOf(b);
        final aa = ia > startIdx ? 0 : 1, bb = ib > startIdx ? 0 : 1;
        return aa != bb ? aa - bb : ia - ib;
      });
    final keyword = '${t.title} ${t.artist.split(' / ').first}'.trim();
    for (final o in others) {
      try {
        if (o.builtin) {
          final lib = ref.read(libraryProvider);
          final hit = lib.tracks.where((x) => _sameSong(x, t)).firstOrNull;
          if (hit?.path != null) {
            return ResolvedStream(url: Uri.file(hit!.path!).toString());
          }
          continue;
        }
        if (!o.meta.caps.contains('search') || !o.meta.caps.contains('stream')) continue;
        final r = await _box(o.meta.id).invoke('search', [keyword, {'type': 'track', 'page': 1, 'pageSize': 10}],
            timeout: const Duration(seconds: 6));
        final items = ((r is Map ? r['items'] : null) as List? ?? const [])
            .map((e) => _toTrack(o.meta.id, Map<String, dynamic>.from(e as Map)))
            .toList();
        final match = items.where((x) => _sameSong(x, t)).firstOrNull;
        if (match == null) continue;
        return await _getStream(o.meta.id, _fromTrack(match), t.id);
      } catch (e) {
        _recordFail(o.meta.id, '$e');
      }
    }
    throw firstError;
  }

  bool _sameSong(Track a, Track b) {
    String n(String s) => s.toLowerCase().replaceAll(RegExp(r'[\s\-_·・()（）\[\]]'), '');
    if (n(a.title) != n(b.title)) return false;
    if (a.durationMs > 0 && b.durationMs > 0 && (a.durationMs - b.durationMs).abs() > 3000) return false;
    return true;
  }

  Future<ResolvedStream> _getStream(String pluginId, Map<String, dynamic> track, String cacheKey) async {
    final r = await _box(pluginId).invoke('getStream', [track, {'quality': _quality()}]);
    if (r is! Map || r['url'] is! String || (r['url'] as String).isEmpty) throw '插件没有返回可播放地址';
    _recordOk(pluginId);
    final headers = (r['headers'] as Map?)?.map((k, v) => MapEntry('$k', '$v'));
    final rs = ResolvedStream(
      url: r['url'] as String,
      headers: headers,
      format: (r['format'] as String?)?.toUpperCase(),
      bitrate: (r['bitrate'] as num?)?.toInt(),
      sampleRate: (r['sampleRate'] as num?)?.toInt(),
      bitDepth: (r['bitDepth'] as num?)?.toInt(),
      quality: r['quality'] as String?,
    );
    final exp = (r['expiresAt'] as num?)?.toInt() ?? DateTime.now().millisecondsSinceEpoch + 10 * 60 * 1000;
    _streamCache[cacheKey] = (exp - 30000, rs);
    return rs;
  }

  /// 歌词：本地同名 .lrc 在 loadLyrics 里处理，这里负责来源插件（结果永久缓存）。
  /// 首页推荐：第一个声明了 recommend 且返回非空的在线音源。
  Future<List<Track>> recommend() async {
    if (!await _onWifiOrAllowed()) return const [];
    for (final info in state.activeOnline.where((e) => e.meta.caps.contains('recommend'))) {
      final id = info.meta.id;
      try {
        final r = await _box(id).invoke('recommend', const [], timeout: const Duration(seconds: 8));
        final items = (r is Map ? r['items'] : null) as List? ?? const [];
        if (items.isNotEmpty) {
          _recordOk(id);
          return [for (final e in items) _toTrack(id, Map<String, dynamic>.from(e as Map))];
        }
      } catch (e) {
        _recordFail(id, '$e');
      }
    }
    return const [];
  }

  // ---------- 云端列表：先给本地缓存，再后台刷新 ----------
  File _cacheFile(String key) => File(p.join(_dir.path, 'cache_${key.replaceAll(RegExp(r'[^A-Za-z0-9_.-]'), '_')}.json'));

  Future<dynamic> _readCache(String key) async {
    try {
      final f = _cacheFile(key);
      if (!await f.exists()) return null;
      final text = await f.readAsString();
      // 大列表（几百首）放到后台线程解析，不卡界面
      return text.length > 20000 ? await Isolate.run(() => jsonDecode(text)) : jsonDecode(text);
    } catch (_) {
      return null;
    }
  }

  Future<void> _writeCache(String key, Object data) async {
    try {
      await _cacheFile(key).writeAsString(jsonEncode(data));
    } catch (_) {}
  }

  Future<void> clearCloudCache() async {
    try {
      for (final e in _dir.listSync()) {
        if (e is File && p.basename(e.path).startsWith('cache_')) await e.delete();
      }
    } catch (_) {}
  }

  Future<void> _checkNet() async {
    if (!await _onWifiOrAllowed()) throw '已开启「仅 Wi-Fi 下使用在线音源」，当前不是 Wi-Fi 网络';
  }

  /// 直接调用插件里的函数（账号信息等）。
  Future<dynamic> call(String id, String fn, [List<Object?> args = const [], Duration timeout = const Duration(seconds: 15)]) =>
      _box(id).invoke(fn, args, timeout: timeout);

  Stream<List<Track>> _pagedTracks(String id, String key, String fn, List<Object?> head) async* {
    List<Track>? cached;
    final c = await _readCache(key);
    if (c is List) {
      try {
        cached = [for (final e in c) Track.fromJson(Map<String, dynamic>.from(e as Map))];
      } catch (_) {}
    }
    if (cached != null) yield cached;
    try {
      await _checkNet();
      final out = <Track>[];
      for (var page = 1; page <= 15; page++) {
        final r = await _box(id).invoke(fn, [
          ...head,
          {'page': page, 'pageSize': 200}
        ], timeout: const Duration(seconds: 20));
        final items = (r is Map ? r['items'] : null) as List? ?? const [];
        out.addAll([for (final e in items) _toTrack(id, Map<String, dynamic>.from(e as Map))]);
        final more = r is Map && r['hasMore'] == true && items.isNotEmpty;
        if (cached == null && more) yield List.of(out); // 没有缓存：边加载边显示
        if (!more) break;
      }
      yield out;
      unawaited(_writeCache(key, [for (final t in out) t.toJson()]));
    } catch (e) {
      if (cached == null) rethrow;
    }
  }

  Stream<List<Track>> likedStream(String id) => _pagedTracks(id, '${id}_liked', 'getLiked', const []);
  Stream<List<Track>> playlistStream(String id, String plId) => _pagedTracks(id, '${id}_pl_$plId', 'getPlaylist', [plId]);

  Stream<List<CloudPlaylist>> playlistsStream(String id) async* {
    final key = '${id}_lists';
    List<CloudPlaylist>? cached;
    final c = await _readCache(key);
    if (c is List) {
      try {
        cached = [for (final e in c) CloudPlaylist.fromJson(Map<String, dynamic>.from(e as Map))];
      } catch (_) {}
    }
    if (cached != null) yield cached;
    try {
      await _checkNet();
      final r = await _box(id).invoke('getPlaylists', const [], timeout: const Duration(seconds: 20));
      final list = [
        for (final e in (r as List? ?? const []))
          CloudPlaylist(
            plugin: id,
            id: '${(e as Map)['id']}',
            name: '${e['name'] ?? '未命名歌单'}',
            count: (e['count'] as num?)?.toInt() ?? 0,
            cover: e['cover'] as String?,
          )
      ];
      yield list;
      unawaited(_writeCache(key, [for (final e in list) e.toJson()]));
    } catch (e) {
      if (cached == null) rethrow;
    }
  }

  /// 登录 / 退出 / 换号之后：清缓存、刷新头像、让各列表重新加载。
  Future<void> accountChanged(String id) async {
    await clearCloudCache();
    ref.invalidate(cloudPlaylistsProvider);
    ref.invalidate(cloudLikedProvider);
    ref.invalidate(cloudPlaylistTracksProvider);
    await ref.read(cloudProfileProvider.notifier).refresh(id);
  }

  Future<void> logoutCloud(String id) async {
    try {
      await _box(id).invoke('logout', const []);
    } catch (_) {}
    await ref.read(cloudProfileProvider.notifier).clear();
    await clearCloudCache();
    ref.invalidate(cloudPlaylistsProvider);
    ref.invalidate(cloudLikedProvider);
    ref.invalidate(cloudPlaylistTracksProvider);
  }

  /// 把曲目喜欢 / 取消喜欢同步到云端，返回错误原因（成功为 null）。
  Future<String?> setCloudLike(Track t, bool like) async {
    try {
      final oid = t.id.startsWith('${t.source}:') ? t.id.substring(t.source.length + 1) : t.id;
      await _box(t.source).invoke('setLike', [
        {'id': oid, 'source': t.source},
        like
      ], timeout: const Duration(seconds: 10));
      return null;
    } catch (e) {
      return _short('$e');
    }
  }

  Future<List<LyricLine>> lyricsFor(Track t) async {
    if (t.isLocal || t.demo) return const [];
    final cached = _lyricCache[t.id];
    if (cached != null) return parseLrc(cached);
    final info = state.items.where((e) => e.meta.id == t.source && e.enabled).firstOrNull;
    if (info == null || !info.meta.caps.contains('lyric')) return const [];
    try {
      final r = await _box(t.source).invoke('getLyric', [_fromTrack(t)], timeout: const Duration(seconds: 8));
      if (r is Map && r['lrc'] is String) {
        var lrc = r['lrc'] as String;
        final tr = r['translation'] as String?;
        if (tr != null && tr.trim().isNotEmpty) lrc = '$lrc\n$tr';
        _lyricCache[t.id] = lrc;
        return parseLrc(lrc);
      }
    } catch (e) {
      log(t.source, '获取歌词失败：$e');
    }
    return const [];
  }
}

extension _Also<T> on T {
  T also(void Function(T) f) {
    f(this);
    return this;
  }
}

/// 首页「每日推荐」的在线内容；音源启用状态变化时重新获取。
final recommendProvider = FutureProvider<List<Track>>((ref) async {
  final key = ref.watch(pluginsProvider.select((s) => s.activeOnline.where((e) => e.meta.caps.contains('recommend')).map((e) => e.meta.id).join(',')));
  if (key.isEmpty) return const [];
  return ref.read(pluginsProvider.notifier).recommend();
});

/// 云端「我喜欢」列表（按插件 id）：先给缓存，再边加载边显示。
final cloudLikedProvider = StreamProvider.family<List<Track>, String>((ref, id) {
  return ref.read(pluginsProvider.notifier).likedStream(id);
});

/// 已启用且声明了 liked 的在线音源。
final likedPluginsProvider = Provider<List<PluginInfo>>((ref) {
  final st = ref.watch(pluginsProvider);
  return st.activeOnline.where((e) => e.meta.caps.contains('liked')).toList();
});

/// 云端已喜欢的曲目 id 集合（只统计已经加载完成的列表）。
final cloudLikedIdsProvider = Provider<Set<String>>((ref) {
  final out = <String>{};
  for (final p in ref.watch(likedPluginsProvider)) {
    final v = ref.watch(cloudLikedProvider(p.meta.id)).value;
    if (v != null) out.addAll(v.map((t) => t.id));
  }
  return out;
});

class CloudPlaylist {
  const CloudPlaylist({required this.plugin, required this.id, required this.name, required this.count, this.cover});
  final String plugin, id, name;
  final int count;
  final String? cover;
  String get key => '$plugin#$id';
  Map<String, dynamic> toJson() => {'plugin': plugin, 'id': id, 'name': name, 'count': count, 'cover': cover};
  factory CloudPlaylist.fromJson(Map<String, dynamic> j) => CloudPlaylist(
      plugin: '${j['plugin']}', id: '${j['id']}', name: '${j['name']}', count: (j['count'] as num?)?.toInt() ?? 0, cover: j['cover'] as String?);
}

/// 声明了 playlist 的在线音源。
final playlistPluginsProvider = Provider<List<PluginInfo>>((ref) {
  return ref.watch(pluginsProvider).activeOnline.where((e) => e.meta.caps.contains('playlist')).toList();
});

/// 账号歌单列表（按插件 id）。
final cloudPlaylistsProvider = StreamProvider.family<List<CloudPlaylist>, String>((ref, id) {
  return ref.read(pluginsProvider.notifier).playlistsStream(id);
});

/// 某个云端歌单里的曲目，key = "插件id#歌单id"。
final cloudPlaylistTracksProvider = StreamProvider.autoDispose.family<List<Track>, String>((ref, key) {
  final i = key.indexOf('#');
  return ref.read(pluginsProvider.notifier).playlistStream(key.substring(0, i), key.substring(i + 1));
});

/// 登录账号的昵称与头像（随登录状态变化，持久化在本地）。
class CloudProfile {
  const CloudProfile({required this.plugin, required this.name, required this.avatar});
  final String plugin, name, avatar;
  Map<String, dynamic> toJson() => {'plugin': plugin, 'name': name, 'avatar': avatar};
  factory CloudProfile.fromJson(Map<String, dynamic> j) =>
      CloudProfile(plugin: '${j['plugin']}', name: '${j['name'] ?? ''}', avatar: '${j['avatar'] ?? ''}');
}

final cloudProfileProvider = NotifierProvider<CloudProfileNotifier, CloudProfile?>(CloudProfileNotifier.new);

class CloudProfileNotifier extends Notifier<CloudProfile?> {
  @override
  CloudProfile? build() {
    Future.microtask(_init);
    return null;
  }

  Future<File> _file() async => File(p.join((await getApplicationDocumentsDirectory()).path, 'plugins', 'profile.json'));

  Future<void> _init() async {
    try {
      final f = await _file();
      if (await f.exists()) {
        state = CloudProfile.fromJson(Map<String, dynamic>.from(jsonDecode(await f.readAsString()) as Map));
        _prefetch(state!.avatar);
      }
    } catch (_) {}
    final cur = state;
    if (cur != null) unawaited(refresh(cur.plugin)); // 悄悄刷新一次，头像换了也能跟上
  }

  Future<void> refresh(String id) async {
    for (var i = 0; i < 50 && !ref.read(pluginsProvider).loaded; i++) {
      await Future.delayed(const Duration(milliseconds: 200));
    }
    try {
      final r = await ref.read(pluginsProvider.notifier).call(id, 'getProfile');
      if (r is Map) {
        final cp = CloudProfile(plugin: id, name: '${r['name'] ?? ''}', avatar: '${r['avatar'] ?? ''}');
        state = cp;
        _prefetch(cp.avatar);
        try {
          await (await _file()).writeAsString(jsonEncode(cp.toJson()));
        } catch (_) {}
      } else {
        await clear();
      }
    } catch (e) {
      if ('$e'.contains('登录')) await clear();
    }
  }

  /// 头像统一取 256 档并提前下好，顶栏和设置页用同一份，不会出现蓝块等几秒
  static String avatarUrl(String u) {
    if (!u.contains('music.126.net')) return u;
    final base = u.split('?').first;
    return base.contains('/obj/') ? '$base?imageView=1&thumbnail=256x256&type=webp' : '$base?param=256y256';
  }
  void _prefetch(String u) {
    if (u.isEmpty) return;
    unawaited(imageCacheManager.downloadFile(avatarUrl(u), authHeaders: imageHeaders).then((_) {}, onError: (_) {}));
  }

  Future<void> clear() async {
    state = null;
    try {
      final f = await _file();
      if (await f.exists()) await f.delete();
    } catch (_) {}
  }
}

/// 「我喜欢」入口：登录了网易云就指向云端列表（带缓存的真实数量），否则指向本地。
final likedEntryProvider = Provider<({ListRef ref, int? count, int ms})?>((ref) {
  if (ref.watch(cloudProfileProvider) == null) return null;
  final p = ref.watch(likedPluginsProvider).firstOrNull;
  if (p == null) return null;
  final v = ref.watch(cloudLikedProvider(p.meta.id)).value;
  return (ref: ListRef(ListKind.cloud, p.meta.id), count: v?.length, ms: v == null ? 0 : v.fold<int>(0, (a, t) => a + t.durationMs));
});
