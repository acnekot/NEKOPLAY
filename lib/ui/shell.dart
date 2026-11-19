import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/icons.dart';
import '../core/theme.dart';
import '../data/settings.dart';
import '../nav/nav.dart';
import '../plugins/plugin_manager.dart';
import '../state.dart';
import 'actions.dart';
import 'mini_bar.dart';
import 'pages/home_page.dart';
import 'pages/library_page.dart';
import 'pages/plugins_page.dart';
import 'pages/search_page.dart';
import 'pages/settings_page.dart';
import 'player_page.dart';
import 'widgets.dart';

class Shell extends ConsumerStatefulWidget {
  const Shell({super.key});
  @override
  ConsumerState<Shell> createState() => _ShellState();
}

class _ShellState extends ConsumerState<Shell> with TickerProviderStateMixin {
  // 首页 ⇄ 曲库：整页横向滑动
  late final AnimationController _pager = AnimationController(vsync: this, duration: const Duration(milliseconds: 400));
  // 子页面从右侧滑入
  late final AnimationController _sub = AnimationController(vsync: this, duration: const Duration(milliseconds: 360));
  Pg _subPage = Pg.settings;
  bool _libBuilt = false;
  String _toast = '';
  int _toastSeq = 0;
  Timer? _toastTimer;

  @override
  void dispose() {
    _pager.dispose();
    _sub.dispose();
    _toastTimer?.cancel();
    super.dispose();
  }

  void _syncNav(NavState n) {
    final base = n.base;
    if (base == Pg.library) {
      if (!_libBuilt) setState(() => _libBuilt = true);
      _pager.springTo(1);
    } else if (base == Pg.home) {
      _pager.springTo(0);
    }
    if (n.isSub) {
      if (_subPage != base) setState(() => _subPage = base);
      _sub.springTo(1);
    } else {
      _sub.springTo(0);
    }
  }

  void _showToast(String m) {
    if (m.isEmpty) return;
    setState(() {
      _toast = m;
      _toastSeq++;
    });
    _toastTimer?.cancel();
    _toastTimer = Timer(const Duration(milliseconds: 2200), () {
      if (mounted) setState(() => _toast = '');
    });
  }

  @override
  void initState() {
    super.initState();
    final pm = ref.read(pluginsProvider.notifier);
    pm.promptUi = (plugin, fields) => formDialog(context, title: plugin, fields: fields);
    pm.uiHandler = (plugin, req) async {
      switch (req['kind']) {
        case 'choose':
          return chooseDialog(context, title: '${req['title'] ?? plugin}', options: [for (final o in (req['options'] as List)) '$o']);
        case 'qr':
          return qrDialog(context, title: '${req['title'] ?? plugin}', text: '${req['text'] ?? ''}', hint: '${req['hint'] ?? ''}', confirm: '${req['confirm'] ?? '确认'}');
      }
      return null;
    };
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<NavState>(navProvider, (p, n) {
      if (p == null || p.base != n.base || p.isSub != n.isSub || p.list != n.list) _syncNav(n);
    });
    ref.listen(toastProvider, (_, t) => _showToast(t.msg));
    ref.listen(playerProvider.select((s) => s.errorSeq), (_, _) {
      final e = ref.read(playerProvider).error;
      if (e != null) _showToast(e);
    });

    final c = context.c;
    final nav = ref.watch(navProvider);
    final navN = ref.read(navProvider.notifier);
    final top = MediaQuery.paddingOf(context).top;
    final bottom = MediaQuery.paddingOf(context).bottom;
    final hasTrack = ref.watch(playerProvider.select((s) => s.current != null));
    final busy = ref.watch(busyProvider);

    final dark = c.dark;
    SystemChrome.setSystemUIOverlayStyle(SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: dark ? Brightness.light : Brightness.dark,
      statusBarBrightness: dark ? Brightness.dark : Brightness.light,
      systemNavigationBarColor: c.bar,
      systemNavigationBarIconBrightness: dark ? Brightness.light : Brightness.dark,
    ));

    return PopScope(
      canPop: !nav.canGoBack,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) navN.back();
      },
      child: Scaffold(
        backgroundColor: Colors.transparent,
        body: _Backdrop(
          child: Stack(children: [
            Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              // ---- 品牌栏
              Container(
                height: 56 + top,
                padding: EdgeInsets.only(top: top, left: 18, right: 6),
                decoration: BoxDecoration(color: c.bar, border: Border(bottom: BorderSide(color: c.k, width: 3))),
                child: Row(children: [
                  Tap(
                    label: nav.playerOpen ? '收起播放页' : '回到主页',
                    onTap: navN.goHome,
                    child: Text('NEKOPLAY', style: NText.sans(22, w: FontWeight.w900, color: c.k, ls: .02)),
                  ),
                  const Spacer(),
                  SquareBtn(icon: NIcons.search, label: '搜索', onTap: () => navN.go(Pg.search), iconSize: 20),
                  const SizedBox(width: 8),
                  _Avatar(onTap: navN.toggleMenu),
                  const SizedBox(width: 6),
                ]),
              ),
              if (busy != null)
                Container(
                  height: 28,
                  width: double.infinity,
                  color: c.p,
                  alignment: Alignment.center,
                  child: Text(busy, style: NText.mono(12, color: Colors.white, ls: .06)),
                ),
              Expanded(
                child: Stack(children: [
                  Positioned.fill(child: TickerMode(enabled: !nav.playerOpen, child: _mainLayers(context, nav, hasTrack, bottom))),
                  const Positioned.fill(child: PlayerLayer()),
                ]),
              ),
            ]),
            // ---- 账号菜单
            if (nav.menuOpen) ...[
              Positioned.fill(child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: navN.closeMenu, child: const SizedBox.expand())),
              Positioned(top: 56 + top + 3, right: 12, child: _AccountMenu()),
            ],
            // ---- 队列
            const Positioned.fill(child: QueueSheet()),
            // ---- 提示
            if (_toast.isNotEmpty)
              Positioned(
                left: 24,
                right: 24,
                bottom: bottom + (hasTrack ? 150 : 90),
                child: IgnorePointer(
                  child: TweenAnimationBuilder<double>(
                    key: ValueKey(_toastSeq),
                    tween: Tween(begin: 0, end: 1),
                    duration: const Duration(milliseconds: 180),
                    builder: (_, v, child) => Opacity(opacity: v, child: child),
                    child: Center(
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
                        decoration: BoxDecoration(color: c.k),
                        child: Text(_toast, textAlign: TextAlign.center, style: NText.sans(14, color: c.tagFg, height: 1.4)),
                      ),
                    ),
                  ),
                ),
              ),
          ]),
        ),
      ),
    );
  }

  Widget _mainLayers(BuildContext context, NavState nav, bool hasTrack, double bottom) {
    final c = context.c;
    final navN = ref.read(navProvider.notifier);
    return LayoutBuilder(builder: (context, box) {
      final w = box.maxWidth;
      return AnimatedBuilder(animation: _sub, builder: (context, _) => Stack(children: [
        // 首页 / 曲库
        Positioned.fill(
          bottom: 0,
          child: TickerMode(enabled: _sub.value < 1, child: Transform.translate(
            offset: Offset(-w * .28 * _sub.value, 0), // 子页面推入时，下面的页面轻微后退（视差）
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Expanded(
              child: AnimatedBuilder(
                animation: _pager,
                builder: (context, _) => ClipRect(
                  child: Stack(children: [
                    if (_pager.value < 1)
                      Transform.translate(offset: Offset(-w * _pager.value, 0), child: const HomePage()),
                    // 曲库页第一次切过去才创建，启动时不去加载几百首的云端列表
                    if (_libBuilt && _pager.value > 0)
                      Transform.translate(offset: Offset(w * (1 - _pager.value), 0), child: const LibraryPage()),
                  ]),
                ),
              ),
            ),
            if (hasTrack) const MiniBar(),
            // 底部导航：首页 / 曲库
            Container(
              height: 56 + bottom,
              padding: EdgeInsets.only(bottom: bottom),
              decoration: BoxDecoration(color: c.bar, border: Border(top: BorderSide(color: c.k, width: 3))),
              child: Row(children: [
                _NavItem(icon: NIcons.home, label: '主页', on: nav.base == Pg.home && !nav.isSub, onTap: () => navN.go(Pg.home)),
                _NavItem(icon: NIcons.library, label: '曲库', on: nav.base == Pg.library && !nav.isSub, onTap: () => navN.go(Pg.library)),
              ]),
            ),
          ]),
          )),
        ),
        if (_sub.value > 0)
          Positioned.fill(child: IgnorePointer(child: ColoredBox(color: Color.fromRGBO(0, 0, 0, .16 * _sub.value)))),
        // 子页面
        if (_sub.value > 0)
          Positioned.fill(
            left: w * (1 - _sub.value),
            right: -w * (1 - _sub.value),
            child: Stack(children: [
              Positioned.fill(child: Container(color: c.dark ? c.bar : const Color(0xFFD3E5EA), child: _subContent(context, nav))),
              // 左边缘：跟手滑动返回，松手按速度 / 位置决定回去还是留下
              Positioned(
                left: 0,
                top: 0,
                bottom: 0,
                width: 22,
                child: GestureDetector(
                  behavior: HitTestBehavior.translucent,
                  onHorizontalDragStart: (_) => _sub.stop(),
                  onHorizontalDragUpdate: (d) => _sub.value = (_sub.value - d.delta.dx / w).clamp(0.0, 1.0),
                  onHorizontalDragEnd: (d) {
                    final v = d.primaryVelocity ?? 0;
                    if (v > 600 || (v > -600 && _sub.value < .55)) {
                      HapticFeedback.selectionClick();
                      navN.back();
                    } else {
                      _sub.springTo(1);
                    }
                  },
                  onHorizontalDragCancel: () => _sub.springTo(1),
                ),
              ),
            ]),
          ),
      ]));
    });
  }

  Widget _subContent(BuildContext context, NavState nav) {
    final c = context.c;
    final navN = ref.read(navProvider.notifier);
    final title = switch (_subPage) {
      Pg.settings => 'SETTINGS · 设置',
      Pg.plugins => 'SOURCES · 音源插件',
      Pg.pl => 'LIST · 列表',
      _ => '',
    };
    final bottom = MediaQuery.paddingOf(context).bottom;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (_subPage != Pg.search)
        Container(
          height: 48,
          decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l2))),
          padding: const EdgeInsets.only(left: 6, right: 18),
          child: Row(children: [
            Tap(label: '返回', onTap: navN.back, child: SizedBox(width: 44, height: 44, child: Center(child: NIcon(NIcons.back, size: 20, color: c.k)))),
            Text(title, style: NText.mono(12, color: c.s, ls: .1)),
          ]),
        ),
      Expanded(
        child: switch (_subPage) {
          Pg.search => const SearchPage(),
          Pg.settings => const SettingsPage(),
          Pg.plugins => const PluginsPage(),
          _ => const DetailPage(),
        },
      ),
      if (ref.watch(playerProvider.select((s) => s.current != null))) const MiniBar(),
      SizedBox(height: bottom),
    ]);
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({required this.icon, required this.label, required this.on, required this.onTap});
  final String icon, label;
  final bool on;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Expanded(
      child: Tap(
        label: label,
        haptic: true,
        onTap: onTap,
        child: Container(
          color: on ? c.p : null,
          alignment: Alignment.center,
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            NIcon(icon, size: 22, color: on ? Colors.white : c.k),
            const SizedBox(height: 2),
            Text(label, style: NText.sans(11, w: FontWeight.w700, color: on ? Colors.white : c.k)),
          ]),
        ),
      ),
    );
  }
}

class _Avatar extends ConsumerWidget {
  const _Avatar({required this.onTap});
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Tap(
      label: '账号菜单',
      onTap: onTap,
      child: SizedBox(
        width: 44,
        height: 44,
        child: Center(
          child: const AvatarBox(size: 34, fontSize: 16),
        ),
      ),
    );
  }
}

class _AccountMenu extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.c;
    final s = ref.watch(settingsProvider);
    final nav = ref.read(navProvider.notifier);
    Widget item(String t, VoidCallback f) => Tap(
          label: t,
          onTap: f,
          child: Container(
            height: 48,
            width: 190,
            padding: const EdgeInsets.symmetric(horizontal: 14),
            alignment: Alignment.centerLeft,
            decoration: BoxDecoration(border: Border(top: BorderSide(color: c.l1))),
            child: Text(t, style: NText.sans(15, color: c.k)),
          ),
        );
    return Material(
      type: MaterialType.transparency,
      child: Container(
        decoration: BoxDecoration(color: c.dark ? c.pop : const Color(0xFFF1F7F8), border: Border.all(color: c.k)),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
            child: Text(s.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(15, w: FontWeight.w800, color: c.k)),
          ),
          item('设置', () => nav.go(Pg.settings)),
          item('音源插件', () => nav.go(Pg.plugins)),
        ]),
      ),
    );
  }
}

/// 背景：深色纯平 #2D333B，浅色 165° 渐变 + 淡淡的冷暖光斑。
class _Backdrop extends StatelessWidget {
  const _Backdrop({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    if (c.dark) return ColoredBox(color: const Color(0xFF2D333B), child: child);
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment(-.26, -1),
          end: Alignment(.26, 1),
          colors: [Color(0xFFC6DCE4), Color(0xFFD3E5EA), Color(0xFFE2EDEE)],
          stops: [0, .5, 1],
        ),
      ),
      child: Stack(children: [
        Positioned(
          right: -80,
          top: 120,
          child: Container(width: 260, height: 260, decoration: const BoxDecoration(shape: BoxShape.circle, gradient: RadialGradient(colors: [Color(0x33FFD9B0), Color(0x00FFD9B0)]))),
        ),
        Positioned(
          left: -90,
          bottom: 160,
          child: Container(width: 280, height: 280, decoration: const BoxDecoration(shape: BoxShape.circle, gradient: RadialGradient(colors: [Color(0x33A8C8FF), Color(0x00A8C8FF)]))),
        ),
        Positioned.fill(child: child),
      ]),
    );
  }
}
