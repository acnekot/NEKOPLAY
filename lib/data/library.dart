import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:audio_metadata_reader/audio_metadata_reader.dart';
import 'package:crypto/crypto.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'track.dart';

const kAudioExts = ['mp3', 'flac', 'm4a', 'aac', 'wav', 'ogg', 'opus', 'oga', 'aiff', 'aif', 'ape', 'wma', 'mp4'];

class LibraryState {
  const LibraryState({
    this.tracks = const [],
    this.online = const {},
    this.playlists = const [],
    this.likes = const [],
    this.recents = const [],
    this.history = const [],
    this.loaded = false,
  });

  final List<Track> tracks; // 已导入的本地曲目
  final Map<String, Track> online; // 被收藏 / 加入歌单的在线曲目
  final List<UserPlaylist> playlists;
  final List<String> likes;
  final List<String> recents;
  final List<String> history;
  final bool loaded;

  List<Track> get all => tracks;

  Track? byId(String id) {
    for (final t in all) {
      if (t.id == id) return t;
    }
    return online[id];
  }

  List<Track> resolve(Iterable<String> ids) =>
      [for (final i in ids) if (byId(i) != null) byId(i)!];

  LibraryState copyWith({
    List<Track>? tracks,
    Map<String, Track>? online,
    List<UserPlaylist>? playlists,
    List<String>? likes,
    List<String>? recents,
    List<String>? history,
    bool? loaded,
  }) =>
      LibraryState(
        tracks: tracks ?? this.tracks,
        online: online ?? this.online,
        playlists: playlists ?? this.playlists,
        likes: likes ?? this.likes,
        recents: recents ?? this.recents,
        history: history ?? this.history,
        loaded: loaded ?? this.loaded,
      );
}

class ImportReport {
  ImportReport({this.added = 0, this.skipped = 0, this.failed = 0});
  int added, skipped, failed;
}

final libraryProvider = NotifierProvider<LibraryNotifier, LibraryState>(LibraryNotifier.new);

class LibraryNotifier extends Notifier<LibraryState> {
  late Directory _docs;
  Timer? _saveTimer;

  Directory get docs => _docs;

  @override
  LibraryState build() {
    ref.onDispose(() => _saveTimer?.cancel());
    return const LibraryState();
  }

  Future<void> load() async {
    _docs = await getApplicationDocumentsDirectory();
    final f = File(p.join(_docs.path, 'library.json'));
    if (!await f.exists()) {
      state = const LibraryState(loaded: true);
      return;
    }
    try {
      final j = jsonDecode(await f.readAsString()) as Map<String, dynamic>;
      final tracks = <Track>[];
      for (final e in (j['tracks'] as List? ?? const [])) {
        final t = _de(Track.fromJson(Map<String, dynamic>.from(e as Map)));
        // 文件被系统清理时跳过
        if (t.path == null || File(t.path!).existsSync()) tracks.add(t);
      }
      final online = <String, Track>{};
      for (final e in (j['online'] as List? ?? const [])) {
        final t = Track.fromJson(Map<String, dynamic>.from(e as Map));
        online[t.id] = t;
      }
      state = LibraryState(
        tracks: tracks,
        online: online,
        playlists: [
          for (final e in (j['playlists'] as List? ?? const []))
            UserPlaylist.fromJson(Map<String, dynamic>.from(e as Map))
        ],
        likes: [for (final e in (j['likes'] as List? ?? const [])) '$e'],
        recents: [for (final e in (j['recents'] as List? ?? const [])) '$e'],
        history: [for (final e in (j['history'] as List? ?? const [])) '$e'],
        loaded: true,
      );
    } catch (_) {
      state = const LibraryState(loaded: true);
    }
  }

  // ---------- 持久化（路径存相对路径，避免 iOS 更新后容器路径变化） ----------
  Track _ser(Track t) => _rel(t, true);
  Track _de(Track t) => _rel(t, false);

  Track _rel(Track t, bool toRel) {
    String? conv(String? v) {
      if (v == null) return null;
      if (toRel) return p.isAbsolute(v) && p.isWithin(_docs.path, v) ? p.relative(v, from: _docs.path) : v;
      return p.isAbsolute(v) ? v : p.join(_docs.path, v);
    }

    return Track(
      id: t.id,
      title: t.title,
      artist: t.artist,
      album: t.album,
      durationMs: t.durationMs,
      path: conv(t.path),
      url: t.url,
      headers: t.headers,
      coverPath: conv(t.coverPath),
      coverAsset: t.coverAsset,
      coverUrl: t.coverUrl,
      format: t.format,
      spec: t.spec,
      bitrate: t.bitrate,
      source: t.source,
      extra: t.extra,
      lrcPath: conv(t.lrcPath),
      lyricsText: t.lyricsText,
      demo: t.demo,
      addedAt: t.addedAt,
    );
  }

  void _set(LibraryState s) {
    state = s;
    _saveTimer?.cancel();
    _saveTimer = Timer(const Duration(milliseconds: 400), _save);
  }

  Future<void> _save() async {
    final data = {
      'tracks': [for (final t in state.tracks) _ser(t).toJson()],
      'online': [for (final t in state.online.values) t.toJson()],
      'playlists': [for (final l in state.playlists) l.toJson()],
      'likes': state.likes,
      'recents': state.recents,
      'history': state.history,
    };
    try {
      final f = File(p.join(_docs.path, 'library.json'));
      final tmp = File('${f.path}.tmp');
      await tmp.writeAsString(jsonEncode(data));
      await tmp.rename(f.path);
    } catch (_) {}
  }

  // ---------- 导入 ----------
  Future<ImportReport> importFiles(List<PlatformFile> picked, {void Function(int done, int total)? onProgress}) async {
    final report = ImportReport();
    final audio = <PlatformFile>[];
    final lrcs = <String, PlatformFile>{};
    for (final f in picked) {
      final ext = (f.extension ?? '').toLowerCase();
      if (ext == 'lrc') {
        lrcs[p.basenameWithoutExtension(f.name).toLowerCase()] = f;
      } else if (kAudioExts.contains(ext)) {
        audio.add(f);
      }
    }
    final musicDir = Directory(p.join(_docs.path, 'music'))..createSync(recursive: true);
    final coverDir = Directory(p.join(_docs.path, 'covers'))..createSync(recursive: true);
    final lyricDir = Directory(p.join(_docs.path, 'lyrics'))..createSync(recursive: true);
    final added = <Track>[];
    var done = 0;
    for (final f in audio) {
      onProgress?.call(done, audio.length);
      try {
        final size = await f.length() ?? 0;
        final id = sha1.convert(utf8.encode('${f.name}|$size')).toString().substring(0, 16);
        if (state.tracks.any((t) => t.id == id) || added.any((t) => t.id == id)) {
          report.skipped++;
          done++;
          continue;
        }
        final ext = p.extension(f.name).toLowerCase();
        final dest = File(p.join(musicDir.path, '$id$ext'));
        final sink = dest.openWrite();
        await sink.addStream(f.readAsByteStream());
        await sink.close();

        final meta = await Isolate.run(() => _readMeta(dest.path));
        String? coverPath;
        final cover = meta['cover'] as List<int>?;
        if (cover != null && cover.isNotEmpty) {
          final cf = File(p.join(coverDir.path, '$id.${meta['coverExt']}'));
          await cf.writeAsBytes(cover);
          coverPath = cf.path;
        }
        String? lrcPath;
        final lrc = lrcs[p.basenameWithoutExtension(f.name).toLowerCase()];
        if (lrc != null) {
          final lf = File(p.join(lyricDir.path, '$id.lrc'));
          final s = lf.openWrite();
          await s.addStream(lrc.readAsByteStream());
          await s.close();
          lrcPath = lf.path;
        }
        final base = p.basenameWithoutExtension(f.name);
        final title = ((meta['title'] as String?) ?? '').trim();
        final artist = ((meta['artist'] as String?) ?? '').trim();
        final format = ext.isEmpty ? '' : ext.substring(1).toUpperCase();
        final sr = meta['sampleRate'] as int?;
        var br = (meta['bitrate'] as int?) ?? 0;
        if (br > 20000) br ~/= 1000;
        added.add(Track(
          id: id,
          title: title.isEmpty ? base : title,
          artist: artist.isEmpty ? '未知艺人' : artist,
          album: ((meta['album'] as String?) ?? '').trim(),
          durationMs: (meta['durationMs'] as int?) ?? 0,
          path: dest.path,
          coverPath: coverPath,
          format: format == 'M4A' ? 'AAC' : format,
          spec: sr == null || sr == 0 ? '' : '${_khz(sr)}kHz',
          bitrate: br,
          lrcPath: lrcPath,
          lyricsText: meta['lyrics'] as String?,
          addedAt: DateTime.now().millisecondsSinceEpoch,
        ));
        report.added++;
      } catch (_) {
        report.failed++;
      }
      done++;
    }
    onProgress?.call(audio.length, audio.length);
    if (added.isNotEmpty) {
      _set(state.copyWith(tracks: [...state.tracks, ...added]));
    }
    return report;
  }

  static String _khz(int sr) {
    final v = sr / 1000;
    return v == v.roundToDouble() ? v.round().toString() : v.toStringAsFixed(1);
  }

  Future<void> removeTrack(String id) async {
    final t = state.tracks.where((e) => e.id == id).firstOrNull;
    if (t == null) return;
    for (final path in [t.path, t.coverPath, t.lrcPath]) {
      if (path == null) continue;
      try {
        await File(path).delete();
      } catch (_) {}
    }
    _set(state.copyWith(
      tracks: state.tracks.where((e) => e.id != id).toList(),
      likes: state.likes.where((e) => e != id).toList(),
      recents: state.recents.where((e) => e != id).toList(),
      playlists: [
        for (final l in state.playlists) l..trackIds.removeWhere((e) => e == id),
      ],
    ));
  }

  Future<void> clearLibrary() async {
    for (final d in ['music', 'covers', 'lyrics']) {
      try {
        await Directory(p.join(_docs.path, d)).delete(recursive: true);
      } catch (_) {}
    }
    _set(state.copyWith(tracks: [], likes: [], recents: [], playlists: [for (final l in state.playlists) l..trackIds.clear()]));
  }

  // ---------- 喜欢 / 最近 ----------
  bool isLiked(String id) => state.likes.contains(id);

  void toggleLike(Track t) {
    _rememberOnline(t);
    final likes = [...state.likes];
    if (!likes.remove(t.id)) likes.insert(0, t.id);
    _set(state.copyWith(likes: likes));
  }

  void pushRecent(Track t) {
    _rememberOnline(t);
    final r = [t.id, ...state.recents.where((e) => e != t.id)].take(60).toList();
    _set(state.copyWith(recents: r));
  }

  void _rememberOnline(Track t) {
    if (t.isOnline && !state.online.containsKey(t.id)) {
      state = state.copyWith(online: {...state.online, t.id: t});
    }
  }

  // ---------- 歌单 ----------
  String createPlaylist(String name) {
    final id = 'pl${DateTime.now().microsecondsSinceEpoch}';
    final n = name.trim().isEmpty ? '新建列表 ${state.playlists.length + 1}' : name.trim();
    _set(state.copyWith(playlists: [...state.playlists, UserPlaylist(id: id, name: n)]));
    return id;
  }

  void renamePlaylist(String id, String name) {
    if (name.trim().isEmpty) return;
    _set(state.copyWith(playlists: [
      for (final l in state.playlists) l.id == id ? (l..name = name.trim()) : l,
    ]));
  }

  void deletePlaylist(String id) =>
      _set(state.copyWith(playlists: state.playlists.where((l) => l.id != id).toList()));

  void addToPlaylist(String id, Track t) {
    _rememberOnline(t);
    _set(state.copyWith(playlists: [
      for (final l in state.playlists)
        if (l.id == id)
          (l.trackIds.contains(t.id) ? l : (l..trackIds.add(t.id)..coverTrackId ??= t.id))
        else
          l,
    ]));
  }

  void removeFromPlaylist(String id, String trackId) {
    _set(state.copyWith(playlists: [
      for (final l in state.playlists) l.id == id ? (l..trackIds.remove(trackId)) : l,
    ]));
  }

  // ---------- 搜索历史 ----------
  void addHistory(String q) {
    final text = q.trim();
    if (text.isEmpty) return;
    _set(state.copyWith(history: [text, ...state.history.where((h) => h != text)].take(8).toList()));
  }

  void removeHistory(String q) => _set(state.copyWith(history: state.history.where((h) => h != q).toList()));
  void clearHistory() => _set(state.copyWith(history: []));

  /// 播放过程中补充真实时长（元数据里没有时长的文件）。
  void patchTrack(Track t) {
    if (t.demo) return;
    if (t.isLocal) {
      final i = state.tracks.indexWhere((e) => e.id == t.id);
      if (i < 0) return;
      final list = [...state.tracks]..[i] = t;
      _set(state.copyWith(tracks: list));
    }
  }
}

Map<String, dynamic> _readMeta(String path) {
  final out = <String, dynamic>{};
  try {
    final m = readMetadata(File(path), getImage: true);
    out['title'] = m.title;
    out['artist'] = m.artist ?? m.albumArtist;
    out['album'] = m.album;
    out['durationMs'] = m.duration?.inMilliseconds;
    out['bitrate'] = m.bitrate;
    out['sampleRate'] = m.sampleRate;
    out['lyrics'] = m.lyrics;
    if (m.pictures.isNotEmpty) {
      final pic = m.pictures.first;
      out['cover'] = pic.bytes;
      out['coverExt'] = pic.mimetype.contains('png') ? 'png' : 'jpg';
    }
  } catch (_) {}
  return out;
}
