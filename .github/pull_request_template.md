## 改了什么

<!-- 一两句话说明这个 PR 做了什么、为什么 -->

## 检查

- [ ] `cd desktop && ./gradlew compileKotlin test ktlintCheck` 通过
- [ ] `./gradlew :app:compileReleaseKotlin` 通过
- [ ] 动了共用代码里平台相关的部分时，iOS 也编译过（`cd ios && ./gradlew compileKotlinIosArm64`）
- [ ] 新的纯逻辑加了单元测试
- [ ] 用户能感知的改动写进了 `CHANGELOG.md` 的「未发布」
- [ ] 没有提交密钥、`local.properties` 等本机文件
