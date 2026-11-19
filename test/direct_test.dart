import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:nekoplay/plugins/plugin_models.dart';
import 'package:nekoplay/plugins/sandbox.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() => HttpOverrides.global = null);
  test('ncm-direct runs in sandbox (needs network)', () async {
    final code = File('plugins/ncm-direct.js').readAsStringSync();
    final store = File('${Directory.systemTemp.path}/ncmd.store.json');
    if (store.existsSync()) store.deleteSync();
    final box = PluginSandbox(PluginMeta.parse(code), code, store, onLog: (p, l) => print(l));
    try {
      final r = await box.invoke('search', ['周杰伦 晴天', {'page': 1, 'pageSize': 3}]);
      print(r);
      expect((r as Map)['items'], isNotEmpty);
      // 未登录时 getLiked 应给出明确提示
      await expectLater(box.invoke('getLiked', [{'page': 1, 'pageSize': 10}]), throwsA(predicate((e) => '$e'.contains('登录'))));
    } finally {
      box.dispose();
    }
  }, timeout: const Timeout(Duration(seconds: 60)));

  test('playlist paging with parallel detail (public list, fake cookie)', () async {
    final code = File('plugins/ncm-direct.js').readAsStringSync();
    final store = File('${Directory.systemTemp.path}/ncmd2.store.json');
    store.writeAsStringSync('{"cookie":"MUSIC_U=x; __csrf=y"}');
    final box = PluginSandbox(PluginMeta.parse(code), code, store, onLog: (p, l) => print(l));
    try {
      final sw = Stopwatch()..start();
      final r = await box.invoke('getPlaylist', ['3778678', {'page': 1, 'pageSize': 200}]);
      print('page1 ${(r as Map)['items'].length}/${r['total']} in ${sw.elapsedMilliseconds}ms; first=${r['items'].first}');
      expect(r['items'].length, greaterThan(100));
    } finally {
      box.dispose();
    }
  }, timeout: const Timeout(Duration(seconds: 60)));
}
