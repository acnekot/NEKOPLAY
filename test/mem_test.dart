import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:nekoplay/data/track.dart';

void main() {
  test('Dart-side cost of 3000 online tracks + cache JSON', () {
    final before = ProcessInfo.currentRss;
    final tracks = [
      for (var i = 0; i < 3000; i++)
        Track(
          id: 'ncm-direct:$i',
          title: '曲目标题 $i',
          artist: '艺人 $i / 合作者',
          album: '专辑 $i',
          durationMs: 200000,
          coverUrl: 'https://p3.music.126.net/xxxxxxxxxxxxxxxxxxxxxx==/109951170413587092.jpg?param=300y300',
          source: 'ncm-direct',
        )
    ];
    final json = jsonEncode([for (final t in tracks) t.toJson()]);
    final back = [for (final e in jsonDecode(json) as List) Track.fromJson(Map<String, dynamic>.from(e as Map))];
    final after = ProcessInfo.currentRss;
    // ignore: avoid_print
    print('3000 tracks: json ${(json.length / 1024).toStringAsFixed(0)} KB, '
        'RSS delta ${((after - before) / 1048576).toStringAsFixed(1)} MB, back=${back.length}');
    expect(back.length, 3000);
  });
}
