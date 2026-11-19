import 'package:flutter/widgets.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// 图标全部来自原型的 24×24 线性图标（直角、2px 线）。
class NIcons {
  static String _stroke(String body, {double sw = 2, String cap = 'square', String join = 'miter'}) =>
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#000" '
      'stroke-width="$sw" stroke-linecap="$cap" stroke-linejoin="$join">$body</svg>';
  static String _fill(String body) =>
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="#000">$body</svg>';

  static final chevronDown = _stroke('<path d="M5 9l7 7 7-7"/>', sw: 2.6);
  static final chevronRight = _stroke('<path d="M9 5l7 7-7 7"/>');
  static final back = _stroke('<path d="M15 5l-7 7 7 7"/>');
  static final search = _stroke('<circle cx="10" cy="10" r="6"/><path d="M15 15l6 6"/>');
  static final pause = _stroke('<path d="M8 4v16M16 4v16"/>', sw: 3);
  static final play = _fill('<path d="M7 4l13 8-13 8z"/>');
  static final heart = _fill('<path d="M12 20l-7.5-7.3A4.8 4.8 0 0 1 12 6.2a4.8 4.8 0 0 1 7.5 6.5z"/>');
  static final heartLine =
      _stroke('<path d="M12 20l-7.5-7.3A4.8 4.8 0 0 1 12 6.2a4.8 4.8 0 0 1 7.5 6.5z"/>');
  static final folder = _stroke('<path d="M3 6h7l2 2h9v11H3z"/>');
  static final clock = _stroke('<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>');
  static final clockSmall = _stroke('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>');
  static final prev = _stroke('<path d="M6 5v14M19 5l-10 7 10 7z"/>');
  static final next = _stroke('<path d="M18 5v14M5 5l10 7-10 7z"/>');
  static final modeSeq = _stroke(
      '<path d="M8 6h8a4.5 4.5 0 0 1 0 9H5"/><path d="M8.5 11.5L5 15l3.5 3.5"/>',
      cap: 'round', join: 'round');
  static final modeShuffle =
      _stroke('<path d="M3 7h4l10 10h4M3 17h4l3-3M14 10l3-3h4M18 4l3 3-3 3M18 14l3 3-3 3"/>');
  static final modeOne = _stroke(
      '<path d="M4 11V9a3 3 0 0 1 3-3h13M17 3l3 3-3 3M20 13v2a3 3 0 0 1-3 3H4M7 21l-3-3 3-3M11.5 10l1-.5V15"/>');
  static final home = _stroke('<path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z"/>');
  static final library = _stroke('<path d="M5 4v16M10 4v16M15 5l4 15"/>');
  static final queue = _stroke('<path d="M4 6h16M4 12h16M4 18h16"/>');
  static final more = _stroke('<path d="M5 12h.01M12 12h.01M19 12h.01"/>', sw: 3.2);
  static final plus = _stroke('<path d="M12 5v14M5 12h14"/>');
  static final close = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 12 12" fill="none" '
      'stroke="#000" stroke-width="1.5" stroke-linecap="square"><path d="M1.5 1.5l9 9M10.5 1.5l-9 9"/></svg>';
}

class NIcon extends StatelessWidget {
  const NIcon(this.svg, {super.key, this.size = 24, this.color});
  final String svg;
  final double size;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final col = color ?? DefaultTextStyle.of(context).style.color ?? const Color(0xFF000000);
    return SvgPicture.string(
      svg,
      width: size,
      height: size,
      colorFilter: ColorFilter.mode(col, BlendMode.srcIn),
    );
  }
}
