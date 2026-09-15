package com.netdisk.parser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 解析历史：SharedPreferences 存 JSON 数组 */
public class HistoryStore {
    private static final String PREFS = "netdisk_parser";
    private static final String KEY = "history";
    private final SharedPreferences sp;

    public HistoryStore(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<String[]> load() {
        List<String[]> out = new ArrayList<String[]>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{o.optString("url"), o.optString("time"), o.optString("name")});
            }
        } catch (Exception e) { }
        return out;
    }

    public void add(String url, String time, String name) {
        try {
            List<String[]> list = load();
            // 去重：同 url 移到最前
            List<String[]> filtered = new ArrayList<String[]>();
            for (String[] it : list) {
                if (!it[0].equals(url)) filtered.add(it);
            }
            filtered.add(0, new String[]{url, time, name});
            if (filtered.size() > 100) filtered = filtered.subList(0, 100);
            JSONArray arr = new JSONArray();
            for (String[] it : filtered) {
                JSONObject o = new JSONObject();
                o.put("url", it[0]);
                o.put("time", it[1]);
                o.put("name", it[2]);
                arr.put(o);
            }
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) { }
    }

    public void clear() {
        sp.edit().remove(KEY).apply();
    }
}
