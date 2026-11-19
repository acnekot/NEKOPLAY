import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/icons.dart';
import '../../core/theme.dart';
import '../../data/library.dart';
import '../../data/track.dart';
import '../../nav/nav.dart';
import '../../plugins/plugin_manager.dart';
import '../../state.dart';
import '../actions.dart';
import '../widgets.dart';

class SearchPage extends ConsumerStatefulWidget {
  const SearchPage({super.key});
  @override
  ConsumerState<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends ConsumerState<SearchPage> {
  final _ctl = TextEditingController();
  final _focus = FocusNode();
  Timer? _debounce;
  String _q = '';
  String _filter = 'all';
  SearchBundle? _bundle;
  bool _loading = false;
  int _gen = 0;

  @override
  void dispose() {
    _debounce?.cancel();
    _ctl.dispose();
    _focus.dispose();
    super.dispose();
  }

  void _onChanged(String v) {
    setState(() {
      _q = v;
      _bundle = null;
    });
    _debounce?.cancel();
    if (v.trim().isEmpty) {
      setState(() => _loading = false);
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 450), _runOnline);
  }

  Future<void> _runOnline() async {
    final q = _q.trim();
    if (q.isEmpty) return;
    final plugins = ref.read(pluginsProvider.notifier);
    final gen = ++_gen;
    setState(() => _loading = true);
    final b = await plugins.search(q);
    if (!mounted || gen != _gen) return;
    setState(() {
      _bundle = b;
      _loading = false;
    });
  }

  List<Track> _local(LibraryState lib) {
    final q = _q.trim().toLowerCase();
    if (q.isEmpty) return const [];
    final lossless = q == '无损' || q == 'flac';
    return lib.all.where((t) => lossless ? t.lossless : '${t.title} ${t.artist} ${t.album}'.toLowerCase().contains(q)).toList();
  }

  void _submit() {
    final q = _q.trim();
    if (q.isNotEmpty) ref.read(libraryProvider.notifier).addHistory(q);
    _debounce?.cancel();
    _runOnline();
    _focus.unfocus();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final lib = ref.watch(libraryProvider);
    final plugins = ref.watch(pluginsProvider);
    final cur = ref.watch(playerProvider.select((s) => s.current));
    final nav = ref.read(navProvider.notifier);
    final hasQuery = _q.trim().isNotEmpty;
    final local = _local(lib);
    final online = plugins.activeOnline.where((e) => e.meta.caps.contains('search')).toList();
    final names = {for (final p in plugins.items) p.meta.id: p.meta.name};

    // 热门搜索：曲库里出现最多的艺人（示例数据时用示例词）
    final counts = <String, int>{};
    for (final t in lib.all) {
      counts[t.albumArtist] = (counts[t.albumArtist] ?? 0) + 1;
    }
    final trending = (counts.entries.toList()..sort((a, b) => b.value.compareTo(a.value))).map((e) => e.key).take(6).toList();

    final bundle = _bundle;
    final rows = <({Track t, String? label, List<Track> queue})>[];
    if (_filter == 'all' || _filter == 'local') {
      for (final t in local) {
        rows.add((t: t, label: null, queue: local));
      }
    }
    if (bundle != null) {
      for (final e in bundle.bySource.entries) {
        if (_filter == 'all' || _filter == e.key) {
          for (final t in e.value) {
            rows.add((t: t, label: names[e.key] ?? e.key, queue: e.value));
          }
        }
      }
    }

    return Column(children: [
      // ---- 搜索框 + 取消
      SizedBox(
        height: 48,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 18),
          child: Row(children: [
            Expanded(
              child: CustomPaint(
                painter: _BracketPainter(c.k),
                child: Container(
                  height: 44,
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  alignment: Alignment.center,
                  child: TextField(
                    controller: _ctl,
                    focusNode: _focus,
                    autofocus: true,
                    onChanged: _onChanged,
                    onSubmitted: (_) => _submit(),
                    textInputAction: TextInputAction.search,
                    cursorColor: c.p,
                    style: NText.mono(13, color: c.k, ls: .08),
                    decoration: InputDecoration(
                      border: InputBorder.none,
                      isCollapsed: true,
                      hintText: 'SEARCH',
                      hintStyle: NText.mono(13, color: c.s, ls: .08),
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Tap(
              label: '取消',
              minSize: 44,
              onTap: () {
                _focus.unfocus();
                nav.back();
              },
              child: Center(widthFactor: 1, child: Text('取消', style: NText.sans(15, color: c.k))),
            ),
          ]),
        ),
      ),
      Container(height: 1, color: c.l2),
      Expanded(
        child: !hasQuery
            ? ListView(padding: const EdgeInsets.fromLTRB(18, 18, 18, 24), children: [
                TagRow('HISTORY · 最近搜索',
                    lineEnd: 12, trailing: lib.history.isEmpty ? null : LinkBtn('清空', size: 14, onTap: ref.read(libraryProvider.notifier).clearHistory)),
                if (lib.history.isEmpty)
                  Padding(padding: const EdgeInsets.symmetric(vertical: 18), child: Text('NO HISTORY · 暂无搜索记录', style: NText.mono(12, color: c.s, ls: .08))),
                for (final h in lib.history)
                  Container(
                    height: 48,
                    decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
                    child: Row(children: [
                      Expanded(
                        child: Tap(
                          label: '搜索 $h',
                          onTap: () {
                            _ctl.text = h;
                            _onChanged(h);
                          },
                          child: Align(
                            alignment: Alignment.centerLeft,
                            child: Row(children: [
                              NIcon(NIcons.clockSmall, size: 14, color: c.s),
                              const SizedBox(width: 10),
                              Expanded(child: Text(h, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(16, color: c.k))),
                            ]),
                          ),
                        ),
                      ),
                      Tap(
                        label: '删除这条记录',
                        onTap: () => ref.read(libraryProvider.notifier).removeHistory(h),
                        child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(NIcons.close, size: 12, color: c.k))),
                      ),
                    ]),
                  ),
                if (trending.isNotEmpty) ...[
                const SizedBox(height: 26),
                const TagRow('TRENDING · 热门搜索'),
                const SizedBox(height: 12),
                Wrap(spacing: 8, runSpacing: 8, children: [
                  for (final t in trending)
                    Tap(
                      label: '搜索 $t',
                      onTap: () {
                        _ctl.text = t;
                        _onChanged(t);
                      },
                      child: Container(
                        height: 36,
                        padding: const EdgeInsets.symmetric(horizontal: 14),
                        alignment: Alignment.center,
                        decoration: BoxDecoration(border: Border.all(color: c.k)),
                        child: Text(t, style: NText.sans(14, color: c.k)),
                      ),
                    ),
                ]),
                ],
              ])
            : ListView(padding: const EdgeInsets.fromLTRB(18, 14, 18, 24), children: [
                if (online.isNotEmpty || (bundle?.bySource.isNotEmpty ?? false))
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(children: [
                        _chip(c, '全部', 'all'),
                        _chip(c, '本地曲库', 'local'),
                        for (final p in online) _chip(c, p.meta.name, p.meta.id),
                      ]),
                    ),
                  ),
                if (bundle?.blockedByWifi ?? false)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Text('已开启「仅 Wi-Fi 下使用在线音源」，当前网络下只显示本地曲库。', style: NText.sans(13, color: c.mute, height: 1.5)),
                  ),
                if (_loading)
                  Padding(padding: const EdgeInsets.symmetric(vertical: 10), child: Text('SEARCHING · 正在搜索在线音源…', style: NText.mono(12, color: c.s, ls: .08))),
                if (bundle != null)
                  for (final e in bundle.errors.entries)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Text('${names[e.key] ?? e.key}：搜索失败（${e.value.length > 30 ? '${e.value.substring(0, 30)}…' : e.value}）',
                          style: NText.sans(12, color: c.bad)),
                    ),
                if (rows.isEmpty && !_loading)
                  Padding(padding: const EdgeInsets.symmetric(vertical: 28), child: Text('NO RESULTS · 没有找到相关曲目', style: NText.mono(13, color: c.s, ls: .08))),
                for (var i = 0; i < rows.length; i++)
                  TrackRow(
                    track: rows[i].t,
                    sourceLabel: rows[i].label,
                    current: cur?.id == rows[i].t.id,
                    onTap: () {
                      ref.read(libraryProvider.notifier).addHistory(_q);
                      final r = rows[i];
                      playList(ref, r.queue, r.queue.indexOf(r.t));
                      nav.go(Pg.now);
                    },
                    onLong: () => showTrackActions(context, ref, rows[i].t),
                  ),
              ]),
      ),
    ]);
  }

  Widget _chip(NekoColors c, String label, String id) {
    final on = _filter == id;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Tap(
        label: '筛选：$label',
        onTap: () => setState(() => _filter = id),
        child: Container(
          height: 36,
          padding: const EdgeInsets.symmetric(horizontal: 14),
          alignment: Alignment.center,
          decoration: BoxDecoration(color: on ? c.k : null, border: Border.all(color: c.k)),
          child: Text(label, style: NText.sans(13, w: on ? FontWeight.w700 : FontWeight.w400, color: on ? c.tagFg : c.k)),
        ),
      ),
    );
  }
}

/// 搜索框的四角方括号造型
class _BracketPainter extends CustomPainter {
  _BracketPainter(this.color);
  final Color color;
  @override
  void paint(Canvas canvas, Size s) {
    final p = Paint()..color = color..strokeWidth = 2..style = PaintingStyle.stroke;
    const l = 8.0;
    void corner(double x, double y, double dx, double dy) {
      canvas.drawLine(Offset(x, y + dy * 1), Offset(x + dx * l, y + dy * 1), p);
      canvas.drawLine(Offset(x + dx * 1, y), Offset(x + dx * 1, y + dy * l), p);
    }

    corner(0, 0, 1, 1);
    corner(s.width, 0, -1, 1);
    corner(0, s.height, 1, -1);
    corner(s.width, s.height, -1, -1);
  }

  @override
  bool shouldRepaint(_BracketPainter o) => o.color != color;
}
