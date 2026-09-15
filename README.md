# NetDiskParser 网盘解析下载器

Android 网盘解析下载 App：粘贴分享链接 → 自动识别网盘与提取码 → 解析出直链 → 内置下载引擎多线程下载。

## 架构

- **Android 壳**：Java 原生（前台保活服务 + FileProvider）
- **核心服务**：Go 语言编译为 `.so`（本地 HTTP 服务 + 解析接口转发 + 下载代理），经 JNI 启动
- **下载引擎**：内置 Gopeed（Go 开源下载器，以库形式集成，负责分片并发下载）
- **界面**：原生 HTML/CSS/JS（单文件 `index.html`，WebView 加载本地服务渲染）
- **网盘解析**：调用 189.qaiu.top 解析服务

## 目录结构

```
netdisk-parser-app/   # Go 主服务（解析转发 + Gopeed 集成 + 下载代理 + 前端 UI）
android-shell/        # Android 壳（Java 源码 + 资源 + 打包脚本）
gopeed-src/           # Gopeed v1.9.3 源码（go.mod replace 引用）
vendor-patch/         # anet 补丁库（Android 编译依赖）
```

## 构建

### 桌面版（Windows / macOS / Linux）

```bash
cd netdisk-parser-app
go build -o NetDiskParser .
./NetDiskParser
```

### Android APK

依赖：Go 1.24+、Android NDK 25、Android SDK build-tools 34、JDK 21

```bash
cd android-shell
bash build_merged.sh   # 输出 ../dist/NetDiskParser-android-arm64.apk
```

Android 打包要点：

- Go 交叉编译：`GOOS=android GOARCH=arm64 CGO_ENABLED=1` + NDK clang → `libnetdiskparser.so`
- 命令行打包（aapt2 / javac / d8 / zipalign / apksigner），不使用 Gradle
- d8 限制：禁用匿名内部类（Java 8 lambda 会 NPE），必须用静态嵌套类

## 功能

- 整段文本识别网盘链接 + 提取码（支持 123 / 夸克 / UC / 蓝奏 / 奶牛 / 超星等）
- 读取剪贴板、目录文件树批量下载
- 任务管理（暂停 / 继续 / 删除，删除同步删本地文件）
- 解析历史、通知栏进度、会话日志
- 已完成任务打开 / 安装（APK 走系统安装器）
