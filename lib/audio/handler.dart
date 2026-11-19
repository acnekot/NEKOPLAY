import 'dart:async';

import 'package:audio_service/audio_service.dart';

import '../data/track.dart';
import 'engine.dart';

/// audio_service 外壳：把引擎状态同步给系统（通知栏、锁屏、耳机键、CarPlay / Android Auto 的基础控制）。
class NekoAudioHandler extends BaseAudioHandler with SeekHandler {
  NekoAudioHandler(this.engine) {
    _sub = engine.stream.listen(_sync);
    _sync(engine.value);
  }

  final PlaybackEngine engine;
  StreamSubscription<PlayerSnapshot>? _sub;
  String? _lastId;
  int _lastDur = -1;

  Uri? _art(Track t) {
    if (t.coverPath != null) return Uri.file(t.coverPath!);
    if (t.coverUrl != null) return Uri.tryParse(t.coverUrl!);
    return null;
  }

  void _sync(PlayerSnapshot s) {
    final t = s.current;
    if (t == null) {
      if (_lastId != null) {
        _lastId = null;
        mediaItem.add(null);
      }
      playbackState.add(PlaybackState(processingState: AudioProcessingState.idle, playing: false));
      return;
    }
    if (_lastId != t.id || _lastDur != s.durationMs) {
      _lastId = t.id;
      _lastDur = s.durationMs;
      mediaItem.add(MediaItem(
        id: t.id,
        title: t.title,
        artist: t.artist,
        album: t.album.isEmpty ? null : t.album,
        duration: s.durationMs > 0 ? Duration(milliseconds: s.durationMs) : null,
        artUri: _art(t),
      ));
    }
    playbackState.add(PlaybackState(
      controls: [
        MediaControl.skipToPrevious,
        s.playing ? MediaControl.pause : MediaControl.play,
        MediaControl.skipToNext,
      ],
      systemActions: const {
        MediaAction.seek,
        MediaAction.seekForward,
        MediaAction.seekBackward,
        MediaAction.skipToNext,
        MediaAction.skipToPrevious,
        MediaAction.play,
        MediaAction.pause,
      },
      androidCompactActionIndices: const [0, 1, 2],
      processingState: s.loading ? AudioProcessingState.loading : AudioProcessingState.ready,
      playing: s.playing,
      updatePosition: Duration(milliseconds: s.anchorMs),
      updateTime: DateTime.fromMillisecondsSinceEpoch(s.anchorAt),
      speed: 1.0,
      queueIndex: s.index < 0 ? null : s.index,
    ));
  }

  @override
  Future<void> play() => engine.play();

  @override
  Future<void> pause() => engine.pause();

  @override
  Future<void> stop() async {
    await engine.pause();
    await super.stop();
  }

  @override
  Future<void> seek(Duration position) => engine.seek(position.inMilliseconds);

  @override
  Future<void> skipToNext() => engine.next();

  @override
  Future<void> skipToPrevious() => engine.previous();

  @override
  Future<void> skipToQueueItem(int index) => engine.playIndex(index);

  Future<void> dispose() async => _sub?.cancel();
}
