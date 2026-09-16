package com.netdisk.parser2;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 纯 Java dnode 节点（替代 py/chaquopy 运行时）：
 * 手写 RFC6455 WSS 客户端 + 二进制帧隧道协议 + 心跳/重连，零第三方依赖。
 * 功能等价 node_clientv4.py 的 ProxyNode/TunnelWorker。
 */
public class DnodeNode {
    private static final String TAG = "DnodeNode";
    private static final String DEFAULT_SERVER = "wss://dnode.qaiu.top/ws/node";

    // 帧类型
    private static final int TYPE_CONNECT_REQ  = 0x01;
    private static final int TYPE_CONNECT_OK   = 0x02;
    private static final int TYPE_CONNECT_FAIL = 0x03;
    private static final int TYPE_DATA         = 0x04;
    private static final int TYPE_CLOSE        = 0x05;
    private static final int MAX_CONCURRENCY   = 50;

    // 心跳（官方参数，防下载高峰误断）
    private static final int PING_INTERVAL_MS  = 25000;
    private static final int PONG_TIMEOUT_MS   = 70000;
    private static final int[] RECONNECT_DELAYS_MS = {0, 500, 1000, 2000, 4000, 8000, 15000};

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // ── 实例/线程引用（供 stop 使用）──
    private static volatile Thread supervisorThread;
    private static volatile boolean stopped = false;
    static volatile DnodeNode current;

    // ── 启动入口（保持与 DnodeBridge.start 相同签名，MainActivity 零改动）──
    private static final Object START_LOCK = new Object();
    public static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        synchronized (START_LOCK) {
            Thread cur = supervisorThread;
            if (cur != null && cur.isAlive()) return; // 已在运行，忽略重复启动（防多实例/多连接冲突）
            stopped = false;
            startStatusPoller(app);
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    NodeSupervisor sv = new NodeSupervisor(app);
                    sv.loop();
                }
            });
            supervisorThread = t;
            t.start();
        }
    }

    /** 停止节点（下载配置开关关闭时调用）：置停止标志 + 打断监控线程，节点实例下次循环退出 */
    public static void stop() {
        synchronized (START_LOCK) {
            stopped = true;
            DnodeNode n = current;
            if (n != null) n.running = false;
            Thread t = supervisorThread;
            if (t != null) {
                t.interrupt();
                try { t.join(3000); } catch (InterruptedException ignored) {}
            }
            supervisorThread = null;
            try { logStatus(n != null ? n.app : null, "已停止（开关关闭）"); } catch (Throwable ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════
    //  监控层：节点线程退出（异常）后 60s 自动拉起（兜底）
    // ══════════════════════════════════════════════════════
    static class NodeSupervisor {
        final Context app;
        NodeSupervisor(Context a) { app = a; }
        void loop() {
            logStatus(app, "Java 节点：初始化…");
            int attempt = 1;
            while (!stopped) {
                try {
                    NodeRunner r = new NodeRunner(app);
                    Thread t = new Thread(r);
                    t.start();
                    t.join();
                    if (stopped) break;
                    logStatus(app, "节点退出，60 秒后自动重启");
                    Thread.sleep(60000);
                } catch (Throwable t) {
                    if (stopped) break;
                    logStatus(app, "节点异常(第" + attempt + "次): " + t);
                    try { Thread.sleep(30000L * Math.min(attempt, 3)); } catch (InterruptedException e) { break; }
                }
                attempt++;
            }
        }
    }

    static class NodeRunner implements Runnable {
        final Context app;
        NodeRunner(Context a) { app = a; }
        @Override public void run() {
            DnodeNode node = new DnodeNode(app);
            node.runLoop();
        }
    }

    // ══════════════════════════════════════════════════════
    //  节点实例
    // ══════════════════════════════════════════════════════
    final Context app;
    final File filesDir;
    final String serverUrl;
    final String nodeId;
    final String secret;
    final String deviceFp;
    final String localIp;
    String[] geo;                       // [ip, city, province, isp]

    volatile boolean running = true;
    volatile long lastPong = 0;
    volatile WssClient ws;
    final ConcurrentHashMap<Integer, Tunnel> tunnels = new ConcurrentHashMap<Integer, Tunnel>();
    final Semaphore sem = new Semaphore(MAX_CONCURRENCY);
    final SecureRandom rnd = new SecureRandom();
    long totalTunnels = 0;

    DnodeNode(Context a) {
        app = a;
        filesDir = a.getFilesDir();
        // 配置（持久化 node_id/secret/server_url）
        String[] cfg = loadConfig();
        serverUrl = cfg[0];
        nodeId = cfg[1];
        secret = cfg[2];
        deviceFp = deviceFp(a);
        localIp = localIp();
        // geo 后台探测（失败不影响节点）
        Thread gt = new Thread(new GeoTask(this));
        gt.setDaemon(true);
        gt.start();
        Log.i(TAG, "节点配置 server=" + serverUrl + " node_id=" + nodeId);
        logStatus(app, "配置 server=" + serverUrl + " node_id=" + shortId(nodeId));
    }

    void runLoop() {
        logStatus(app, "节点启动中… node_id=" + shortId(nodeId));
        int attempt = 0;
        while (running) {
            try {
                connectOnce();
                if (!running) break;
                // 连接正常结束（含服务端主动关闭）：同样退避重连，避免 0s 快循环风暴
                // （服务端对同一 node_id 限单连接时，无退避会形成连上即关的刷屏循环）
            } catch (Throwable e) {
                if (!running) break;
                // 网络异常：按退避表递增
            }
            int d = RECONNECT_DELAYS_MS[Math.min(attempt, RECONNECT_DELAYS_MS.length - 1)];
            if (d > 0) {
                logStatus(app, "连接断开，" + (d / 1000) + "s 后重连…");
                try { Thread.sleep(d); } catch (InterruptedException ie) { break; }
            }
            attempt++;
        }
    }

    void connectOnce() throws Exception {
        writeStatus("连接服务端中… node_id=" + shortId(nodeId));
        logStatus(app, "连接服务端  platform=Android  node_id=" + shortId(nodeId));
        WssClient c = new WssClient(this);
        c.connect();
        ws = c;
        lastPong = System.currentTimeMillis();
        Log.i(TAG, "✅ 已连接 node_id=" + shortId(nodeId));
        writeStatus("已连接 node_id=" + shortId(nodeId));
        logStatus(app, "已连接 node_id=" + shortId(nodeId));

        Thread ping = new Thread(new PingTask(this));
        ping.setDaemon(true);
        ping.start();
        try {
            while (running) {
                WssClient.Frame f = c.readFrame();
                if (f == null) break;
                handleFrame(f);
            }
        } finally {
            c.close();
            ping.interrupt();
            for (Tunnel t : tunnels.values()) t.close();
            tunnels.clear();
            ws = null;
            writeStatus("连接断开，等待重连…");
            logStatus(app, "连接断开，等待重连…");
        }
    }

    void handleFrame(WssClient.Frame f) {
        if (f.opcode == 0x8) {          // close
            Log.i(TAG, "服务端关闭连接");
            logStatus(app, "服务端关闭连接");
            closeWs();
        } else if (f.opcode == 0x9) {   // ping → pong
            try { ws.sendFrame(0xA, f.payload); } catch (Exception ignored) {}
        } else if (f.opcode == 0x2) {   // binary → 隧道帧
            dispatch(f.payload);
        } else if (f.opcode == 0x1) {   // text → pong JSON
            try {
                JSONObject o = new JSONObject(new String(f.payload, java.nio.charset.StandardCharsets.UTF_8));
                if ("pong".equals(o.optString("type"))) {
                    lastPong = System.currentTimeMillis();
                    Log.d(TAG, "pong conn=" + o.optString("conn_id") + " load=" + o.optInt("load") + " pool=" + o.optInt("pool"));
                }
            } catch (Throwable ignored) {}
        }
    }

    void dispatch(byte[] raw) {
        if (raw.length < 5) return;
        int tid = ((raw[0] & 0xff) << 24) | ((raw[1] & 0xff) << 16) | ((raw[2] & 0xff) << 8) | (raw[3] & 0xff);
        int ftype = raw[4] & 0xff;
        byte[] payload = Arrays.copyOfRange(raw, 5, raw.length);
        if (ftype == TYPE_CONNECT_REQ) {
            if (payload.length < 3) return;
            int hl = payload[0] & 0xff;
            if (payload.length < 3 + hl) return;
            String host = new String(payload, 1, hl, java.nio.charset.StandardCharsets.UTF_8);
            int port = ((payload[1 + hl] & 0xff) << 8) | (payload[2 + hl] & 0xff);
            totalTunnels++;
            Log.i(TAG, "新隧道 → " + host + ":" + port + "  active=" + (tunnels.size() + 1) + "/" + MAX_CONCURRENCY);
            logStatus(app, "服务端派发隧道 → " + host + ":" + port + "  active=" + (tunnels.size() + 1) + "/" + MAX_CONCURRENCY);
            if (!sem.tryAcquire()) {
                try { sendFrame(tid, TYPE_CONNECT_FAIL, "busy".getBytes()); } catch (Exception ignored) {}
                return;
            }
            Tunnel t = new Tunnel(this, tid, host, port);
            tunnels.put(tid, t);
            t.start();
        } else if (ftype == TYPE_DATA) {
            Tunnel t = tunnels.get(tid);
            if (t != null) t.feedData(payload);
        } else if (ftype == TYPE_CLOSE) {
            Tunnel t = tunnels.remove(tid);
            if (t != null) { t.close(); sem.release(); }
        }
    }

    void sendFrame(int tid, int ftype, byte[] payload) throws IOException {
        WssClient c = ws;
        if (c == null) throw new IOException("未连接");
        c.sendFrame(0x2, packFrame(tid, ftype, payload));
    }

    static byte[] packFrame(int tid, int ftype, byte[] payload) {
        byte[] f = new byte[5 + payload.length];
        f[0] = (byte) (tid >>> 24); f[1] = (byte) (tid >>> 16); f[2] = (byte) (tid >>> 8); f[3] = (byte) tid;
        f[4] = (byte) ftype;
        System.arraycopy(payload, 0, f, 5, payload.length);
        return f;
    }

    void closeWs() {
        WssClient c = ws;
        if (c != null) { try { c.close(); } catch (Exception ignored) {} }
    }

    // ══════════════════════════════════════════════════════
    //  单条隧道
    // ══════════════════════════════════════════════════════
    static class Tunnel implements Runnable {
        final DnodeNode owner;
        final int tid;
        final String host;
        final int port;
        volatile Socket tcp;
        volatile boolean closed;
        long tx, rx;
        Thread reader;

        Tunnel(DnodeNode o, int t, String h, int p) {
            owner = o; tid = t; host = h; port = p;
        }

        void start() {
            Thread th = new Thread(this);
            th.setDaemon(true);
            th.start();
        }

        @Override public void run() {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), 10000);
                tcp = s;
                owner.sendFrame(tid, TYPE_CONNECT_OK, new byte[0]);
                Log.d(TAG, "隧道 " + host + ":" + port + " TCP_CONNECTED");
                DnodeNode.logStatus(owner.app, "隧道已建立 → " + host + ":" + port);
                reader = new Thread(new TcpToWs(this, s));
                reader.setDaemon(true);
                reader.start();
                // 等待读线程结束
                try { reader.join(); } catch (InterruptedException ignored) {}
            } catch (Throwable e) {
                if (!closed) {
                    DnodeNode.logStatus(owner.app, "隧道连接失败 → " + host + ":" + port + " " + e);
                    try { owner.sendFrame(tid, TYPE_CONNECT_FAIL, String.valueOf(e).getBytes()); } catch (Exception ignored) {}
                }
            } finally {
                closeTcp();
                owner.tunnels.remove(tid);
                owner.sem.release();
            }
        }

        void tcpToWs(Socket s) {
            try {
                InputStream in = s.getInputStream();
                byte[] buf = new byte[65536];
                int n;
                while (!closed && (n = in.read(buf)) > 0) {
                    rx += n;
                    owner.sendFrame(tid, TYPE_DATA, Arrays.copyOf(buf, n));
                }
            } catch (Throwable ignored) {
            } finally {
                try { owner.sendFrame(tid, TYPE_CLOSE, new byte[0]); } catch (Exception ignored) {}
                closed = true;
                closeTcp();
            }
        }

        void feedData(byte[] data) {
            if (closed) return;
            try {
                Socket s = tcp;
                if (s == null) return;
                tx += data.length;
                OutputStream os = s.getOutputStream();
                os.write(data);
                os.flush();
            } catch (Throwable ignored) {}
        }

        void close() {
            closed = true;
            closeTcp();
        }

        void closeTcp() {
            Socket s = tcp;
            if (s != null) { try { s.close(); } catch (Exception ignored) {} tcp = null; }
        }
    }

    static class TcpToWs implements Runnable {
        final Tunnel owner;
        final Socket s;
        TcpToWs(Tunnel t, Socket sk) { owner = t; s = sk; }
        @Override public void run() { owner.tcpToWs(s); }
    }

    // ══════════════════════════════════════════════════════
    //  心跳
    // ══════════════════════════════════════════════════════
    static class PingTask implements Runnable {
        final DnodeNode owner;
        PingTask(DnodeNode o) { owner = o; }
        @Override public void run() {
            while (owner.running) {
                try { Thread.sleep(PING_INTERVAL_MS); } catch (InterruptedException e) { return; }
                if (!owner.running) return;
                WssClient c = owner.ws;
                if (c == null) return;
                if (owner.lastPong > 0 && System.currentTimeMillis() - owner.lastPong > PONG_TIMEOUT_MS) {
                    Log.w(TAG, "超过 " + (PONG_TIMEOUT_MS / 1000) + "s 无 pong，连接僵死，主动关闭");
                    owner.writeStatus("连接僵死，主动重连中…");
                    owner.logStatus(owner.app, "连接僵死，主动重连中…");
                    owner.closeWs();
                    return;
                }
                try {
                    JSONObject ping = new JSONObject();
                    ping.put("type", "ping");
                    if (owner.geo != null) {
                        JSONObject g = new JSONObject();
                        g.put("city", owner.geo[1] == null ? "" : owner.geo[1]);
                        g.put("province", owner.geo[2] == null ? "" : owner.geo[2]);
                        g.put("isp", owner.geo[3] == null ? "" : owner.geo[3]);
                        g.put("country", "中国");
                        g.put("lat", 0);
                        g.put("lon", 0);
                        ping.put("geo", g);
                    }
                    c.sendFrame(0x1, ping.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Throwable ignored) {}
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  WSS 客户端（手写 RFC6455，无 mask 解帧 + 有 mask 发帧）
    // ══════════════════════════════════════════════════════
    static class WssClient {
        final DnodeNode owner;
        SSLSocket sock;
        InputStream in;
        OutputStream out;
        volatile boolean closed;

        WssClient(DnodeNode o) { owner = o; }

        void connect() throws Exception {
            URI uri = new URI(owner.serverUrl);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getQuery() != null) path += "?" + uri.getQuery();

            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket s = (SSLSocket) f.createSocket();
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setSoTimeout(0);
            try {
                SSLParameters p = s.getSSLParameters();
                p.setServerNames(Collections.singletonList(new SNIHostName(host)));
                s.setSSLParameters(p);
            } catch (Throwable ignored) {}
            s.startHandshake();
            // 主机名验证（默认信任系统 CA）
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                s.close();
                throw new IOException("证书主机名不匹配: " + host);
            }
            sock = s;
            in = new BufferedInputStream(s.getInputStream(), 16384);
            out = new BufferedOutputStream(s.getOutputStream(), 16384);

            // HTTP Upgrade 握手
            byte[] keyBytes = new byte[16];
            owner.rnd.nextBytes(keyBytes);
            String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            req.append("X-Node-ID: ").append(owner.nodeId).append("\r\n");
            req.append("X-Platform: Android\r\n");
            req.append("X-Version: 3.0\r\n");
            req.append("X-Device-FP: ").append(owner.deviceFp).append("\r\n");
            req.append("X-Local-IP: ").append(owner.localIp).append("\r\n");
            req.append("X-Default-Node: true\r\n");
            if (owner.geo != null) {
                if (owner.geo[1] != null && !owner.geo[1].isEmpty()) req.append("X-Geo-City: ").append(owner.geo[1]).append("\r\n");
                if (owner.geo[2] != null && !owner.geo[2].isEmpty()) req.append("X-Geo-Province: ").append(owner.geo[2]).append("\r\n");
                if (owner.geo[3] != null && !owner.geo[3].isEmpty()) req.append("X-Geo-ISP: ").append(owner.geo[3]).append("\r\n");
                if (owner.geo[0] != null && !owner.geo[0].isEmpty()) req.append("X-Geo-IP: ").append(owner.geo[0]).append("\r\n");
            }
            if (owner.secret != null && !owner.secret.isEmpty()) {
                req.append("Authorization: Bearer ").append(owner.secret).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();

            // 响应头
            String status = readLine(in);
            if (status == null || !status.contains("101")) {
                throw new IOException("WS 握手失败: " + status);
            }
            String accept = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int ci = line.indexOf(':');
                if (ci > 0 && line.regionMatches(true, 0, "Sec-WebSocket-Accept", 0, 21)) {
                    accept = line.substring(ci + 1).trim();
                }
            }
            // 校验 Sec-WebSocket-Accept
            String expect = base64sha1(key + WS_GUID);
            if (accept != null && !accept.equals(expect)) {
                throw new IOException("WS Accept 校验失败");
            }
        }

        static String base64sha1(String s) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.encodeToString(h, Base64.NO_WRAP);
        }

        String readLine(InputStream is) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int b;
            while ((b = is.read()) >= 0) {
                if (b == '\n') break;
                if (b != '\r') bos.write(b);
            }
            if (bos.size() == 0 && b < 0) return null;
            return bos.toString("ISO-8859-1");
        }

        void readFully(InputStream is, byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int n = is.read(buf, off, buf.length - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
        }

        static class Frame {
            final int opcode;
            final byte[] payload;
            Frame(int op, byte[] p) { opcode = op; payload = p; }
        }

        /** 读一帧（服务端帧无 mask） */
        Frame readFrame() throws IOException {
            InputStream is = in;
            int b0 = is.read();
            if (b0 < 0) throw new EOFException();
            int b1 = is.read();
            if (b1 < 0) throw new EOFException();
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = (is.read() << 8) | is.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (is.read() & 0xff);
            }
            if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("帧过大: " + len);
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(is, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(is, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            return new Frame(opcode, payload);
        }

        /** 发送一帧（客户端帧带 mask） */
        synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            if (closed) throw new IOException("已关闭");
            OutputStream os = out;
            os.write(0x80 | opcode);
            int len = payload.length;
            if (len < 126) {
                os.write(0x80 | len);
            } else if (len < 65536) {
                os.write(0x80 | 126);
                os.write(len >> 8); os.write(len & 0xff);
            } else {
                os.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) os.write((int) ((len >>> (8 * i)) & 0xff));
            }
            byte[] mask = new byte[4];
            owner.rnd.nextBytes(mask);
            os.write(mask);
            for (int i = 0; i < len; i++) os.write(payload[i] ^ mask[i & 3]);
            os.flush();
        }

        void close() {
            closed = true;
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════
    //  geo 探测（migufun，失败忽略）
    // ══════════════════════════════════════════════════════
    static class GeoTask implements Runnable {
        final DnodeNode owner;
        GeoTask(DnodeNode o) { owner = o; }
        @Override public void run() {
            owner.geo = fetchGeo();
            Log.i(TAG, "geo=" + (owner.geo == null ? "探测失败" : Arrays.toString(owner.geo)));
        }

        static String[] fetchGeo() {
            try {
                URL u = new URL("https://effic.migufun.com:8443/user/queryProvince");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                InputStream is = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                JSONObject o = new JSONObject(bos.toString(java.nio.charset.StandardCharsets.UTF_8));
                if ("000000".equals(o.optString("returnCode"))) {
                    JSONObject rd = o.optJSONObject("resultData");
                    if (rd != null) {
                        String city = strip(rd.optString("ipCity", ""));
                        String province = strip(rd.optString("ipProvince", ""));
                        return new String[]{rd.optString("ip", ""), city, province, rd.optString("ipOperator", "")};
                    }
                }
            } catch (Throwable ignored) {}
            return null;
        }

        static String strip(String s) {
            return s.replaceAll("[市省区县自治州盟]$", "");
        }
    }

    // ══════════════════════════════════════════════════════
    //  配置 / 状态 / 工具
    // ══════════════════════════════════════════════════════
    String[] loadConfig() {
        String server = DEFAULT_SERVER;
        String id = "";
        String sec = "";
        File cfg = new File(filesDir, "node_config.json");
        if (cfg.exists()) {
            try {
                String content = new String(readAll(cfg), java.nio.charset.StandardCharsets.UTF_8);
                JSONObject o = new JSONObject(content);
                server = o.optString("server_url", DEFAULT_SERVER);
                id = o.optString("node_id", "");
                sec = o.optString("secret", "");
            } catch (Throwable ignored) {}
        }
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
            try {
                JSONObject o = new JSONObject();
                o.put("server_url", server);
                o.put("node_id", id);
                o.put("secret", sec);
                FileOutputStream fos = new FileOutputStream(cfg);
                fos.write(o.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.close();
            } catch (Throwable ignored) {}
        }
        return new String[]{server, id, sec};
    }

    void writeStatus(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("status", msg);
            o.put("ts", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            FileOutputStream fos = new FileOutputStream(new File(filesDir, "dnode_status.json"));
            fos.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {}
    }

    static String shortId(String id) {
        return id != null && id.length() > 8 ? id.substring(0, 8) : id;
    }

    static String deviceFp(Context ctx) {
        try {
            String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((aid == null ? "" : aid).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static String localIp() {
        for (String host : new String[]{"8.8.8.8", "1.1.1.1", "114.114.114.114"}) {
            try {
                DatagramSocket s = new DatagramSocket();
                s.connect(InetAddress.getByName(host), 80);
                String ip = s.getLocalAddress().getHostAddress();
                s.close();
                if (ip != null && !ip.startsWith("0.")) return ip;
            } catch (Throwable ignored) {}
        }
        return "?";
    }

    static byte[] readAll(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = fis.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        } finally {
            fis.close();
        }
    }

    /** 状态上报到会话日志（经 Go 侧 /app/pylog） */
    static void logStatus(Context ctx, String msg) {
        try {
            URL u = new URL("http://127.0.0.1:18091/app/pylog?msg=" + URLEncoder.encode(msg, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignored) {}
    }

    /** 轮询状态文件，变化时上报会话日志（复用原机制，日志前缀 节点:） */
    static void startStatusPoller(final Context app) {
        new Thread(new Runnable() {
            @Override public void run() {
                String last = "";
                while (true) {
                    try {
                        File f = new File(app.getFilesDir(), "dnode_status.json");
                        if (f.exists()) {
                            String content = new String(readAll(f), java.nio.charset.StandardCharsets.UTF_8);
                            int i = content.indexOf("\"status\"");
                            if (i >= 0) {
                                int a = content.indexOf('"', i + 8);
                                int b = content.indexOf('"', a + 1);
                                if (a >= 0 && b > a) {
                                    String status = content.substring(a + 1, b);
                                    if (!status.equals(last)) {
                                        last = status;
                                        logStatus(app, status);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                }
            }
        }).start();
    }
}
