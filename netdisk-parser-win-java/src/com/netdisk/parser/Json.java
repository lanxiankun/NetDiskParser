package com.netdisk.parser;

import java.util.*;

/** 极简 JSON 解析/生成（够用即可，无第三方依赖） */
public class Json {

    public static Map<String, Object> parseObj(String s) {
        P p = new P(s);
        Object v = p.value();
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    public static List<Object> parseArr(String s) {
        P p = new P(s);
        Object v = p.value();
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { quote((String) o, sb); return; }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Iterable<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(v, sb);
            }
            sb.append(']');
            return;
        }
        quote(String.valueOf(o), sb);
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ── 解析器 ──
    private static class P {
        final String s; int i = 0;
        P(String s) { this.s = s == null ? "" : s; }

        Object value() {
            skip();
            char c = peek();
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            return numOrLit();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skip();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skip();
                String k = str();
                skip();
                expect(':');
                m.put(k, value());
                skip();
                char c = next();
                if (c == ',') continue;
                if (c == '}') break;
            }
            return m;
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            expect('[');
            skip();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                skip();
                char c = next();
                if (c == ',') continue;
                if (c == ']') break;
            }
            return l;
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        Object numOrLit() {
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) || c == ',' || c == '}' || c == ']') break;
                sb.append(c); i++;
            }
            String t = sb.toString().trim();
            if ("null".equals(t)) return null;
            if ("true".equals(t)) return true;
            if ("false".equals(t)) return false;
            try { return t.contains(".") ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t); }
            catch (NumberFormatException e) { return t; }
        }

        void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
        char next() { return i < s.length() ? s.charAt(i++) : '\0'; }
        void expect(char c) { skip(); if (i >= s.length() || s.charAt(i) != c) throw new RuntimeException("JSON 语法错误: 期望 '" + c + "' 在 " + i); i++; }
    }
}
