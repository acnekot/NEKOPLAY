import 'dart:async';
import 'dart:math';

import 'package:audio_session/audio_session.dart';
import 'package:just_audio/just_audio.dart';

import '../data/track.dart';

enum PlayMode { sequence, shuffle, repeatOne }

const kModeLabels = ['顺序播放', '随机播放', '单曲循环'];

int nowMs() => DateTime.now().millisecondsSinceEpoch;

/// 播放地址（本地文件之外的曲目由插件解析得到）。
class ResolvedStream {
  const ResolvedStream({
    required this.url,
    this.headers,
    this.format,
    this.bitrate,
    this.sampleRate,
    this.bitDepth,
    this.quality,
  });
  final String url;
  final Map<String, String>? headers;
  final String? format;
  final int? bitrate, sampleRate, bitDepth;
  final String? quality;
}

class PlayerSnapshot {
  const PlayerSnapshot({
    this.queue = const [],
    this.index = -1,
    this.playing = false,
    this.loading = false,
    this.mode = PlayMode.sequence,
    this.anchorMs = 0,
    this.anchorAt = 0,
    this.durationMs = 0,
    this.error,
    this.errorSeq = 0,
  });

  final List<Track> queue;
  final int index;
  final bool playing, loading;
  final PlayMode mode;

  /// 位置锚点：playing 时当前位置 = anchorMs + (now - anchorAt)，用于 60fps 平滑进度，不必高频推送。
  final int anchorMs, anchorAt, durationMs;
  final String? error;
  final int errorSeq;

  Track? get current => index >= 0 && index < queue.length ? queue[index] : null;

  int positionAt(int now) {
    final p = playing ? anchorMs + (now - anchorAt) : anchorMs;
    if (durationMs > 0) return p.clamp(0, durationMs);
    return max(0, p);
  }

  PlayerSnapshot copyWith({
    List<Track>? queue,
    int? index,
    bool? playing,
    bool? loading,
    PlayMode? mode,
    int? anchorMs,
    int? anchorAt,
    int? durationMs,
    String? error,
    bool clearError = false,
  }) =>
      PlayerSnapshot(
        queue: queue ?? this.queue,
        index: index ?? this.index,
        playing: playing ?? this.playing,
        loading: loading ?? this.loading,
        mode: mode ?? this.mode,
        anchorMs: anchorMs ?? this.anchorMs,
        anchorAt: anchorAt ?? this.anchorAt,
        durationMs: durationMs ?? this.durationMs,
        error: clearError ? null : (error ?? this.error),
        errorSeq: error != null ? errorSeq + 1 : errorSeq,
      );
}

typedef StreamResolver = Future<ResolvedStream?> Function(Track t);

/// 播放引擎：自己管理队列，just_audio 一次只播一首；
/// 示例曲目（没有音频文件）走虚拟时钟，方便预览界面。
class PlaybackEngine {
  PlaybackEngine();

  // 缓冲只留 15~25 秒、最多约 4MB（默认 50 秒，高码率时会占到十几 MB）
  final AudioPlayer _player = AudioPlayer(
    audioLoadConfiguration: const AudioLoadConfiguration(
      androidLoadControl: AndroidLoadControl(
        minBufferDuration: Duration(seconds: 15),
        maxBufferDuration: Duration(seconds: 25),
        bufferForPlaybackDuration: Duration(milliseconds: 1500),
        bufferForPlaybackAfterRebufferDuration: Duration(seconds: 4),
        targetBufferBytes: 4 * 1024 * 1024,
        backBufferDuration: Duration.zero,
      ),
    ),
  );
  final _ctl = StreamController<PlayerSnapshot>.broadcast();
  PlayerSnapshot _s = const PlayerSnapshot();
  Timer? _vEnd;
  Timer? _fade;
  int _gen = 0;
  bool _virtual = false;
  bool _handledEnd = false;
  bool _resumeAfterInterrupt = false;
  final _rng = Random();

  StreamResolver? resolver;
  void Function(Track t)? onTrackStarted;
  void Function(Track t)? onTrackPatched;
  void Function(PlayerSnapshot s)? onPersist;
  bool Function()? fadeEnabled;

  Stream<PlayerSnapshot> get stream => _ctl.stream;
  PlayerSnapshot get value => _s;

  void _emit(PlayerSnapshot s, {bool persist = false}) {
    _s = s;
    if (!_ctl.isClosed) _ctl.add(s);
    if (persist) onPersist?.call(s);
  }

  Future<void> init() async {
    try {
      final session = await AudioSession.instance;
      await session.configure(const AudioSessionConfiguration.music());
      session.interruptionEventStream.listen((e) {
        if (e.begin) {
          if (e.type == AudioInterruptionType.duck) {
            _player.setVolume(.4);
          } else if (_s.playing) {
            _resumeAfterInterrupt = true;
            pause();
          }
        } else {
          if (e.type == AudioInterruptionType.duck) {
            _player.setVolume(1);
          } else if (_resumeAfterInterrupt) {
            _resumeAfterInterrupt = false;
            play();
          }
        }
      });
      session.becomingNoisyEventStream.listen((_) => pause());
    } catch (_) {}

    _player.playerStateStream.listen((st) {
      if (_virtual) return;
      if (st.processingState == ProcessingState.completed) {
        if (!_handledEnd) {
          _handledEnd = true;
          _onEnded();
        }
        return;
      }
      final loading = st.processingState == ProcessingState.loading || st.processingState == ProcessingState.buffering;
      _emit(_s.copyWith(
        playing: st.playing,
        loading: loading,
        anchorMs: _player.position.inMilliseconds,
        anchorAt: nowMs(),
      ));
    });
    _player.playbackEventStream.listen((e) {
      if (_virtual) return;
      _emit(_s.copyWith(anchorMs: _player.position.inMilliseconds, anchorAt: nowMs()));
    }, onError: (Object e, StackTrace st) {});
    _player.durationStream.listen((d) {
      if (_virtual || d == null || d.inMilliseconds <= 0) return;
      final t = _s.current;
      if (t == null) return;
      if ((t.durationMs - d.inMilliseconds).abs() > 1500) {
        final nt = t.copyWith(durationMs: d.inMilliseconds);
        final q = [..._s.queue]..[_s.index] = nt;
        _emit(_s.copyWith(queue: q, durationMs: d.inMilliseconds));
        onTrackPatched?.call(nt);
      }
    });
  }

  // ---------- 队列 ----------
  Future<void> setQueue(List<Track> queue, int start,
      {PlayMode? mode, bool autoplay = true, int startMs = 0}) async {
    if (queue.isEmpty) return;
    _emit(_s.copyWith(queue: List.of(queue), index: start, mode: mode ?? _s.mode));
    await _load(start, autoplay: autoplay, startMs: startMs);
  }

  Future<void> playIndex(int i) async {
    if (i < 0 || i >= _s.queue.length) return;
    await _load(i);
  }

  Future<void> removeAt(int i) async {
    if (i < 0 || i >= _s.queue.length) return;
    final q = [..._s.queue]..removeAt(i);
    if (q.isEmpty) {
      await stop();
      _emit(const PlayerSnapshot().copyWith(mode: _s.mode), persist: true);
      return;
    }
    if (i == _s.index) {
      final wasPlaying = _s.playing;
      _emit(_s.copyWith(queue: q));
      await _load(min(i, q.length - 1), autoplay: wasPlaying);
    } else {
      _emit(_s.copyWith(queue: q, index: i < _s.index ? _s.index - 1 : _s.index), persist: true);
    }
  }

  void clearUpcoming() {
    final c = _s.current;
    if (c == null) return;
    _emit(_s.copyWith(queue: [c], index: 0), persist: true);
  }

  Future<void> enqueueNext(Track t) async {
    if (_s.queue.isEmpty) return setQueue([t], 0);
    final q = [..._s.queue]..insert(_s.index + 1, t);
    _emit(_s.copyWith(queue: q), persist: true);
  }

  Future<void> enqueue(Track t) async {
    if (_s.queue.isEmpty) return setQueue([t], 0);
    _emit(_s.copyWith(queue: [..._s.queue, t]), persist: true);
  }

  void setMode(PlayMode m) => _emit(_s.copyWith(mode: m), persist: true);
  void cycleMode() => setMode(PlayMode.values[(_s.mode.index + 1) % 3]);

  // ---------- 加载 / 播放 ----------
  Future<void> _load(int index, {bool autoplay = true, int startMs = 0}) async {
    final gen = ++_gen;
    _vEnd?.cancel();
    _fade?.cancel();
    _handledEnd = false;
    var t = _s.queue[index];
    _emit(_s.copyWith(
      index: index,
      loading: !t.demo,
      playing: false,
      anchorMs: startMs,
      anchorAt: nowMs(),
      durationMs: t.durationMs,
      clearError: true,
    ));
    if (autoplay) onTrackStarted?.call(t);

    if (t.demo) {
      _virtual = true;
      try {
        await _player.stop();
      } catch (_) {}
      if (gen != _gen) return;
      _emit(_s.copyWith(loading: false, playing: autoplay, anchorMs: startMs, anchorAt: nowMs()), persist: true);
      if (autoplay) _vSchedule();
      return;
    }

    _virtual = false;
    try {
      String uri;
      Map<String, String>? headers = t.headers;
      if (t.path != null) {
        uri = Uri.file(t.path!).toString();
      } else {
        final r = await resolver?.call(t);
        if (gen != _gen) return;
        if (r == null) throw '无法获取播放地址';
        uri = r.url;
        headers = r.headers;
        t = t.copyWith(
          url: r.url,
          format: r.format ?? t.format,
          bitrate: r.bitrate ?? t.bitrate,
          spec: r.sampleRate != null
              ? '${r.bitDepth != null ? '${r.bitDepth}bit / ' : ''}${_khz(r.sampleRate!)}kHz'
              : t.spec,
        );
        final q = [..._s.queue]..[index] = t;
        _emit(_s.copyWith(queue: q));
      }
      await _player.setAudioSource(
        AudioSource.uri(Uri.parse(uri), headers: headers),
        initialPosition: startMs > 0 ? Duration(milliseconds: startMs) : null,
      );
      if (gen != _gen) return;
      if (autoplay) {
        _startPlayback();
      } else {
        _emit(_s.copyWith(loading: false), persist: true);
      }
    } catch (e) {
      if (gen != _gen) return;
      _emit(_s.copyWith(loading: false, playing: false, error: '无法播放「${t.title}」：${_why(e)}'), persist: true);
    }
  }

  static String _khz(int sr) {
    final v = sr / 1000;
    return v == v.roundToDouble() ? v.round().toString() : v.toStringAsFixed(1);
  }

  String _why(Object e) {
    final s = '$e';
    if (e is PlayerException) return '文件无法解码';
    if (s.length > 60) return '${s.substring(0, 60)}…';
    return s;
  }

  void _startPlayback() {
    if (fadeEnabled?.call() ?? false) {
      _player.setVolume(0);
      _player.play();
      var step = 0;
      _fade?.cancel();
      _fade = Timer.periodic(const Duration(milliseconds: 40), (tm) {
        step++;
        _player.setVolume((step / 10).clamp(0, 1));
        if (step >= 10) tm.cancel();
      });
    } else {
      _player.setVolume(1);
      _player.play();
    }
  }

  void _vSchedule() {
    _vEnd?.cancel();
    final remain = _s.durationMs - _s.positionAt(nowMs());
    _vEnd = Timer(Duration(milliseconds: max(remain, 0)), _onEnded);
  }

  Future<void> play() async {
    final t = _s.current;
    if (t == null) return;
    if (_virtual) {
      final pos = _s.positionAt(nowMs());
      final start = pos >= _s.durationMs - 100 ? 0 : pos;
      _emit(_s.copyWith(playing: true, anchorMs: start, anchorAt: nowMs()), persist: true);
      _vSchedule();
      return;
    }
    if (_player.processingState == ProcessingState.idle) {
      await _load(_s.index, startMs: _s.anchorMs);
      return;
    }
    if (_player.processingState == ProcessingState.completed) {
      _handledEnd = false;
      await _player.seek(Duration.zero);
    }
    _startPlayback();
  }

  Future<void> pause() async {
    _fade?.cancel();
    if (_virtual) {
      _vEnd?.cancel();
      _emit(_s.copyWith(playing: false, anchorMs: _s.positionAt(nowMs()), anchorAt: nowMs()), persist: true);
      return;
    }
    await _player.pause();
    _emit(_s.copyWith(playing: false, anchorMs: _player.position.inMilliseconds, anchorAt: nowMs()), persist: true);
  }

  Future<void> toggle() => _s.playing ? pause() : play();

  Future<void> stop() async {
    _gen++;
    _vEnd?.cancel();
    _fade?.cancel();
    try {
      await _player.stop();
    } catch (_) {}
  }

  Future<void> seek(int ms) async {
    final t = _s.current;
    if (t == null) return;
    final max0 = _s.durationMs > 0 ? _s.durationMs - 1 : ms;
    final target = ms.clamp(0, max0);
    _emit(_s.copyWith(anchorMs: target, anchorAt: nowMs()));
    if (_virtual) {
      if (_s.playing) _vSchedule();
      return;
    }
    _handledEnd = false;
    await _player.seek(Duration(milliseconds: target));
  }

  int _nextIndex() {
    final n = _s.queue.length;
    if (n <= 1) return _s.index;
    if (_s.mode == PlayMode.shuffle) {
      var i = _s.index;
      while (i == _s.index) {
        i = _rng.nextInt(n);
      }
      return i;
    }
    return (_s.index + 1) % n;
  }

  Future<void> next() async {
    if (_s.queue.isEmpty) return;
    final wasPlaying = _s.playing || _s.loading;
    await _load(_nextIndex(), autoplay: wasPlaying || true);
  }

  Future<void> previous() async {
    if (_s.queue.isEmpty) return;
    if (_s.positionAt(nowMs()) > 3000) {
      await seek(0);
      return;
    }
    final n = _s.queue.length;
    await _load((_s.index - 1 + n) % n);
  }

  Future<void> _onEnded() async {
    if (_s.queue.isEmpty) return;
    if (_s.mode == PlayMode.repeatOne) {
      _handledEnd = false;
      if (_virtual) {
        _emit(_s.copyWith(anchorMs: 0, anchorAt: nowMs(), playing: true));
        _vSchedule();
      } else {
        await _player.seek(Duration.zero);
        _player.play();
      }
      return;
    }
    final n = _nextIndex();
    if (n == _s.index) {
      // 只有一首：播完停在开头
      _emit(_s.copyWith(playing: false, anchorMs: 0, anchorAt: nowMs()), persist: true);
      if (!_virtual) {
        try {
          await _player.pause();
          await _player.seek(Duration.zero);
        } catch (_) {}
      }
      return;
    }
    await _load(n);
  }

  Future<void> dispose() async {
    _vEnd?.cancel();
    _fade?.cancel();
    await _player.dispose();
    await _ctl.close();
  }
}
