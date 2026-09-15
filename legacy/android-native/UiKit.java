package com.netdisk.parser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 原生 UI 工具：dp 换算、圆角/描边背景、常用控件工厂（保持浅色 MoePal 风格） */
public class UiKit {
    public static final int ACCENT   = Color.rgb(47, 107, 255);
    public static final int ACCENT_D = Color.rgb(235, 241, 255);
    public static final int BG       = Color.rgb(245, 246, 248);
    public static final int SURFACE  = Color.WHITE;
    public static final int SURFACE2 = Color.rgb(248, 249, 251);
    public static final int BORDER   = Color.rgb(233, 236, 241);
    public static final int TEXT     = Color.rgb(17, 24, 39);
    public static final int TEXT2    = Color.rgb(107, 114, 128);
    public static final int TEXT3    = Color.rgb(156, 163, 175);
    public static final int OK       = Color.rgb(16, 185, 129);
    public static final int ERR      = Color.rgb(239, 68, 68);
    public static final int DISABLED_BG = Color.rgb(216, 219, 224);
    public static final int DISABLED_FG = Color.rgb(154, 161, 171);

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(int radius, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        if (color != 0) g.setColor(color);
        return g;
    }

    public static GradientDrawable stroke(int radius, int color, int width) {
        GradientDrawable g = round(radius, 0);
        g.setStroke(width, color);
        return g;
    }

    public static TextView tv(Context c, String s, float sp, int color, int style) {
        TextView t = new TextView(c);
        t.setText(s == null ? "" : s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 白色圆角卡片容器 */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(dp(c, 14), SURFACE));
        return l;
    }

    public static Button btn(Context c, String text, int bg, int fg) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(dp(c, 12), bg));
        b.setPadding(dp(c, 10), dp(c, 9), dp(c, 10), dp(c, 9));
        return b;
    }

    public static Button ghostBtn(Context c, String text) {
        Button b = btn(c, text, SURFACE2, TEXT);
        b.setBackground(stroke(dp(c, 12), BORDER, 1));
        return b;
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint == null ? "" : hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setTextColor(TEXT);
        e.setHintTextColor(TEXT3);
        e.setBackground(round(dp(c, 10), SURFACE2));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
    }

    /** 空状态卡片 */
    public static LinearLayout emptyCard(Context c, String text) {
        LinearLayout l = card(c);
        l.setPadding(dp(c, 20), dp(c, 24), dp(c, 20), dp(c, 24));
        l.setGravity(Gravity.CENTER);
        l.addView(tv(c, text, 13, TEXT2, Typeface.NORMAL));
        return l;
    }

    /** 页面通用滚动容器 */
    public static ScrollView scroll(Context c) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(false);
        sv.setVerticalScrollBarEnabled(false);
        return sv;
    }

    /** 纵向 LinearLayout */
    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** 横向 LinearLayout */
    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 分割线 */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    /** 页面顶部标题 */
    public static LinearLayout hero(Context c, String title, String sub) {
        LinearLayout l = vbox(c);
        l.setPadding(dp(c, 2), 0, dp(c, 2), dp(c, 8));
        l.addView(tv(c, title, 24, TEXT, Typeface.BOLD));
        if (sub != null) {
            TextView s = tv(c, sub, 13, TEXT2, Typeface.NORMAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(c, 3);
            s.setLayoutParams(lp);
            l.addView(s);
        }
        return l;
    }

    /** 小 chip 按钮 */
    public static Button chip(Context c, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        if (primary) {
            b.setTextColor(ACCENT);
            b.setBackground(round(dp(c, 99), ACCENT_D));
        } else {
            b.setTextColor(TEXT2);
            b.setBackground(round(dp(c, 99), SURFACE2));
        }
        return b;
    }
}
