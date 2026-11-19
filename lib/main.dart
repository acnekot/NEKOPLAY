import 'dart:async';

import 'package:audio_service/audio_service.dart';
import 'package:audio_session/audio_session.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'core/perf.dart';
import 'core/theme.dart';
import 'audio/handler.dart';
import 'data/library.dart';
import 'data/settings.dart';
import 'plugins/plugin_manager.dart';
import 'state.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // 图片内存缓存收紧：默认 100MB，改成 40MB / 250 张，列表滑动靠磁盘缓存补
  PaintingBinding.instance.imageCache
    ..maximumSizeBytes = 24 << 20
    ..maximumSize = 250;
  final prefs = await SharedPreferences.getInstance();
  animLevel = (prefs.getInt('anim_speed') ?? 1).clamp(0, 3);
  final container = ProviderContainer(overrides: [prefsProvider.overrideWithValue(prefs)]);

  WidgetsBinding.instance.addObserver(_MemTrim());
  // 先把界面画出来，再在后台加载曲库 / 插件 / 音频引擎（以前要等这些全部完成才出第一帧）
  Perf.overlay.value = prefs.getBool('perf_overlay') ?? false;
  Perf.start();
  runApp(UncontrolledProviderScope(container: container, child: const NekoApp()));
  // Skia 的显存缓存默认按屏幕大小给到上百 MB，限制到 48MB
  unawaited(SystemChannels.skia.invokeMethod('Skia.setResourceCacheMaxBytes', 48 << 20).then((_) {}, onError: (_) {}));

  // 曲库、插件、音频引擎互不依赖，并行初始化
  final engine = container.read(engineProvider);
  await Future.wait([
    container.read(libraryProvider.notifier).load(),
    container.read(pluginsProvider.notifier).load(),
    () async {
      try {
        final session = await AudioSession.instance;
        await session.configure(const AudioSessionConfiguration.music());
      } catch (_) {}
      await engine.init();
    }(),
  ]);
  // 通知栏 / 锁屏控制不挡首屏
  unawaited(() async {
    try {
      await AudioService.init(
        builder: () => NekoAudioHandler(engine),
        config: const AudioServiceConfig(
          androidNotificationChannelId: 'com.catsuki.nekoplay.channel.audio',
          androidNotificationChannelName: 'NEKOPLAY 播放',
          androidNotificationOngoing: true,
          androidStopForegroundOnPause: true,
        ),
      );
    } catch (_) {}
  }());
  unawaited(restoreSession(container));
}

/// 切到后台或系统内存吃紧时，丢掉解码好的图片缓存（磁盘缓存还在，回来秒出）。
class _MemTrim with WidgetsBindingObserver {
  void _trim() {
    final c = PaintingBinding.instance.imageCache;
    c.clear();
  }

  @override
  void didHaveMemoryPressure() => _trim();

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.paused || s == AppLifecycleState.hidden) _trim();
  }
}
