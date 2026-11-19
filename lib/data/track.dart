import '../core/format.dart';

/// 曲目：本地文件 / 插件在线曲目 / 示例曲目（无音频，仅演示界面）。
class Track {
  Track({
    required this.id,
    required this.title,
    required this.artist,
    this.album = '',
    this.durationMs = 0,
    this.path,
    this.url,
    this.headers,
    this.coverPath,
    this.coverAsset,
    this.coverUrl,
    this.format = '',
    this.spec = '',
    this.bitrate = 0,
    this.source = 'local',
    this.extra = const {},
    this.lrcPath,
    this.lyricsText,
    this.demo = false,
    this.addedAt = 0,
  });

  final String id;
  final String title;
  final String artist;
  final String album;
  final int durationMs;
  final String? path;
  final String? url;
  final Map<String, String>? headers;
  final String? coverPath;
  final String? coverAsset;
  final String? coverUrl;
  final String format;
  final String spec;
  final int bitrate;
  final String source;
  final Map<String, String> extra;
  final String? lrcPath;
  final String? lyricsText;
  final bool demo;
  final int addedAt;

  bool get isLocal => source == 'local';
  bool get isOnline => !isLocal && !demo;
  String get durStr => fmtMs(durationMs);
  String get albumKey => (album.isEmpty ? title : album);
  String get albumArtist => artist.split(' / ').first;

  bool get lossless => const ['FLAC', 'WAV', 'ALAC', 'APE', 'AIFF', 'AIF'].contains(format.toUpperCase());

  String get qualityLine1 {
    if (format.isEmpty) return 'AUDIO';
    return spec.isEmpty ? format.toUpperCase() : '${format.toUpperCase()} · $spec';
  }

  String get qualityLine2 {
    final a = lossless ? '无损' : '有损';
    return bitrate > 0 ? '$a · $bitrate kbps' : a;
  }

  Track copyWith({
    int? durationMs,
    String? url,
    Map<String, String>? headers,
    String? format,
    String? spec,
    int? bitrate,
    String? lrcPath,
    String? title,
    String? artist,
    String? album,
  }) =>
      Track(
        id: id,
        title: title ?? this.title,
        artist: artist ?? this.artist,
        album: album ?? this.album,
        durationMs: durationMs ?? this.durationMs,
        path: path,
        url: url ?? this.url,
        headers: headers ?? this.headers,
        coverPath: coverPath,
        coverAsset: coverAsset,
        coverUrl: coverUrl,
        format: format ?? this.format,
        spec: spec ?? this.spec,
        bitrate: bitrate ?? this.bitrate,
        source: source,
        extra: extra,
        lrcPath: lrcPath ?? this.lrcPath,
        lyricsText: lyricsText,
        demo: demo,
        addedAt: addedAt,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'artist': artist,
        'album': album,
        'durationMs': durationMs,
        'path': path,
        'url': url,
        'coverPath': coverPath,
        'coverAsset': coverAsset,
        'coverUrl': coverUrl,
        'format': format,
        'spec': spec,
        'bitrate': bitrate,
        'source': source,
        'extra': extra,
        'lrcPath': lrcPath,
        'lyricsText': lyricsText,
        'demo': demo,
        'addedAt': addedAt,
      };

  factory Track.fromJson(Map<String, dynamic> j) => Track(
        id: j['id'] as String,
        title: (j['title'] as String?) ?? '未知曲目',
        artist: (j['artist'] as String?) ?? '未知艺人',
        album: (j['album'] as String?) ?? '',
        durationMs: (j['durationMs'] as num?)?.toInt() ?? 0,
        path: j['path'] as String?,
        url: j['url'] as String?,
        coverPath: j['coverPath'] as String?,
        coverAsset: j['coverAsset'] as String?,
        coverUrl: j['coverUrl'] as String?,
        format: (j['format'] as String?) ?? '',
        spec: (j['spec'] as String?) ?? '',
        bitrate: (j['bitrate'] as num?)?.toInt() ?? 0,
        source: (j['source'] as String?) ?? 'local',
        extra: ((j['extra'] as Map?) ?? const {}).map((k, v) => MapEntry('$k', '$v')),
        lrcPath: j['lrcPath'] as String?,
        lyricsText: j['lyricsText'] as String?,
        demo: (j['demo'] as bool?) ?? false,
        addedAt: (j['addedAt'] as num?)?.toInt() ?? 0,
      );

  /// 示例数据：data/tracks.json 的 6 首，封面来自 covers/。
  static List<Track> demoTracks() {
    Track t(int i, String title, String artist, int sec, int cover) => Track(
          id: 'demo-$i',
          title: title,
          artist: artist,
          album: title,
          durationMs: sec * 1000,
          coverAsset: 'assets/covers/cover$cover.jpg',
          format: 'FLAC',
          spec: '24bit / 96kHz',
          bitrate: 2304,
          demo: true,
        );
    return [
      t(0, 'Lament Rain (哀叹之雨)', '打打だいず / Ashrount', 165, 1),
      t(1, 'DIE IN', 'TAK / Sobrem', 152, 0),
      t(2, 'マイバッド', 'はるまきごはん', 168, 2),
      t(3, 'photopsia star', 'Fujiyama', 224, 3),
      t(4, '余裕欲 (从容欲)', '稲葉曇 / nagiβ / カゼヒキβ', 174, 4),
      t(5, 'ワンダー', 'r-906 / 初音ミク', 180, 5),
    ];
  }
}

class UserPlaylist {
  UserPlaylist({required this.id, required this.name, List<String>? trackIds, this.coverTrackId})
      : trackIds = trackIds ?? [];
  final String id;
  String name;
  final List<String> trackIds;
  String? coverTrackId;

  Map<String, dynamic> toJson() =>
      {'id': id, 'name': name, 'trackIds': trackIds, 'coverTrackId': coverTrackId};

  factory UserPlaylist.fromJson(Map<String, dynamic> j) => UserPlaylist(
        id: j['id'] as String,
        name: (j['name'] as String?) ?? '歌单',
        trackIds: ((j['trackIds'] as List?) ?? const []).map((e) => '$e').toList(),
        coverTrackId: j['coverTrackId'] as String?,
      );
}
