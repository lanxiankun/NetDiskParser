# NetDiskParser 网盘解析下载器

Android 网盘解析下载工具，仓库包含两个独立 App：

| | **NetDiskParser**（云盘解析下载器） | **NetDiskParser2**（网盘直链下载） |
|---|---|---|
| 包名 | `com.netdisk.parser` | `com.netdisk.parser2` |
| 最新版本 | v2.0.37 | v1.0.5 |
| 解析方式 | 需在设置中填写 189.qaiu.top 解析站 API Key | **免 API Key**，粘贴链接自动识别解析 |
| 支持链接 | 夸克、UC、123 网盘、蓝奏云、天翼云、移动/联通云盘等 20+ 平台分享链接 | 189.qaiu.top 的 `/dir/` 目录分享链接（如 `https://189.qaiu.top/dir/xxxxxx`） |
| 目录分享 | v2.0.37 起支持：把分享链接一键生成 `/dir/` 目录分享页（需 API Key） | — |
| 下载 | 内置 Gopeed 多线程下载，可选节点代理中转（123 网盘提速） | 同左 |

两个 App 均为：**粘贴链接 → 自动识别网盘与提取码 → 解析直链 → 内置下载引擎多线程下载**。数据目录、日志目录、下载目录彼此独立，互不干扰。

## 架构

- **Android 壳**：Java 原生（前台保活服务 + FileProvider）
- **核心服务**：Go 编译为 `.so`（本地 HTTP 服务 + 解析接口转发 + 下载代理），经 JNI 启动
- **下载引擎**：内置 Gopeed（Go 开源下载器，以库形式集成，负责分片并发下载）
- **界面**：原生 HTML/CSS/JS（单文件 `index.html`，WebView 加载本地服务渲染）
- **网盘解析**：调用 189.qaiu.top 解析服务（本地网关 `/parse/*` 转发，免跨域）
- **节点代理**：内置 dnode 节点（WebSocket 隧道），123 网盘等平台经 SOCKS5 节点中转提速

## 目录结构

```
netdisk-parser-app/   # App1 云盘解析下载器：Go 主服务 + 前端 UI + 安装包
  ui/index.html       #   前端（单文件）
  dist/               #   v2.0.37 安装包
netdisk-parser2/      # App2 网盘直链下载：Go 主服务 + 前端 UI + Android 壳 + 安装包
  ui/index.html       #   前端（单文件）
  android-shell/      #   Android 壳（Java 源码 + 打包脚本）
  dist/               #   v1.0.5 安装包
android-shell/        # App1 的 Android 壳（Java 源码 + 资源 + 打包脚本）
gopeed-src/           # Gopeed v1.9.3 源码（go.mod replace 引用）
vendor-patch/         # anet 补丁库（Android 编译依赖）
legacy/               # 历史版本存档（android-native、早期 netdisk-parser）
netdisk-parser-win-java/  # 桌面版 Java 实现（Windows 端）
```

## 构建

依赖：Go 1.24+、Android NDK 25、Android SDK build-tools 34、JDK 21

### 桌面版（Windows / macOS / Linux）

```bash
cd netdisk-parser-app   # 或 netdisk-parser2
go build -o NetDiskParser .
./NetDiskParser
```

### App1（云盘解析下载器）Android APK

```bash
cd android-shell
bash build_merged.sh   # 输出 ../dist/NetDiskParser-android-arm64.apk
```

### App2（网盘直链下载）Android APK

```bash
cd netdisk-parser2/android-shell
bash build_merged.sh   # 输出 ../dist/NetDiskParser2-android-arm64.apk
```

Android 打包要点：

- Go 交叉编译：`GOOS=android GOARCH=arm64 CGO_ENABLED=1` + NDK clang → `libnetdiskparser.so`
- 命令行打包（aapt2 / javac / d8 / zipalign / apksigner），不使用 Gradle
- d8 限制：禁用匿名内部类（Java 8 lambda 会 NPE），必须用静态嵌套类

## 分享文件夹功能（App1 v2.0.37+）

设置中填写解析站 API Key 后，解析页点「分享文件夹」按钮，即可把当前分享链接生成目录分享页：

```
GET https://189.qaiu.top/v2/directoryShare/create?url={分享链接}&pwd={提取码}&token={API Key}
→ 返回 https://189.qaiu.top/dir/{code}
```

生成的 `/dir/` 链接任何人免 Key 即可浏览目录并下载。
