/// 插件元数据（见 SPEC_PLUGIN.md 第 1 节）。
class PluginMeta {
  PluginMeta({
    required this.id,
    required this.name,
    required this.version,
    required this.author,
    required this.api,
    required this.hosts,
    required this.caps,
    this.update,
    this.builtin = false,
    this.desc,
  });

  final String id, name, version, author;
  final int api;
  final List<String> hosts;
  final Set<String> caps; // search, stream, lyric, playlist, login
  final String? update;
  final bool builtin;
  final String? desc;

  static const allCaps = {'search', 'stream', 'lyric', 'playlist', 'recommend', 'liked', 'login'};

  static final local = PluginMeta(
    id: 'local',
    name: '本地曲库',
    version: '内置',
    author: '读取手机里的音乐文件',
    api: 1,
    hosts: const [],
    caps: const {'search', 'stream', 'lyric'},
    builtin: true,
  );

  String get badge => name.isEmpty ? '?' : String.fromCharCode(name.runes.first).toUpperCase();
  String get capsLine => ['search', 'stream', 'lyric', 'playlist', 'recommend', 'liked', 'login']
      .where(caps.contains)
      .map((e) => e.toUpperCase())
      .join(' · ');

  bool allowsHost(String host) {
    final h = host.toLowerCase();
    for (final p in hosts) {
      final pat = p.toLowerCase();
      if (pat == '*') return true;
      if (pat.startsWith('*.')) {
        final base = pat.substring(2);
        if (h == base || h.endsWith('.$base')) return true;
      } else if (h == pat) {
        return true;
      }
    }
    return false;
  }

  /// 解析代码顶部的 // ==NekoPlugin== 块。失败时抛出中文原因。
  static PluginMeta parse(String code) {
    final start = code.indexOf('==NekoPlugin==');
    final end = code.indexOf('==/NekoPlugin==');
    if (start < 0 || end < 0 || end < start) throw '缺少 ==NekoPlugin== 元数据块';
    final block = code.substring(start + '==NekoPlugin=='.length, end);
    final map = <String, String>{};
    for (final raw in block.split('\n')) {
      final m = RegExp(r'^\s*(?://)?\s*@(\w+)\s+(.*?)\s*$').firstMatch(raw);
      if (m == null) continue;
      var v = m.group(2)!;
      v = v.replaceFirst(RegExp(r'\s+[（(].*[）)]\s*$'), '').trim();
      map[m.group(1)!.toLowerCase()] = v;
    }
    String need(String k) {
      final v = map[k];
      if (v == null || v.isEmpty) throw '元数据缺少 @$k';
      return v;
    }

    final id = need('id');
    if (!RegExp(r'^[a-z0-9-]{1,40}$').hasMatch(id)) throw '@id 只能用小写字母、数字和 -';
    if (id == 'local') throw '@id 不能是 local';
    final version = need('version');
    if (!RegExp(r'^\d+(\.\d+){0,2}').hasMatch(version)) throw '@version 需要是 x.y.z 形式';
    final api = int.tryParse(need('api')) ?? -1;
    if (api != 1) throw '插件接口版本 @api $api 与应用不匹配（需要 1）';
    final hosts = (map['hosts'] ?? '')
        .split(RegExp(r'[,，\s]+'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList();
    final caps = (map['caps'] ?? '')
        .split(RegExp(r'[,，\s]+'))
        .map((e) => e.trim().toLowerCase())
        .where((e) => e.isNotEmpty)
        .toSet();
    if (caps.isEmpty) throw '@caps 不能为空';
    final unknown = caps.difference(allCaps);
    if (unknown.isNotEmpty) throw '未知能力：${unknown.join(', ')}';
    return PluginMeta(
      id: id,
      name: need('name'),
      version: version,
      author: map['author'] ?? '社区插件',
      api: api,
      hosts: hosts,
      caps: caps,
      update: map['update'],
    );
  }
}

/// 简单的语义化版本比较：a > b ?
bool versionGreater(String a, String b) {
  List<int> p(String v) => v.split(RegExp(r'[.\-+]')).map((e) => int.tryParse(e) ?? 0).toList();
  final x = p(a), y = p(b);
  for (var i = 0; i < 3; i++) {
    final xi = i < x.length ? x[i] : 0, yi = i < y.length ? y[i] : 0;
    if (xi != yi) return xi > yi;
  }
  return false;
}

enum PStatus { unverified, checking, ok, fail }

class PluginInfo {
  PluginInfo({
    required this.meta,
    this.enabled = false,
    this.status = PStatus.unverified,
    this.ms = 0,
    this.error = '',
    this.fails = 0,
  });

  PluginMeta meta;
  bool enabled;
  PStatus status;
  int ms;
  String error;
  int fails;

  bool get builtin => meta.builtin;

  /// 随应用内置的在线音源（界面上按原生功能呈现，不显示插件细节）。
  static const bundledIds = {'ncm-direct'};
  bool get bundled => bundledIds.contains(meta.id);

  PluginInfo copy() => PluginInfo(meta: meta, enabled: enabled, status: status, ms: ms, error: error, fails: fails);
}

class UpdateOffer {
  UpdateOffer(this.code, this.meta, this.newHosts);
  final String code;
  final PluginMeta meta;
  final List<String> newHosts;
}
