import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:nekoplay/app.dart';
import 'package:nekoplay/data/settings.dart';
import 'package:nekoplay/data/track.dart';
import 'package:nekoplay/nav/nav.dart';
import 'package:nekoplay/state.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> _font(String family, String path) async {
  final l = FontLoader(family)..addFont(Future.value(ByteData.sublistView(File(path).readAsBytesSync())));
  await l.load();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  for (final ch in ['com.ryanheise.just_audio.methods', 'com.ryanheise.audio_session', 'com.ryanheise.audio_service.client.methods']) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(MethodChannel(ch), (c) async => null);
  }

  Future<void> shot(WidgetTester t, GlobalKey k, String name) async {
    await t.runAsync(() async {
      final b = k.currentContext!.findRenderObject() as RenderRepaintBoundary;
      final img = await b.toImage(pixelRatio: 1.5);
      final data = await img.toByteData(format: ui.ImageByteFormat.png);
      File('/tmp/shots/$name.png').writeAsBytesSync(data!.buffer.asUint8List());
    });
  }

  for (final dark in [false, true]) {
    testWidgets('shots dark=$dark', (tester) async {
      Directory('/tmp/shots').createSync(recursive: true);
      await tester.runAsync(() async {
        await _font('Inter', 'assets/fonts/Inter.ttf');
        await _font('JetBrainsMono', 'assets/fonts/JetBrainsMono.ttf');
      });
      tester.view.physicalSize = const Size(390 * 2, 844 * 2);
      tester.view.devicePixelRatio = 2;
      SharedPreferences.setMockInitialValues({'theme': dark ? 2 : 1});
      final prefs = await SharedPreferences.getInstance();
      final container = ProviderContainer(overrides: [prefsProvider.overrideWithValue(prefs)]);
      addTearDown(container.dispose);
      final key = GlobalKey();
      await tester.pumpWidget(UncontrolledProviderScope(container: container, child: RepaintBoundary(key: key, child: const NekoApp())));
      for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 150)); }
      final tag = dark ? 'd' : 'l';
      await shot(tester, key, '$tag-home');
      final nav = container.read(navProvider.notifier);
      nav.go(Pg.library);
      for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 150)); }
      await shot(tester, key, '$tag-library');
      final engine = container.read(engineProvider);
      await engine.setQueue(Track.demoTracks(), 0);
      nav.go(Pg.home);
      for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 150)); }
      await shot(tester, key, '$tag-minibar');
      nav.go(Pg.now);
      for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 150)); }
      await shot(tester, key, '$tag-now');
      await engine.seek(40000);
      nav.go(Pg.lyrics);
      await tester.pump(const Duration(milliseconds: 1200));
      await tester.pump(const Duration(milliseconds: 1200));
      await shot(tester, key, '$tag-lyrics');
      nav.openQueue();
      for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 150)); }
      await shot(tester, key, '$tag-queue');
      nav.closeQueue();
      nav.back();
      for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 150)); }
      nav.go(Pg.settings);
      for (var i = 0; i < 8; i++) { await tester.pump(const Duration(milliseconds: 150)); }
      await shot(tester, key, '$tag-settings');
      await engine.stop();
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 1));
    });
  }
}
