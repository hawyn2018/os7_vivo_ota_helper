# LK 升级助手 2.2

Android 应用，包名 `net.hawyn.vivootahelper`，版本 `2.2.0`。

用于 OTA 安装完成、重启之前，保留当前可用 LK 并准备对槽的启动兼容修改。使用设备上的 Root 权限，识别当前槽和另一槽，备份当前 LK、目标 LK/vendor_boot 及只读 SCP 快照；预览修补结果后弹出确认框，用户勾选并确认才写入另一槽的 LK 和 vendor_boot。提供备份导出、恢复预览和事务记录。

Native 修改涵盖主 DSI 路径、SCP 兼容预留、`/chosen/bt_log` 交接字段，以及 vr/vklp 模块和加载索引清理。应用不写 init_boot、不切槽、不重启；保留原 AVB 元数据，不重签。写入成功表示 dd 与 sync 命令完成，应用不进行备份、输出或分区摘要检查及写后内容比较。

## 使用与兼容记录

- 设备需已取得 Root；OTA 写入另一槽完成后再准备。准备后若继续安装更新，请重新准备。
- init_boot 由用户使用 KernelSU 等工具另行处理，基底应为本次 OTA 的同版本原厂镜像，并对应目标槽。仓库不附带 LK、固件或 KernelSU 镜像。
- 2.1 曾有用户报告在 OriginOS 7 `17.1.9.8` 成功开机。2.2 沿用 2.1 修补逻辑，更新包名与首页文案；该记录覆盖当次设备/固件组合，每次新 OTA 仍需验证启动和实际使用。
- 旧 LK 的 SCP 安全世界完整元数据表尚未补齐，低功耗网络代理等业务未全面验证；报告保留 `fullMetadataRepair=false`。

## 构建

需要 JDK 21、Android SDK 37 和 NDK `29.0.14206865`。项目使用 Gradle Wrapper 9.6.1；Android Studio 或本机 SDK 配置负责设置 SDK 路径。

```sh
bash gradlew :app:testDebugUnitTest :app:assembleDebug
bash gradlew :app:assembleRelease
```

Windows 可使用 `gradlew.bat`。发布签名使用自己的密钥及本地 `signing.properties`；这些文件受 `.gitignore` 排除。未配置发布签名时，release 构建输出未签名 APK。

本仓库只提供源码，未发布 APK，也不包含私钥、设备日志或固件。Native 宿主实镜像回归脚本保留在 `patcher/tests/`，需要仓库外的固件、参考解析器和测试 fixture；这些材料不随源码分发。

## 许可证

项目许可证见 [LICENSE](LICENSE)。第三方许可与版本说明见 [patcher/THIRD_PARTY_NOTICES.txt](patcher/THIRD_PARTY_NOTICES.txt) 和 `app/src/main/assets/licenses/`。
