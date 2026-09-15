package com.netdisk.parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 应用配置（下载目录等），持久化 ~/.NetDiskParser/config.json */
public class Config {
    private static final Path CFG = Paths.get(System.getProperty("user.home"), ".NetDiskParser", "config.json");
    private static Map<String, Object> cache;

    public static synchronized Map<String, Object> get() {
        if (cache == null) cache = load();
        return cache;
    }

    private static Map<String, Object> load() {
        try {
            if (Files.exists(CFG)) {
                return Json.parseObj(new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dlDir", Paths.get(System.getProperty("user.home"), "Downloads").toString());
        return m;
    }

    public static synchronized void save(Map<String, Object> m) {
        cache = m;
        try {
            Files.createDirectories(CFG.getParent());
            Files.write(CFG, Json.stringify(m).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public static String downloadDir() {
        Object d = get().get("dlDir");
        return d != null ? String.valueOf(d) : Paths.get(System.getProperty("user.home"), "Downloads").toString();
    }
}
