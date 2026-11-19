import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/perf.dart';
import 'core/theme.dart';
import 'data/settings.dart';
import 'ui/shell.dart';

class NekoApp extends ConsumerWidget {
  const NekoApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pref = ref.watch(settingsProvider.select((s) => s.theme));
    return MaterialApp(
      title: 'NEKOPLAY',
      scrollBehavior: const _AppleScroll(),
      debugShowCheckedModeBanner: false,
      theme: NekoTheme.light,
      darkTheme: NekoTheme.dark,
      themeMode: switch (pref) {
        ThemePref.system => ThemeMode.system,
        ThemePref.light => ThemeMode.light,
        ThemePref.dark => ThemeMode.dark,
      },
      builder: (context, child) {
        final mq = MediaQuery.of(context);
        return MediaQuery(
          data: mq.copyWith(textScaler: mq.textScaler.clamp(minScaleFactor: 1.0, maxScaleFactor: 1.3)),
          child: Stack(children: [
            child!,
            // 性能浮窗：不拦截点击
            ValueListenableBuilder(
              valueListenable: Perf.overlay,
              builder: (context, on, _) => !on
                  ? const SizedBox.shrink()
                  : Positioned(
                      left: 6,
                      bottom: mq.padding.bottom + 140,
                      child: IgnorePointer(
                        child: ValueListenableBuilder(
                          valueListenable: Perf.stat,
                          builder: (context, st, _) => Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                            color: const Color(0xCC000000),
                            child: Text(
                              'CPU ${st.cpu.toStringAsFixed(0)}%  ${st.fps}fps\nB ${st.buildMs.toStringAsFixed(1)} R ${st.rasterMs.toStringAsFixed(1)}ms  卡${st.jank}\nGPU ${st.gpu ?? '-'}  RSS ${(ProcessInfo.currentRss / 1048576).toStringAsFixed(0)}M',
                              style: const TextStyle(color: Colors.white, fontSize: 10, fontFamily: 'JetBrainsMono', height: 1.3, decoration: TextDecoration.none),
                            ),
                          ),
                        ),
                      ),
                    ),
            ),
          ]),
        );
      },
      home: const Shell(),
    );
  }
}

/// iOS 风格的滚动：到边缘有回弹，没有安卓的发光效果。
class _AppleScroll extends MaterialScrollBehavior {
  const _AppleScroll();
  @override
  ScrollPhysics getScrollPhysics(BuildContext context) => const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics());
  @override
  Widget buildOverscrollIndicator(BuildContext context, Widget child, ScrollableDetails details) => child;
}
