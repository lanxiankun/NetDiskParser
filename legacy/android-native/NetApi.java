package com.netdisk.parser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** 轻量 HTTP 客户端（本地 Go 服务 + 解析代理），无第三方依赖 */
public class NetApi {
    public static final int TIMEOUT = 30000;

    public static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    public static String request(String urlStr, String method, String body, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "NetDiskParser/1.0 (Android)");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.getOutputStream().write(body.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            text = bos.toString("UTF-8");
        }
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code + " " + text);
        return text;
    }

    public static String get(String urlStr) throws Exception { return request(urlStr, "GET", null, TIMEOUT); }
    public static String post(String urlStr, String body) throws Exception { return request(urlStr, "POST", body, TIMEOUT); }
    public static String put(String urlStr) throws Exception { return request(urlStr, "PUT", null, TIMEOUT); }
    public static String delete(String urlStr) throws Exception { return request(urlStr, "DELETE", null, TIMEOUT); }
}
