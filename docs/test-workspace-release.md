# MP工作台 发布更新源

普通 Mika 构建继续查询 `wangzan101/Mika`。MP工作台构建必须配置独立的公开 GitHub 仓库；未配置时关闭更新查询，不会把官方 Mika APK 展示为 MP工作台更新。

```sh
./gradlew :app:assembleWorkspace -PmpReleaseRepository=https://github.com/OWNER/MP-Workspace
```

配置值只接受 `https://github.com/OWNER/REPOSITORY`。MP工作台从该仓库的 GitHub Releases 最新发布读取更新。发布附件需要包含 APK；安装时仍校验下载地址的仓库路径、APK 包名、版本和签名。后续版本必须使用与已安装 MP工作台相同的签名密钥。

`workspace` build type 目前继承本地 debug 签名，适合开发和设备验收。正式分发前应为发布构建配置固定的 MP工作台签名，并用同一签名生成后续更新 APK。
