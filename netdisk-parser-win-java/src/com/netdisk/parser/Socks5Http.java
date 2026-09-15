package com.netdisk.parser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 极简 SOCKS5 客户端（带用户名密码认证）：
 * 用于下载分片经 dnode 节点 / 云端 SOCKS5 代理中转。
 * 协议：RFC1928 + RFC1929（USER/PASS 认证）。
 */
public class Socks5Http {

    public final String host;
    public final int port;
    public final String user;
    public final String pass;

    public Socks5Http(String host, int port, String user, String pass) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
    }

    /** 建立到目标 host:port 的 SOCKS5 隧道，返回已就绪的 Socket（发 HTTP 请求即可） */
    public Socket connect(String targetHost, int targetPort) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 15000);
        s.setSoTimeout(0);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

        // 方法协商：支持 无认证(0) / 用户名密码(2)
        out.writeByte(0x05); out.writeByte(0x02); out.writeByte(0x00); out.writeByte(0x02);
        out.flush();
        int ver = in.readByte() & 0xff;
        int method = in.readByte() & 0xff;
        if (ver != 0x05) throw new IOException("SOCKS5 版本错误: " + ver);
        if (method == 0x02) {
            byte[] ub = (user == null ? "" : user).getBytes(StandardCharsets.UTF_8);
            byte[] pb = (pass == null ? "" : pass).getBytes(StandardCharsets.UTF_8);
            out.writeByte(0x01);
            out.writeByte(ub.length); out.write(ub);
            out.writeByte(pb.length); out.write(pb);
            out.flush();
            int av = in.readByte() & 0xff;
            int st = in.readByte() & 0xff;
            if (av != 0x01 || st != 0x00) throw new IOException("SOCKS5 认证失败 (" + st + ")");
        } else if (method != 0x00) {
            throw new IOException("SOCKS5 无可用认证方式: " + method);
        }

        // CONNECT 请求（域名）
        byte[] hb = targetHost.getBytes(StandardCharsets.UTF_8);
        out.writeByte(0x05); out.writeByte(0x01); out.writeByte(0x00);
        out.writeByte(0x03); out.writeByte(hb.length); out.write(hb);
        out.writeByte((targetPort >> 8) & 0xff); out.writeByte(targetPort & 0xff);
        out.flush();
        int rv = in.readByte() & 0xff;
        int rc = in.readByte() & 0xff;
        if (rv != 0x05 || rc != 0x00) {
            throw new IOException("SOCKS5 CONNECT 失败: " + rc + " → " + targetHost + ":" + targetPort);
        }
        // 跳过 BND.ADDR/BND.PORT
        int atyp = in.readByte() & 0xff;
        if (atyp == 0x01) { in.skipBytes(6); }
        else if (atyp == 0x03) { int l = in.readByte() & 0xff; in.skipBytes(l + 2); }
        else if (atyp == 0x04) { in.skipBytes(18); }
        return s;
    }

    /** 单次 HTTP GET（支持 Range），返回响应（调用方负责 close body 与 socket） */
    public Response get(String url, Map<String, String> headers, String range) throws IOException {
        URI uri = URI.create(url);
        String targetHost = uri.getHost();
        int targetPort = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equals("https") ? 443 : 80);
        Socket s = connect(targetHost, targetPort);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath());
        if (uri.getQuery() != null) req.append("?").append(uri.getQuery());
        req.append(" HTTP/1.1\r\n");
        req.append("Host: ").append(targetHost);
        if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) req.append(":").append(uri.getPort());
        req.append("\r\n");
        req.append("Accept: */*\r\n");
        req.append("Connection: close\r\n");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        if (range != null) req.append("Range: ").append(range).append("\r\n");
        req.append("\r\n");

        OutputStream os = s.getOutputStream();
        os.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        os.flush();

        InputStream in = s.getInputStream();
        // 读状态行
        String statusLine = readLine(in);
        if (statusLine == null || !statusLine.startsWith("HTTP/")) throw new IOException("非法响应: " + statusLine);
        int status = 0;
        try {
            String[] parts = statusLine.split(" ");
            status = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {}
        // 读头
        Map<String, String> respHeaders = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int ci = line.indexOf(':');
            if (ci > 0) respHeaders.put(line.substring(0, ci).trim().toLowerCase(), line.substring(ci + 1).trim());
        }
        Response r = new Response(status, respHeaders, in, s);
        // 有 Content-Length 则按长度读；否则读到 EOF
        String cl = respHeaders.get("content-length");
        if (cl != null) {
            long len = Long.parseLong(cl);
            r.body = new LengthLimitedInputStream(in, len);
        } else {
            r.body = in;  // 靠 Connection: close 结束
        }
        return r;
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        if (bos.size() == 0 && b < 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    public static class Response implements Closeable {
        public final int status;
        public final Map<String, String> headers;
        public InputStream body;
        public final Socket socket;

        Response(int s, Map<String, String> h, InputStream b, Socket sk) { status = s; headers = h; body = b; socket = sk; }

        public String header(String name) { return headers.get(name.toLowerCase()); }

        @Override public void close() {
            try { body.close(); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    /** 限制最多读取 len 字节（Content-Length 精确截断，避免 Connection: close 超读） */
    static class LengthLimitedInputStream extends InputStream {
        final InputStream in;
        long remain;
        LengthLimitedInputStream(InputStream i, long l) { in = i; remain = l; }
        @Override public int read() throws IOException {
            if (remain <= 0) return -1;
            int b = in.read();
            if (b >= 0) remain--;
            return b;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (remain <= 0) return -1;
            int n = in.read(buf, off, (int) Math.min(len, remain));
            if (n > 0) remain -= n;
            return n;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    /** 解析 "host:port" */
    public static Socks5Http parse(String target, String user, String pass) {
        String host = target;
        int port = 1080;
        int ci = target.lastIndexOf(':');
        if (ci > 0) {
            try { port = Integer.parseInt(target.substring(ci + 1)); host = target.substring(0, ci); } catch (Exception ignored) {}
        }
        return new Socks5Http(host, port, user, pass);
    }
}
