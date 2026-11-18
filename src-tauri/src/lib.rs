use tauri::{LogicalSize, Manager};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // 本地播放器：允许从文件选择器导入后直接播放，不被 WebView2 的自动播放策略拦截。
    if std::env::var_os("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS").is_none() {
        std::env::set_var(
            "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS",
            "--autoplay-policy=no-user-gesture-required",
        );
    }

    tauri::Builder::default()
        .setup(|app| {
            // 按屏幕可用区域缩放，避免在 125% / 150% 缩放的屏幕上超出桌面。
            if let Some(window) = app.get_webview_window("main") {
                if let Ok(Some(monitor)) = window.current_monitor() {
                    let scale = monitor.scale_factor();
                    let screen = monitor.size().to_logical::<f64>(scale);
                    let width = (screen.width * 0.88).clamp(1000.0, 1440.0);
                    let height = (screen.height * 0.88).clamp(700.0, 900.0);
                    let _ = window.set_size(LogicalSize::new(width, height));
                    let _ = window.center();
                }
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running NEKOPLAY");
}
