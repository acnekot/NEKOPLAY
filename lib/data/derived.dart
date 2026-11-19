import '../core/format.dart';
import '../nav/nav.dart';
import 'library.dart';
import 'track.dart';

class Album {
  Album(this.key, this.title, this.artist, this.tracks);
  final String key, title, artist;
  final List<Track> tracks;
  Track get cover => tracks.first;
}

List<Album> albumsOf(LibraryState lib) {
  final map = <String, Album>{};
  for (final t in lib.all) {
    final key = '${t.albumKey}|${t.albumArtist}';
    map.putIfAbsent(key, () => Album(key, t.albumKey, t.albumArtist, [])).tracks.add(t);
  }
  return map.values.toList();
}

int totalMs(Iterable<Track> l) => l.fold(0, (a, t) => a + t.durationMs);

class ListView0 {
  ListView0({required this.ref, required this.title, required this.tag, required this.tracks, this.cover, this.sysIcon, this.sub});
  final ListRef ref;
  final String title, tag;
  final List<Track> tracks;
  final Track? cover; // 歌单 / 专辑封面
  final String? sysIcon; // liked | local | recent
  final String? sub; // 专辑：艺人
  String get meta => '${sub != null ? '$sub · ' : ''}${listMeta(tracks.length, totalMs(tracks))}';
}

ListView0 resolveList(LibraryState lib, ListRef r) {
  switch (r.kind) {
    case ListKind.liked:
      return ListView0(ref: r, title: '我喜欢', tag: 'COLLECTION', tracks: lib.resolve(lib.likes), sysIcon: 'liked');
    case ListKind.local:
      return ListView0(ref: r, title: '本地音乐', tag: 'COLLECTION', tracks: lib.all, sysIcon: 'local');
    case ListKind.recent:
      return ListView0(ref: r, title: '最近播放', tag: 'COLLECTION', tracks: lib.resolve(lib.recents), sysIcon: 'recent');
    case ListKind.daily:
      return ListView0(ref: r, title: '每日推荐', tag: 'DAILY MIX', tracks: const []);
    case ListKind.cloud:
      return ListView0(ref: r, title: '云端 · 我喜欢', tag: 'CLOUD', tracks: const [], sysIcon: 'liked');
    case ListKind.album:
      final a = albumsOf(lib).where((e) => e.key == r.id).firstOrNull;
      if (a == null) return ListView0(ref: r, title: '专辑', tag: 'ALBUM', tracks: const []);
      return ListView0(ref: r, title: a.title, tag: 'ALBUM', tracks: a.tracks, cover: a.cover, sub: a.artist);
    case ListKind.playlist:
      final p = lib.playlists.where((e) => e.id == r.id).firstOrNull;
      if (p == null) return ListView0(ref: r, title: '歌单', tag: 'PLAYLIST', tracks: const []);
      final tr = lib.resolve(p.trackIds);
      return ListView0(ref: r, title: p.name, tag: 'PLAYLIST', tracks: tr, cover: tr.isEmpty ? null : tr.first);
  }
}
