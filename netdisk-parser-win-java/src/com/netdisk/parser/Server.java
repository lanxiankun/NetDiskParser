package com.netdisk.parser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.Duration;

/** 内嵌 HTTP 服务（127.0.0.1:18090）：接口与安卓版完全对齐，前端 index.html 直接复用 */
public class Server {
    /** 内存会话日志（含节点日志），/logs 返回完整内容 */
    private static final java.util.List<String> LOG = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static void appLog(String msg) {
        String line = "[" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + "] " + msg;
        System.out.println(line);
        LOG.add(line);
        if (LOG.size() > 2000) LOG.remove(0);
    }

    public static final int PORT = 18090;
    private static final String PARSER_BASE = "https://189.qaiu.top";
    private static HttpServer server;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", Server::dispatch);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("[Server] 服务启动成功 http://127.0.0.1:" + PORT);
    }

    public static void stop() { if (server != null) server.stop(0); }

    private static void dispatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/") || path.isEmpty()) { sendFile(ex, "index.html", "text/html; charset=utf-8"); }
            else if (path.startsWith("/parse")) handleParse(ex);
            else if (path.equals("/logs")) handleLogs(ex);
            else if (path.equals("/log")) handleLogs(ex);
            else if (path.equals("/app/node-status")) handleNodeStatus(ex);
            else if (path.equals("/app/node")) handleNode(ex);
            else if (path.equals("/app/pylog")) handlePyLog(ex);
            else if (path.equals("/app/config")) handleAppConfig(ex);
            else if (path.startsWith("/api/v1/tasks")) handleTasks(ex);
            else if (path.equals("/api/v1/config")) handleEngineConfig(ex);
            else if (path.equals("/api/v1/info")) handleInfo(ex);
            else if (path.equals("/delete-file")) handleDeleteFile(ex);
            else if (path.startsWith("/dl/") || path.startsWith("/dl?")) handleDl(ex);
            else send404(ex);
        } catch (Throwable t) {
            try { respond(ex, 500, "{\"code\":1,\"msg\":\"服务异常: " + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}", "application/json"); }
            catch (Exception ignored) {}
        }
    }

    // ── /parse/* ：反向代理到 189.qaiu.top（免跨域）──
    private static void handleParse(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath().substring("/parse".length());
        String target = PARSER_BASE + path;
        String q = ex.getRequestURI().getRawQuery();
        if (q != null && !q.isEmpty()) target += "?" + q;
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(target)).GET();
        String ua = ex.getRequestHeaders().getFirst("User-Agent");
        if (ua != null) b.header("User-Agent", ua);
        HttpResponse<InputStream> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        ex.getResponseHeaders().set("Content-Type", r.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8"));
        ex.sendResponseHeaders(r.statusCode(), 0);
        try (InputStream in = r.body(); OutputStream os = ex.getResponseBody()) { in.transferTo(os); }
    }

    // ── Gopeed 兼容任务 API ──
    @SuppressWarnings("unchecked")
    private static void handleTasks(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        String rest = path.substring("/api/v1/tasks".length());
        String method = ex.getRequestMethod();
        if (rest.isEmpty() && "POST".equalsIgnoreCase(method)) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> o = Json.parseObj(body);
            Map<String, Object> req = (Map<String, Object>) o.getOrDefault("req", new LinkedHashMap<>());
            Map<String, Object> opts = (Map<String, Object>) o.getOrDefault("opts", new LinkedHashMap<>());
            try {
                String id = Downloader.create(req, opts);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", id);
                respondJson(ex, 200, ok(data));
            } catch (Exception e) {
                respondJson(ex, 200, err(e.getMessage()));
            }
            return;
        }
        if (rest.isEmpty() && "GET".equalsIgnoreCase(method)) {
            List<Object> tasks = new ArrayList<>();
            for (Downloader.Task t : Downloader.list()) tasks.add(taskJson(t));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tasks", tasks);
            respondJson(ex, 200, ok(data));
            return;
        }
        // /api/v1/tasks/{id}/pause|continue  /api/v1/tasks/{id}
        if (!rest.isEmpty()) {
            String[] seg = rest.split("/");
            String id = seg[1];
            if (seg.length >= 3 && "pause".equals(seg[2])) { Downloader.pause(id); respondJson(ex, 200, ok(null)); return; }
            if (seg.length >= 3 && "continue".equals(seg[2])) { Downloader.resume(id); respondJson(ex, 200, ok(null)); return; }
            if (seg.length == 2 && "DELETE".equalsIgnoreCase(method)) {
                boolean force = Boolean.parseBoolean(query(ex, "force"));
                Downloader.delete(id, force);
                respondJson(ex, 200, ok(null));
                return;
            }
        }
        send404(ex);
    }

    private static Map<String, Object> taskJson(Downloader.Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("name", t.name);
        m.put("status", t.status);
        m.put("createdAt", String.valueOf(t.createdAt));
        m.put("error", t.error);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("downloaded", t.downloaded);
        progress.put("speed", t.speed);
        m.put("progress", progress);
        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("size", t.total);
        meta.put("res", res);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("url", t.url);
        meta.put("req", req);
        m.put("meta", meta);
        return m;
    }

    // ── /api/v1/config：下载引擎配置（并发数/下载目录）──
    private static void handleEngineConfig(HttpExchange ex) throws Exception {
        if ("PUT".equalsIgnoreCase(ex.getRequestMethod()) || "POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> o = Json.parseObj(body);
            Map<String, Object> cfg = Config.get();
            if (o.containsKey("downloadDir")) cfg.put("dlDir", String.valueOf(o.get("downloadDir")));
            if (o.get("extra") instanceof Map) {
                Map<String, Object> extra = (Map<String, Object>) o.get("extra");
                if (extra.containsKey("connections")) cfg.put("threads", ((Number) extra.get("connections")).intValue());
            }
            Config.save(cfg);
            respondJson(ex, 200, ok(null));
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("downloadDir", Config.downloadDir());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("connections", Config.get().getOrDefault("threads", 4));
        data.put("extra", extra);
        respondJson(ex, 200, ok(data));
    }

    private static void handleInfo(HttpExchange ex) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", "1.0.0");
        data.put("name", "NetDiskParser");
        data.put("downloader", "java");
        respondJson(ex, 200, ok(data));
    }

    // ── /dl/ ：下载代理（direct 模式/兜底），Cookie 跟随 + Range ──
    private static void handleDl(HttpExchange ex) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) q.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        String url = q.getOrDefault("url", "");
        if (url.isEmpty()) { respond(ex, 400, "url 为空", "text/plain"); return; }
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", q.getOrDefault("ua", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0"));
        if (q.containsKey("cookie")) b.header("Cookie", q.get("cookie"));
        if (q.containsKey("hdr")) {
            try {
                Map<String, Object> hm = Json.parseObj(q.get("hdr"));
                for (Map.Entry<String, Object> e : hm.entrySet()) b.header(e.getKey(), String.valueOf(e.getValue()));
            } catch (Exception ignored) {}
        }
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null) b.header("Range", range);
        HttpResponse<InputStream> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        for (String h : new String[]{"Content-Type", "Content-Length", "Content-Range", "Content-Disposition", "Accept-Ranges"}) {
            r.headers().firstValue(h).ifPresent(v -> ex.getResponseHeaders().set(h, v));
        }
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.sendResponseHeaders(r.statusCode(), 0);
        try (InputStream in = r.body(); OutputStream os = ex.getResponseBody()) { in.transferTo(os); }
    }

    // ── 文件删除（校验路径在下载目录内）──
    private static void handleDeleteFile(HttpExchange ex) throws Exception {
        String p = query(ex, "path");
        if (p == null || p.isEmpty()) { respondJson(ex, 200, err("path 为空")); return; }
        Path f = Paths.get(p).toAbsolutePath().normalize();
        Path dir = Paths.get(Config.downloadDir()).toAbsolutePath().normalize();
        if (!f.startsWith(dir)) { respondJson(ex, 200, err("非法路径")); return; }
        boolean ok = Files.deleteIfExists(f);
        respondJson(ex, 200, ok(Map.of("deleted", ok)));
    }

    // ── 日志 / 节点状态 / pylog（与安卓版一致）──
    private static void handleLogs(HttpExchange ex) throws Exception {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) { for (String l : LOG) sb.append(l).append("\n"); }
        respond(ex, 200, sb.toString(), "text/plain; charset=utf-8");
    }

    private static void handleNode(HttpExchange ex) throws Exception {
        if (!isLoopback(ex)) { respond(ex, 403, "forbidden", "text/plain"); return; }
        String action = query(ex, "action");
        if ("start".equals(action)) { DnodeNode.start(); appLog("节点: 手动启动"); respondJson(ex, 200, ok("started")); }
        else if ("stop".equals(action)) { DnodeNode.stop(); appLog("节点: 手动停止"); respondJson(ex, 200, ok("stopped")); }
        else respondJson(ex, 200, err("未知 action"));
    }

    private static void handleNodeStatus(HttpExchange ex) throws Exception {
        if (!isLoopback(ex)) { respond(ex, 403, "forbidden", "text/plain"); return; }
        java.io.File f = new java.io.File(System.getProperty("user.home"), ".NetDiskParser/dnode_status.json");
        String body = "{\"status\":\"未运行\",\"ts\":0}";
        if (f.exists()) {
            try { body = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) {}
        }
        respond(ex, 200, body, "application/json");
    }

    private static void handlePyLog(HttpExchange ex) throws Exception {
        if (!isLoopback(ex)) { respond(ex, 403, "forbidden", "text/plain"); return; }
        String msg = query(ex, "msg");
        if (msg != null && !msg.isEmpty()) { appLog("节点: " + msg); }
        respond(ex, 200, "", "text/plain");
    }

    private static void handleAppConfig(HttpExchange ex) throws Exception {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Config.save(Json.parseObj(body));
            respondJson(ex, 200, ok(null));
            return;
        }
        respondJson(ex, 200, ok(Config.get()));
    }

    // ── 工具 ──
    private static boolean isLoopback(HttpExchange ex) { return ex.getRemoteAddress().getAddress().isLoopbackAddress(); }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                try { return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); } catch (Exception e) { return null; }
            }
        }
        return null;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 0);
        m.put("data", data);
        return m;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 1);
        m.put("msg", msg == null ? "未知错误" : msg);
        return m;
    }

    private static void respondJson(HttpExchange ex, int code, Object o) throws IOException {
        respond(ex, code, Json.stringify(o), "application/json; charset=utf-8");
    }

    private static void sendFile(HttpExchange ex, String name, String mime) throws IOException {
        for (Path dir : new Path[]{Paths.get("ui"), Paths.get("netdisk-parser-win-java/ui")}) {
            Path f = dir.resolve(name);
            if (Files.exists(f)) {
                respond(ex, 200, new String(Files.readAllBytes(f), StandardCharsets.UTF_8), mime);
                return;
            }
        }
        send404(ex);
    }

    private static void send404(HttpExchange ex) throws IOException { respond(ex, 404, "not found", "text/plain"); }

    private static void respond(HttpExchange ex, int code, String body, String mime) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
