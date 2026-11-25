// @id coverflow
// @name 3D 封面墙
// @type feature
// @feature coverflow
// @version 1.0.0
// @author NEKOPLAY
// @desc 经典 3D 透视封面墙（镜面倒影、实体唱片厚度、鼠标 3D 随动、点击 180° 翻转看曲目；手机横屏自动进入）
// @api 1

nk.registerFeature({
  manifest() {
    return {
      type: 'feature',
      feature: 'coverflow',
      autoLandscape: true,
      navEntry: true,
    };
  },
  action() {
    return {
      open: 'coverflow',
      message: '已打开 3D 封面墙',
    };
  },
});
