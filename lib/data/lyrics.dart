import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

import 'track.dart';

class LyricLine {
  const LyricLine(this.t, this.orig, [this.tr]);
  final int t; // 毫秒
  final String orig;
  final String? tr;
}

final _ts = RegExp(r'\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]');

/// 解析 LRC：
/// - 支持一行多个时间戳；
/// - 相同时间戳的两行 = 原文 + 翻译；
/// - 也支持 `原文 | 翻译` 写法。
List<LyricLine> parseLrc(String text) {
  final entries = <MapEntry<int, String>>[];
  for (final raw in const LineSplitter().convert(text)) {
    final line = raw.trim();
    if (line.isEmpty) continue;
    final ms = _ts.allMatches(line).toList();
    if (ms.isEmpty) continue; // [ar:xx] 之类的标签与无时间戳行跳过
    final content = line.substring(ms.last.end).trim();
    for (final m in ms) {
      final mm = int.parse(m.group(1)!);
      final ss = int.parse(m.group(2)!);
      final frac = m.group(3);
      var f = 0;
      if (frac != null) {
        f = int.parse(frac.padRight(3, '0').substring(0, 3));
      }
      entries.add(MapEntry(mm * 60000 + ss * 1000 + f, content));
    }
  }
  // 稳定排序
  final indexed = entries.asMap().entries.toList()
    ..sort((a, b) {
      final c = a.value.key.compareTo(b.value.key);
      return c != 0 ? c : a.key.compareTo(b.key);
    });
  final out = <LyricLine>[];
  int? lastT;
  for (final e in indexed) {
    final t = e.value.key;
    final txt = e.value.value;
    if (txt.isEmpty && lastT != t) {
      // 空行保留为间奏；连续的空行、开头的空行都不要
      if (out.isEmpty || out.last.orig.isEmpty) {
        lastT = t;
        continue;
      }
      out.add(LyricLine(t, ''));
      lastT = t;
      continue;
    }
    if (lastT == t && out.isNotEmpty && out.last.tr == null && out.last.orig.isNotEmpty && txt.isNotEmpty) {
      final prev = out.removeLast();
      out.add(LyricLine(prev.t, prev.orig, txt));
      continue;
    }
    String orig = txt;
    String? tr;
    final sep = txt.indexOf(' | ');
    if (sep > 0) {
      orig = txt.substring(0, sep).trim();
      tr = txt.substring(sep + 3).trim();
    }
    out.add(LyricLine(t, orig, tr));
    lastT = t;
  }
  return out;
}

/// 读取歌词：示例曲目读 assets；有内嵌歌词用内嵌；否则读同名 .lrc。
Future<List<LyricLine>> loadLyrics(Track t) async {
  try {
    if (t.demo) {
      if (t.id != 'demo-0') return const [];
      final j = jsonDecode(await rootBundle.loadString('assets/data/lyrics.json')) as Map;
      return (j['lines'] as List)
          .map((e) => LyricLine(((e['t'] as num) * 1000).toInt(), e['orig'] as String, e['zh'] as String?))
          .toList();
    }
    if (t.lrcPath != null) {
      final f = File(t.lrcPath!);
      if (await f.exists()) {
        final l = parseLrc(await _readText(f));
        if (l.isNotEmpty) return l;
      }
    }
    if (t.lyricsText != null && t.lyricsText!.trim().isNotEmpty) {
      return parseLrc(t.lyricsText!);
    }
  } catch (_) {}
  return const [];
}

Future<String> _readText(File f) async {
  final bytes = await f.readAsBytes();
  try {
    return utf8.decode(bytes);
  } catch (_) {
    return latin1.decode(bytes);
  }
}

/// 当前行：最后一个 t <= 位置 的行。
int currentLine(List<LyricLine> lines, int posMs) {
  var cur = -1;
  for (var i = 0; i < lines.length; i++) {
    if (posMs >= lines[i].t) {
      cur = i;
    } else {
      break;
    }
  }
  return cur < 0 ? 0 : cur;
}
