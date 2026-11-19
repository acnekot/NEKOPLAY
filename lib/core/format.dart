String two(int n) => n.toString().padLeft(2, '0');

/// 毫秒 → 02:45（超过一小时为 1:02:45）
String fmtMs(num ms) {
  final total = (ms / 1000).floor().clamp(0, 1 << 30);
  final h = total ~/ 3600;
  final m = (total % 3600) ~/ 60;
  final s = total % 60;
  if (h > 0) return '$h:${two(m)}:${two(s)}';
  return '${two(m)}:${two(s)}';
}

String fmtDuration(int totalMs) => fmtMs(totalMs);

/// 06 首 · 17:43
String listMeta(int count, int totalMs) => '${two(count)} 首 · ${fmtMs(totalMs)}';
