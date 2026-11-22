package cc.catsuki.nekoplay.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.catsuki.nekoplay.ActionRow
import cc.catsuki.nekoplay.Backup
import cc.catsuki.nekoplay.Boot
import cc.catsuki.nekoplay.Features
import cc.catsuki.nekoplay.Fs
import cc.catsuki.nekoplay.LocalNav
import cc.catsuki.nekoplay.Mono
import cc.catsuki.nekoplay.NIcons
import cc.catsuki.nekoplay.OutlineBtn
import cc.catsuki.nekoplay.Plat
import cc.catsuki.nekoplay.PrimaryBar
import cc.catsuki.nekoplay.SheetItem
import cc.catsuki.nekoplay.SheetSpec
import cc.catsuki.nekoplay.SwitchRow
import cc.catsuki.nekoplay.T
import cc.catsuki.nekoplay.Tag
import cc.catsuki.nekoplay.c
import cc.catsuki.nekoplay.nbf
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun BackupPage() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    @Composable fun Field(label: String, v: String, hint: String, secret: Boolean = false, set: (String) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            T(label, 13, p.s); Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (v.isEmpty()) T(hint, 13, p.s, mono = true)
                BasicTextField(v, { set(it); Backup.save() }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono),
                    cursorBrush = SolidColor(p.p), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp)) {
        if (Features.on("webdav")) item {
            Column {
                Tag("BACKUP"); Spacer(Modifier.height(4.dp))
                T("WebDAV 备份", Fs.HERO, w = 800, lh = 1.3f)
                T("把设置、主题、首页布局、效果器、歌词偏移、喜欢和最近播放存到你的 WebDAV 网盘，手机和电脑可以互相恢复", 13, p.s, lines = 3)
                Spacer(Modifier.height(10.dp))
                Field("地址（文件夹）", Backup.url, "https://example.com/dav/NEKOPLAY/") { Backup.url = it }
                Field("用户名", Backup.user, "") { Backup.user = it }
                Field("密码", Backup.pass, "", secret = true) { Backup.pass = it }
                SwitchRow("包含账号登录", "恢复后不用重新登录；登录信息会以明文存在网盘里，介意就别开", Backup.withLogin) { Backup.withLogin = it; Backup.save() }
                Spacer(Modifier.height(14.dp))
                ActionRow {
                    PrimaryBar(if (busy) "处理中…" else "备份到网盘", NIcons.chevronRight, Modifier.weight(1f)) {
                        if (busy) return@PrimaryBar; busy = true
                        scope.launch { runCatching { Backup.upload() }.onSuccess { nav.toast = "已备份" }.onFailure { nav.toast = "备份失败：${it.message}" }; busy = false }
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlineBtn("从网盘恢复", pad = 14.dp) {
                        if (busy) return@OutlineBtn; busy = true
                        scope.launch {
                            runCatching { Backup.restore() }.onSuccess {
                                Boot.reloadPrefs()
                                nav.toast = "已恢复（备份于 ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(it))}），部分设置重启后生效"
                            }.onFailure { nav.toast = "恢复失败：${it.message}" }
                            busy = false
                        }
                    }
                }
                if (Backup.last > 0) T("上次备份：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(Backup.last)), 12, p.s, mono = true, modifier = Modifier.padding(top = 10.dp))
            }
        }
        item { LocalBackup() }
    }
}

/** 本地配置备份：导出成文件 / 复制成文字，换设备或重装后导入 */
@Composable private fun LocalBackup() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var login by remember { mutableStateOf(false) }
    fun importText(text: String) {
        val root = runCatching { JSONObject(text.trim()) }.getOrNull() ?: run { nav.toast = "不是有效的配置"; return }
        nav.sheet = SheetSpec("导入配置？", "会覆盖本机现在的设置" + (if (Backup.hasLogin(root)) "，包括里面带的账号登录" else ""), listOf(
            SheetItem("导入并覆盖", danger = true) {
                scope.launch {
                    runCatching { Backup.apply(root) }.onSuccess { Boot.reloadPrefs(); nav.toast = "已导入，部分设置和登录重启后生效" }.onFailure { nav.toast = "导入失败：${it.message}" }
                }
            }))
    }
    val saver = Plat.impl.fileSaver { where -> if (where != null) nav.toast = "已导出：$where" }
    val picker = Plat.impl.filePicker { bytes -> importText(bytes.decodeToString()) }
    Column(Modifier.padding(top = 30.dp)) {
        Tag("LOCAL"); Spacer(Modifier.height(4.dp))
        T("配置备份 / 导入", Fs.HERO, w = 800, lh = 1.3f)
        T("把设置、主题、首页布局、效果器、喜欢和最近播放导出成一个文件（或一段文字），在别的设备上导入", 13, p.s, lines = 3)
        Spacer(Modifier.height(6.dp))
        SwitchRow("包含账号登录", "网易云 / QQ 音乐 / 酷狗的登录信息会以明文写进文件，别发给别人", login) { login = it }
        Spacer(Modifier.height(10.dp))
        ActionRow {
            if (saver != null) {
                PrimaryBar("导出到文件", NIcons.download, Modifier.weight(1f)) {
                    val name = "nekoplay-config-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(java.util.Date()) + ".json"
                    saver(name, Backup.dump(login).toString(2).encodeToByteArray())
                }
                Spacer(Modifier.width(10.dp))
            }
            if (picker != null) OutlineBtn("从文件导入", pad = 14.dp) { picker() }
        }
        Spacer(Modifier.height(10.dp))
        ActionRow {
            OutlineBtn("复制配置", pad = 14.dp) { clip.setText(androidx.compose.ui.text.AnnotatedString(Backup.dump(login).toString())); nav.toast = "已复制到剪贴板" }
            Spacer(Modifier.width(10.dp))
            OutlineBtn("从剪贴板导入", pad = 14.dp) { clip.getText()?.text?.let { importText(it) } ?: run { nav.toast = "剪贴板是空的" } }
        }
    }
}
