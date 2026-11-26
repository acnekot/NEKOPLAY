package cc.catsuki.nekoplay

/** 多语言词库（English / 日本語 / 繁體中文术语 + 简繁字表） */
internal object I18nDict {
    val en: Map<String, String> = mapOf(
        // 导航与主界面
        "主页" to "Home", "曲库" to "Library", "搜索" to "Search", "私人 FM" to "Personal FM",
        "发现 · 排行榜" to "Discover · Charts", "封面墙" to "Cover Flow", "设置" to "Settings",
        "我的主页" to "Profile", "本地用户" to "Local User", "点击登录" to "Tap to sign in",
        "登录" to "Sign in", "退出登录" to "Sign out", "取消" to "Cancel", "确定" to "OK",
        "保存" to "Save", "重置" to "Reset", "清空" to "Clear", "删除" to "Delete", "编辑" to "Edit",
        "应用" to "Apply", "还原" to "Revert", "移除" to "Remove", "换一张" to "Change", "选择图片" to "Choose Image",
        "关闭" to "Off", "关" to "Off", "开" to "On", "短" to "Short", "中" to "Medium", "长" to "Long",
        "慢" to "Slow", "标准" to "Standard", "快" to "Fast", "很快" to "Snappy",
        "弹簧" to "Spring", "缓动" to "Ease", "线性" to "Linear", "自定义曲线" to "Custom Curve",
        // 首页与曲库
        "还没有曲目" to "No Track Playing", "选择一首歌开始播放" to "Pick a track to start listening",
        "选一首歌开始播放" to "Pick a track to start listening",
        "播放" to "Play", "暂停" to "Pause", "继续播放" to "Resume", "加载中" to "Loading",
        "打开队列" to "Queue", "随机播放" to "Shuffle", "每日推荐" to "Daily Mix",
        "按你的口味无限连播" to "Endless radio tailored to your taste", "发现" to "Discover",
        "排行榜" to "Top Charts", "歌单广场" to "Playlists", "新碟上架" to "New Albums",
        "新歌速递" to "New Songs", "MV 精选" to "Music Videos", "播客电台" to "Podcasts",
        "历史日推" to "Daily History", "推荐合集" to "Collections", "查看全部" to "View All",
        "全部" to "All", "我喜欢" to "Liked Songs", "最近播放" to "Recently Played",
        "本地下载" to "Downloads", "听歌统计" to "Listening Stats", "本地音乐" to "Local Music",
        "WebDAV 音乐" to "WebDAV Music", "本机喜欢" to "On-Device Likes", "新建歌单" to "New Playlist",
        "新建本地歌单" to "New Local Playlist", "签到" to "Check in", "已签到" to "Checked in",
        "云盘" to "Cloud Drive", "自己上传的歌" to "Your uploaded tracks",
        "本机的播放次数和时长" to "Local play counts and listening time",
        "扫描手机 / 电脑里的歌" to "Scan audio files on this device",
        "浏览网盘里的歌" to "Browse audio on your WebDAV server",
        "填好地址后直接播放网盘里的歌" to "Connect a WebDAV server to stream audio",
        "本地歌和没登录的来源点喜欢会记在这里" to "Local & offline likes are saved here",
        "管理插件" to "Manage Plugins", "列表" to "List", "卡片" to "Grid", "收起" to "Collapse", "显示全部" to "Show All",
        "每日更新" to "Updated daily", "本机记录" to "Local history", "相似推荐" to "Similar tracks",
        "在线歌单" to "Online playlist", "本机" to "On device", "本地" to "Local", "收藏" to "Collect", "已收藏" to "Collected",
        "单曲" to "Tracks", "专辑" to "Albums", "歌手" to "Artists", "歌单" to "Playlists", "用户" to "Users", "歌词" to "Lyrics",
        // 播放与歌词
        "列表循环" to "Repeat All", "单曲循环" to "Repeat One", "清空待播" to "Clear Up Next",
        "热门" to "Hot", "最新" to "Latest", "回复" to "Reply", "收起回复" to "Hide replies", "加载更多回复" to "Load more replies",
        "登录后才能评论" to "Sign in to comment", "说点什么…" to "Write a comment…", "刚刚" to "Just now",
        "等待下载" to "Queued", "已下载" to "Downloaded", "已经下载过了" to "Already downloaded",
        "已打开桌面歌词" to "Desktop lyrics enabled", "已关闭桌面歌词" to "Desktop lyrics disabled",
        "解锁桌面歌词" to "Unlock Lyrics", "回正" to "Center", "一键回正" to "Center Now",
        "单行" to "1 Line", "双行 · 译文" to "2 Lines · Trans", "双行 · 下一句" to "2 Lines · Next",
        "居中" to "Center", "左对齐" to "Left", "右对齐" to "Right",
        "跟随主题" to "Theme", "冰蓝" to "Ice Blue", "樱粉" to "Sakura", "琥珀" to "Amber", "翠绿" to "Emerald", "纯白" to "White",
        "歌名" to "Title", "不再推荐这首" to "Removed from FM",
        // 设置首页
        "外观" to "Appearance", "主题、深色、字体、取色、背景、动效" to "Theme, dark mode, fonts, colors, background, motion",
        "语言" to "Language", "跟随系统 / 简体中文 / 繁體中文 / English / 日本語" to "Follow System / 简体中文 / 繁體中文 / English / 日本語",
        "布局" to "Layout", "首页模块、播放页按钮的位置" to "Home sections & player button placement",
        "字号、当前句位置、逐字高亮、桌面歌词" to "Font size, anchor, karaoke & desktop lyrics",
        "字号、当前句位置、逐字高亮、通知栏歌词" to "Font size, anchor, karaoke & status bar lyrics",
        "音质、效果器、淡入淡出、无缝播放" to "Audio quality, equalizer, crossfade & gapless",
        "存储" to "Storage", "下载、本地音乐、播放缓存" to "Downloads, local music & playback cache",
        "桌面" to "Desktop", "托盘、侧拉模式、Discord、快捷键" to "Tray, sidebar mode, Discord & shortcuts",
        "插件" to "Plugins", "导入 JS 插件：在线音源、歌词等服务" to "Import JS plugins for sources, motion & extensions",
        "功能管理" to "Features", "数据" to "Data", "配置备份 / 导入、WebDAV、听歌统计" to "Backup / restore, WebDAV & listening stats",
        "高级" to "Advanced", "性能、内存、调试" to "Performance, memory & diagnostics", "关于" to "About",
        // 设置子页：外观 / 语言 / 布局 / 歌词 / 播放 / 存储 / 桌面 / 数据 / 高级
        "跟随系统" to "Follow System", "跟随系统的深浅色设置" to "Match system light/dark theme",
        "深色模式" to "Dark Mode", "切换为暗色界面" to "Switch to dark appearance",
        "当前跟随系统，点击可手动切换" to "Following system; tap to override",
        "主题" to "Style", "选择主题" to "Select Style", "界面风格：BenchLab 粗线框 / 无边框（默认）" to "Visual style: framed or borderless",
        "自适应大小" to "Adaptive Scale", "界面缩放" to "UI Scale", "字体" to "Font", "系统字体" to "System Font",
        "默认 Inter 无衬线；也可以改用系统字体" to "Inter by default, or switch to system font",
        "自定义背景" to "Custom Background", "已设置" to "Configured", "未设置" to "Not set",
        "选一张图片，可调模糊、透明度、位置和大小" to "Pick an image with blur, opacity & crop controls",
        "栏位不透明度" to "Bar Opacity", "动效" to "Motion", "简单配置 / 高级曲线与外置动效插件" to "Quick presets, curves & motion plugins",
        "界面语言" to "App Language", "立即生效，无需重启应用" to "Applies immediately without restart",
        "首页布局" to "Home Layout", "调整首页模块的顺序，或关掉不需要的" to "Reorder or hide home sections",
        "首页内容来源" to "Home Content Source", "播放页布局" to "Player Layout",
        "每个按钮放在哪、什么顺序；手机和 PC 各一套" to "Customize player button slots for mobile & desktop",
        "PC 按钮竖栏自动隐藏" to "Auto-Hide Desktop Side Controls", "PC 播放页封面大小" to "Desktop Cover Size",
        "进度条显示缓存" to "Show Buffer Progress", "歌词滚动样式" to "Lyric Scroll Style",
        "导出样式 JSON" to "Export JSON", "导入样式 JSON" to "Import JSON", "歌词字号" to "Lyric Font Size",
        "当前句位置" to "Active Line Anchor", "歌词对齐" to "Lyric Alignment", "当前句指示条" to "Active Line Indicator",
        "逐字高亮" to "Word-by-Word Highlight", "远处歌词模糊" to "Depth Blur",
        "通知栏歌词" to "Notification Lyrics", "桌面歌词" to "Desktop Lyrics", "锁定桌面歌词" to "Lock Desktop Lyrics",
        "桌面歌词字号" to "Desktop Lyric Size", "显示行数" to "Line Mode", "水平对齐" to "Alignment",
        "高亮颜色" to "Accent Color", "文字不透明度" to "Text Opacity", "常驻背景底板" to "Persistent Backdrop",
        "桌面歌词逐字高亮" to "Desktop Word Highlight", "字体加粗" to "Bold Text", "文字深色描边" to "Text Outline",
        "悬浮窗位置" to "Overlay Position", "音质" to "Audio Quality", "Wi-Fi 音质" to "Wi-Fi Quality",
        "流量音质" to "Cellular Quality", "同 Wi-Fi" to "Same as Wi-Fi", "和 Wi-Fi 一样" to "Same as Wi-Fi",
        "选择音质" to "Select Audio Quality", "效果器" to "Equalizer & FX", "淡入淡出" to "Fade In/Out",
        "无缝播放" to "Gapless Playback", "下载位置" to "Download Folder", "播放缓存" to "Playback Cache",
        "清空播放缓存" to "Clear Playback Cache", "已清空播放缓存" to "Playback cache cleared",
        "窗口圆角" to "Rounded Window", "后台留存" to "Keep in Tray", "侧拉模式" to "Edge Slide Mode",
        "使用中" to "Active", "侧拉宽度" to "Edge Slide Width", "Discord 状态" to "Discord Rich Presence",
        "快捷键" to "Shortcuts", "备份 / 导入" to "Backup & Restore", "性能浮窗" to "Performance Overlay",
        "软件渲染（省内存）" to "Software Rendering", "省内存模式" to "Low Memory Mode", "内存占用" to "Memory Usage",
        "分析" to "Analyze", "清理缓存" to "Clear Cache", "已清理图片缓存" to "Image cache cleared",
        "调试模式" to "Debug Mode", "请求记录" to "Request Log", "重启后生效" to "Takes effect after restart",
        // 关于与版本更新（不含 nightly 字样）
        "版本" to "Version", "启动时检查更新" to "Check on Startup",
        "打开应用时自动检查新版本并弹窗提醒" to "Automatically check for updates when launching",
        "更新通道" to "Update Channel", "正式版：GitHub Release；测试版：GitHub Pre-release 预发布包" to "Stable: official release; Beta: pre-release preview builds",
        "正式版" to "Stable", "测试版" to "Beta", "检查更新" to "Check for Updates", "检查" to "Check",
        "正在检查…" to "Checking…", "还没检查" to "Not checked yet", "下载" to "Download", "查看页面" to "Release Page",
        "预发布包包含最新修复与试验功能，直接下载对应平台安装包即可" to "Beta builds include the latest fixes and experimental features.",
        "源代码" to "Source Code", "发现新版本" to "Update Available",
        "包含新功能与稳定性修复，建议更新体验。" to "Includes new features and stability improvements.",
        "当前为预发布测试通道，直接下载对应平台安装包覆盖安装即可。" to "Beta channel: download your platform package to update in place.",
        "立即下载更新" to "Download Update", "查看详情" to "Release Notes", "跳过此版本" to "Skip Version", "稍后再说" to "Later",
        "打不开浏览器" to "Unable to open browser", "选择语言" to "Select Language",
    )

    val ja: Map<String, String> = mapOf(
        // ナビゲーション
        "主页" to "ホーム", "曲库" to "ライブラリ", "搜索" to "検索", "私人 FM" to "パーソナル FM",
        "发现 · 排行榜" to "見つける · ランキング", "封面墙" to "カバーフロー", "设置" to "設定",
        "我的主页" to "マイページ", "本地用户" to "ローカルユーザー", "点击登录" to "タップしてログイン",
        "登录" to "ログイン", "退出登录" to "ログアウト", "取消" to "キャンセル", "确定" to "OK",
        "保存" to "保存", "重置" to "リセット", "清空" to "クリア", "删除" to "削除", "编辑" to "編集",
        "应用" to "適用", "还原" to "元に戻す", "移除" to "削除", "换一张" to "変更", "选择图片" to "画像を選択",
        "关闭" to "オフ", "关" to "オフ", "开" to "オン", "短" to "短", "中" to "中", "长" to "長",
        "慢" to "遅い", "标准" to "標準", "快" to "速い", "很快" to "高速",
        "弹簧" to "スプリング", "缓动" to "イージング", "线性" to "リニア", "自定义曲线" to "カスタム曲線",
        // ホーム・ライブラリ
        "还没有曲目" to "再生中の曲なし", "选择一首歌开始播放" to "曲を選んで再生を開始",
        "选一首歌开始播放" to "曲を選んで再生を開始",
        "播放" to "再生", "暂停" to "一時停止", "继续播放" to "再開", "加载中" to "読み込み中",
        "打开队列" to "キュー", "随机播放" to "シャッフル", "每日推荐" to "デイリーおすすめ",
        "按你的口味无限连播" to "好みに合わせて自動連続再生", "发现" to "見つける",
        "排行榜" to "ランキング", "歌单广场" to "プレイリスト", "新碟上架" to "新着アルバム",
        "新歌速递" to "新着ソング", "MV 精选" to "MV 特集", "播客电台" to "ポッドキャスト",
        "历史日推" to "過去のおすすめ", "推荐合集" to "コレクション", "查看全部" to "すべて表示",
        "全部" to "すべて", "我喜欢" to "お気に入り", "最近播放" to "最近再生",
        "本地下载" to "ダウンロード", "听歌统计" to "再生統計", "本地音乐" to "ローカル音楽",
        "WebDAV 音乐" to "WebDAV 音楽", "本机喜欢" to "端末のお気に入り", "新建歌单" to "新規プレイリスト",
        "新建本地歌单" to "新規ローカルリスト", "签到" to "チェックイン", "已签到" to "済",
        "云盘" to "クラウド", "自己上传的歌" to "アップロードした曲",
        "本机的播放次数和时长" to "この端末の再生回数と時間", "扫描手机 / 电脑里的歌" to "端末内の音楽ファイルをスキャン",
        "浏览网盘里的歌" to "WebDAV サーバーの曲を参照", "填好地址后直接播放网盘里的歌" to "WebDAV を設定して直接再生",
        "本地歌和没登录的来源点喜欢会记在这里" to "ローカル曲のお気に入りはここに保存されます",
        "管理插件" to "プラグイン管理", "列表" to "リスト", "卡片" to "グリッド", "收起" to "折りたたむ", "显示全部" to "すべて表示",
        "每日更新" to "毎日更新", "本机记录" to "端末の履歴", "相似推荐" to "関連おすすめ",
        "在线歌单" to "オンラインリスト", "本机" to "端末内", "本地" to "ローカル", "收藏" to "保存", "已收藏" to "保存済み",
        "单曲" to "曲", "专辑" to "アルバム", "歌手" to "アーティスト", "歌单" to "プレイリスト", "用户" to "ユーザー", "歌词" to "歌詞",
        // 再生・歌詞
        "列表循环" to "全曲リピート", "单曲循环" to "1曲リピート", "清空待播" to "キューをクリア",
        "热门" to "人気", "最新" to "新着", "回复" to "返信", "收起回复" to "返信を閉じる", "加载更多回复" to "さらに読み込む",
        "登录后才能评论" to "コメントするにはログインが必要です", "说点什么…" to "コメントを入力…", "刚刚" to "たった今",
        "等待下载" to "待機中", "已下载" to "保存済み", "已经下载过了" to "すでにダウンロード済みです",
        "已打开桌面歌词" to "デスクトップ歌詞をオンにしました", "已关闭桌面歌词" to "デスクトップ歌詞をオフにしました",
        "解锁桌面歌词" to "ロック解除", "回正" to "中央に戻す", "一键回正" to "中央にリセット",
        "单行" to "1行", "双行 · 译文" to "2行 · 翻訳", "双行 · 下一句" to "2行 · 次の行",
        "居中" to "中央", "左对齐" to "左揃え", "右对齐" to "右揃え",
        "跟随主题" to "テーマ連動", "冰蓝" to "アイスブルー", "樱粉" to "サクラ", "琥珀" to "アンバー", "翠绿" to "エメラルド", "纯白" to "ホワイト",
        "歌名" to "曲名", "不再推荐这首" to "おすすめから除外しました",
        // 設定メイン
        "外观" to "外観", "主题、深色、字体、取色、背景、动效" to "テーマ、ダークモード、フォント、背景、モーション",
        "语言" to "言語", "跟随系统 / 简体中文 / 繁體中文 / English / 日本語" to "システムに従う / 简体中文 / 繁體中文 / English / 日本語",
        "布局" to "レイアウト", "首页模块、播放页按钮的位置" to "ホーム構成と再生画面のボタン配置",
        "字号、当前句位置、逐字高亮、桌面歌词" to "文字サイズ、位置、カラオケ表示、デスクトップ歌詞",
        "字号、当前句位置、逐字高亮、通知栏歌词" to "文字サイズ、位置、カラオケ表示、通知歌詞",
        "音质、效果器、淡入淡出、无缝播放" to "音質、イコライザー、フェード、ギャップレス再生",
        "存储" to "ストレージ", "下载、本地音乐、播放缓存" to "ダウンロード、ローカル音楽、キャッシュ",
        "桌面" to "デスクトップ", "托盘、侧拉模式、Discord、快捷键" to "トレイ、サイドバー、Discord、ショートカット",
        "插件" to "プラグイン", "导入 JS 插件：在线音源、歌词等服务" to "音源・モーション・拡張機能の JS プラグインを管理",
        "功能管理" to "機能管理", "数据" to "データ", "配置备份 / 导入、WebDAV、听歌统计" to "バックアップ / 復元、WebDAV、再生統計",
        "高级" to "詳細設定", "性能、内存、调试" to "パフォーマンス、メモリ、デバッグ", "关于" to "このアプリについて",
        // 設定サブページ
        "跟随系统" to "システムに従う", "跟随系统的深浅色设置" to "端末のライト / ダーク設定に連動",
        "深色模式" to "ダークモード", "切换为暗色界面" to "ダークテーマに切り替え",
        "当前跟随系统，点击可手动切换" to "システムに連動中（タップで手動切替）",
        "主题" to "スタイル", "选择主题" to "スタイルを選択", "界面风格：BenchLab 粗线框 / 无边框（默认）" to "枠線あり / ボーダーレス（標準）",
        "自适应大小" to "自動スケール", "界面缩放" to "UI スケール", "字体" to "フォント", "系统字体" to "システムフォント",
        "默认 Inter 无衬线；也可以改用系统字体" to "標準は Inter、システムフォントに変更可能",
        "自定义背景" to "カスタム背景", "已设置" to "設定済み", "未设置" to "未設定",
        "选一张图片，可调模糊、透明度、位置和大小" to "背景画像とぼかし・透明度・位置を調整",
        "栏位不透明度" to "バーの不透明度", "动效" to "モーション", "简单配置 / 高级曲线与外置动效插件" to "プリセット・曲線・外部モーションプラグイン",
        "界面语言" to "表示言語", "立即生效，无需重启应用" to "再起動不要で即座に反映されます",
        "首页布局" to "ホームレイアウト", "调整首页模块的顺序，或关掉不需要的" to "ホームセクションの並び替えと表示切替",
        "首页内容来源" to "ホーム音源", "播放页布局" to "再生画面レイアウト",
        "每个按钮放在哪、什么顺序；手机和 PC 各一套" to "モバイル / PC 別にボタン配置をカスタマイズ",
        "PC 按钮竖栏自动隐藏" to "サイドボタン自動非表示", "PC 播放页封面大小" to "デスクトップ表紙サイズ",
        "进度条显示缓存" to "バッファ進捗を表示", "歌词滚动样式" to "歌詞スクロールスタイル",
        "导出样式 JSON" to "JSON を書き出し", "导入样式 JSON" to "JSON を読み込み", "歌词字号" to "歌詞サイズ",
        "当前句位置" to "現在行の位置", "歌词对齐" to "歌詞の揃え", "当前句指示条" to "現在行インジケーター",
        "逐字高亮" to "カラオケ逐字表示", "远处歌词模糊" to "遠景ぼかし",
        "通知栏歌词" to "通知歌詞", "桌面歌词" to "デスクトップ歌詞", "锁定桌面歌词" to "デスクトップ歌詞をロック",
        "桌面歌词字号" to "デスクトップ歌詞サイズ", "显示行数" to "表示行数", "水平对齐" to "水平位置",
        "高亮颜色" to "ハイライト色", "文字不透明度" to "文字の不透明度", "常驻背景底板" to "常時背景パネル",
        "桌面歌词逐字高亮" to "逐字ハイライト", "字体加粗" to "太字", "文字深色描边" to "文字の縁取り",
        "悬浮窗位置" to "ウィンドウ位置", "音质" to "音質", "Wi-Fi 音质" to "Wi-Fi 音質",
        "流量音质" to "モバイル通信音質", "同 Wi-Fi" to "Wi-Fi と同じ", "和 Wi-Fi 一样" to "Wi-Fi と同じ",
        "选择音质" to "音質を選択", "效果器" to "イコライザー / FX", "淡入淡出" to "フェード",
        "无缝播放" to "ギャップレス再生", "下载位置" to "保存先フォルダ", "播放缓存" to "再生キャッシュ",
        "清空播放缓存" to "キャッシュを削除", "已清空播放缓存" to "再生キャッシュを削除しました",
        "窗口圆角" to "ウィンドウの角丸", "后台留存" to "トレイに常駐", "侧拉模式" to "サイドバーモード",
        "使用中" to "使用中", "侧拉宽度" to "サイドバー幅", "Discord 状态" to "Discord ステータス",
        "快捷键" to "ショートカット", "备份 / 导入" to "バックアップ / 復元", "性能浮窗" to "パフォーマンス表示",
        "软件渲染（省内存）" to "ソフトウェア描画", "省内存模式" to "省メモリモード", "内存占用" to "メモリ使用量",
        "分析" to "分析", "清理缓存" to "キャッシュ削除", "已清理图片缓存" to "画像キャッシュを削除しました",
        "调试模式" to "デバッグモード", "请求记录" to "リクエスト履歴", "重启后生效" to "再起動後に反映されます",
        // バージョン・更新
        "版本" to "バージョン", "启动时检查更新" to "起動時に更新を確認",
        "打开应用时自动检查新版本并弹窗提醒" to "起動時に新しいバージョンを自動で確認します",
        "更新通道" to "更新チャンネル", "正式版：GitHub Release；测试版：GitHub Pre-release 预发布包" to "安定版：GitHub Release／テスト版：GitHub Pre-release",
        "正式版" to "安定版", "测试版" to "テスト版", "检查更新" to "更新を確認", "检查" to "確認",
        "正在检查…" to "確認中…", "还没检查" to "未確認", "下载" to "ダウンロード", "查看页面" to "リリースページ",
        "预发布包包含最新修复与试验功能，直接下载对应平台安装包即可" to "テスト版には最新の修正や試験機能が含まれます。",
        "源代码" to "ソースコード", "发现新版本" to "新しいバージョンがあります",
        "包含新功能与稳定性修复，建议更新体验。" to "新機能と安定性の改善が含まれています。",
        "当前为预发布测试通道，直接下载对应平台安装包覆盖安装即可。" to "テスト版チャンネルです。対応パッケージをダウンロードして上書き更新できます。",
        "立即下载更新" to "今すぐダウンロード", "查看详情" to "詳細を見る", "跳过此版本" to "スキップ", "稍后再说" to "あとで",
        "打不开浏览器" to "ブラウザを開けませんでした", "选择语言" to "言語を選択",
    )

    /** 繁體中文慣用語詞映射（優先於字表轉換） */
    val zhTw: Map<String, String> = mapOf(
        "设置" to "設定", "曲库" to "音樂庫", "本地用户" to "本機使用者", "本地下载" to "本機下載",
        "本地音乐" to "本機音樂", "本地" to "本機", "默认" to "預設", "缓存" to "快取",
        "播放缓存" to "播放快取", "清空播放缓存" to "清除播放快取", "清理缓存" to "清除快取",
        "软件渲染（省内存）" to "軟體算繪（省記憶體）", "省内存模式" to "省記憶體模式",
        "内存占用" to "記憶體佔用", "性能浮窗" to "效能浮窗", "调试模式" to "除錯模式",
        "性能、内存、调试" to "效能、記憶體、除錯", "通知栏歌词" to "通知列歌詞",
        "字体" to "字體", "系统字体" to "系統字體", "源代码" to "原始碼",
        "登录" to "登入", "退出登录" to "登出", "点击登录" to "點擊登入", "插件" to "外掛",
        "管理插件" to "管理外掛", "播放列表" to "播放清單", "流量音质" to "行動網路音質",
        "备份 / 导入" to "備份 / 匯入", "导出样式 JSON" to "匯出樣式 JSON", "导入样式 JSON" to "匯入樣式 JSON",
        "快捷键" to "快捷鍵", "后台留存" to "背景常駐", "跟随系统" to "跟隨系統",
        "语言" to "語言", "选择语言" to "選擇語言", "界面语言" to "介面語言",
        "正式版" to "正式版", "测试版" to "測試版",
    )

    /** 简繁成对字表（每两字为一组「简→繁」，结构上杜绝错位） */
    private const val SC_TC_PAIRS =
        "万萬与與专專业業丛叢东東丝絲两兩严嚴个個丰豐临臨为為丽麗举舉么麼义義乐樂习習乡鄉书書买買乱亂争爭云雲亚亞产產亲親仅僅从從仪儀们們价價众眾优優会會传傳伤傷体體余餘佣傭侠俠侦偵侧側侨僑俭儉倾傾偿償储儲备備复複儿兒兑兌党黨兰蘭关關兴興养養兽獸内內册冊写寫军軍农農冲衝决決况況净淨准準凉涼减減几幾凤鳳凭憑凯凱击擊划劃则則刚剛创創删刪别別制製刹剎剂劑剑劍剧劇劝勸办辦务務动動励勵劲勁劳勞势勢勋勳区區医醫华華协協单單卖賣卫衛却卻卷捲厂廠厅廳历歷厉厲压壓厌厭厕廁厘釐县縣参參双雙发發变變叙敘叠疊叶葉号號叹歎听聽启啟吴吳员員鸣鳴咏詠哑啞响響唤喚团團园園围圍国國图圖圆圓圣聖场場块塊坚堅坛壇坝壩坞塢坠墜垒壘垫墊垦墾墙牆壮壯声聲处處够夠梦夢夹夾夺奪奖獎奥奧妇婦妈媽妆妝娱娛婴嬰学學宁寧实實宠寵审審宪憲宫宮宽寬宾賓寻尋导導寿壽将將尔爾尘塵尝嘗尽盡层層届屆属屬屡屢岁歲岂豈岗崗岛島岭嶺岳嶽峡峽币幣帅帥师師帐帳帘簾带帶帮幫干幹并並广廣庄莊庆慶库庫应應庙廟庞龐废廢开開异異弃棄张張弥彌弯彎弹彈强強归歸当當录錄彻徹径徑御禦忆憶忧憂态態总總恋戀恳懇恶惡恼惱悦悅悬懸惊驚惧懼惨慘惩懲惯慣愿願懒懶怀懷戏戲战戰户戶扎紮扑撲执執扩擴扫掃扬揚扰擾抚撫抛拋抢搶护護报報顶頂拥擁拦攔拧擰拨撥择擇挂掛挟挾挠撓挡擋挣掙挤擠挥揮捞撈损損捡撿换換捣搗据據掷擲揽攬搁擱搂摟搅攪携攜摄攝摆擺摇搖摊攤撑撐敌敵敛斂数數斗鬥断斷无無旧舊显顯晋晉晒曬晓曉暂暫畅暢术術朴樸机機杀殺杂雜权權条條来來杨楊杰傑极極构構枢樞枪槍枫楓柜櫃标標栈棧栋棟栏欄树樹栖棲样樣档檔桥橋桨槳桩樁检檢椭橢楼樓榄欖横橫樱櫻橱櫥欢歡欧歐残殘毁毀毕畢气氣氢氫水永汇匯汉漢污汙汤湯汹洶沟溝没沒沥瀝沦淪沧滄沪滬泪淚泽澤洁潔洒灑洼窪浅淺浆漿浇澆浊濁测測济濟浑渾浓濃涂塗涌湧涛濤涝澇涟漣涡渦涣渙涤滌润潤涧澗涨漲涩澀淀澱渐漸渔漁渗滲温溫游遊湾灣湿濕溃潰溅濺滤濾滥濫滨濱滩灘潜潛濒瀕灭滅灯燈灵靈灾災灿燦炉爐点點炼煉炽熾烁爍烂爛烛燭烟煙烦煩烧燒热熱爱愛爷爺牵牽牺犧状狀犹猶独獨狭狹狮獅狱獄猎獵猫貓献獻环環现現琐瑣琼瓊瑶瑤电電画畫疗療疯瘋痒癢瘫癱瘾癮皱皺盏盞盐鹽监監盖蓋盗盜盘盤着著睁睜瞒瞞矫矯矿礦码碼砖磚砚硯砾礫础礎硅矽硕碩确確碍礙碱鹼礼禮祸禍禄祿禅禪离離种種积積称稱税稅稳穩穷窮窃竊窍竅窑窯窝窩竖豎竞競笔筆笼籠筑築筛篩筹籌签簽简簡篮籃篱籬类類粮糧纠糾红紅纤纖约約级級纪紀纬緯纯純纱紗纲綱纳納纵縱纷紛纸紙纹紋纺紡纽紐线線练練组組绅紳细細织織终終绊絆绍紹绎繹经經绑綁绒絨结結绕繞绘繪给給绚絢络絡绝絕绞絞统統绢絹绣繡继繼绩績绪緒续續综綜绿綠维維绵綿绷繃绸綢绽綻缀綴缅緬缆纜缉緝缎緞缓緩缔締缕縷编編缘緣缚縛缝縫缠纏缩縮缴繳网網罗羅罚罰罢罷翘翹耸聳耻恥职職联聯聪聰肠腸肤膚肾腎肿腫胀脹胁脅胆膽胜勝脉脈脏臟脑腦脓膿脚腳脱脫脸臉腊臘腾騰胶膠舰艦舱艙艳豔艺藝节節芦蘆苍蒼苏蘇苹蘋范範茎莖茧繭荐薦药藥莱萊莲蓮获獲莹瑩莺鶯萝蘿萤螢营營萧蕭萨薩葱蔥蓝藍蕴蘊虑慮虚虛虫蟲虽雖虾蝦蚀蝕蚁蟻蚂螞蚕蠶蛮蠻蜗蝸蜡蠟蝇蠅蝉蟬补補袭襲装裝裤褲见見观觀规規觅覓视視览覽觉覺触觸誉譽计計订訂认認讨討让讓训訓议議讯訊记記讲講讳諱讶訝许許论論讼訟讽諷设設访訪诀訣证證评評诅詛识識诈詐诉訴诊診词詞译譯诗詩诚誠话話诞誕诡詭询詢该該详詳语語误誤说說请請诸諸诺諾读讀课課谁誰调調谅諒谈談谊誼谋謀谍諜谎謊谐諧谓謂谚諺谜謎谢謝谣謠谦謙谨謹谱譜谷穀贝貝贞貞负負贡貢财財责責贤賢败敗账賬货貨质質贩販贪貪贫貧贬貶购購贮貯贯貫贱賤贴貼贵貴贷貸贸貿费費贺賀贼賊贾賈贿賄赁賃赂賂资資赋賦赌賭赏賞赐賜赔賠赖賴赚賺赛賽赞贊赠贈赢贏赶趕趋趨跃躍践踐踪蹤躯軀车車轧軋轨軌轩軒转轉轮輪软軟轰轟轻輕载載轿轎较較辅輔辆輛辈輩辉輝辑輯输輸辖轄辙轍辞辭辩辯还還这這进進远遠违違连連迟遲适適选選逊遜递遞逻邏遗遺遥遙邮郵邻鄰郁鬱郑鄭酱醬酿釀释釋里裡鉴鑒针針钉釘钓釣钙鈣钝鈍钞鈔钟鐘钠鈉钢鋼钩鉤钦欽钮鈕钱錢钳鉗钻鑽钾鉀铁鐵铃鈴铅鉛铆鉚铜銅铝鋁铭銘铬鉻铲鏟银銀铸鑄铺鋪链鏈销銷锁鎖锄鋤锅鍋锈鏽锋鋒锌鋅锐銳错錯锚錨锡錫锤錘锦錦锭錠键鍵锯鋸锰錳锹鍬锻鍛镀鍍镇鎮镊鑷镍鎳镜鏡镐鎬镑鎊长長门門闪閃闭閉问問闯闖闰閏闲閒间間闷悶闸閘闹鬧闺閨闻聞阀閥阁閣阅閱阉閹阎閻阐闡阔闊队隊阳陽阴陰阵陣阶階际際陆陸陈陳险險随隨隐隱难難雏雛雾霧霉黴静靜韵韻页頁项項顺順须須顽頑顾顧顿頓颁頒预預颅顱领領颇頗颈頸颊頰频頻颓頹颖穎颗顆题題额額颜顏颠顛颤顫风風飘飄飙飆飞飛饥饑饭飯饮飲饰飾饱飽饲飼饵餌饶饒饺餃饼餅饿餓馁餒馅餡馆館馈饋馋饞馏餾馒饅马馬驱驅驳駁驴驢驶駛驻駐驼駝驾駕骂罵骄驕骆駱骇駭验驗骏駿骑騎骗騙骚騷骤驟鬓鬢鱼魚鲁魯鲜鮮鲤鯉鲸鯨鳄鱷鸟鳥鸡雞鸥鷗鸦鴉鸭鴨鸯鴦鸳鴛鸵鴕鸽鴿鸿鴻鹅鵝鹊鵲鹏鵬鹤鶴鹰鷹麦麥黄黃齐齊齿齒龄齡龙龍龟龜"

    private val scToTcMap: Map<Char, Char> by lazy {
        HashMap<Char, Char>(SC_TC_PAIRS.length).apply {
            var i = 0
            while (i + 1 < SC_TC_PAIRS.length) {
                val s = SC_TC_PAIRS[i]; val t = SC_TC_PAIRS[i + 1]
                if (s != t) put(s, t)
                i += 2
            }
        }
    }

    /** 简→繁平滑转换（先替换台湾/港澳常用词，再逐字映射） */
    fun toTraditional(s: String): String {
        val r = s.replace("设置", "設定").replace("默认", "預設").replace("缓存", "快取")
            .replace("软件", "軟體").replace("内存", "記憶體").replace("性能", "效能")
            .replace("调试", "除錯").replace("曲库", "音樂庫").replace("登录", "登入")
            .replace("插件", "外掛").replace("导入", "匯入").replace("导出", "匯出")
            .replace("本地", "本機").replace("音频", "音訊").replace("视频", "影片")
            .replace("信息", "資訊").replace("搜索", "搜尋").replace("支持", "支援")
            .replace("网络", "網路").replace("点击", "點擊").replace("界面", "介面")
            .replace("后台", "背景").replace("用户", "使用者")
        val sb = StringBuilder(r.length)
        for (ch in r) sb.append(scToTcMap[ch] ?: ch)
        return sb.toString()
    }
}
