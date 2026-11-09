# Minekuai YSM Bridge

**适用于 Minekuai 麦块面板服的 YSM 上传管理工具：服务端重载桥。**

这是一个 Minecraft Java / NeoForge 服务端辅助模组，配合 [Minekuai YSM Web](https://github.com/shallow-zzt/minekuai-ysm-web) 使用。网页经由面板已有的 SFTP 通道上传模型并写入重载请求，本模组在服务端读取请求，执行固定的 `ysm model reload` 命令，将结果写回文件供网页查询。

适合可以使用 SFTP、可以安装服务端模组，但无法新增端口或不方便使用 RCON 的麦块面板服。桥接模组不监听网络端口，也不保存面板用户名或密码。

> 本项目是社区工具，与 Minekuai 麦块及 Yes Steve Model 官方没有隶属关系。网页与 Go 后端在独立的 [Web 仓库](https://github.com/shallow-zzt/minekuai-ysm-web) 中维护。

## 工作流程

```text
玩家浏览器 → Web / Go 后端 → 面板 SFTP → YSM 模型目录
                                 └→ requests/<请求 ID>.req
Minecraft 服务端 → 本模组定期读取请求 → 执行 ysm model reload
                                 └→ acks/<请求 ID>.json → 网页显示结果
```

模型解析与客户端同步仍由 YSM 完成。本模组只负责触发重载、报告命令执行结果和写入在线心跳。

## 版本要求

| 组件 | 要求或构建目标 |
| --- | --- |
| Minecraft Java | 1.21.1 |
| NeoForge | 源码构建目标 21.1.265；模组元数据声明范围为 21.1.x |
| Yes Steve Model | 以 2.6.5 为目标；模组元数据声明范围为 `>=2.6.5, <2.7` |
| Java | JDK / JRE 21 |
| Gradle | 自带 Wrapper，固定为 9.2.1 |

仅向服务端安装本模组，玩家继续使用服务器要求的原有 YSM 客户端环境。仓库不包含 Minecraft、YSM 或其他第三方模组 JAR。

## 安装

1. 按下文构建模组，取得 `build/libs/ysm-reload-bridge-1.0.0.jar`。也可使用本仓库成功完成的 GitHub Actions 构建产物。
2. 将 JAR 上传到麦块面板服的 `mods` 目录，确认服务端已经安装对应版本的 NeoForge 和 YSM。
3. 正常重启 Minecraft 服务端一次。
4. 启动 [配套 Web 应用](https://github.com/shallow-zzt/minekuai-ysm-web)，在其后端填写面板显示的 SFTP 连接信息，并将根目录指向包含 `mods`、`config`、`server.properties` 的游戏目录。
5. 服务端启动后约 5 秒，应出现 `config/ysm_upload_bridge/heartbeat.json`，其时间随后持续更新。
6. 通过网页上传一个已知可用的小模型，观察重载回执，再进游戏检查模型是否正常加载。

不需要在面板新增端口。SFTP 用户和 Minecraft 进程必须能读写同一个游戏目录下的桥接文件。

## 构建

需要 JDK 21，首次构建需要联网下载 Gradle、NeoForge 和相关构建依赖。

Windows PowerShell：

```powershell
.\gradlew.bat build
```

Linux / macOS：

```sh
bash ./gradlew build
```

输出：`build/libs/ysm-reload-bridge-1.0.0.jar`。CI 使用相同的 Gradle Wrapper 构建，并将 JAR 保存为 Actions artifact。

## 请求与回执协议

所有路径均相对于 **Minecraft 游戏根目录**：

```text
config/ysm_upload_bridge/
├── heartbeat.json             # 本模组约每 5 秒更新
├── requests/
│   └── <32 位小写十六进制 ID>.req
└── acks/
    └── <同一 ID>.json
```

Web 应用还会使用 `config/ysm_upload_bridge/staging/` 临时保存上传数据，最终模型写入 `config/yes_steve_model/custom/`。这些上传操作由 Web 后端负责。

### 重载请求

创建名称符合 `[a-f0-9]{32}.req` 的普通文件即可。文件内容不会被当作命令读取；无论内容是什么，本模组只执行固定命令 `ysm model reload`。不处理符号链接或不符合命名规则的请求。

### 心跳

```json
{
  "version": "1.0.0",
  "time": "2026-01-01T00:00:00Z"
}
```

### 命令回执

```json
{
  "id": "0123456789abcdef0123456789abcdef",
  "status": "dispatched",
  "message": "重载命令已执行，模型加载和客户端同步结果请在游戏内确认",
  "time": "2026-01-01T00:00:00Z"
}
```

- `dispatched`：命令处理器返回成功。**不代表**每个模型已经解析成功或所有客户端已经同步完成。
- `failed`：命令返回失败或抛出异常，需要查看服务端日志。
- 回执写入完成后删除相应请求。已存在回执的请求会被清理，不再次执行。

JSON 先写入同目录临时文件，再通过重命名发布；文件系统不支持原子重命名时会回退为普通替换。

## 运行行为与限制

- 后台线程每 5 秒检查一次；每批最多处理 64 个请求，两批重载之间至少间隔 30 秒。
- 命令在 Minecraft 服务端主线程以权限等级 4 执行。后台线程等待这一批命令完成，不会并发触发下一批。
- 服务正常、队列较短时，网页提交后通常约 5–35 秒触发重载；服务器卡顿、积压请求及 YSM 后续处理可能延长等待。
- 服务端停机期间，请求文件保留，下一次开服后继续处理。
- 命令执行后、回执写入前发生异常退出，恢复后可能再次执行一次重载，因此不承诺严格的恰好一次执行。
- 回执按天清理，通常保留至少 7 天；仍有同 ID 请求的回执会保留。
- 重载会作用于服务器模型库。模型兼容性、授权、内部 ID 冲突、解析失败及客户端同步应结合 YSM 日志和游戏内结果确认。

## 验证状态

源码沿用已在本地使用 JDK 21 与 Minecraft 1.21.1 / NeoForge 21.1.257 API 编译过的桥接实现；标准 Gradle 构建配置的目标为 NeoForge 21.1.265。先前完整 Gradle 依赖下载遇到 TLS 连接失败。此公开仓库提供标准构建配置和 CI，具体构建结果以 Actions 记录为准。

目前尚未完成此模组在 Minecraft 进程或实际麦块面板服中的加载与端到端重载验收。请先用测试服或已知可用的小模型按安装步骤验证；网页显示的命令回执与游戏内模型加载是两个不同阶段。

## 源码结构

- `src/main/java/dev/ysmbridge/ReloadBridge.java`：轮询、固定命令执行、回执和心跳。
- `src/main/resources/META-INF/neoforge.mods.toml`：模组与依赖声明。
- `build.gradle`：Java 21、NeoForge 与 JAR 构建配置。
- `.github/workflows/build.yml`：公开仓库的构建检查。

## 许可与参考

本仓库的桥接实现使用 [MIT License](LICENSE)。YSM 及上传的玩家模型遵循各自的许可，本仓库不包含它们的资源。

- [配套 Web 应用源码](https://github.com/shallow-zzt/minekuai-ysm-web)
- [YSM 常见问题](https://yesstevemodel.github.io/en/wiki/faq/)
- [YSM 命令说明](https://yesstevemodel.github.io/en/wiki/command/)
