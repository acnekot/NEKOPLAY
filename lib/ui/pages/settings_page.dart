import 'dart:async';
import 'dart:io';
import 'dart:isolate';

import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/icons.dart';
import '../../core/perf.dart';
import '../../core/theme.dart';
import '../../data/library.dart';
import '../../data/settings.dart';
import '../../nav/nav.dart';
import '../../plugins/plugin_manager.dart';
import '../../plugins/plugin_models.dart';
import '../actions.dart';
import '../widgets.dart';

class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.c;
    final s = ref.watch(settingsProvider);
    final n = ref.read(settingsProvider.notifier);
    final plugins = ref.watch(pluginsProvider);
    final lib = ref.watch(libraryProvider);
    final sysDark = MediaQuery.platformBrightnessOf(context) == Brightness.dark;
    final followSys = s.theme == ThemePref.system;
    final darkOn = followSys ? sysDark : s.theme == ThemePref.dark;

    return ListView(
      key: const PageStorageKey('settings'),
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 28),
      children: [
        _group(context, 'ACCOUNT'),
        Container(
          padding: const EdgeInsets.only(bottom: 16),
          decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.k, width: 2))),
          child: Row(children: [
            const AvatarBox(size: 54, fontSize: 24),
            const SizedBox(width: 16),
            Expanded(
              child: Tap(
                label: '修改昵称',
                onTap: () async {
                  final v = await promptDialog(context, title: '修改昵称', initial: s.name, hint: '昵称');
                  if (v != null && v.trim().isNotEmpty) n.update((x) => x.copyWith(name: v.trim()));
                },
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(s.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(20, w: FontWeight.w800, color: c.k)),
                  Text(ref.watch(cloudProfileProvider) == null ? 'LOCAL ACCOUNT' : 'NETEASE CLOUD MUSIC', style: NText.mono(11, color: c.s, ls: .08)),
                ]),
              ),
            ),
          ]),
        ),

        for (final bp in plugins.items.where((e) => e.bundled)) _cloudAccountRow(context, ref, bp),

        _group(context, 'APPEARANCE'),
        _switchRow(context, '深色模式', '切换为暗色界面', darkOn, enabled: !followSys,
            onChanged: (v) => n.update((x) => x.copyWith(theme: v ? ThemePref.dark : ThemePref.light))),
        _switchRow(context, '跟随系统', '跟随手机的深浅色设置', followSys,
            onChanged: (v) => n.update((x) => x.copyWith(theme: v ? ThemePref.system : (sysDark ? ThemePref.dark : ThemePref.light)))),

        _group(context, 'LIBRARY'),
        _navRow(context, '导入音乐文件', '选择本地音频，可同时选同名 .lrc 歌词', value: '${lib.tracks.length} 首', onTap: () => importMusic(context, ref)),
        if (lib.tracks.isNotEmpty)
          _navRow(context, '清空已导入的曲库', '删除应用内保存的音频副本，不影响原文件', danger: true, onTap: () async {
            final ok = await confirmDialog(context, title: '清空曲库', body: '会删除应用内保存的全部音频、封面和歌词副本，歌单里的曲目也会一并移除。', ok: '清空', danger: true);
            if (ok) await ref.read(libraryProvider.notifier).clearLibrary();
          }),

        _group(context, 'SOURCES'),
        _navRow(context, '音源插件', '接入在线曲库，搜索和播放更多歌曲',
            value: '${plugins.enabledCount} 个已启用', onTap: () => ref.read(navProvider.notifier).go(Pg.plugins)),

        _group(context, 'PLAYBACK'),
        _navRow(context, '音质', null, value: s.qualityLabel,
            onTap: () => n.update((x) => x.copyWith(quality: (x.quality + 1) % kQualityLabels.length))),
        _switchRow(context, '淡入淡出', '切歌时音量渐变', s.fade, onChanged: (v) => n.update((x) => x.copyWith(fade: v))),

        _group(context, 'SPECTRUM'),
        _switchRow(context, '显示频谱', '仅在正在播放页（桌面）', s.spectrum, onChanged: (v) => n.update((x) => x.copyWith(spectrum: v))),
        _navRow(context, '样式', null, value: const ['柱状', '波形', '圆环'][s.spectrumStyle],
            onTap: () => n.update((x) => x.copyWith(spectrumStyle: (x.spectrumStyle + 1) % 3))),

        _group(context, 'PERFORMANCE'),
        ValueListenableBuilder(valueListenable: Perf.stat, builder: (context, st, _) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Text(Perf.line(st), style: NText.mono(11, color: c.s, height: 1.5)),
            )),
        ValueListenableBuilder(valueListenable: Perf.overlay, builder: (context, on, _) => _switchRow(context, '性能浮窗', '在所有页面左下角显示 CPU、帧率和 GPU', on, onChanged: (v) {
              Perf.overlay.value = v;
              ref.read(prefsProvider).setBool('perf_overlay', v);
            })),

        _group(context, 'MOTION'),
        StatefulBuilder(builder: (context, set) => _navRow(context, '动效速度', '页面切换、播放页、队列的动画快慢', value: animLabels[animLevel], onTap: () {
              set(() => animLevel = (animLevel + 1) % 4);
              ref.read(prefsProvider).setInt('anim_speed', animLevel);
            })),

        _group(context, 'ABOUT'),
        _navRow(context, '版本', null, value: '1.0.0'),
        const _MemoryRow(),
      ],
    );
  }

  Widget _group(BuildContext context, String tag) => Padding(
        padding: const EdgeInsets.only(top: 22, bottom: 8),
        child: Align(alignment: Alignment.centerLeft, child: Tag(tag)),
      );

  Widget _switchRow(BuildContext context, String title, String? sub, bool on,
      {required ValueChanged<bool> onChanged, bool enabled = true}) {
    final c = context.c;
    return Container(
      constraints: const BoxConstraints(minHeight: 56),
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: NText.sans(16, color: enabled ? c.k : c.s)),
            if (sub != null) Text(sub, style: NText.sans(13, color: c.s, height: 1.4)),
          ]),
        ),
        NSwitch(on: on, label: title, enabled: enabled, onChanged: onChanged),
      ]),
    );
  }

  Widget _navRow(BuildContext context, String title, String? sub, {String? value, VoidCallback? onTap, bool danger = false}) {
    final c = context.c;
    return Tap(
      label: title,
      onTap: onTap,
      child: Container(
        constraints: const BoxConstraints(minHeight: 56),
        padding: const EdgeInsets.symmetric(vertical: 8),
        decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
        child: Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: NText.sans(16, color: danger ? c.bad : c.k)),
              if (sub != null) Text(sub, style: NText.sans(13, color: c.s, height: 1.4)),
            ]),
          ),
          if (value != null) Text(value, style: NText.sans(13, color: c.s)),
          if (onTap != null) ...[const SizedBox(width: 6), NIcon(NIcons.chevronRight, size: 16, color: c.s)],
        ]),
      ),
    );
  }
}

/// 云端音乐账号（网易云音乐）：登录状态、登录 / 退出。
Widget _cloudAccountRow(BuildContext context, WidgetRef ref, PluginInfo bp) {
  final c = context.c;
  final prof = ref.watch(cloudProfileProvider);
  final mgr = ref.read(pluginsProvider.notifier);
  final toast = ref.read(toastProvider.notifier);
  return Container(
    margin: const EdgeInsets.only(top: 14),
    padding: const EdgeInsets.only(bottom: 14),
    decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
    child: Row(children: [
      Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(bp.meta.name, style: NText.sans(16, w: FontWeight.w700, color: c.k)),
          Text(prof == null ? '未登录 · 登录后可使用歌单和「我喜欢」' : '已登录 · ${prof.name}',
              maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(13, color: c.s, height: 1.4)),
        ]),
      ),
      if (prof == null)
        LinkBtn('登录', size: 14, onTap: () async {
          final r = await mgr.login(bp.meta.id);
          toast.show(r.error == null ? (r.message ?? '已完成') : '失败：${r.error}');
        })
      else
        LinkBtn('退出登录', color: c.bad, size: 14, onTap: () async {
          final ok = await confirmDialog(context, title: '退出网易云音乐', body: '会清除这台设备上保存的登录信息，头像恢复为本地头像。', ok: '退出', danger: true);
          if (ok) {
            await mgr.logoutCloud(bp.meta.id);
            toast.show('已退出网易云音乐');
          }
        }),
    ]),
  );
}


/// 实时内存读数：进程占用（RSS）和图片缓存，方便在手机上直接看到占用情况。
class _MemoryRow extends ConsumerStatefulWidget {
  const _MemoryRow();
  @override
  ConsumerState<_MemoryRow> createState() => _MemoryRowState();
}

class _MemoryRowState extends ConsumerState<_MemoryRow> {
  Timer? _t;
  int _peak = 0;
  String _analysis = '';

  @override
  void initState() {
    super.initState();
    _t = Timer.periodic(const Duration(seconds: 2), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _t?.cancel();
    super.dispose();
  }

  String _mb(int b) => '${(b / 1048576).toStringAsFixed(1)} MB';

  /// PSS（按共享比例折算，Android 设置里「内存使用」看的就是它）和分项
  String _detail() {
    try {
      final m = <String, int>{};
      for (final l in File('/proc/self/smaps_rollup').readAsLinesSync()) {
        final p = l.split(RegExp(r'\s+'));
        if (p.length >= 2 && p[0].endsWith(':')) m[p[0]] = int.tryParse(p[1]) ?? 0;
      }
      String k(String n) => '${((m[n] ?? 0) / 1024).toStringAsFixed(0)}M';
      final e = imageErrors.value;
      return '实占(PSS) ${k('Pss:')} · 匿名 ${k('Pss_Anon:')} · 文件 ${k('Pss_File:')}'
          '${e.count > 0 ? '\n图片失败 ${e.count} 次：${e.last}' : ''}';
    } catch (_) {
      return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final rss = ProcessInfo.currentRss;
    if (rss > _peak) _peak = rss;
    final ic = PaintingBinding.instance.imageCache;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('内存占用', style: NText.sans(16, color: c.k)),
            Text('进程 ${_mb(rss)}（峰值 ${_mb(_peak)}）· 图片缓存 ${_mb(ic.currentSizeBytes)}',
                style: NText.sans(13, color: c.s, height: 1.4)),
            if (_detail().isNotEmpty) Text(_detail(), style: NText.mono(11, color: c.s, height: 1.4)),
            if (_analysis.isNotEmpty) Text(_analysis, style: NText.mono(11, color: c.k, height: 1.4)),
          ]),
        ),
        Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.end, children: [
        LinkBtn('分析', size: 14, onTap: () async {
          final r = await Isolate.run(_smapsBreakdown);
          if (mounted) setState(() => _analysis = r);
        }),
        LinkBtn('清理缓存', size: 14, onTap: () async {
          ic.clear();
          ic.clearLiveImages();
          await DefaultCacheManager().emptyCache();
          await imageCacheManager.emptyCache();
          imageErrors.value = (count: 0, last: '', url: '');
          ref.read(toastProvider.notifier).show('已清理图片缓存');
          if (mounted) setState(() {});
        }),
        ]),
      ]),
    );
  }
}


/// 逐段读取 /proc/self/smaps，按用途把实占内存（PSS）分类，找出大头。
String _smapsBreakdown() {
  final sum = <String, int>{};
  String cat = '其他';
  for (final l in File('/proc/self/smaps').readAsLinesSync()) {
    if (RegExp(r'^[0-9a-f]+-[0-9a-f]+ ').hasMatch(l)) {
      final parts = l.split(RegExp(r'\s+'));
      final name = parts.length >= 6 ? parts.sublist(5).join(' ') : '';
      final n = name.toLowerCase();
      if (n.contains('dart')) {
        cat = 'Dart 堆';
      } else if (n.contains('dalvik') || n.contains('.art') || n.contains('jit')) {
        cat = 'Java/ART';
      } else if (n.contains('kgsl') || n.contains('mali') || n.contains('dmabuf') || n.contains('gpu') || n.contains('/dev/')) {
        cat = '显存/驱动';
      } else if (n.contains('scudo') || n.contains('malloc') || n.contains('jemalloc')) {
        cat = '原生堆(Skia/JS/解码)';
      } else if (n.contains('stack')) {
        cat = '线程栈';
      } else if (n.startsWith('/')) {
        cat = '文件映射';
      } else if (n.isEmpty) {
        cat = '匿名(未命名)';
      } else {
        cat = '其他 ${name.length > 24 ? name.substring(0, 24) : name}';
      }
    } else if (l.startsWith('Pss:')) {
      final kb = int.tryParse(l.split(RegExp(r'\s+'))[1]) ?? 0;
      sum[cat] = (sum[cat] ?? 0) + kb;
    }
  }
  final top = sum.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
  return top.take(7).map((e) => '${e.key} ${(e.value / 1024).toStringAsFixed(0)}M').join('\n');
}
