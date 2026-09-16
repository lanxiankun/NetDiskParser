// 云盘解析下载器 - 桌面 App
// 一个二进制 = 解析器(189.qaiu.top 代理) + 内置 Gopeed 下载引擎 + Web 界面
package main

import (
	"bytes"
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/GopeedLab/gopeed/pkg/base"
	"github.com/GopeedLab/gopeed/pkg/rest"
	"github.com/GopeedLab/gopeed/pkg/rest/model"
	"golang.org/x/net/proxy"
)

//go:embed ui/index.html
var uiFS embed.FS

const (
	ParserBase  = "https://189.qaiu.top"
	DefaultPort = 18091
	AppName     = "NetDiskParser2"
)

type appConfig struct {
	DownloadDir string `json:"downloadDir"`
	QuarkCookie string `json:"quarkCookie,omitempty"`
}

var gopeedPort int
var serverAddr string
var appConfigPath string
var appLogPath string
var quarkCookie string // 夸克 Cookie（设置页保存，下载代理自动携带）

var logMu sync.Mutex

// tzLoc 日志用本地时区：Android 上 Go 读不到系统时区会回退 UTC，
// 由 Java 侧在 JNI 启动时把设备时区偏移传入（setTzOffset），强制用设备本地时间
var tzLoc = time.Local

// setTzOffset 设置日志时区偏移（秒）。offset 为设备时区相对 UTC 的偏移秒数
func setTzOffset(offsetSec int) {
	if offsetSec != 0 {
		tzLoc = time.FixedZone("Local", offsetSec)
	}
}

// writeLogFile 把一条日志追加写入当前会话日志文件
func writeLogFile(level, msg string) {
	if appLogPath == "" {
		return
	}
	logMu.Lock()
	defer logMu.Unlock()
	line := fmt.Sprintf("%s [%s] %s\n", time.Now().In(tzLoc).Format("2006-01-02 15:04:05"), level, msg)
	f, err := os.OpenFile(appLogPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	_, _ = f.WriteString(line)
	_ = f.Close()
}

// cleanupOldLogs 只保留最近 30 个会话日志文件，删除更早的
func cleanupOldLogs(dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	var logs []string
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if strings.HasSuffix(e.Name(), ".log") {
			logs = append(logs, e.Name())
		}
	}
	if len(logs) <= 30 {
		return
	}
	sort.Strings(logs) // 文件名带时间戳，字典序即时间序
	for i := 0; i < len(logs)-30; i++ {
		_ = os.Remove(filepath.Join(dir, logs[i]))
	}
}

// logsHandler 返回完整运行日志（供 App 内查看）
func logsHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	if appLogPath == "" {
		_, _ = w.Write([]byte("(日志尚未初始化)"))
		return
	}
	b, err := os.ReadFile(appLogPath)
	if err != nil {
		_, _ = w.Write([]byte("(暂无日志)"))
		return
	}
	_, _ = w.Write(b)
}

func main() {
	if err := runServer(""); err != nil {
		fmt.Fprintln(os.Stderr, "启动失败:", err)
		os.Exit(1)
	}
	// ===== 5. 自动打开浏览器 =====
	openBrowser("http://" + serverAddr + "/")

	// ===== 6. 常驻 =====
	select {}
}

// runServer 完成：数据目录 → 下载目录 → 内置 Gopeed → 主 HTTP 服务（桌面版与 Android 版共用）
// dataDir 为空时使用系统用户配置目录（桌面）；Android 由 Java 传入 App 私有目录
func runServer(dataDir string) error {
	// ===== 1. 应用数据目录 =====
	appDir := dataDir
	if appDir == "" {
		cfgDir, err := os.UserConfigDir()
		if err != nil {
			cfgDir = "."
		}
		appDir = filepath.Join(cfgDir, AppName)
	}
	if err := os.MkdirAll(appDir, 0o755); err != nil {
		return fmt.Errorf("创建配置目录失败: %w", err)
	}
	appConfigPath = filepath.Join(appDir, "app.json")
	// 日志目录：Android 优先公共专属文件夹（用户可直接在文件管理器看到），失败则退回 App 私有目录
	logDir := filepath.Join(appDir, "logs")
	if runtime.GOOS == "android" {
		if pub, err := publicAppRoot(); err == nil {
			if ldir := filepath.Join(pub, "logs"); os.MkdirAll(ldir, 0o755) == nil {
				logDir = ldir
			}
		}
	}
	if err := os.MkdirAll(logDir, 0o755); err == nil {
		// 每次启动创建新会话日志：logs/2006-01-02_15-04-05.log（本地时区）
		appLogPath = filepath.Join(logDir, time.Now().In(tzLoc).Format("2006-01-02_15-04-05")+".log")
		cleanupOldLogs(logDir)
	}
	appLog("数据目录: %s", appDir)
	appLog("日志文件: %s", appLogPath)

	// ===== 2. 下载目录（读持久化配置，默认各平台下载目录）=====
	downloadDir := defaultDownloadDir(appDir)
	if b, err := os.ReadFile(filepath.Join(appDir, "app.json")); err == nil {
		var cfg appConfig
		if json.Unmarshal(b, &cfg) == nil {
			if cfg.DownloadDir != "" {
				downloadDir = cfg.DownloadDir
			}
			quarkCookie = cfg.QuarkCookie
		}
	}
	if err := os.MkdirAll(downloadDir, 0o755); err != nil {
		// 公共目录创建失败（多半是存储权限未授权）→ 降级到 App 私有目录，保证 App 可用
		appLogError("下载目录创建失败，降级到 App 私有目录: %v", err)
		downloadDir = filepath.Join(appDir, "Download")
		if err := os.MkdirAll(downloadDir, 0o755); err != nil {
			return fmt.Errorf("创建下载目录失败: %w", err)
		}
	}
	appLog("下载目录: %s", downloadDir)

	// ===== 2.5 内置 dnode 节点 =====
	// Go 版节点已停用：节点功能由 py 版（DnodeBridge 启动官方 node_clientv4 脚本）接管，
	// 避免双节点并存导致下载中崩溃/服务端路由竞争

	// ===== 3. 启动内置 Gopeed 下载引擎（本地随机端口）=====
	startCfg := &model.StartConfig{
		Network:        "tcp",
		Address:        "127.0.0.1:0",
		Storage:        model.StorageBolt,
		StorageDir:     filepath.Join(appDir, "gopeed-data"),
		ProductionMode: true,
		DownloadConfig: &base.DownloaderStoreConfig{
			DownloadDir: downloadDir,
			MaxRunning:  5,
		},
		WebEnable: false,
	}
	port, err := rest.Start(startCfg)
	if err != nil {
		return fmt.Errorf("内置下载引擎启动失败: %w", err)
	}
	gopeedPort = port
	appLog("内置 Gopeed 引擎已启动 (127.0.0.1:%d)", port)

	// 引擎启动后初始化下载目录（Gopeed 的 BuildServer 不读取 StartConfig.DownloadConfig，
	// 下载目录须通过 PUT /api/v1/config 设置并持久化）
	if err := initGopeedConfig(downloadDir, 5); err != nil {
		appLogError("初始化下载目录失败: %v", err)
	}

	// ===== 4. 主 HTTP 服务 =====
	uiHTML, _ := uiFS.ReadFile("ui/index.html")
	mux := http.NewServeMux()

	// 4.1 前端界面
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(uiHTML)
	})

	// 4.2 Gopeed REST API 反向代理（同源访问），并记录下载任务的创建与结果
	proxy := httputil.NewSingleHostReverseProxy(&url.URL{
		Scheme: "http",
		Host:   fmt.Sprintf("127.0.0.1:%d", gopeedPort),
	})
	baseDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		baseDirector(req)
		if req.Method == http.MethodPost && strings.HasPrefix(req.URL.Path, "/api/v1/tasks") {
			body, _ := io.ReadAll(req.Body)
			req.Body.Close()
			req.Body = io.NopCloser(bytes.NewReader(body))
			appLog("创建下载任务: %s", string(body))
		}
	}
	proxy.ModifyResponse = func(resp *http.Response) error {
		if resp.Request != nil && resp.Request.Method == http.MethodPost &&
			strings.HasPrefix(resp.Request.URL.Path, "/api/v1/tasks") {
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			resp.Body = io.NopCloser(bytes.NewReader(body))
			appLog("任务创建结果: %s", string(body))
		}
		return nil
	}
	mux.Handle("/api/v1/", proxy)

	// 4.2.1 后台轮询任务状态，把失败原因写入日志
	go watchTaskStatus()

	// 4.3 解析 API 代理（189.qaiu.top，服务端转发免跨域）
	mux.HandleFunc("/parse/", parseProxy)

	// 4.4 App 级配置（下载目录持久化）
	mux.HandleFunc("/app/config", appConfigHandler)
	// py 节点状态上报：Java 侧（DnodeBridge）把内置节点运行状态写入会话日志
	mux.HandleFunc("/app/pylog", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.RemoteAddr, "127.0.0.1") {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		msg := r.URL.Query().Get("msg")
		tag := r.URL.Query().Get("tag")
		if tag == "" {
			tag = "节点" // 兼容 Java 节点日志
		}
		if msg != "" {
			appLog("%s: %s", tag, msg)
		}
		w.WriteHeader(http.StatusOK)
	})

	// 4.5 代理插件状态：读脚本写入的 dnode_status.json（内置节点运行状态）
	mux.HandleFunc("/app/node-status", func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.RemoteAddr, "127.0.0.1") {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		sf := filepath.Join(appDir, "dnode_status.json")
		if data, err := os.ReadFile(sf); err == nil && len(data) > 0 {
			w.Write(data)
			return
		}
		json.NewEncoder(w).Encode(map[string]interface{}{"status": "未运行", "ts": 0})
	})

	// 4.6 下载代理（保留 Cookie 跟随跳转）
	mux.HandleFunc("/dl/", downloadProxy)

	// 4.6 运行日志（App 内查看）
	mux.HandleFunc("/logs", logsHandler)

	// 4.7 删除下载文件（校验必须位于下载目录内，防误删/路径穿越）
	mux.HandleFunc("/delete-file", func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Query().Get("path")
		if p == "" {
			writeJSON(w, map[string]string{"status": "err", "msg": "path required"})
			return
		}
		abs := p
		if !filepath.IsAbs(abs) {
			abs = filepath.Join(downloadDir, p)
		}
		abs = filepath.Clean(abs)
		rel, err := filepath.Rel(downloadDir, abs)
		if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
			writeJSON(w, map[string]string{"status": "err", "msg": "path outside download dir"})
			return
		}
		if err := os.Remove(abs); err != nil && !os.IsNotExist(err) {
			writeJSON(w, map[string]string{"status": "err", "msg": err.Error()})
			return
		}
		appLog("删除本地文件: %s", abs)
		writeJSON(w, map[string]string{"status": "ok"})
	})

	// 4.8 前端调试日志（解析返回的 otherParam 等写入 app.log）
	mux.HandleFunc("/log", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			b, _ := io.ReadAll(io.LimitReader(r.Body, 8192))
			appLog("[前端] %s", strings.TrimSpace(string(b)))
		}
		writeJSON(w, map[string]string{"status": "ok"})
	})

	// 随机端口绑定：避免与同机其他 App（如旧版云盘解析下载器）冲突
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("监听本地端口失败: %w", err)
	}
	port = ln.Addr().(*net.TCPAddr).Port
	serverAddr = fmt.Sprintf("127.0.0.1:%d", port)
	// 端口写入 filesDir/server.port，供安卓壳读取后加载页面/上报日志
	if err := os.WriteFile(filepath.Join(appDir, "server.port"), []byte(strconv.Itoa(port)), 0o644); err != nil {
		appLogError("写入端口文件失败: %v", err)
	}
	srv := &http.Server{Addr: serverAddr, Handler: mux}
	go func() {
		if err := srv.Serve(ln); err != nil && err != http.ErrServerClosed {
			appLogError("主服务启动失败: %v", err)
		}
	}()
	appLog("界面地址 http://%s", serverAddr)
	return nil
}

// watchTaskStatus 后台轮询 Gopeed 任务状态，记录状态变化与失败原因
func watchTaskStatus() {
	apiURL := fmt.Sprintf("http://127.0.0.1:%d/api/v1/tasks", gopeedPort)
	client := &http.Client{Timeout: 5 * time.Second}
	last := map[string]string{}
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		resp, err := client.Get(apiURL)
		if err != nil {
			continue
		}
		var data struct {
			Code int `json:"code"`
			Data struct {
				Tasks []struct {
					ID     string `json:"id"`
					Name   string `json:"name"`
					Status string `json:"status"`
					Error  string `json:"error"`
					Req    struct {
						URL string `json:"url"`
					} `json:"req"`
				} `json:"tasks"`
			} `json:"data"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&data)
		resp.Body.Close()
		for _, t := range data.Data.Tasks {
			prev, seen := last[t.ID]
			if !seen {
				last[t.ID] = t.Status
				if t.Status == "error" || t.Status == "done" {
					appLog("任务 [%s] %s 结束: %s 错误=%q", t.ID, t.Name, t.Status, t.Error)
				}
				continue
			}
			if prev != t.Status {
				appLog("任务 [%s] %s 状态: %s -> %s 错误=%q", t.ID, t.Name, prev, t.Status, t.Error)
				last[t.ID] = t.Status
			}
			if t.Status == "error" {
				appLogError("任务 [%s] %s 下载失败: %s (url=%s)", t.ID, t.Name, t.Error, t.Req.URL)
			}
		}
	}
}

// initGopeedConfig 通过 REST 接口初始化下载目录与并发数（保留已有协议配置）
func initGopeedConfig(dir string, maxRunning int) error {
	apiURL := fmt.Sprintf("http://127.0.0.1:%d/api/v1/config", gopeedPort)
	client := &http.Client{Timeout: 5 * time.Second}

	resp, err := client.Get(apiURL)
	if err != nil {
		return err
	}
	var cur struct {
		Code int                        `json:"code"`
		Data base.DownloaderStoreConfig `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&cur); err != nil {
		resp.Body.Close()
		return err
	}
	resp.Body.Close()

	cfg := cur.Data
	cfg.DownloadDir = dir
	cfg.MaxRunning = maxRunning
	if cfg.ProtocolConfig == nil {
		cfg.ProtocolConfig = map[string]any{}
	}
	if cfg.Proxy == nil {
		cfg.Proxy = &base.DownloaderProxyConfig{}
	}
	if cfg.Webhook == nil {
		cfg.Webhook = &base.WebhookConfig{}
	}
	if cfg.Script == nil {
		cfg.Script = &base.ScriptConfig{}
	}
	if cfg.AutoTorrent == nil {
		cfg.AutoTorrent = &base.AutoTorrentConfig{}
	}
	if cfg.Archive == nil {
		cfg.Archive = &base.ArchiveConfig{}
	}

	body, err := json.Marshal(cfg)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPut, apiURL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	putResp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer putResp.Body.Close()
	if putResp.StatusCode != http.StatusOK {
		return fmt.Errorf("config update failed: %d", putResp.StatusCode)
	}
	return nil
}

// defaultUA 夸克/UC 等网盘下载所需的浏览器 UA
const defaultUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// buildDownloadHeaders 根据目标地址里的网盘标识生成防盗链请求头
func buildDownloadHeaders(target, uaOverride string) map[string]string {
	h := map[string]string{}
	pan := ""
	// 目录分享直链：/v2/directoryShare/redirectUrl/{shareCode}/{diskType}/{fileId}
	if m := regexp.MustCompile(`/directoryShare/redirectUrl/([a-z0-9]+)/([a-z0-9]+)`).FindStringSubmatch(target); len(m) > 2 {
		pan = m[2]
	} else if m := regexp.MustCompile(`/(?:redirectUrl|parser|directLink|getFileList)/([a-z0-9]+)`).FindStringSubmatch(target); len(m) > 1 {
		pan = m[1]
	}
	// 123 网盘直链域名识别（downloadUrl 为 cjjd19.com 等，不走 /parser 标识）
	if pan == "" && (strings.Contains(target, "cjjd19.com") || strings.Contains(target, "123pan")) {
		pan = "ye"
	}
	switch pan {
	case "qk":
		h["Referer"] = "https://pan.quark.cn/"
		h["User-Agent"] = defaultUA
	case "uc":
		h["Referer"] = "https://pan.uc.cn/"
		h["User-Agent"] = defaultUA
	case "cow":
		h["Referer"] = "https://cowtransfer.com/"
	case "pcx":
		h["Referer"] = "https://pan-yz.chaoxing.com"
	case "ye":
		h["Referer"] = "https://www.123pan.com/"
	}
	// 所有网盘默认带浏览器 UA（123 等 CDN 会拒绝 UA 为空的脚本请求）
	h["User-Agent"] = defaultUA
	// 夸克/UC 需要登录 Cookie（设置页填写），跨域跳转时也不会丢失
	if quarkCookie != "" && (pan == "qk" || pan == "uc") {
		h["Cookie"] = quarkCookie
	}
	if uaOverride != "" {
		h["User-Agent"] = uaOverride
	}
	return h
}

// downloadProxy 下载代理：手动跟随 302 跳转并全程保留 Cookie/Referer/UA，
// 解决 Gopeed 跨域重定向默认丢弃 Cookie 导致夸克等网盘 403 的问题
func downloadProxy(w http.ResponseWriter, r *http.Request) {
	target := r.URL.Query().Get("url")
	if target == "" {
		http.Error(w, "missing url", http.StatusBadRequest)
		return
	}
	if !strings.HasPrefix(target, "http://") && !strings.HasPrefix(target, "https://") {
		http.Error(w, "bad url", http.StatusBadRequest)
		return
	}
	headers := buildDownloadHeaders(target, r.URL.Query().Get("ua"))
	// 前端可通过 ?cookie= 参数直接传 Cookie（优先级高于设置页配置），
	// 满足"请求 location 时带 cookie"的夸克等网盘下载要求
	if ck := r.URL.Query().Get("cookie"); ck != "" {
		headers["Cookie"] = ck
	}
	// 前端可通过 ?hdr= JSON 参数透传完整下载请求头（/v2/getFileDownInfo 返回的
	// downloadHeaders，含 Cookie/Referer/UA 等权威值），覆盖默认与 cookie 参数
	if hdrRaw := r.URL.Query().Get("hdr"); hdrRaw != "" {
		var hdr map[string]string
		if json.Unmarshal([]byte(hdrRaw), &hdr) == nil {
			// Referer/User-Agent/Cookie 只保留一个权威值：解析服务返回的小写键优先
			// （downloadHeaders），防 Go map 遍历随机导致 Referer 被错误的默认值覆盖
			// （如 UC 回调校验 Referer=fast.uc.cn，发成 pan.uc.cn 会被 checkplay 拒绝 403）
			if v, ok := hdr["referer"]; ok && v != "" {
				headers["Referer"] = v
			} else if v, ok := hdr["Referer"]; ok && v != "" {
				headers["Referer"] = v
			}
			if v, ok := hdr["user-agent"]; ok && v != "" {
				headers["User-Agent"] = v
			} else if v, ok := hdr["User-Agent"]; ok && v != "" {
				headers["User-Agent"] = v
			}
			if v, ok := hdr["cookie"]; ok && v != "" {
				headers["Cookie"] = v
			} else if v, ok := hdr["Cookie"]; ok && v != "" {
				headers["Cookie"] = v
			}
			for k, v := range hdr {
				if v == "" || strings.EqualFold(k, "referer") ||
					strings.EqualFold(k, "user-agent") || strings.EqualFold(k, "cookie") {
					continue
				}
				headers[k] = v
			}
		}
	}
	panTag := "未知"
	if m := regexp.MustCompile(`/directoryShare/redirectUrl/([a-z0-9]+)/([a-z0-9]+)`).FindStringSubmatch(target); len(m) > 2 {
		panTag = m[2]
	} else if m := regexp.MustCompile(`/(?:redirectUrl|parser|directLink|getFileList)/([a-z0-9]+)`).FindStringSubmatch(target); len(m) > 1 {
		panTag = m[1]
	} else if strings.Contains(target, "pds.quark.cn") {
		panTag = "qk"
	} else if strings.Contains(target, "pds.uc.cn") {
		panTag = "uc"
	}
	hasCookie := false
	cookieVal := ""
	for k, v := range headers {
		if strings.EqualFold(k, "Cookie") && v != "" {
			hasCookie = true
			cookieVal = v
			break
		}
	}
	if hasCookie {
		c := cookieVal
		if len(c) > 24 {
			c = c[:24] + "..."
		}
		appLog("下载代理: 网盘=%s 携带Cookie=%s", panTag, c)
	} else {
		appLog("下载代理: 网盘=%s 未携带Cookie", panTag)
	}

	// 上游 SOCKS5 代理（123 等网盘由 dnode 节点中转下载；?proxy=host:port 可选带 proxyUser/proxyPass）
	proxyAddr := r.URL.Query().Get("proxy")
	if proxyAddr != "" {
		proxyUser := r.URL.Query().Get("proxyUser")
		proxyPass := r.URL.Query().Get("proxyPass")
		if _, terr := socks5Transport(proxyAddr, proxyUser, proxyPass); terr != nil {
			http.Error(w, "bad proxy: "+terr.Error(), http.StatusBadRequest)
			return
		}
		appLog("下载代理: 经 SOCKS5 节点 %s 中转(连接池复用)", proxyAddr)
	}

	// 手动跟随跳转：收集每一跳下发的 Set-Cookie 并在后续请求携带
	client := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	proxyKey := ""
	if proxyAddr != "" {
		proxyKey = proxyAddr + "|" + r.URL.Query().Get("proxyUser") + "|" + r.URL.Query().Get("proxyPass")
		// 复用带 SOCKS5 上游的 Transport：多分片共享隧道连接池，避免每连接重新握手
		if tr, ok := socks5TransportCache.Load(proxyKey); ok {
			client.Transport = tr.(*http.Transport)
		}
	}

	rangeHdr := r.Header.Get("Range")
	var cookies []*http.Cookie
	cur := target
	base, _ := url.Parse(target)

	for hop := 0; hop < 16; hop++ {
		// 每跳按目标域名决定链路：189.qaiu.top 等解析服务必须直连（节点无法回源，
		// 否则 SOCKS5 端 host unreachable 导致拿不到 CDN 直链），CDN 直链走节点中转
		hopProxyKey := ""
		client.Transport = nil
		if proxyAddr != "" {
			if isQaiuHost(cur) {
				appLog("下载代理: 解析服务 %s 直连（不走节点）", curHost(cur))
			} else {
				hopProxyKey = proxyKey
				if tr, ok := socks5TransportCache.Load(proxyKey); ok {
					client.Transport = tr.(*http.Transport)
				}
			}
		}

		req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, cur, nil)
		if err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		for _, c := range cookies {
			req.AddCookie(c)
		}
		if rangeHdr != "" {
			req.Header.Set("Range", rangeHdr)
		}

		// 网络错误（换网/断流）时清缓存并快速重试，1 秒内恢复，Gopeed 无感
		resp, err := doWithRetry(client, req, hopProxyKey, r.Context())
		if err != nil {
			// 已走到 CDN 直链但请求失败（典型：节点到 123 CDN 部分 IP host unreachable）：
			// 重试同一地址无意义（DNS 由节点侧解析，短时间同 IP），重置回解析服务重新走 302，
			// 服务端会分配新的 CDN 主机（cjjd19.com 多机房轮询），大概率可绕开不可达 IP
			if cur != target && !isQaiuHost(cur) {
				appLogError("下载代理: CDN %s 请求失败，重新走 302 换地址: %v", curHost(cur), err)
				cur = target
				continue
			}
			appLogError("下载代理请求失败: %v", err)
			http.Error(w, "下载请求失败: "+err.Error(), http.StatusBadGateway)
			return
		}

		// 收集本跳 Set-Cookie
		for _, c := range resp.Cookies() {
			cookies = append(cookies, c)
			v := c.Value
			if len(v) > 20 {
				v = v[:20] + "..."
			}
			appLog("下载代理: 第%d跳 Set-Cookie %s=%s", hop+1, c.Name, v)
		}

		loc := resp.Header.Get("Location")
		if resp.StatusCode >= 300 && resp.StatusCode < 400 && loc != "" {
			resp.Body.Close()
			ref, err := url.Parse(loc)
			if err != nil {
				http.Error(w, "bad redirect", http.StatusBadGateway)
				return
			}
			next := base.ResolveReference(ref)
			ns := next.String()
			if !strings.HasPrefix(ns, "http://") && !strings.HasPrefix(ns, "https://") {
				ns = loc
			}
			cur = ns
			appLog("下载代理: 跳转 %d → %s", hop+1, cur)
			continue
		}

		// 最终响应：流式转发给 Gopeed
		// 非 2xx 时打印实际请求头摘要 + OSS 错误体（定位 403 根因）
		if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
			ua := req.Header.Get("User-Agent")
			ref := req.Header.Get("Referer")
			ck := req.Header.Get("Cookie")
			if len(ck) > 30 {
				ck = ck[:30] + "..."
			}
			rg := req.Header.Get("Range")
			appLogError("下载代理: 状态%d UA=%q Referer=%q Cookie=%q Range=%q", resp.StatusCode, ua, ref, ck, rg)
			errBody, _ := io.ReadAll(io.LimitReader(resp.Body, 800))
			resp.Body.Close()
			resp.Body = io.NopCloser(bytes.NewReader(errBody))
			appLogError("下载代理: 错误响应体: %s", string(errBody))
		}
		defer resp.Body.Close()
		appLog("下载代理: 最终状态 %d  地址 %s", resp.StatusCode, resp.Request.URL.String())
		w.Header().Set("Content-Type", resp.Header.Get("Content-Type"))
		if cl := resp.Header.Get("Content-Length"); cl != "" {
			w.Header().Set("Content-Length", cl)
		}
		if cr := resp.Header.Get("Content-Range"); cr != "" {
			w.Header().Set("Content-Range", cr)
		}
		if cd := resp.Header.Get("Content-Disposition"); cd != "" {
			w.Header().Set("Content-Disposition", cd)
		}
		// 强制声明支持 Range：部分 CDN（如123 cjjd19.com）不返回 Accept-Ranges 头，
		// 会导致 Gopeed 判定不可分段而退化为单连接下载（速度受限）。源站实际支持 206，强制声明可让分片并发生效。
		w.Header().Set("Accept-Ranges", "bytes")
		w.WriteHeader(resp.StatusCode)
		startCopy := time.Now()
		n, _ := io.Copy(w, resp.Body)
		elapsed := time.Since(startCopy)
		rate := float64(0)
		if elapsed > 0 {
			rate = float64(n) / elapsed.Seconds() / 1024 / 1024
		}
		appLog("下载代理: 回传完成 %d 字节 / %s / %.1f MB/s", n, elapsed.Round(time.Millisecond), rate)
		return
	}
	http.Error(w, "重定向次数过多", http.StatusBadGateway)
}

// isQaiuHost 判断目标是否解析服务域名（189.qaiu.top）：必须直连，
// 代理节点（dnode SOCKS5）无法回源该服务，否则拿不到 CDN 直链
func isQaiuHost(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	h := u.Hostname()
	return h == "189.qaiu.top" || strings.HasSuffix(h, ".qaiu.top")
}

// curHost 提取目标主机名（仅用于日志）
func curHost(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	return u.Hostname()
}

// doWithRetry 下载请求带快速重试：换网/断流导致的瞬时错误，清掉死连接池后 500ms 间隔重试，
// 窗口约 5 秒，覆盖内置节点重连时间（Gopeed 无自动重试，段请求必须由本层扛住）。
func doWithRetry(client *http.Client, req *http.Request, proxyKey string, ctx context.Context) (*http.Response, error) {
	const maxAttempt = 10
	var lastErr error
	for attempt := 0; attempt < maxAttempt; attempt++ {
		// 每次重试重建请求（旧请求可能已被底层标记不可复用）
		nr := req.Clone(ctx)
		nr.Body = nil
		resp, err := client.Do(nr)
		if err == nil {
			return resp, nil
		}
		lastErr = err
		if proxyKey != "" {
			socks5TransportCache.Delete(proxyKey) // 换网后旧 TCP 全断，清池重建
		}
		appLogError("下载代理请求失败(第%d次): %v", attempt+1, err)
		if attempt < maxAttempt-1 {
			select {
			case <-time.After(500 * time.Millisecond):
			case <-ctx.Done():
				return nil, lastErr
			}
		}
	}
	return nil, lastErr
}

// 按 (proxy,user,pass) 缓存带 SOCKS5 上游的 Transport，分片请求复用隧道连接池
var socks5TransportCache sync.Map

func socks5Transport(proxyAddr, proxyUser, proxyPass string) (*http.Transport, error) {
	key := proxyAddr + "|" + proxyUser + "|" + proxyPass
	if v, ok := socks5TransportCache.Load(key); ok {
		return v.(*http.Transport), nil
	}
	var auth *proxy.Auth
	if proxyUser != "" {
		auth = &proxy.Auth{User: proxyUser, Password: proxyPass}
	}
	dialer, err := proxy.SOCKS5("tcp", proxyAddr, auth, proxy.Direct)
	if err != nil {
		return nil, err
	}
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialer.Dial(network, addr)
		},
		MaxIdleConns:        128,
		MaxIdleConnsPerHost: 64,
		IdleConnTimeout:     90 * time.Second,
	}
	socks5TransportCache.Store(key, tr)
	return tr, nil
}

// parseProxy 将 /parse/* 转发到 189.qaiu.top（去掉 /parse 前缀）
var parseProxyClient = &http.Client{Timeout: 20 * time.Second}

func parseProxy(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/parse")
	target := ParserBase + path
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target, nil)
	if err != nil {
		http.Error(w, "bad proxy request", http.StatusBadRequest)
		return
	}
	// 透传必要的请求头（解析站接口支持 X-API-Key 鉴权，网页版即用请求头方式）
	for k := range r.Header {
		if strings.EqualFold(k, "Host") || strings.EqualFold(k, "Content-Length") {
			continue
		}
		req.Header.Set(k, r.Header.Get(k))
	}
	resp, err := parseProxyClient.Do(req)
	if err != nil {
		http.Error(w, "解析服务不可达: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for k, vv := range resp.Header {
		if strings.EqualFold(k, "Access-Control-Allow-Origin") ||
			strings.EqualFold(k, "Access-Control-Allow-Credentials") {
			continue
		}
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func appConfigHandler(w http.ResponseWriter, r *http.Request) {
	cfgPath := appConfigPath
	if cfgPath == "" {
		cfgDir, _ := os.UserConfigDir()
		cfgPath = filepath.Join(cfgDir, AppName, "app.json")
	}
	switch r.Method {
	case http.MethodGet:
		cfg := appConfig{}
		if b, err := os.ReadFile(cfgPath); err == nil {
			_ = json.Unmarshal(b, &cfg)
		}
		writeJSON(w, cfg)
	case http.MethodPost:
		var cfg appConfig
		if err := json.NewDecoder(r.Body).Decode(&cfg); err != nil {
			http.Error(w, "invalid json", http.StatusBadRequest)
			return
		}
		if cfg.DownloadDir == "" {
			cfg.DownloadDir = defaultDownloadDir("")
		}
		quarkCookie = cfg.QuarkCookie
		_ = os.MkdirAll(filepath.Dir(cfgPath), 0o755)
		b, _ := json.Marshal(cfg)
		_ = os.WriteFile(cfgPath, b, 0o644)
		writeJSON(w, map[string]string{"status": "ok", "downloadDir": cfg.DownloadDir})
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}

// publicAppRoot 返回 Android 公共存储下的专属文件夹（/storage/emulated/0/NetDiskParser2），
// 用户可在文件管理器直接看到下载文件与日志
func publicAppRoot() (string, error) {
	ext := os.Getenv("EXTERNAL_STORAGE")
	if ext == "" {
		ext = "/storage/emulated/0"
	}
	root := filepath.Join(ext, AppName)
	if err := os.MkdirAll(root, 0o755); err != nil {
		return "", err
	}
	return root, nil
}

func defaultDownloadDir(appDir string) string {
	// Android：专属文件夹内的 Download（/storage/emulated/0/NetDiskParser2/Download），用户可在设置页修改
	if runtime.GOOS == "android" {
		if root, err := publicAppRoot(); err == nil {
			return filepath.Join(root, "Download")
		}
		// 公共目录不可用时退回 App 私有目录
		return filepath.Join(appDir, "Download")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	return filepath.Join(home, "Downloads")
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}
