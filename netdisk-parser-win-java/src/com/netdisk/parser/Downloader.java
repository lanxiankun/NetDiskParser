package com.netdisk.parser;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 多线程下载引擎（Gopeed 兼容 API）：
 * 任务 URL 支持 /dl/?url=... 与直链；多分片 Range 并发、断点续传、暂停/继续/删除。
 * 字段格式对齐 Gopeed：{id,name,status,progress:{downloaded,speed},meta:{res:{size},req:{url}},createdAt}
 */
public class Downloader {
    public static final ConcurrentHashMap<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService STATS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dl-stats"); t.setDaemon(true); return t;
    });

    static {
        // 每秒采样速度
        STATS.scheduleAtFixedRate(() -> {
            for (Task t : TASKS.values()) {
                long now = System.currentTimeMillis();
                long dt = now - t.lastSample;
                if (dt < 800) continue;
                long bytes = t.downloaded - t.lastBytes;
                t.speed = bytes * 1000L / dt;
                t.lastSample = now;
                t.lastBytes = t.downloaded;
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public static class Task {
        public String id, name, url, status = "wait";
        public volatile long downloaded, total = -1, speed;
        public long createdAt = System.currentTimeMillis();
        volatile long lastSample, lastBytes;
        public Path file;
        public volatile boolean paused, cancelled;
        public final List<Chunk> chunks = new CopyOnWriteArrayList<>();
        public int connections = 4;
        public String error;
        public String rawUrl;          // 真实下载 URL（解析 /dl/?url= 后）
        public String ua, cookie, referer; // 请求参数
        public Socks5Http proxy;       // SOCKS5 代理（任务 URL 带 proxy 参数时启用）
        public String downloadDir;     // 下载目录
        public ExecutorService pool;
    }

    public static class Chunk {
        public final long start, end;
        public volatile long done;
        public volatile boolean finished;
        Chunk(long s, long e) { start = s; end = e; }
    }

    /** 创建任务（前端 body: {req:{url,...}, opts:{name,path,extra:{connections}}}），返回任务 ID */
    public static String create(Map<String, Object> reqOpts, Map<String, Object> opts) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) reqOpts;
        String url = String.valueOf(req.getOrDefault("url", ""));
        if (url.isEmpty()) throw new IllegalArgumentException("url 为空");
        String name = opts != null ? String.valueOf(opts.getOrDefault("name", "")) : "";
        int conns = 4;
        if (opts != null && opts.get("extra") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = (Map<String, Object>) opts.get("extra");
            Object c = extra.get("connections");
            if (c instanceof Number) conns = Math.max(1, Math.min(64, ((Number) c).intValue()));
        }
        Task t = new Task();
        t.id = genId();
        t.name = name;
        t.url = url;
        t.connections = conns;
        t.createdAt = System.currentTimeMillis();
        t.downloadDir = Config.downloadDir();
        Files.createDirectories(Paths.get(t.downloadDir));
        // 解析请求参数（/dl/?url=X&ua=&cookie=&hdr=）
        t.rawUrl = url;
        t.ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        if (url.startsWith("/dl/") || url.startsWith("dl/") || url.contains("/dl/?url=")) {
            Map<String, String> q = parseQuery(url.contains("?") ? url.substring(url.indexOf('?') + 1) : "");
            t.rawUrl = q.getOrDefault("url", url);
            if (q.containsKey("ua")) t.ua = q.get("ua");
            if (q.containsKey("cookie")) t.cookie = q.get("cookie");
            if (q.containsKey("hdr")) {
                String hdr = q.get("hdr");
                try {
                    Map<String, Object> hm = Json.parseObj(hdr);
                    if (hm.containsKey("User-Agent")) t.ua = String.valueOf(hm.get("User-Agent"));
                    if (hm.containsKey("Referer")) t.referer = String.valueOf(hm.get("Referer"));
                } catch (Exception ignored) {}
            }
            // 代理参数：proxy=host:port&proxyUser=&proxyPass=
            if (q.containsKey("proxy") && !q.get("proxy").isEmpty()) {
                t.proxy = Socks5Http.parse(q.get("proxy"), q.getOrDefault("proxyUser", ""), q.getOrDefault("proxyPass", ""));
            }
        }
        if (t.name == null || t.name.isEmpty()) t.name = fileNameFromUrl(t.rawUrl);
        t.file = Paths.get(t.downloadDir, sanitize(t.name));
        // 同目录同名任务自动加 (n)
        t.file = uniqueFile(t.file);
        TASKS.put(t.id, t);
        start(t);
        return t.id;
    }

    private static void start(Task t) {
        t.status = "running";
        t.paused = false;
        t.cancelled = false;
        t.error = null;
        t.pool = Executors.newFixedThreadPool(Math.max(1, Math.min(t.connections, 32)));
        t.pool.submit(() -> {
            try {
                probe(t);           // 探测大小/支持 Range
                scheduleChunks(t);  // 分片并发下载
                t.status = "done";
                t.speed = 0;
                cleanPartFiles(t);
                t.pool.shutdown();
            } catch (Throwable e) {
                t.error = e.getMessage();
                if (!t.cancelled && !t.paused) t.status = "error";
                t.speed = 0;
                t.pool.shutdownNow();
            }
        });
    }

    /** 探测：GET 带 Range 拿大小与 206 支持（有代理走 SOCKS5） */
    private static void probe(Task t) throws Exception {
        int status;
        String cr;
        long cl;
        if (t.proxy != null) {
            try (Socks5Http.Response r = t.proxy.get(t.rawUrl, reqHeaders(t), "bytes=0-0")) {
                status = r.status;
                cr = r.header("Content-Range");
                cl = parseLong(r.header("Content-Length"), -1);
            }
        } else {
            HttpRequest.Builder b = reqBuilder(t, "bytes=0-0");
            HttpResponse<InputStream> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            r.body().close();
            status = r.statusCode();
            cr = firstHeader(r, "Content-Range");
            cl = contentLength(r);
        }
        long size = -1;
        if (cr != null && cr.contains("/")) {
            String part = cr.substring(cr.lastIndexOf('/') + 1).trim();
            if (!"*".equals(part)) size = Long.parseLong(part);
        }
        if (size < 0) size = cl;
        t.total = size;
        // 若源不支持 Range（200 而非 206）或大小未知：单连接整块下载
        if (status != 206 || size <= 0) {
            t.chunks.add(new Chunk(0, -1));
        } else {
            int n = t.connections;
            long base = size / n;
            for (int i = 0; i < n; i++) {
                long s = i * base;
                long e = (i == n - 1) ? size - 1 : (i + 1) * base - 1;
                if (s > e) break;
                t.chunks.add(new Chunk(s, e));
            }
        }
    }

    private static void scheduleChunks(Task t) {
        CountDownLatch latch = new CountDownLatch(t.chunks.size());
        for (Chunk c : t.chunks) {
            t.pool.submit(() -> {
                try { downloadChunk(t, c); } catch (Exception e) {
                    if (!t.cancelled && !t.paused) { t.error = "分片失败: " + e.getMessage(); t.status = "error"; }
                } finally { latch.countDown(); }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
    }

    private static void downloadChunk(Task t, Chunk c) throws Exception {
        // 断点：已有部分从 c.done 续传
        String range = c.end < 0 ? null : "bytes=" + (c.start + c.done) + "-" + c.end;
        long off = c.start + c.done;
        InputStream in;
        if (t.proxy != null) {
            Socks5Http.Response r = t.proxy.get(t.rawUrl, reqHeaders(t), range);
            if (r.status != 200 && r.status != 206) {
                String msg = r.header("x-error");
                r.close();
                throw new IOException("HTTP " + r.status + (msg != null ? " " + msg : ""));
            }
            in = r.body;
        } else {
            HttpRequest.Builder b = reqBuilder(t, range);
            HttpResponse<InputStream> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            in = r.body();
        }
        try (RandomAccessFile raf = new RandomAccessFile(t.file.toFile(), "rw")) {
            try (InputStream body = in) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = body.read(buf)) > 0) {
                    if (t.cancelled) throw new IOException("cancelled");
                    while (t.paused && !t.cancelled) Thread.sleep(100);
                    raf.seek(off);
                    raf.write(buf, 0, n);
                    off += n;
                    c.done += n;
                    t.downloaded += n;
                }
            }
        }
        c.finished = true;
    }

    private static Map<String, String> reqHeaders(Task t) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", t.ua != null ? t.ua : "Mozilla/5.0");
        h.put("Accept", "*/*");
        if (t.referer != null && !t.referer.isEmpty()) h.put("Referer", t.referer);
        if (t.cookie != null && !t.cookie.isEmpty()) h.put("Cookie", t.cookie);
        return h;
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    // ── 控制 ──
    public static void pause(String id) {
        Task t = TASKS.get(id);
        if (t == null || !"running".equals(t.status)) return;
        t.paused = true;
        t.status = "pause";
        t.speed = 0;
        if (t.pool != null) t.pool.shutdownNow();
    }

    public static void resume(String id) {
        Task t = TASKS.get(id);
        if (t == null) return;
        t.chunks.clear();
        t.paused = false;
        t.cancelled = false;
        t.status = "wait";
        start(t);
    }

    public static void delete(String id, boolean force) {
        Task t = TASKS.get(id);
        if (t == null) return;
        t.cancelled = true;
        t.paused = false;
        if (t.pool != null) t.pool.shutdownNow();
        TASKS.remove(id);
        // 删除本地文件 + 分片文件
        try { if (t.file != null) Files.deleteIfExists(t.file); } catch (IOException ignored) {}
        try {
            if (t.file != null) {
                String dir = t.file.getParent().toString();
                Files.deleteIfExists(Paths.get(dir, t.file.getFileName() + ".part"));
            }
        } catch (IOException ignored) {}
        cleanPartFiles(t);
    }

    private static void cleanPartFiles(Task t) {
        try {
            if (t.file != null) {
                Files.deleteIfExists(Paths.get(t.file.getParent().toString(), t.file.getFileName() + ".part"));
            }
        } catch (IOException ignored) {}
    }

    // ── 查询 ──
    public static List<Task> list() { return new ArrayList<>(TASKS.values()); }

    // ── 工具 ──
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static HttpRequest.Builder reqBuilder(Task t, String range) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(t.rawUrl))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", t.ua != null ? t.ua : "Mozilla/5.0");
        if (t.referer != null && !t.referer.isEmpty()) b.header("Referer", t.referer);
        if (t.cookie != null && !t.cookie.isEmpty()) b.header("Cookie", t.cookie);
        b.header("Accept", "*/*");
        if (range != null) b.header("Range", range);
        return b;
    }

    private static String firstHeader(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    private static long contentLength(HttpResponse<?> r) {
        try { return Long.parseLong(r.headers().firstValue("Content-Length").orElse("-1")); } catch (Exception e) { return -1; }
    }

    private static String genId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                try { m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
                catch (Exception ignored) {}
            }
        }
        return m;
    }

    private static String fileNameFromUrl(String u) {
        try {
            String p = URI.create(u).getPath();
            int i = p.lastIndexOf('/');
            if (i >= 0 && i < p.length() - 1) return URLDecoder.decode(p.substring(i + 1), "UTF-8");
        } catch (Exception ignored) {}
        return "download_" + genId().substring(0, 8);
    }

    private static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "download" : s;
    }

    private static Path uniqueFile(Path f) {
        if (!Files.exists(f)) return f;
        Path dir = f.getParent();
        String base = f.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int i = 1; i < 100; i++) {
            Path cand = dir.resolve(stem + " (" + i + ")" + ext);
            if (!Files.exists(cand)) return cand;
        }
        return f;
    }
}
