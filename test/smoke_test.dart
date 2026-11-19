import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nekoplay/app.dart';
import 'package:nekoplay/data/settings.dart';
import 'package:nekoplay/nav/nav.dart';
import 'package:nekoplay/data/track.dart';
import 'package:nekoplay/state.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final ch in ['com.ryanheise.just_audio.methods', 'com.ryanheise.audio_session', 'com.ryanheise.audio_service.client.methods']) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(MethodChannel(ch), (c) async => null);
  }

  testWidgets('pages render without exceptions', (tester) async {
    tester.view.physicalSize = const Size(390 * 3, 844 * 3);
    tester.view.devicePixelRatio = 3;
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final container = ProviderContainer(overrides: [prefsProvider.overrideWithValue(prefs)]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(container: container, child: const NekoApp()));
    await tester.pump(const Duration(milliseconds: 500));

    final nav = container.read(navProvider.notifier);
    for (final p in [Pg.library, Pg.home, Pg.search, Pg.settings, Pg.plugins]) {
      nav.go(p);
      await tester.pump(const Duration(milliseconds: 100));
      await tester.pump(const Duration(milliseconds: 600));
      expect(tester.takeException(), isNull, reason: 'page $p');
      nav.go(Pg.home);
      await tester.pump(const Duration(milliseconds: 700));
    }
    nav.openList(const ListRef(ListKind.liked));
    await tester.pump(const Duration(milliseconds: 700));
    expect(tester.takeException(), isNull, reason: 'detail');
    nav.goHome();
    await tester.pump(const Duration(milliseconds: 700));

    // 播放示例曲目，展开播放页 / 歌词 / 队列
    final engine = container.read(engineProvider);
    await engine.setQueue(Track.demoTracks(), 0);
    await tester.pump(const Duration(milliseconds: 300));
    expect(tester.takeException(), isNull, reason: 'minibar');
    nav.go(Pg.now);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(milliseconds: 600));
    expect(tester.takeException(), isNull, reason: 'now');
    nav.go(Pg.lyrics);
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(milliseconds: 800));
    await tester.pump(const Duration(milliseconds: 800));
    expect(tester.takeException(), isNull, reason: 'lyrics');
    nav.openQueue();
    await tester.pump(const Duration(milliseconds: 100));
    await tester.pump(const Duration(milliseconds: 600));
    expect(tester.takeException(), isNull, reason: 'queue');
    nav.closeQueue();
    nav.back();
    await tester.pump(const Duration(milliseconds: 700));
    expect(tester.takeException(), isNull, reason: 'back');
    await engine.stop();
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
  });
}
