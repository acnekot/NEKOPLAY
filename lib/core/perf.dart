import 'dart:async';
import 'dart:io';
import 'dart:ui' show FrameTiming;

import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';

/// 性能监测：每秒统计一次本进程 CPU、帧率、构建/渲染耗时、卡顿帧，以及能读到的 GPU 占用。
class PerfStat {
  const PerfStat({this.cpu = 0, this.cpuPeak = 0, this.fps = 0, this.buildMs = 0, this.rasterMs = 0, this.jank = 0, this.gpu});
  final double cpu, cpuPeak, buildMs, rasterMs;
  final int fps, jank;
  final String? gpu;
}

class Perf {
  Perf._();
  static final stat = ValueNotifier(const PerfStat());
  static final overlay = ValueNotifier(false);
  static Timer? _t;
  static int _lastTicks = -1;
  static DateTime _lastAt = DateTime.now();
  static double _peak = 0;
  static int _frames = 0, _jank = 0;
  static int _buildUs = 0, _rasterUs = 0;

  static void start() {
    if (_t != null) return;
    SchedulerBinding.instance.addTimingsCallback(_onTimings);
    _t = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
  }

  static void _onTimings(List<FrameTiming> l) {
    for (final f in l) {
      _frames++;
      _buildUs += f.buildDuration.inMicroseconds;
      _rasterUs += f.rasterDuration.inMicroseconds;
      if (f.totalSpan.inMicroseconds > 16700) _jank++;
    }
  }

  /// /proc/self/stat 第 14、15 项是本进程用户态 / 内核态的累计时钟数（每秒 100 个）
  static int _cpuTicks() {
    final s = File('/proc/self/stat').readAsStringSync();
    final f = s.substring(s.lastIndexOf(')') + 2).split(' ');
    return int.parse(f[11]) + int.parse(f[12]);
  }

  static const _gpuPaths = [
    '/sys/kernel/ged/hal/gpu_utilization', // 天玑
    '/sys/class/misc/mali0/device/utilization',
    '/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage', // 骁龙
    '/sys/kernel/gpu/gpu_busy',
  ];

  static String? _gpu() {
    for (final p in _gpuPaths) {
      try {
        final v = File(p).readAsStringSync().trim();
        if (v.isNotEmpty) return '${v.split(RegExp(r'\s+')).first.replaceAll('%', '')}%';
      } catch (_) {}
    }
    return null;
  }

  static void _tick() {
    double cpu = 0;
    try {
      final now = DateTime.now();
      final ticks = _cpuTicks();
      if (_lastTicks >= 0) {
        final sec = now.difference(_lastAt).inMicroseconds / 1e6;
        // 换算成「占整机 CPU 的百分比」
        cpu = (ticks - _lastTicks) / 100 / sec / Platform.numberOfProcessors * 100;
      }
      _lastTicks = ticks;
      _lastAt = now;
    } catch (_) {}
    if (cpu > _peak) _peak = cpu;
    final n = _frames;
    stat.value = PerfStat(
      cpu: cpu,
      cpuPeak: _peak,
      fps: n,
      buildMs: n == 0 ? 0 : _buildUs / n / 1000,
      rasterMs: n == 0 ? 0 : _rasterUs / n / 1000,
      jank: _jank,
      gpu: _gpu(),
    );
    _frames = 0;
    _buildUs = 0;
    _rasterUs = 0;
  }

  static String line(PerfStat s) =>
      'CPU ${s.cpu.toStringAsFixed(1)}%（峰值 ${s.cpuPeak.toStringAsFixed(0)}%）· ${s.fps} 帧/秒\n'
      '构建 ${s.buildMs.toStringAsFixed(1)}ms · 渲染(GPU线程) ${s.rasterMs.toStringAsFixed(1)}ms · 卡顿帧累计 ${s.jank}\n'
      'GPU 整机占用 ${s.gpu ?? '系统不允许读取'}';
}
