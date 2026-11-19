import 'dart:convert';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../data/settings.dart';
import '../../plugins/plugin_manager.dart';
import '../../plugins/plugin_models.dart';
import '../widgets.dart';

class PluginsPage extends ConsumerStatefulWidget {
  const PluginsPage({super.key});
  @override
  ConsumerState<PluginsPage> createState() => _PluginsPageState();
}

class _PluginsPageState extends ConsumerState<PluginsPage> {
  final _url = TextEditingController();
  bool _busy = false;
  final Set<String> _checking = {};

  @override
  void dispose() {
    _url.dispose();
    super.dispose();
  }

  void _toast(String m) => ref.read(toastProvider.notifier).show(m);

  Future<void> _confirmInstall(String code) async {
    final mgr = ref.read(pluginsProvider.notifier);
    final PluginMeta meta;
    try {
      meta = mgr.inspect(code);
    } catch (e) {
      _toast('$e');
      return;
    }
    if (!mounted) return;
    final ok = await confirmDialog(
      context,
      title: '安装插件',
      ok: '安装',
      body: '${meta.name}  v${meta.version}\n作者：${meta.author}\n'
          '能力：${meta.capsLine}\n'
          '会访问的域名：${meta.hosts.isEmpty ? '（无）' : meta.hosts.join('、')}\n\n'
          '这是第三方插件，代码由作者提供。它只能访问上面声明的域名，不能读取你的文件。请只安装信任的来源。安装后默认停用，需要手动打开。',
    );
    if (!ok) return;
    try {
      await mgr.install(code);
      _toast('已安装「${meta.name}」，默认停用');
      _url.clear();
    } catch (e) {
      _toast('$e');
    }
  }

  Future<void> _installUrl() async {
    final u = _url.text.trim();
    if (u.isEmpty) {
      _toast('请先填写插件地址');
      return;
    }
    setState(() => _busy = true);
    try {
      final code = await ref.read(pluginsProvider.notifier).download(u);
      await _confirmInstall(code);
    } catch (e) {
      _toast('$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _installFile() async {
    try {
      final f = await FilePicker.pickFiles(type: FileType.custom, allowedExtensions: const ['js']);
      if (f.isEmpty) return;
      final path = f.first.path;
      final bytes = path == null ? null : await File(path).readAsBytes();
      if (bytes == null) {
        _toast('读取文件失败');
        return;
      }
      await _confirmInstall(utf8.decode(bytes, allowMalformed: true));
    } catch (e) {
      _toast('导入失败：$e');
    }
  }

  Future<void> _check(PluginInfo p) async {
    setState(() => _checking.add(p.meta.id));
    final mgr = ref.read(pluginsProvider.notifier);
    try {
      final o = await mgr.checkUpdate(p.meta.id);
      if (!mounted) return;
      if (o == null) {
        _toast('「${p.meta.name}」已是最新版本');
      } else {
        final ok = await confirmDialog(context,
            title: '发现新版本',
            ok: '更新',
            body: '${p.meta.name}  v${p.meta.version} → v${o.meta.version}'
                '${o.newHosts.isEmpty ? '' : '\n\n新版要求访问新的域名：\n${o.newHosts.join('\n')}\n请确认你信任它。'}');
        if (ok) {
          await mgr.applyUpdate(o);
          _toast('已更新到 v${o.meta.version}');
        }
      }
    } catch (e) {
      _toast('$e');
    } finally {
      if (mounted) setState(() => _checking.remove(p.meta.id));
    }
  }

  Future<void> _showLogs(PluginInfo p) async {
    final logs = ref.read(pluginsProvider).logs.where((l) => l.contains('[${p.meta.id}]')).toList();
    await showNekoDialog<void>(context, builder: (ctx) {
      final c = ctx.c;
      return NekoDialogBox(
        title: '${p.meta.name} · 日志',
        actions: [OutlineBtn(label: '关闭', height: 44, onTap: () => Navigator.of(ctx).pop())],
        child: SelectableText(
          logs.isEmpty ? '暂无日志' : logs.reversed.take(80).join('\n'),
          style: NText.mono(11, color: c.s2, height: 1.6),
        ),
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final c = context.c;
    final st = ref.watch(pluginsProvider);
    final mgr = ref.read(pluginsProvider.notifier);
    final s = ref.watch(settingsProvider);
    final n = ref.read(settingsProvider.notifier);

    return ListView(
      key: const PageStorageKey('plugins'),
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 28),
      children: [
        const TagRow('PLUGINS · 已安装'),
        const SizedBox(height: 4),
        for (var i = 0; i < st.items.length; i++) _row(context, st.items[i], i, mgr),
        const SizedBox(height: 26),
        const TagRow('INSTALL · 安装插件'),
        const SizedBox(height: 12),
        Container(
          height: 44,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(border: Border.all(color: c.k)),
          child: TextField(
            controller: _url,
            keyboardType: TextInputType.url,
            autocorrect: false,
            enableSuggestions: false,
            cursorColor: c.p,
            style: NText.mono(13, color: c.k),
            decoration: InputDecoration(
                border: InputBorder.none, isCollapsed: true, hintText: 'https://…/plugin.js', hintStyle: NText.mono(13, color: c.s)),
          ),
        ),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: OutlineBtn(label: _busy ? '下载中…' : '从地址安装', onTap: _busy ? null : _installUrl)),
          const SizedBox(width: 10),
          Expanded(child: OutlineBtn(label: '选择 .js 文件', onTap: _installFile)),
        ]),
        const SizedBox(height: 10),
        Text('插件是第三方提供的 JS 文件，只能访问它声明的域名。安装后默认停用，打开开关时会先做一次连通检查。',
            style: NText.sans(12, color: c.s, height: 1.6)),
        const SizedBox(height: 26),
        const TagRow('RULES · 规则'),
        _switch(context, '自动换源', '当前音源失败时，按「歌名 + 歌手」自动尝试其他音源', s.fallback,
            (v) => n.update((x) => x.copyWith(fallback: v))),
        _switch(context, '仅 Wi-Fi 下使用在线音源', '移动网络下只播放本地曲库', s.wifiOnly, (v) => n.update((x) => x.copyWith(wifiOnly: v))),
      ],
    );
  }

  Widget _switch(BuildContext context, String title, String sub, bool on, ValueChanged<bool> cb) {
    final c = context.c;
    return Container(
      constraints: const BoxConstraints(minHeight: 56),
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: NText.sans(16, color: c.k)),
            Text(sub, style: NText.sans(13, color: c.s, height: 1.4)),
          ]),
        ),
        NSwitch(on: on, label: title, onChanged: cb),
      ]),
    );
  }

  Widget _row(BuildContext context, PluginInfo p, int i, PluginsNotifier mgr) {
    final c = context.c;
    final m = p.meta;
    final (String txt, Color col) = switch (p.status) {
      PStatus.checking => ('检查中…', c.s),
      PStatus.ok => (p.builtin ? '内置' : '正常 · ${p.ms}ms', c.ok),
      PStatus.fail => (p.error.isEmpty ? '不可用' : p.error, c.bad),
      PStatus.unverified => (p.enabled ? (p.bundled ? '已启用' : '待检查') : '已停用', c.s),
    };
    final hasLogin = m.caps.contains('login');
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 14),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: c.l1))),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Text((i + 1).toString().padLeft(2, '0'), style: NText.mono(12, color: c.s)),
          const SizedBox(width: 10),
          Container(
            width: 34,
            height: 34,
            alignment: Alignment.center,
            decoration: BoxDecoration(color: p.enabled ? c.p : null, border: Border.all(color: c.k)),
            child: Text(m.badge, style: NText.sans(16, w: FontWeight.w900, color: p.enabled ? Colors.white : c.k)),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(p.bundled ? m.name : '${m.name}  v${m.version}', maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(16, w: FontWeight.w700, color: c.k)),
              Text(p.bundled ? '内置 · 搜索、歌单、我喜欢' : '${m.author} · ${m.capsLine}', maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(12, color: c.s)),
            ]),
          ),
          NSwitch(on: p.enabled, label: '启用 ${m.name}', onChanged: (_) => mgr.toggle(m.id)),
        ]),
        Padding(
          padding: const EdgeInsets.only(left: 2, top: 2),
          child: Row(children: [
            Expanded(child: Text(txt, maxLines: 1, overflow: TextOverflow.ellipsis, style: NText.sans(12, color: col))),
          ]),
        ),
        Wrap(spacing: 4, children: [
          if (i > 0) LinkBtn('上移', onTap: () => mgr.moveUp(m.id)),
          if (!p.builtin && !p.bundled && m.update != null)
            LinkBtn(_checking.contains(m.id) ? '检查中…' : '检查更新', onTap: _checking.contains(m.id) ? null : () => _check(p)),
          if (!p.builtin && !p.bundled) LinkBtn('日志', onTap: () => _showLogs(p)),
          if (hasLogin)
            LinkBtn(p.bundled ? '账号' : '设置', onTap: () async {
              final r = await mgr.login(m.id);
              _toast(r.error == null ? (r.message ?? '设置已保存') : '失败：${r.error}');
            }),
          if (!p.builtin && !p.bundled)
            LinkBtn('删除', color: c.bad, onTap: () async {
              final ok = await confirmDialog(context, title: '删除插件', body: '会删除「${m.name}」及它保存的数据。', ok: '删除', danger: true);
              if (ok) await mgr.remove(m.id);
            }),
        ]),
      ]),
    );
  }
}
