import 'package:flutter_riverpod/flutter_riverpod.dart';

enum Pg { home, library, search, settings, plugins, pl, now, lyrics }

enum NavDir { none, fwd, back, fade }

enum ListKind { liked, local, recent, album, playlist, cloud, daily }

class ListRef {
  const ListRef(this.kind, [this.id = '']);
  final ListKind kind;
  final String id;
  @override
  bool operator ==(Object o) => o is ListRef && o.kind == kind && o.id == id;
  @override
  int get hashCode => Object.hash(kind, id);
}

class NavState {
  const NavState({
    this.page = Pg.home,
    this.stack = const [],
    this.dir = NavDir.none,
    this.queueOpen = false,
    this.menuOpen = false,
    this.list = const ListRef(ListKind.liked),
    this.seq = 0,
  });

  final Pg page;
  final List<Pg> stack;
  final NavDir dir;
  final bool queueOpen, menuOpen;
  final ListRef list;
  final int seq;

  bool get playerOpen => page == Pg.now || page == Pg.lyrics;

  /// 播放页打开时，它下面那一页。
  Pg get base => playerOpen ? (stack.isEmpty ? Pg.home : stack.last) : page;

  bool get isSub => const [Pg.search, Pg.settings, Pg.plugins, Pg.pl].contains(base);
  bool get canGoBack => page != Pg.home || stack.isNotEmpty || queueOpen;

  NavState copyWith({
    Pg? page,
    List<Pg>? stack,
    NavDir? dir,
    bool? queueOpen,
    bool? menuOpen,
    ListRef? list,
  }) =>
      NavState(
        page: page ?? this.page,
        stack: stack ?? this.stack,
        dir: dir ?? this.dir,
        queueOpen: queueOpen ?? this.queueOpen,
        menuOpen: menuOpen ?? this.menuOpen,
        list: list ?? this.list,
        seq: seq + 1,
      );
}

final navProvider = NotifierProvider<NavNotifier, NavState>(NavNotifier.new);

/// 页面栈状态机，行为对应 reference/demo_logic.js 的 go / back / goHome。
class NavNotifier extends Notifier<NavState> {
  @override
  NavState build() => const NavState();

  static const _player = [Pg.now, Pg.lyrics];
  static const _sub = [Pg.search, Pg.settings, Pg.plugins, Pg.pl];

  void go(Pg page) {
    final s = state;
    if (page == s.page) {
      state = s.copyWith(menuOpen: false);
      return;
    }
    var stack = s.stack;
    var dir = s.dir;
    if (_player.contains(page)) {
      if (!_player.contains(s.page)) stack = [...s.stack, s.page];
      dir = NavDir.none;
    } else if (_sub.contains(page)) {
      stack = [...s.stack, s.page];
      dir = NavDir.fwd;
    } else {
      stack = [];
      dir = ([Pg.home, Pg.library].contains(s.page) && [Pg.home, Pg.library].contains(page))
          ? NavDir.none
          : NavDir.fade;
    }
    state = s.copyWith(page: page, stack: stack, dir: dir, menuOpen: false, queueOpen: false);
  }

  void openList(ListRef ref) {
    state = state.copyWith(list: ref);
    go(Pg.pl);
  }

  void openQueue() {
    final s = state;
    final inPlayer = s.playerOpen;
    state = s.copyWith(
      page: inPlayer ? s.page : Pg.now,
      stack: inPlayer ? s.stack : [...s.stack, s.page],
      queueOpen: true,
      menuOpen: false,
    );
  }

  void closeQueue() => state = state.copyWith(queueOpen: false);

  /// 返回「来的那一页」。播放页打开时 = 收起播放页。
  void back() {
    final s = state;
    if (s.queueOpen && s.playerOpen) {
      closeQueue();
      return;
    }
    final st = [...s.stack];
    final to = st.isEmpty ? Pg.home : st.removeLast();
    state = s.copyWith(
      page: to,
      stack: st,
      menuOpen: false,
      queueOpen: false,
      dir: s.playerOpen ? NavDir.none : NavDir.back,
    );
  }

  /// 点 NEKOPLAY：播放页里 = 收起；其他页 = 回首页并清空返回记录。
  void goHome() {
    final s = state;
    if (s.playerOpen) {
      back();
      return;
    }
    if (s.page == Pg.home) {
      state = s.copyWith(menuOpen: false);
      return;
    }
    state = s.copyWith(page: Pg.home, stack: [], menuOpen: false, queueOpen: false, dir: NavDir.fade);
  }

  void toggleMenu() => state = state.copyWith(menuOpen: !state.menuOpen);
  void closeMenu() {
    if (state.menuOpen) state = state.copyWith(menuOpen: false);
  }
}
