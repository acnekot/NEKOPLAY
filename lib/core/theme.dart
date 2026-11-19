import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

/// 颜色全部取自 tokens-mobile.css（浅色 / 深色两套）。
class NekoColors extends ThemeExtension<NekoColors> {
  const NekoColors({
    required this.dark,
    required this.k,
    required this.p,
    required this.s,
    required this.s2,
    required this.l1,
    required this.l2,
    required this.l3,
    required this.bar,
    required this.ov,
    required this.pop,
    required this.tagBg,
    required this.tagFg,
    required this.hl,
    required this.lyBg,
    required this.lyFg,
    required this.lyFg2,
    required this.lyBar,
    required this.bad,
    required this.ok,
    required this.mute,
  });

  final bool dark;
  final Color k, p, s, s2, l1, l2, l3, bar, ov, pop, tagBg, tagFg, hl;
  final Color lyBg, lyFg, lyFg2, bad, ok, mute;
  final bool lyBar;

  static const light = NekoColors(
    dark: false,
    k: Color(0xFF0E1117),
    p: Color(0xFF1A4BE0),
    s: Color(0xFF46505E),
    s2: Color(0xFF3D4350),
    l1: Color.fromRGBO(14, 17, 23, .12),
    l2: Color.fromRGBO(14, 17, 23, .20),
    l3: Color.fromRGBO(14, 17, 23, .30),
    bar: Color(0xFFCBE0E7),
    ov: Color.fromRGBO(226, 237, 238, .88),
    pop: Color.fromRGBO(255, 255, 255, .92),
    tagBg: Color(0xFF0E1117),
    tagFg: Color(0xFFFFFFFF),
    hl: Color.fromRGBO(255, 255, 255, .5),
    lyBg: Color.fromRGBO(255, 255, 255, .55),
    lyFg: Color(0xFF1A4BE0),
    lyFg2: Color(0xFF3D4350),
    lyBar: true,
    bad: Color(0xFFC42B2B),
    ok: Color(0xFF157A3E),
    mute: Color(0xFF46505E),
  );

  static const darkC = NekoColors(
    dark: true,
    k: Color(0xFFE6EDF3),
    p: Color(0xFF5580F7),
    s: Color(0xFF9DA7B3),
    s2: Color(0xFFB8C1CC),
    l1: Color.fromRGBO(230, 237, 243, .12),
    l2: Color.fromRGBO(230, 237, 243, .20),
    l3: Color.fromRGBO(230, 237, 243, .28),
    bar: Color(0xFF2D333B),
    ov: Color.fromRGBO(45, 51, 59, .92),
    pop: Color(0xFF373E47),
    tagBg: Color(0xFFE6EDF3),
    tagFg: Color(0xFF2D333B),
    hl: Color.fromRGBO(255, 255, 255, .07),
    lyBg: Color(0xFFF0F2F5),
    lyFg: Color(0xFF0E1117),
    lyFg2: Color(0xFF0E1117),
    lyBar: false,
    bad: Color(0xFFF85149),
    ok: Color(0xFF3FB950),
    mute: Color(0xFF9DA7B3),
  );

  @override
  NekoColors copyWith() => this;

  @override
  NekoColors lerp(ThemeExtension<NekoColors>? other, double t) =>
      other is NekoColors && t >= .5 ? other : this;
}

extension NekoCtx on BuildContext {
  NekoColors get c => Theme.of(this).extension<NekoColors>()!;
}

/// 字体：英文数字 Inter，中文 Noto Sans SC，等宽 JetBrains Mono。
class NText {
  static const _fallback = <String>[]; // 中文用系统字体，不再内置 17MB 的思源黑体

  static TextStyle sans(double size,
          {FontWeight w = FontWeight.w400,
          Color? color,
          double? height,
          double ls = 0,
          TextDecoration? deco}) =>
      TextStyle(
        fontFamily: 'Inter',
        fontFamilyFallback: _fallback,
        fontSize: size,
        fontWeight: w,
        color: color,
        height: height,
        letterSpacing: ls * size,
        decoration: deco,
        decorationColor: color,
        decorationThickness: 1,
      );

  static TextStyle mono(double size,
          {FontWeight w = FontWeight.w400,
          Color? color,
          double? height,
          double ls = 0}) =>
      TextStyle(
        fontFamily: 'JetBrainsMono',
        fontFamilyFallback: _fallback,
        fontSize: size,
        fontWeight: w,
        color: color,
        height: height,
        letterSpacing: ls * size,
        fontFeatures: const [FontFeature.tabularFigures()],
      );
}

class NekoTheme {
  static ThemeData build(NekoColors c) {
    final base = ThemeData(
      useMaterial3: true,
      brightness: c.dark ? Brightness.dark : Brightness.light,
      splashFactory: NoSplash.splashFactory,
      highlightColor: Colors.transparent,
      hoverColor: Colors.transparent,
      splashColor: Colors.transparent,
      scaffoldBackgroundColor: Colors.transparent,
      colorScheme: ColorScheme.fromSeed(
        seedColor: c.p,
        brightness: c.dark ? Brightness.dark : Brightness.light,
      ).copyWith(primary: c.p, surface: c.bar, onSurface: c.k),
      textSelectionTheme: TextSelectionThemeData(
        cursorColor: c.p,
        selectionColor: c.p.withValues(alpha: .25),
      ),
      pageTransitionsTheme: const PageTransitionsTheme(builders: {
        TargetPlatform.android: _NoTransition(),
        TargetPlatform.iOS: _NoTransition(),
      }),
      extensions: [c],
    );
    return base;
  }

  static final light = build(NekoColors.light);
  static final dark = build(NekoColors.darkC);
}

class _NoTransition extends PageTransitionsBuilder {
  const _NoTransition();
  @override
  Widget buildTransitions<T>(PageRoute<T> route, BuildContext context,
          Animation<double> animation, Animation<double> secondaryAnimation, Widget child) =>
      child;
}

/// 统一缓动 cubic-bezier(.16,1,.3,1)
const kEase = Cubic(.32, .72, 0, 1); // iOS 常用的减速曲线
const kEaseIn = Cubic(.32, 0, .67, 0);
const kEaseStd = Cubic(.4, 0, .2, 1);

/// 弹簧动画（对应 iOS 的 response ≈ 0.42、阻尼比 1）：打断时会带着当前速度继续，手势松手也能自然衔接。
const kSpring = SpringDescription(mass: 1, stiffness: 224, damping: 30);

/// 动效速度：0 慢 / 1 标准 / 2 快 / 3 很快。弹簧时长约与 1/√刚度 成正比，刚度按速度平方、阻尼按速度缩放（保持不回弹）
int animLevel = 1;
const animLabels = ['慢', '标准', '快', '很快'];
const _animFactors = [0.7, 1.0, 1.4, 2.0];
SpringDescription animSpring() {
  final f = _animFactors[animLevel];
  return SpringDescription(mass: 1, stiffness: 224 * f * f, damping: 30 * f);
}

extension SpringAnim on AnimationController {
  TickerFuture springTo(double target, {SpringDescription? spring}) {
    spring ??= animSpring();
    return animateWith(_SnapSpring(SpringSimulation(spring, value, target, velocity), target));
  }
}

/// 弹簧结束时精确落在目标值上（原生弹簧会停在 0.0008 这类近似值，导致遮罩层残留、挡住点击）。
class _SnapSpring extends Simulation {
  _SnapSpring(this.s, this.target);
  final SpringSimulation s;
  final double target;
  @override
  double x(double t) => s.isDone(t) ? target : s.x(t);
  @override
  double dx(double t) => s.isDone(t) ? 0 : s.dx(t);
  @override
  bool isDone(double t) => s.isDone(t);
}
