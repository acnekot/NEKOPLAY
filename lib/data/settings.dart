import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum ThemePref { system, light, dark }

const kQualityLabels = ['无损优先', '高品质', '省流量'];
const kQualityKeys = ['lossless', 'high', 'standard'];

class Settings {
  const Settings({
    this.theme = ThemePref.system,
    this.fade = true,
    this.gapless = true,
    this.quality = 0,
    this.fallback = true,
    this.wifiOnly = false,
    this.spectrum = true,
    this.spectrumStyle = 0,
    this.name = '好奇猫a',
  });

  final ThemePref theme;
  final bool fade, gapless, fallback, wifiOnly, spectrum;
  final int quality, spectrumStyle;
  final String name;

  Settings copyWith({
    ThemePref? theme,
    bool? fade,
    bool? gapless,
    int? quality,
    bool? fallback,
    bool? wifiOnly,
    bool? spectrum,
    int? spectrumStyle,
    String? name,
  }) =>
      Settings(
        theme: theme ?? this.theme,
        fade: fade ?? this.fade,
        gapless: gapless ?? this.gapless,
        quality: quality ?? this.quality,
        fallback: fallback ?? this.fallback,
        wifiOnly: wifiOnly ?? this.wifiOnly,
        spectrum: spectrum ?? this.spectrum,
        spectrumStyle: spectrumStyle ?? this.spectrumStyle,
        name: name ?? this.name,
      );

  String get qualityLabel => kQualityLabels[quality];
  String get qualityKey => kQualityKeys[quality];
  String get avatar => name.isEmpty ? '猫' : String.fromCharCode(name.runes.first);
}

final prefsProvider = Provider<SharedPreferences>((ref) => throw UnimplementedError());

final settingsProvider = NotifierProvider<SettingsNotifier, Settings>(SettingsNotifier.new);

class SettingsNotifier extends Notifier<Settings> {
  late SharedPreferences _p;

  @override
  Settings build() {
    _p = ref.read(prefsProvider);
    return Settings(
      theme: ThemePref.values[(_p.getInt('theme') ?? 0).clamp(0, 2)],
      fade: _p.getBool('fade') ?? true,
      gapless: _p.getBool('gapless') ?? true,
      quality: (_p.getInt('quality') ?? 0).clamp(0, 2),
      fallback: _p.getBool('fallback') ?? true,
      wifiOnly: _p.getBool('wifiOnly') ?? false,
      spectrum: _p.getBool('spectrum') ?? true,
      spectrumStyle: (_p.getInt('spectrumStyle') ?? 0).clamp(0, 2),
      name: _p.getString('name') ?? '好奇猫a',
    );
  }

  void set(Settings s) {
    state = s;
    _p.setInt('theme', s.theme.index);
    _p.setBool('fade', s.fade);
    _p.setBool('gapless', s.gapless);
    _p.setInt('quality', s.quality);
    _p.setBool('fallback', s.fallback);
    _p.setBool('wifiOnly', s.wifiOnly);
    _p.setBool('spectrum', s.spectrum);
    _p.setInt('spectrumStyle', s.spectrumStyle);
    _p.setString('name', s.name);
  }

  void update(Settings Function(Settings s) f) => set(f(state));
}
