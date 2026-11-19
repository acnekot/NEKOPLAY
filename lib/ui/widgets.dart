import 'dart:async';
import 'dart:io';

import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../audio/engine.dart';
import '../core/format.dart';
import '../core/icons.dart';
import '../core/theme.dart';
import '../data/settings.dart';
import '../data/track.dart';
import '../plugins/plugin_manager.dart';
import '../state.dart';

/// 内置音源的显示名（界面上不暴露内部 id）。
String? sourceName(String? id) => id == null ? null : (id == 'ncm-direct' ? '网易云' : id);

/// 网易云图片按实际显示大小取合适的尺寸（小图加载更快），其他地址原样返回。
int imageBucket(double px) => px <= 160 ? 160 : px <= 256 ? 256 : px <= 512 ? 512 : 768;

String sizedImage(String url, double px) {
  if (!url.contains('music.126.net')) return url;
  final b = imageBucket(px) > 800 ? 800 : imageBucket(px);
  final base = url.split('?').first;
  // /obj/ 开头的新式图片（用户歌单封面等）不认 param，用 imageView 缩略图，webp 只有原图的几十分之一
  if (base.contains('/obj/')) return '$base?imageView=1&thumbnail=${b}x$b&type=webp';
  return '$base?param=${b}y$b';
}

/// 封面专用缓存：最多 3000 张（默认只有 200 张，大歌单滑一圈就被挤掉重下），同时最多 4 个下载，
/// 请求头模拟浏览器，避免网易云图片服务器把批量请求当成爬虫返回 403。
final imageCacheManager = CacheManager(Config(
  'neko_covers',
  stalePeriod: const Duration(days: 60),
  maxNrOfCacheObjects: 3000,
  fileService: HttpFileService()..concurrentFetches = 4,
));

const imageHeaders = {
  'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36',
  'Referer': 'https://music.163.com/',
};

/// 网易云图片换一个 CDN 节点（p1~p4 内容相同）
String _altHost(String u) => u.replaceFirstMapped(RegExp(r'//p(\d)\.music\.126\.net'), (m) => '//p${m[1] == '3' ? 4 : 3}.music.126.net');

/// 带磁盘缓存的网络图片：第二次打开直接出图，滑动时不再是灰块。
Widget cachedImage(String url, {required double size, required double dpr, Widget Function()? fallback, BoxFit fit = BoxFit.cover}) {
  final px = size * dpr;
  final is163 = url.contains('music.126.net');
  return CachedNetworkImage(
    imageUrl: sizedImage(url, px),
    cacheManager: imageCacheManager,
    httpHeaders: is163 ? imageHeaders : null,
    fit: fit,
    memCacheWidth: imageBucket(px),
    fadeInDuration: const Duration(milliseconds: 90),
    fadeOutDuration: Duration.zero,
    // 大图还没到时，先把列表里已经缓存的小图放大垫着，不出现灰块
    placeholder: (_, _) => px > 256
        ? CachedNetworkImage(
            imageUrl: sizedImage(url, 160),
            cacheManager: imageCacheManager,
            httpHeaders: is163 ? imageHeaders : null,
            fit: fit,
            memCacheWidth: 160,
            fadeInDuration: Duration.zero,
            placeholder: (_, _) => const SizedBox.shrink(),
            errorWidget: (_, _, _) => const SizedBox.shrink(),
          )
        : const SizedBox.shrink(),
    errorWidget: (_, u, e) {
      imageErrors.value = (count: imageErrors.value.count + 1, last: '$e'.split('\n').first, url: u);
      // 失败时换一个 CDN 节点再试一次
      final alt = _altHost(u);
      if (is163 && alt != u) {
        return CachedNetworkImage(
          imageUrl: alt,
          cacheManager: imageCacheManager,
          httpHeaders: imageHeaders,
          fit: fit,
          memCacheWidth: imageBucket(px),
          errorWidget: (_, _, _) => fallback?.call() ?? const SizedBox.shrink(),
        );
      }
      return fallback?.call() ?? const SizedBox.shrink();
    },
  );
}

/// 图片加载失败的次数和最近一次原因（设置页里显示，方便排查）。
final imageErrors = ValueNotifier<({int count, String last, String url})>((count: 0, last: '', url: ''));

// ---------------------------------------------------------------- 标签
class Tag extends StatelessWidget {
  const Tag(this.text, {super.key});
  final String text;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 1),
      color: c.tagBg,
      child: Text(text, style: NText.sans(11, w: FontWeight.w700, color: c.tagFg, ls: .04, height: 1.6)),
    );
  }
}

/// 标签 + 延长线（+ 右侧附加内容）
class TagRow extends StatelessWidget {
  const TagRow(this.text, {super.key, this.trailing, this.lineEnd = 0});
  final String text;
  final Widget? trailing;
  final double lineEnd;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Row(children: [
      Tag(text),
      Expanded(child: Container(height: 1, margin: EdgeInsets.only(left: 10, right: lineEnd), color: c.l3)),
      if (trailing != null) trailing!,
    ]);
  }
}

// ---------------------------------------------------------------- 点击
/// 按下变淡（原型的 .btn:active），自带无障碍标签与 ≥44 的点击区域。
class Tap extends StatefulWidget {
  const Tap({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.label,
    this.minSize = 0,
    this.behavior = HitTestBehavior.opaque,
    this.pressScale = 1,
    this.haptic = false,
  });
  final Widget child;
  final double pressScale; // 按下时的缩放（按钮用 0.96，整行列表保持 1）
  final bool haptic;
  final VoidCallback? onTap, onLongPress;
  final String? label;
  final double minSize;
  final HitTestBehavior behavior;
  @override
  State<Tap> createState() => _TapState();
}

class _TapState extends State<Tap> {
  bool _down = false;
  @override
  Widget build(BuildContext context) {
    Widget w = AnimatedOpacity(
      opacity: _down ? .7 : 1,
      duration: Duration(milliseconds: _down ? 60 : 220),
      child: widget.child,
    );
    if (widget.pressScale != 1) {
      w = AnimatedScale(
        scale: _down ? widget.pressScale : 1,
        duration: Duration(milliseconds: _down ? 90 : 260),
        curve: _down ? Curves.easeOut : kEase,
        child: w,
      );
    }
    if (widget.minSize > 0) {
      w = ConstrainedBox(constraints: BoxConstraints(minWidth: widget.minSize, minHeight: widget.minSize), child: w);
    }
    return Semantics(
      button: true,
      label: widget.label,
      onTap: widget.onTap,
      onLongPress: widget.onLongPress,
      child: GestureDetector(
        behavior: widget.behavior,
        onTapDown: (_) => setState(() => _down = true),
        onTapCancel: () => setState(() => _down = false),
        onTapUp: (_) => setState(() => _down = false),
        onTap: widget.onTap == null
            ? null
            : () {
                if (widget.haptic) HapticFeedback.selectionClick();
                widget.onTap!();
              },
        onLongPress: widget.onLongPress == null
            ? null
            : () {
                HapticFeedback.mediumImpact();
                widget.onLongPress!();
              },
        child: w,
      ),
    );
  }
}

class SquareBtn extends StatelessWidget {
  const SquareBtn({super.key, required this.icon, required this.label, this.onTap, this.size = 44, this.iconSize = 18, this.filled = false});
  final String icon, label;
  final VoidCallback? onTap;
  final double size, iconSize;
  final bool filled;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Tap(
      pressScale: .96,
      label: label,
      onTap: onTap,
      child: Container(
        width: size,
        height: size,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: filled ? c.p : null,
          border: filled ? null : Border.all(color: c.k),
        ),
        child: NIcon(icon, size: iconSize, color: filled ? Colors.white : c.k),
      ),
    );
  }
}

/// 整条主色按钮：文字在左，图标在右。
class PrimaryBar extends StatelessWidget {
  const PrimaryBar({super.key, required this.label, required this.icon, this.onTap, this.height = 48, this.semantics});
  final String label, icon;
  final VoidCallback? onTap;
  final double height;
  final String? semantics;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Tap(
      pressScale: .96,
      label: semantics ?? label,
      onTap: onTap,
      child: Container(
        height: height,
        padding: const EdgeInsets.symmetric(horizontal: 18),
        color: c.p,
        child: Row(children: [
          Expanded(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(16, w: FontWeight.w700, color: Colors.white))),
          NIcon(icon, size: 22, color: Colors.white),
        ]),
      ),
    );
  }
}

class OutlineBtn extends StatelessWidget {
  const OutlineBtn({super.key, required this.label, this.onTap, this.height = 48, this.fontSize = 15, this.pad = 18, this.color});
  final String label;
  final VoidCallback? onTap;
  final double height, fontSize, pad;
  final Color? color;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Tap(
      pressScale: .96,
      label: label,
      onTap: onTap,
      child: Container(
        height: height,
        padding: EdgeInsets.symmetric(horizontal: pad),
        alignment: Alignment.center,
        decoration: BoxDecoration(border: Border.all(color: color ?? c.k)),
        child: Text(label, style: NText.sans(fontSize, color: color ?? c.k)),
      ),
    );
  }
}

/// 下划线文字按钮（「查看全部」「清空」「上移」…）
class LinkBtn extends StatelessWidget {
  const LinkBtn(this.text, {super.key, this.onTap, this.color, this.size = 13, this.minHeight = 32});
  final String text;
  final VoidCallback? onTap;
  final Color? color;
  final double size, minHeight;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Tap(
      label: text,
      onTap: onTap,
      child: ConstrainedBox(
        constraints: BoxConstraints(minHeight: minHeight, minWidth: 44),
        child: Center(
          widthFactor: 1,
          child: Text(text,
              style: NText.sans(size, color: color ?? c.k).copyWith(
                  decoration: TextDecoration.underline,
                  decorationColor: color ?? c.k,
                  decorationThickness: 1)),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 开关 44×24
class NSwitch extends StatelessWidget {
  const NSwitch({super.key, required this.on, required this.label, this.onChanged, this.enabled = true});
  final bool on, enabled;
  final String label;
  final ValueChanged<bool>? onChanged;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Semantics(
      toggled: on,
      label: label,
      button: true,
      onTap: enabled ? () => onChanged?.call(!on) : null,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: enabled ? () => onChanged?.call(!on) : null,
        child: SizedBox(
          width: 52,
          height: 44,
          child: Center(
            child: Opacity(
              opacity: enabled ? 1 : .45,
              child: Container(
                width: 44,
                height: 24,
                decoration: BoxDecoration(color: on ? c.p : Colors.transparent, border: Border.all(color: c.k)),
                child: AnimatedAlign(
                  duration: const Duration(milliseconds: 150),
                  curve: kEase,
                  alignment: on ? Alignment.centerRight : Alignment.centerLeft,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 3),
                    child: Container(width: 16, height: 16, color: on ? Colors.white : c.k),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 封面
class Cover extends StatelessWidget {
  const Cover(this.track, {super.key, required this.size, this.border = true});
  final Track? track;
  final double size;
  final bool border;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final cw = imageBucket(size * MediaQuery.devicePixelRatioOf(context));
    final t = track;
    Widget img;
    if (t?.coverAsset != null) {
      img = Image.asset(t!.coverAsset!, fit: BoxFit.cover, cacheWidth: cw, gaplessPlayback: true);
    } else if (t?.coverPath != null) {
      img = Image.file(File(t!.coverPath!), fit: BoxFit.cover, cacheWidth: cw, gaplessPlayback: true, errorBuilder: (_, _, _) => _ph(c, t));
    } else if (t?.coverUrl != null) {
      img = cachedImage(t!.coverUrl!, size: size, dpr: MediaQuery.devicePixelRatioOf(context), fallback: () => _ph(c, t));
    } else {
      img = _ph(c, t);
    }
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: c.dark ? const Color(0xFF1B2027) : const Color(0xFFB9D0D9),
        border: border ? Border.all(color: c.k) : null,
      ),
      child: ClipRect(child: SizedBox.expand(child: img)),
    );
  }

  Widget _ph(NekoColors c, Track? t) {
    final ch = (t == null || t.title.isEmpty) ? '♪' : String.fromCharCode(t.title.runes.first).toUpperCase();
    return Center(child: Text(ch, style: NText.sans(size * .42, w: FontWeight.w800, color: c.s)));
  }
}

/// 网络图片封面（歌单封面等），加载失败时回落到图标。
class NetCover extends StatelessWidget {
  const NetCover(this.url, {super.key, required this.size, this.fallbackIcon});
  final String url;
  final double size;
  final String? fallbackIcon;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: c.dark ? const Color(0xFF1B2027) : const Color(0xFFB9D0D9), border: Border.all(color: c.k)),
      child: ClipRect(
        child: SizedBox.expand(
          child: cachedImage(url,
              size: size,
              dpr: MediaQuery.devicePixelRatioOf(context),
              fallback: () => Center(child: NIcon(fallbackIcon ?? NIcons.folder, size: size * .4, color: c.s))),
        ),
      ),
    );
  }
}

/// 账号头像：登录了云端账号就用对方头像，否则显示昵称首字。
class AvatarBox extends ConsumerWidget {
  const AvatarBox({super.key, required this.size, required this.fontSize});
  final double size, fontSize;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = context.c;
    final s = ref.watch(settingsProvider);
    final url = ref.watch(cloudProfileProvider.select((e) => e?.avatar ?? ''));
    final letter = Text(s.avatar, style: NText.sans(fontSize, w: FontWeight.w900, color: Colors.white));
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      clipBehavior: Clip.hardEdge,
      decoration: BoxDecoration(color: c.p, border: Border.all(color: c.k)),
      child: url.isEmpty
          ? letter
          : cachedImage(CloudProfileNotifier.avatarUrl(url), size: 85, dpr: 3, fallback: () => letter),
    );
  }
}

class IconCover extends StatelessWidget {
  const IconCover({super.key, required this.icon, required this.size, this.filled = false, this.iconSize});
  final String icon;
  final double size;
  final double? iconSize;
  final bool filled;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(color: filled ? c.p : null, border: Border.all(color: c.k)),
      child: NIcon(icon, size: iconSize ?? (size > 80 ? 44 : 24), color: filled ? Colors.white : c.k),
    );
  }
}

// ---------------------------------------------------------------- 曲目行
class TrackRow extends StatelessWidget {
  const TrackRow({super.key, required this.track, required this.onTap, this.current = false, this.onLong, this.sourceLabel, this.dim = false});
  final Track track;
  final VoidCallback onTap;
  final VoidCallback? onLong;
  final bool current, dim;
  final String? sourceLabel;

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final fg = current ? c.p : c.k;
    return Tap(
      label: '播放 ${track.title}，${track.artist}',
      onTap: onTap,
      onLongPress: onLong,
      child: Opacity(
        opacity: dim ? .45 : 1,
        child: SizedBox(
          height: 64,
          child: Stack(clipBehavior: Clip.none, children: [
            if (current)
              Positioned(
                left: -10,
                right: -10,
                top: 0,
                bottom: 0,
                child: Container(
                  decoration: BoxDecoration(
                    color: c.hl,
                    border: Border(left: BorderSide(color: c.p, width: 3), bottom: BorderSide(color: c.l1)),
                  ),
                ),
              ),
            Container(
              decoration: BoxDecoration(border: current ? null : Border(bottom: BorderSide(color: c.l1))),
              child: Row(children: [
                Cover(track, size: 44),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(16, w: current ? FontWeight.w700 : FontWeight.w600, color: fg, height: 1.4)),
                    Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(13, color: c.s, height: 1.4)),
                  ]),
                ),
                const SizedBox(width: 12),
                Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [
                  Text(track.durationMs > 0 ? track.durStr : '--:--', style: NText.mono(12, color: fg)),
                  Text(sourceName(sourceLabel) ?? (track.format.isEmpty ? '' : track.format.toUpperCase()),
                      style: NText.mono(11, color: c.s, ls: .06)),
                ]),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------- 位置驱动的重建
/// 以 60fps 读取播放位置（只在播放中跑 Ticker）。granularityMs 控制重建频率。
class PosBuilder extends ConsumerStatefulWidget {
  const PosBuilder({super.key, required this.builder, this.granularityMs = 0});
  final Widget Function(BuildContext context, int posMs, PlayerSnapshot s) builder;
  final int granularityMs;
  @override
  ConsumerState<PosBuilder> createState() => _PosBuilderState();
}

class _PosBuilderState extends ConsumerState<PosBuilder> {
  // 用定时器按需刷新（以前用 Ticker 每帧都触发，播放时整个界面一直满帧重画）
  Timer? _timer;
  int _last = -1;

  void _tick() {
    final s = ref.read(playerProvider);
    final pos = s.positionAt(nowMs());
    final g = widget.granularityMs > 0 ? widget.granularityMs : 250;
    final k = pos ~/ g;
    if (k == _last) return;
    _last = k;
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final s = ref.watch(playerProvider);
    // 页面不在屏幕上（TickerMode 关闭）时也不刷新
    final visible = TickerMode.valuesOf(context).enabled;
    final want = s.playing && visible;
    if (want && _timer == null) {
      final g = widget.granularityMs > 0 ? widget.granularityMs : 250;
      _timer = Timer.periodic(Duration(milliseconds: g.clamp(16, 1000)), (_) => _tick());
    } else if (!want && _timer != null) {
      _timer!.cancel();
      _timer = null;
    }
    return widget.builder(context, s.positionAt(nowMs()), s);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}

// ---------------------------------------------------------------- 进度条（三处统一）
/// 4px 线、平滑前进；平时没有凸点，按住 / 拖动时出现 12px 方形凸点、线变 6px，松手跳转。
class ScrubBar extends ConsumerStatefulWidget {
  const ScrubBar({super.key, this.interactive = true, this.onScrub, this.hitHeight = 16});
  final bool interactive;
  final double hitHeight;

  /// 拖动中的位置（0~1），松手后回调 null。
  final ValueChanged<double?>? onScrub;
  @override
  ConsumerState<ScrubBar> createState() => _ScrubBarState();
}

class _ScrubBarState extends ConsumerState<ScrubBar> {
  double? _frac;

  double _calc(double dx, double w) => (dx / w).clamp(0.0, 1.0);

  /// 进度线每走 1 个物理像素才需要重画一次
  int _granularity(double w) {
    final dur = ref.read(playerProvider).durationMs;
    final px = w * MediaQuery.devicePixelRatioOf(context);
    if (dur <= 0 || px <= 0) return 250;
    return (dur / px).round().clamp(50, 500);
  }

  void _set(double? f) {
    setState(() => _frac = f);
    widget.onScrub?.call(f);
  }

  void _end(bool commit, double w) {
    final f = _frac;
    if (f != null && commit) {
      final s = ref.read(playerProvider);
      if (s.durationMs > 0) ref.read(engineProvider).seek((f * s.durationMs).round());
    }
    _set(null);
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return LayoutBuilder(builder: (context, box) {
      final w = box.maxWidth;
      return Semantics(
        label: '播放进度，按住拖动跳转',
        slider: true,
        child: Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: widget.interactive ? (e) => _set(_calc(e.localPosition.dx, w)) : null,
          onPointerMove: widget.interactive ? (e) => _set(_calc(e.localPosition.dx, w)) : null,
          onPointerUp: widget.interactive ? (_) => _end(true, w) : null,
          onPointerCancel: widget.interactive ? (_) => _end(false, w) : null,
          child: SizedBox(
            height: widget.hitHeight,
            child: PosBuilder(granularityMs: _granularity(w), builder: (context, pos, s) {
              final frac = _frac ?? (s.durationMs > 0 ? (pos / s.durationMs).clamp(0.0, 1.0) : 0.0);
              final dragging = _frac != null;
              final h = dragging ? 6.0 : 4.0;
              return Stack(clipBehavior: Clip.none, alignment: Alignment.centerLeft, children: [
                AnimatedContainer(duration: const Duration(milliseconds: 150), height: h, color: c.l3),
                AnimatedContainer(duration: const Duration(milliseconds: 150), height: h, width: w * frac, color: c.p),
                Positioned(
                  left: w * frac - 6,
                  child: AnimatedScale(
                    scale: dragging ? 1 : .4,
                    duration: const Duration(milliseconds: 150),
                    child: AnimatedOpacity(
                      opacity: dragging ? 1 : 0,
                      duration: const Duration(milliseconds: 150),
                      child: Container(width: 12, height: 12, color: c.p),
                    ),
                  ),
                ),
              ]);
            }),
          ),
        ),
      );
    });
  }
}

/// 02:45 之类的时间行：左已播（主色）右总长。
class TimeRow extends StatelessWidget {
  const TimeRow({super.key, this.scrubFrac, this.size = 12});
  final double? scrubFrac;
  final double size;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return PosBuilder(
      granularityMs: 250,
      builder: (context, pos, s) {
        final shown = scrubFrac != null ? (scrubFrac! * s.durationMs).round() : pos;
        return Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          Text(fmtMs(shown), style: NText.mono(size, color: c.p)),
          Text(fmtMs(s.durationMs), style: NText.mono(size, color: c.s)),
        ]);
      },
    );
  }
}

// ---------------------------------------------------------------- 提示
final toastProvider = NotifierProvider<ToastNotifier, ({int seq, String msg})>(ToastNotifier.new);

class ToastNotifier extends Notifier<({int seq, String msg})> {
  @override
  ({int seq, String msg}) build() => (seq: 0, msg: '');
  void show(String msg) => state = (seq: state.seq + 1, msg: msg);
}

// ---------------------------------------------------------------- 方角对话框 / 底部面板
Future<T?> showNekoDialog<T>(BuildContext context, {required Widget Function(BuildContext) builder}) {
  return showGeneralDialog<T>(
    context: context,
    barrierDismissible: true,
    barrierLabel: '关闭',
    barrierColor: const Color(0x99000000),
    transitionDuration: const Duration(milliseconds: 160),
    pageBuilder: (ctx, _, _) => SafeArea(
      child: Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Material(type: MaterialType.transparency, child: builder(ctx)),
        ),
      ),
    ),
    transitionBuilder: (ctx, a, _, child) => FadeTransition(opacity: a, child: child),
  );
}

class NekoDialogBox extends StatelessWidget {
  const NekoDialogBox({super.key, required this.title, required this.child, this.actions = const []});
  final String title;
  final Widget child;
  final List<Widget> actions;
  @override
  Widget build(BuildContext context) {
    final c = context.c;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 420, maxHeight: 620),
      child: Container(
        decoration: BoxDecoration(color: c.dark ? const Color(0xFF373E47) : const Color(0xFFE6F0F3), border: Border.all(color: c.k)),
        padding: const EdgeInsets.all(18),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: NText.sans(18, w: FontWeight.w800, color: c.k)),
          const SizedBox(height: 12),
          Flexible(child: SingleChildScrollView(child: child)),
          if (actions.isNotEmpty) ...[
            const SizedBox(height: 16),
            Row(mainAxisAlignment: MainAxisAlignment.end, children: [
              for (var i = 0; i < actions.length; i++) ...[if (i > 0) const SizedBox(width: 10), actions[i]],
            ]),
          ],
        ]),
      ),
    );
  }
}

Future<bool> confirmDialog(BuildContext context,
    {required String title, required String body, String ok = '确定', String cancel = '取消', bool danger = false}) async {
  final r = await showNekoDialog<bool>(context, builder: (ctx) {
    final c = ctx.c;
    return NekoDialogBox(
      title: title,
      actions: [
        OutlineBtn(label: cancel, height: 44, onTap: () => Navigator.of(ctx).pop(false)),
        Tap(
          label: ok,
          onTap: () => Navigator.of(ctx).pop(true),
          child: Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 18),
            alignment: Alignment.center,
            color: danger ? c.bad : c.p,
            child: Text(ok, style: NText.sans(15, w: FontWeight.w700, color: Colors.white)),
          ),
        ),
      ],
      child: Text(body, style: NText.sans(14, color: c.s2, height: 1.6)),
    );
  });
  return r ?? false;
}

Future<String?> promptDialog(BuildContext context,
    {required String title, String hint = '', String initial = '', String ok = '确定'}) {
  final ctl = TextEditingController(text: initial);
  return showNekoDialog<String>(context, builder: (ctx) {
    final c = ctx.c;
    return NekoDialogBox(
      title: title,
      actions: [
        OutlineBtn(label: '取消', height: 44, onTap: () => Navigator.of(ctx).pop()),
        Tap(
          label: ok,
          onTap: () => Navigator.of(ctx).pop(ctl.text),
          child: Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 18),
            alignment: Alignment.center,
            color: c.p,
            child: Text(ok, style: NText.sans(15, w: FontWeight.w700, color: Colors.white)),
          ),
        ),
      ],
      child: Container(
        height: 44,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        decoration: BoxDecoration(border: Border.all(color: c.k)),
        child: TextField(
          controller: ctl,
          autofocus: true,
          onSubmitted: (v) => Navigator.of(ctx).pop(v),
          cursorColor: c.p,
          style: NText.sans(15, color: c.k),
          decoration: InputDecoration(border: InputBorder.none, isCollapsed: true, hintText: hint, hintStyle: NText.sans(15, color: c.s)),
        ),
      ),
    );
  });
}

class SheetItem {
  const SheetItem(this.label, this.onTap, {this.danger = false, this.icon});
  final String label;
  final VoidCallback onTap;
  final bool danger;
  final String? icon;
}

Future<void> showNekoSheet(BuildContext context, {required String title, String? subtitle, required List<SheetItem> items}) {
  return showGeneralDialog<void>(
    context: context,
    barrierDismissible: true,
    barrierLabel: '关闭',
    barrierColor: const Color(0x80000000),
    transitionDuration: const Duration(milliseconds: 260),
    pageBuilder: (ctx, _, _) {
      final c = ctx.c;
      return Align(
        alignment: Alignment.bottomCenter,
        child: Material(
          type: MaterialType.transparency,
          child: Container(
            width: double.infinity,
            padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(ctx).bottom),
            decoration: BoxDecoration(color: c.bar, border: Border(top: BorderSide(color: c.k, width: 3))),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 14, 18, 8),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(16, w: FontWeight.w800, color: c.k)),
                  if (subtitle != null) Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(13, color: c.s)),
                ]),
              ),
              for (final it in items)
                Tap(
                  label: it.label,
                  onTap: () {
                    Navigator.of(ctx).pop();
                    it.onTap();
                  },
                  child: Container(
                    height: 52,
                    padding: const EdgeInsets.symmetric(horizontal: 18),
                    alignment: Alignment.centerLeft,
                    decoration: BoxDecoration(border: Border(top: BorderSide(color: c.l1))),
                    child: Text(it.label, style: NText.sans(16, color: it.danger ? c.bad : c.k)),
                  ),
                ),
            ]),
          ),
        ),
      );
    },
    transitionBuilder: (ctx, a, _, child) => SlideTransition(
      position: Tween(begin: const Offset(0, 1), end: Offset.zero).animate(CurvedAnimation(parent: a, curve: kEase, reverseCurve: kEaseIn)),
      child: child,
    ),
  );
}

String modeLabel(PlayMode m) => kModeLabels[m.index];


/// 多字段表单（插件 ctx.prompt 使用）。取消返回 null。
Future<Map<String, String>?> formDialog(BuildContext context, {required String title, required List<Map<String, dynamic>> fields}) {
  final ctls = {for (final f in fields) '${f['key']}': TextEditingController()};
  return showNekoDialog<Map<String, String>>(context, builder: (ctx) {
    final c = ctx.c;
    return NekoDialogBox(
      title: title,
      actions: [
        OutlineBtn(label: '取消', height: 44, onTap: () => Navigator.of(ctx).pop()),
        Tap(
          label: '保存',
          onTap: () => Navigator.of(ctx).pop({for (final e in ctls.entries) e.key: e.value.text}),
          child: Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 18),
            alignment: Alignment.center,
            color: c.p,
            child: Text('保存', style: NText.sans(15, w: FontWeight.w700, color: Colors.white)),
          ),
        ),
      ],
      child: SingleChildScrollView(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          for (final f in fields) ...[
            Text('${f['label'] ?? f['key']}', style: NText.sans(13, color: c.s, height: 1.4)),
            const SizedBox(height: 6),
            Container(
              height: 44,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              decoration: BoxDecoration(border: Border.all(color: c.k)),
              child: TextField(
                controller: ctls['${f['key']}'],
                obscureText: f['type'] == 'password',
                autocorrect: false,
                enableSuggestions: false,
                keyboardType: TextInputType.url,
                cursorColor: c.p,
                style: NText.mono(13, color: c.k),
                decoration: const InputDecoration(border: InputBorder.none, isCollapsed: true),
              ),
            ),
            const SizedBox(height: 12),
          ],
        ]),
      ),
    );
  });
}


/// 单选列表，返回选中序号，取消返回 null。
Future<int?> chooseDialog(BuildContext context, {required String title, required List<String> options}) {
  return showNekoDialog<int>(context, builder: (ctx) {
    final c = ctx.c;
    return NekoDialogBox(
      title: title,
      actions: [OutlineBtn(label: '取消', height: 44, onTap: () => Navigator.of(ctx).pop())],
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        for (var i = 0; i < options.length; i++)
          Tap(
            label: options[i],
            onTap: () => Navigator.of(ctx).pop(i),
            child: Container(
              height: 52,
              alignment: Alignment.centerLeft,
              decoration: BoxDecoration(border: Border(top: BorderSide(color: c.l1))),
              child: Text(options[i], style: NText.sans(16, color: c.k)),
            ),
          ),
      ]),
    );
  });
}

/// 二维码弹窗：点确认返回 true，取消返回 false。
Future<bool> qrDialog(BuildContext context, {required String title, required String text, String hint = '', String confirm = '确认'}) async {
  final r = await showNekoDialog<bool>(context, builder: (ctx) {
    final c = ctx.c;
    return NekoDialogBox(
      title: title,
      actions: [
        OutlineBtn(label: '取消', height: 44, onTap: () => Navigator.of(ctx).pop(false)),
        Tap(
          label: confirm,
          onTap: () => Navigator.of(ctx).pop(true),
          child: Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 18),
            alignment: Alignment.center,
            color: c.p,
            child: Text(confirm, style: NText.sans(15, w: FontWeight.w700, color: Colors.white)),
          ),
        ),
      ],
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Container(
          color: Colors.white,
          padding: const EdgeInsets.all(12),
          child: QrImageView(data: text, size: 200, backgroundColor: Colors.white),
        ),
        if (hint.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 12), child: Text(hint, style: NText.sans(13, color: c.s, height: 1.5))),
      ]),
    );
  });
  return r ?? false;
}
