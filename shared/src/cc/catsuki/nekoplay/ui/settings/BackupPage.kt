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
import cc.catsuki.nekoplay.InputSpec
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
                T("将设置、主题、首页布局、效果器、歌词偏移、听歌统计、喜欢和最近播放同步到你的 WebDAV 网盘，手机和电脑可互通恢复", 13, p.s, lines = 3)
                Spacer(Modifier.height(10.dp))
                Field("地址（文件夹）", Backup.url, "https://example.com/dav/NEKOPLAY/") { Backup.url = it }
                Field("用户名", Backup.user, "") { Backup.user = it }
                Field("密码", Backup.pass, "", secret = true) { Backup.pass = it }
                Field("备份加密口令（可选，AES-256）", Backup.cryptPass, "留空则明文备份；包含账号登录时强烈建议设置", secret = true) { Backup.cryptPass = it }
                SwitchRow("包含账号登录", "恢复后免重新登录；若未设置加密口令，凭证将以明文存放在网盘", Backup.withLogin) { Backup.withLogin = it; Backup.save() }
                Spacer(Modifier.height(14.dp))
                ActionRow {
                    PrimaryBar(if (busy) "处理中…" else "备份到网盘", NIcons.chevronRight, Modifier.weight(1f)) {
                        if (busy) return@PrimaryBar; busy = true
                        scope.launch { runCatching { Backup.upload() }.onSuccess { nav.toast = if (Backup.cryptPass.isNotBlank()) "已加密备份" else "已备份" }.onFailure { nav.toast = "备份失败：${it.message}" }; busy = false }
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

/** 本地配置备份：支持可选 AES-256 口令加密导出成文件或文字，换设备与重装互通导入 */
@Composable private fun LocalBackup() {
    val nav = LocalNav.current
    val p = c
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var login by remember { mutableStateOf(false) }
    var pwd by remember { mutableStateOf(Backup.cryptPass) }

    fun confirmApply(root: JSONObject) {
        nav.sheet = SheetSpec("导入配置？", "会覆盖本机现在的设置" + (if (Backup.hasLogin(root)) "，包括里面带的账号登录凭证" else ""), listOf(
            SheetItem("导入并覆盖", danger = true) {
                scope.launch {
                    runCatching { Backup.apply(root) }.onSuccess { Boot.reloadPrefs(); nav.toast = "已导入，部分设置和登录重启后生效" }.onFailure { nav.toast = "导入失败：${it.message}" }
                }
            }))
    }
    fun importText(text: String) {
        val raw = runCatching { JSONObject(text.trim()) }.getOrNull() ?: run { nav.toast = "不是有效的配置"; return }
        if (Backup.isEncrypted(raw)) {
            if (pwd.isNotBlank()) {
                val dec = runCatching { Backup.unseal(raw, pwd) }.getOrNull()
                if (dec != null) { confirmApply(dec); return }
            }
            nav.input = InputSpec("输入备份解密口令", "该配置文件已启用 AES-256 加密，请输入导出时设置的口令") { inputPwd ->
                runCatching { Backup.unseal(raw, inputPwd) }.onSuccess { confirmApply(it) }.onFailure { nav.toast = it.message ?: "解密失败" }
            }
        } else confirmApply(raw)
    }

    val saver = Plat.impl.fileSaver { where -> if (where != null) nav.toast = "已导出：$where" }
    val picker = Plat.impl.filePicker { bytes -> importText(bytes.decodeToString()) }
    Column(Modifier.padding(top = 30.dp)) {
        Tag("LOCAL"); Spacer(Modifier.height(4.dp))
        T("配置备份 / 导入", Fs.HERO, w = 800, lh = 1.3f)
        T("将设置、主题、首页布局、效果器、听歌统计、喜欢和最近播放导出为文件或文本，支持跨设备导入", 13, p.s, lines = 3)
        Spacer(Modifier.height(6.dp))
        SwitchRow("包含账号登录", "已安装音源插件与网盘的登录凭证将写入备份，建议下方设置加密口令并妥善保管", login) { login = it }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            T("导出加密口令（可选，AES-256）", 13, p.s); Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).nbf(1.dp, p.k).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (pwd.isEmpty()) T("留空则导出明文 JSON", 13, p.s, mono = true)
                BasicTextField(pwd, { pwd = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = p.k, fontSize = 13.sp, fontFamily = Mono),
                    cursorBrush = SolidColor(p.p), visualTransformation = PasswordVisualTransformation())
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionRow {
            if (saver != null) {
                PrimaryBar("导出到文件", NIcons.download, Modifier.weight(1f)) {
                    val name = "nekoplay-config-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm").format(java.util.Date()) + ".json"
                    val payload = Backup.seal(Backup.dump(login), pwd)
                    saver(name, payload.toString(2).encodeToByteArray())
                }
                Spacer(Modifier.width(10.dp))
            }
            if (picker != null) OutlineBtn("从文件导入", pad = 14.dp) { picker() }
        }
        Spacer(Modifier.height(10.dp))
        ActionRow {
            OutlineBtn("复制配置", pad = 14.dp) {
                val payload = Backup.seal(Backup.dump(login), pwd)
                clip.setText(androidx.compose.ui.text.AnnotatedString(payload.toString()))
                nav.toast = if (pwd.isNotBlank()) "已复制加密配置到剪贴板" else "已复制到剪贴板"
            }
            Spacer(Modifier.width(10.dp))
            OutlineBtn("从剪贴板导入", pad = 14.dp) { clip.getText()?.text?.let { importText(it) } ?: run { nav.toast = "剪贴板是空的" } }
        }
    }
}
