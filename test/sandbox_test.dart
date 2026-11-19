import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:nekoplay/plugins/plugin_models.dart';
import 'package:nekoplay/plugins/sandbox.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() => HttpOverrides.global = null);
  test('ncm plugin runs in sandbox against local server', () async {
    final server = await HttpServer.bind('127.0.0.1', 0);
    server.listen((req) {
      final u = req.uri;
      Object body = {'code': 404};
      if (u.path == '/cloudsearch') {
        body = {
          'code': 200,
          'result': {
            'songs': [
              {'id': 1, 'name': 'T', 'ar': [{'name': 'A'}], 'al': {'name': 'AL', 'picUrl': 'http://p/a.jpg'}, 'dt': 120000}
            ]
          }
        };
      }
      req.response
        ..headers.contentType = ContentType.json
        ..write(jsonEncode(body))
        ..close();
    });
    final code = File('plugins/ncm-enhanced.js').readAsStringSync();
    final meta = PluginMeta.parse(code);
    final store = File('${Directory.systemTemp.path}/ncm.store.json');
    if (store.existsSync()) store.deleteSync();
    store.writeAsStringSync(jsonEncode({'base': 'http://127.0.0.1:${server.port}'}));
    final logs = <String>[];
    final box = PluginSandbox(meta, code, store, onLog: (p, l) => logs.add(l));
    try {
      final r = await box.invoke('search', ['x', {'page': 1, 'pageSize': 20}]);
      // ignore: avoid_print
      print(r);
      expect((r as Map)['items'], isNotEmpty);
      final p = await box.invoke('ping', const []);
      expect((p as Map)['ok'], true);
    } finally {
      print(logs);
      box.dispose();
      await server.close(force: true);
    }
  });
}
