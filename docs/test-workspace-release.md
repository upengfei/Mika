# MP工作台 发布更新源

普通 Mika 构建继续查询 `wangzan101/Mika`。MP工作台可查询其配置的公开 GitHub 仓库，但只接受 `mp-v` 开头的 Release 标签；未配置仓库或仓库没有 MP Release 时，更新检查不会把 Mika Release 当成 MP工作台更新。

```sh
./gradlew :app:assembleWorkspace -PmpReleaseRepository=https://github.com/upengfei/Mika -PmpVersionCode=40
```

配置值只接受 `https://github.com/OWNER/REPOSITORY`。MP工作台从该仓库的 GitHub Releases 列表选择最新的 `mp-v*` 发布。发布附件需要包含 APK；安装时仍校验下载地址的仓库路径、APK 包名、版本和签名。后续版本必须使用与已安装 MP工作台相同的签名密钥。

`workspace` build type 目前继承本地 debug 签名，适合开发和设备验收。正式分发前，GitHub Actions 必须使用与已安装 MP工作台相同的签名密钥生成后续 APK。若换签名，Android 会拒绝覆盖安装，用户只能卸载旧版后重装并丢失本机数据。

## Fork 配置

仓库：`https://github.com/upengfei/Mika`。源码镜像保留在 `main`，MP工作台改动放在 `mp-workspace`。将 `mp-workspace` 设为 fork 的默认分支，GitHub 才会按计划触发该分支里的定时工作流；`main` 仍只保存 Mika 上游镜像。首次启用前，在 fork 的 Settings → Secrets and variables → Actions 添加 `MP_SIGNING_KEYSTORE_BASE64`、`MP_SIGNING_STORE_PASSWORD`、`MP_SIGNING_KEY_ALIAS` 和 `MP_SIGNING_KEY_PASSWORD`。这些值必须来自当前手机已安装 MP工作台所用的签名密钥；签名密钥另存加密离线备份，不要提交到 Git。

`.github/workflows/mp-sync-release.yml` 每 6 小时检查 `wangzan101/Mika` 的 `main`，只在发现新上游提交时将其合并进 `mp-workspace`。工作流依次运行 `:core:test`、`:app:testDebugUnitTest`、用既有签名构建 workspace APK、运行 Android 模拟器启动冒烟，然后推送分支并发布带 `mp-v` 前缀的 Release。`mp-version-code.txt` 为 MP 包单独递增版本码，即使 Mika 上游提交没有改版本号，也能覆盖安装。普通 Mika 构建和更新 API 不变。
