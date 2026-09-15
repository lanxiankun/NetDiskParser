package com.netdisk.parser;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 云盘解析下载器 —— 原生 Android 版（无 WebView）
 * 界面层原生实现；Go 解析服务 + 内置 Gopeed 引擎经 JNI 启动，通过 127.0.0.1:18090 的 HTTP 接口调用。
 * 说明：本文件刻意不使用匿名内部类与 lambda（规避 d8 对匿名类的 NPE 解析问题）。
 */
public class MainActivity extends Activity {

    static final String TAG = "NetDiskParser";
    static final String BASE = "http://127.0.0.1:18090";
    static final String PARSER_BASE = BASE + "/parse";
    static final String DL_BASE = BASE + "/dl";
    static final String DOWNLOAD_DIR = "/storage/emulated/0/NetDiskParser/Download";
    static final String LOG_DIR = "/storage/emulated/0/NetDiskParser/logs";
    static final String LOG_FILE = LOG_DIR + "/app.log";
    static final String UA_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    static final String[][] PAN_DOMAINS = {
            {"yun.139.com", "移动云盘"}, {"caiyun.139.com", "移动云盘"},
            {"pan.quark.cn", "夸克网盘"}, {"drive.uc.cn", "UC网盘"}, {"pan.baidu.com", "百度网盘"},
            {"cloud.189.cn", "天翼云盘"}, {"pan.xunlei.com", "迅雷云盘"}, {"alipan.com", "阿里云盘"},
            {"aliyundrive.com", "阿里云盘"}, {"lanzoux.com", "蓝奏云"}, {"lanzouj.com", "蓝奏云"},
            {"lanzoui.com", "蓝奏云"}, {"lanzou.com", "蓝奏云"}, {"woozooo.com", "蓝奏云"},
            {"123pan.com", "123云盘"}, {"cowtransfer.com", "奶牛快传"}, {"share.weiyun.com", "腾讯微云"},
            {"chaoxing.com", "超星云盘"}, {"pan.wps.cn", "WPS云文档"}, {"drive.google.com", "Google Drive"},
            {"1drv.ms", "OneDrive"}, {"onedrive.live.com", "OneDrive"}, {"pan.huang1111.cn", "Huang1111"},
            {"pan.189.cn", "天翼云盘"}
    };

    // ---------- 原生 JNI：启动内置 Go 服务 ----------
    static { System.loadLibrary("netdiskparser"); }
    public static native String StartServer(String dataDir);

    // ---------- 状态 ----------
    String apiKey = "";
    int threads = 4;
    HistoryStore history;
    boolean gopeedOnline = false;
    boolean parseBusy = false;
    boolean polling = false;
    String currentTab = "parse";
    String currentTreePan = "";
    String currentTreePanName = "";
    String currentTreeOther = ""; // otherParam JSON
    String treeRootUrl = "";
    String treeRootPwd = "";
    final LinkedHashMap<String, JSONObject> selectedItems = new LinkedHashMap<String, JSONObject>();
    JSONObject currentResult = null; // 单文件结果
    JSONArray taskList = new JSONArray();
    final Map<String, String> speedCache = new HashMap<String, String>();
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newCachedThreadPool();

    // ---------- UI ----------
    FrameLayout contentArea;
    View pageParse, pageHistory, pageDownloads, pageMine;
    EditText linkInput, pwdInput, apiKeyInput, threadsInput;
    TextView clipHint, engineStateText, dirText;
    Button btnParse, btnClear, btnReadClip;
    LinearLayout resultArea, historyListBox, taskListBox;
    Button tabParse, tabHistory, tabDownloads, tabMine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 剔除系统标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 沉浸式状态栏：透明 + 内容延伸到状态栏下 + 深色图标适配浅色界面
        if (Build.VERSION.SDK_INT >= 21) {
            Window win = getWindow();
            win.setStatusBarColor(Color.TRANSPARENT);
            int vis = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            if (Build.VERSION.SDK_INT >= 23) vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            win.getDecorView().setSystemUiVisibility(vis);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
        self = this;
        Log.i(TAG, "onCreate: 原生界面启动");
        appendLog("[INFO] 原生界面启动");
        history = new HistoryStore(this);
        loadSettings();
        ensureDirs();
        requestStorage();
        buildUI();
        // 后台启动内置服务（JNI），失败自动重试
        pool.execute(new ServiceStarter(getFilesDir().getAbsolutePath()));
    }

    void ensureDirs() {
        try {
            new File(DOWNLOAD_DIR).mkdirs();
            new File(LOG_DIR).mkdirs();
        } catch (Exception e) { }
    }

    void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception e2) { }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            }
        }
    }

    void loadSettings() {
        apiKey = getSharedPreferences("netdisk_parser", MODE_PRIVATE).getString("apiKey", "");
        threads = getSharedPreferences("netdisk_parser", MODE_PRIVATE).getInt("threads", 4);
    }

    void saveSettings() {
        getSharedPreferences("netdisk_parser", MODE_PRIVATE).edit()
                .putString("apiKey", apiKey).putInt("threads", threads).apply();
    }

    // ================= UI 构建 =================
    void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        setContentView(root);

        contentArea = new FrameLayout(this);
        contentArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(contentArea);

        pageParse = buildParsePage();
        pageHistory = buildHistoryPage();
        pageDownloads = buildDownloadsPage();
        pageMine = buildMinePage();
        // 沉浸式：内容区让出状态栏高度；各页面铺满内容区
        int statusH = getStatusBarHeight();
        contentArea.setPadding(0, statusH, 0, 0);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pageParse.setLayoutParams(fp);
        pageHistory.setLayoutParams(fp);
        pageDownloads.setLayoutParams(fp);
        pageMine.setLayoutParams(fp);
        contentArea.addView(pageParse);
        contentArea.addView(pageHistory);
        contentArea.addView(pageDownloads);
        contentArea.addView(pageMine);

        // 底部导航
        LinearLayout tabbar = new LinearLayout(this);
        tabbar.setOrientation(LinearLayout.HORIZONTAL);
        tabbar.setGravity(Gravity.CENTER);
        tabbar.setBackgroundColor(UiKit.SURFACE);
        tabbar.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        tabbar.setElevation(UiKit.dp(this, 8));
        // 底部手势区避让（窗口 insets 就绪后设置）
        tabbar.post(new InsetSetter(this, tabbar));
        root.addView(tabbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabParse = tabBtn("解析");
        tabHistory = tabBtn("历史");
        tabDownloads = tabBtn("下载");
        tabMine = tabBtn("我的");
        tabbar.addView(tabParse, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabbar.addView(tabHistory, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabbar.addView(tabDownloads, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabbar.addView(tabMine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        navigate("parse");
        refreshTasks();
    }

    int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return UiKit.dp(this, 24);
    }

    int getBottomInset() {
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = getWindow().getDecorView();
            return decor.getRootWindowInsets() == null ? 0
                    : decor.getRootWindowInsets().getStableInsetBottom();
        }
        return 0;
    }

    Button tabBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        b.setOnClickListener(new TabClick(label));
        return b;
    }

    // ================= 解析页 =================
    View buildParsePage() {
        ScrollView sv = UiKit.scroll(this);
        sv.setBackgroundColor(UiKit.BG);
        LinearLayout box = UiKit.vbox(this);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 22), UiKit.dp(this, 16), UiKit.dp(this, 24));
        sv.addView(box);

        // 问候语 + 标题
        String greet = greetText();
        box.addView(UiKit.hero(this, greet, "NetDisk Parser · Gopeed 驱动"));

        // 粘贴卡片
        LinearLayout card = UiKit.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 14);
        card.setLayoutParams(lp);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16));

        LinearLayout headRow = UiKit.hbox(this);
        headRow.addView(UiKit.tv(this, "粘贴内容", 14, UiKit.TEXT, Typeface.BOLD));
        LinearLayout headRight = new LinearLayout(this);
        headRight.setOrientation(LinearLayout.HORIZONTAL);
        headRight.setGravity(Gravity.CENTER_VERTICAL);
        btnReadClip = UiKit.chip(this, "读取剪贴板", true);
        btnReadClip.setOnClickListener(new ReadClipClick());
        headRight.addView(btnReadClip);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headRight.setLayoutParams(hrLp);
        headRight.setGravity(Gravity.END);
        headRow.addView(headRight);
        card.addView(headRow);

        linkInput = UiKit.input(this, "粘贴网盘分享链接或整段文本…");
        linkInput.setMinLines(2);
        linkInput.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams liLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liLp.topMargin = UiKit.dp(this, 12);
        linkInput.setLayoutParams(liLp);
        card.addView(linkInput);

        pwdInput = UiKit.input(this, "提取码（可选）");
        LinearLayout.LayoutParams piLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        piLp.topMargin = UiKit.dp(this, 10);
        pwdInput.setLayoutParams(piLp);
        card.addView(pwdInput);

        clipHint = UiKit.tv(this, "", 12, UiKit.OK, Typeface.NORMAL);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chLp.topMargin = UiKit.dp(this, 8);
        clipHint.setLayoutParams(chLp);
        card.addView(clipHint);

        // 按钮行：清空 + 开始解析
        LinearLayout btnRow = UiKit.hbox(this);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = UiKit.dp(this, 14);
        btnRow.setLayoutParams(brLp);
        btnClear = UiKit.ghostBtn(this, "清空");
        btnClear.setOnClickListener(new ClearClick());
        btnRow.addView(btnClear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnParse = UiKit.btn(this, "开始解析", UiKit.ACCENT, Color.WHITE);
        btnParse.setEnabled(false);
        btnParse.setOnClickListener(new ParseClick());
        LinearLayout.LayoutParams bpLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f);
        bpLp.leftMargin = UiKit.dp(this, 10);
        btnParse.setLayoutParams(bpLp);
        btnRow.addView(btnParse);
        card.addView(btnRow);

        box.addView(card);

        // 结果区
        resultArea = UiKit.vbox(this);
        LinearLayout.LayoutParams raLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        raLp.topMargin = UiKit.dp(this, 14);
        resultArea.setLayoutParams(raLp);
        box.addView(resultArea);

        // 输入监听：同步按钮可用态
        linkInput.addTextChangedListener(new SyncInputWatcher());
        pwdInput.addTextChangedListener(new SyncInputWatcher());
        return sv;
    }

    String greetText() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int h = c.get(java.util.Calendar.HOUR_OF_DAY);
        String p = h < 6 ? "凌晨好" : h < 12 ? "早上好" : h < 14 ? "中午好" : h < 18 ? "下午好" : "晚上好";
        return p + "，欢迎使用";
    }

    void showClipHint(String s) {
        clipHint.setText(s == null ? "" : s);
        clipHint.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    // ================= 历史页 =================
    View buildHistoryPage() {
        ScrollView sv = UiKit.scroll(this);
        sv.setBackgroundColor(UiKit.BG);
        LinearLayout box = UiKit.vbox(this);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 22), UiKit.dp(this, 16), UiKit.dp(this, 24));
        sv.addView(box);

        LinearLayout heroRow = UiKit.hbox(this);
        heroRow.addView(UiKit.hero(this, "解析历史", null), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clearHist = UiKit.ghostBtn(this, "清空");
        clearHist.setOnClickListener(new ClearHistoryClick());
        heroRow.addView(clearHist);
        box.addView(heroRow);

        historyListBox = UiKit.vbox(this);
        LinearLayout.LayoutParams hlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlLp.topMargin = UiKit.dp(this, 12);
        historyListBox.setLayoutParams(hlLp);
        box.addView(historyListBox);
        return sv;
    }

    void renderHistory() {
        historyListBox.removeAllViews();
        java.util.List<String[]> list = history.load();
        if (list.isEmpty()) {
            historyListBox.addView(UiKit.emptyCard(this, "暂无解析历史"));
            return;
        }
        LinearLayout card = UiKit.card(this);
        card.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 4), UiKit.dp(this, 6), UiKit.dp(this, 4));
        for (String[] it : list) {
            LinearLayout row = UiKit.vbox(this);
            row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
            row.addView(UiKit.tv(this, it[2] == null || it[2].length() == 0 ? it[0] : it[2],
                    14, UiKit.TEXT, Typeface.BOLD));
            TextView sub = UiKit.tv(this, it[0], 11, UiKit.TEXT2, Typeface.NORMAL);
            sub.setMaxLines(1);
            row.addView(sub);
            TextView time = UiKit.tv(this, it[1], 11, UiKit.TEXT3, Typeface.NORMAL);
            row.addView(time);
            row.setOnClickListener(new HistoryRowClick(it[0]));
            card.addView(row);
            card.addView(UiKit.divider(this));
        }
        historyListBox.addView(card);
    }

    // ================= 下载页 =================
    View buildDownloadsPage() {
        ScrollView sv = UiKit.scroll(this);
        sv.setBackgroundColor(UiKit.BG);
        LinearLayout box = UiKit.vbox(this);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 22), UiKit.dp(this, 16), UiKit.dp(this, 24));
        sv.addView(box);

        LinearLayout heroRow = UiKit.hbox(this);
        heroRow.addView(UiKit.hero(this, "下载任务", "内置 Gopeed 引擎"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = UiKit.chip(this, "刷新", false);
        refresh.setOnClickListener(new RefreshClick());
        heroRow.addView(refresh);
        box.addView(heroRow);

        // 引擎状态卡
        LinearLayout stateCard = UiKit.card(this);
        stateCard.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        engineStateText = UiKit.tv(this, "正在检测引擎状态…", 13, UiKit.TEXT2, Typeface.NORMAL);
        stateCard.addView(engineStateText);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = UiKit.dp(this, 12);
        stateCard.setLayoutParams(scLp);
        box.addView(stateCard);

        taskListBox = UiKit.vbox(this);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = UiKit.dp(this, 12);
        taskListBox.setLayoutParams(tlLp);
        box.addView(taskListBox);
        return sv;
    }

    void renderEngineState() {
        if (engineStateText == null) return;
        if (gopeedOnline) {
            engineStateText.setTextColor(UiKit.OK);
            engineStateText.setText("● 引擎运行中 · 已连接 Gopeed");
        } else {
            engineStateText.setTextColor(UiKit.ERR);
            engineStateText.setText("● 引擎未连接（服务启动中或异常）");
        }
    }

    // ================= 我的页 =================
    View buildMinePage() {
        ScrollView sv = UiKit.scroll(this);
        sv.setBackgroundColor(UiKit.BG);
        LinearLayout box = UiKit.vbox(this);
        box.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 22), UiKit.dp(this, 16), UiKit.dp(this, 24));
        sv.addView(box);
        box.addView(UiKit.hero(this, "我的", "解析服务与下载设置"));

        // 设置卡
        LinearLayout card = UiKit.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 14);
        card.setLayoutParams(lp);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16));

        card.addView(UiKit.tv(this, "解析 API Key", 13, UiKit.TEXT2, Typeface.BOLD));
        apiKeyInput = UiKit.input(this, "在解析站个人中心获取");
        apiKeyInput.setText(apiKey);
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aiLp.topMargin = UiKit.dp(this, 6);
        apiKeyInput.setLayoutParams(aiLp);
        card.addView(apiKeyInput);

        card.addView(UiKit.tv(this, "下载线程数", 13, UiKit.TEXT2, Typeface.BOLD));
        threadsInput = UiKit.input(this, "默认 4");
        threadsInput.setText(String.valueOf(threads));
        threadsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams tiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tiLp.topMargin = UiKit.dp(this, 6);
        threadsInput.setLayoutParams(tiLp);
        card.addView(threadsInput);

        Button saveBtn = UiKit.btn(this, "保存设置", UiKit.ACCENT, Color.WHITE);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = UiKit.dp(this, 14);
        saveBtn.setLayoutParams(sbLp);
        saveBtn.setOnClickListener(new SaveSettingsClick());
        card.addView(saveBtn);
        box.addView(card);

        // 信息卡
        LinearLayout info = UiKit.card(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = UiKit.dp(this, 14);
        info.setLayoutParams(ilp);
        info.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14), UiKit.dp(this, 16), UiKit.dp(this, 14));
        info.addView(UiKit.tv(this, "下载目录", 13, UiKit.TEXT2, Typeface.BOLD));
        dirText = UiKit.tv(this, DOWNLOAD_DIR, 12, UiKit.TEXT, Typeface.NORMAL);
        info.addView(dirText);
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtLp.topMargin = UiKit.dp(this, 4);
        dirText.setLayoutParams(dtLp);
        Button logBtn = UiKit.ghostBtn(this, "查看运行日志");
        LinearLayout.LayoutParams lgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lgLp.topMargin = UiKit.dp(this, 12);
        logBtn.setLayoutParams(lgLp);
        logBtn.setOnClickListener(new LogClick());
        info.addView(logBtn);

        TextView link = UiKit.tv(this, "解析服务文档：189.qaiu.top ↗", 13, UiKit.ACCENT, Typeface.BOLD);
        LinearLayout.LayoutParams lkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lkLp.topMargin = UiKit.dp(this, 12);
        link.setLayoutParams(lkLp);
        link.setOnClickListener(new ExternalLinkClick("https://189.qaiu.top"));
        info.addView(link);
        box.addView(info);
        return sv;
    }

    // ================= Tab =================
    void navigate(String tab) {
        currentTab = tab;
        pageParse.setVisibility(tab.equals("parse") ? View.VISIBLE : View.GONE);
        pageHistory.setVisibility(tab.equals("history") ? View.VISIBLE : View.GONE);
        pageDownloads.setVisibility(tab.equals("downloads") ? View.VISIBLE : View.GONE);
        pageMine.setVisibility(tab.equals("mine") ? View.VISIBLE : View.GONE);
        updateTabStyles();
        if (tab.equals("history")) renderHistory();
        if (tab.equals("downloads")) {
            refreshTasks();
            startPolling();
        } else {
            stopPolling();
        }
        if (tab.equals("mine")) {
            apiKeyInput.setText(apiKey);
            threadsInput.setText(String.valueOf(threads));
        }
    }

    void updateTabStyles() {
        tabParse.setTextColor(currentTab.equals("parse") ? UiKit.ACCENT : UiKit.TEXT3);
        tabHistory.setTextColor(currentTab.equals("history") ? UiKit.ACCENT : UiKit.TEXT3);
        tabDownloads.setTextColor(currentTab.equals("downloads") ? UiKit.ACCENT : UiKit.TEXT3);
        tabMine.setTextColor(currentTab.equals("mine") ? UiKit.ACCENT : UiKit.TEXT3);
    }

    // ================= 链接识别 =================
    String[] extractShareInfo(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"'<>，。；、\\[\\]（）()《》【】]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        String url = "";
        while (m.find()) {
            String u = m.group();
            if (u.length() > url.length()) url = u;
        }
        if (url.length() == 0) return null;
        String pan = detectPanName(url);
        String pwd = "";
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("提取码[：:\\s]*([0-9a-zA-Z]{4,8})").matcher(text);
        if (m1.find()) pwd = m1.group(1);
        if (pwd.length() == 0) {
            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("(?:密码|访问码|口令)[：:\\s]*([0-9a-zA-Z]{4,8})").matcher(text);
            if (m2.find()) pwd = m2.group(1);
        }
        if (pwd.length() == 0) {
            java.util.regex.Matcher m3 = java.util.regex.Pattern
                    .compile("[#&?](?:pwd|code|passcode)=([0-9a-zA-Z]{4,8})", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url);
            if (m3.find()) pwd = m3.group(1);
        }
        return new String[]{url, pwd, pan};
    }

    String detectPanName(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return "";
            h = h.toLowerCase(Locale.ROOT);
            for (String[] d : PAN_DOMAINS) {
                if (h.equals(d[0]) || h.endsWith("." + d[0])) return d[1];
            }
        } catch (Exception e) { }
        return "";
    }

    // ================= 解析流程 =================
    void onParse() {
        final String raw = linkInput.getText().toString().trim();
        if (raw.length() == 0) { toast("请先粘贴网盘分享链接"); return; }
        String[] info = extractShareInfo(raw);
        final String url = (info != null && info[0].length() > 0) ? info[0] : raw;
        final String detectedPwd = (info != null) ? info[1] : "";
        final String detectedPan = (info != null) ? info[2] : "";
        String inputPwd = pwdInput.getText().toString().trim();
        final String pwd = (inputPwd.length() > 0) ? inputPwd : detectedPwd;
        if (!url.toLowerCase(Locale.ROOT).startsWith("http://")
                && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            toast("未识别到有效的网盘链接");
            return;
        }
        if (info != null && !raw.equals(url)) {
            linkInput.setText(url);
            if (detectedPwd.length() > 0 && inputPwd.length() == 0) pwdInput.setText(detectedPwd);
            showClipHint("已识别：" + (detectedPan.length() > 0 ? detectedPan : "网盘")
                    + "分享链接" + (detectedPwd.length() > 0 ? "，提取码已自动填入" : ""));
        }
        if (parseBusy) return;
        parseBusy = true;
        setParseLoading(true);
        resultArea.removeAllViews();
        appendLog("[INFO] 开始解析: " + url);
        pool.execute(new ParseTask(this, url, pwd));
    }

    void setParseLoading(boolean loading) {
        parseBusy = loading;
        btnParse.setText(loading ? "解析中…" : "开始解析");
        btnParse.setEnabled(!loading && linkInput.getText().toString().trim().length() > 0);
        btnParse.setBackground(UiKit.round(UiKit.dp(this, 12),
                (!loading && linkInput.getText().toString().trim().length() > 0) ? UiKit.ACCENT : UiKit.DISABLED_BG));
    }

    void onUiParseResult(JSONObject linkInfo, JSONArray fileList, String url, String pwd) {
        try {
            String panType = "";
            String panName = "";
            String otherRaw = "null";
            if (linkInfo != null && linkInfo.has("shareLinkInfo")) {
                JSONObject sl = linkInfo.getJSONObject("shareLinkInfo");
                panType = sl.optString("type", "");
                panName = sl.optString("panName", "");
                if (sl.has("otherParam")) otherRaw = sl.getJSONObject("otherParam").toString();
            }
            if (fileList != null && fileList.length() > 0) {
                // 目录模式
                currentTreePan = panType;
                currentTreePanName = panName;
                currentTreeOther = otherRaw;
                treeRootUrl = url;
                treeRootPwd = pwd;
                selectedItems.clear();
                renderTree(fileList);
                history.add(url, now(), (panName.length() > 0 ? panName : "网盘") + " 目录");
                appendLog("[INFO] 目录解析成功: " + panName + " " + fileList.length() + " 项");
                setParseLoading(false);
            } else {
                // 单文件模式
                pool.execute(new ParseJsonTask(MainActivity.this, url, pwd, panType, panName, otherRaw));
            }
        } catch (Exception e) {
            renderError("解析失败：" + e.getMessage());
            setParseLoading(false);
        }
    }

    void onUiSingleResult(JSONObject data, String shareUrl, String pwd, String panType, String panName, String otherRaw) {
        try {
            currentResult = data;
            JSONObject fi = data.has("fileInfo") ? data.getJSONObject("fileInfo") : null;
            String fileName = fi != null ? fi.optString("fileName", "") : "";
            String sizeStr = fi != null ? fi.optString("sizeStr", "") : "";
            String directLink = data.optString("directLink", "");
            String name = fileName.length() > 0 ? fileName : "解析结果";
            renderSingleResult(name, sizeStr, directLink, panType, panName, otherRaw);
            history.add(shareUrl, now(), name);
            appendLog("[INFO] 单文件解析成功: " + name);
        } catch (Exception e) {
            renderError("解析失败：" + e.getMessage());
        }
        setParseLoading(false);
    }

    void renderError(String msg) {
        resultArea.removeAllViews();
        LinearLayout card = UiKit.card(this);
        card.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 24), UiKit.dp(this, 20), UiKit.dp(this, 24));
        card.setGravity(Gravity.CENTER);
        card.addView(UiKit.tv(this, "解析失败", 16, UiKit.ERR, Typeface.BOLD));
        TextView sub = UiKit.tv(this, msg, 13, UiKit.TEXT2, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = UiKit.dp(this, 8);
        slp.gravity = Gravity.CENTER;
        sub.setLayoutParams(slp);
        card.addView(sub);
        resultArea.addView(card);
    }

    // ================= 单文件结果 =================
    void renderSingleResult(String name, String sizeStr, String downLink, String panType, String panName, String otherRaw) {
        resultArea.removeAllViews();
        LinearLayout card = UiKit.card(this);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16));
        LinearLayout head = UiKit.hbox(this);
        head.addView(UiKit.tv(this, "解析结果", 15, UiKit.TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (panName.length() > 0) {
            Button tag = UiKit.chip(this, panName, true);
            head.addView(tag);
        }
        card.addView(head);

        TextView fn = UiKit.tv(this, name, 15, UiKit.TEXT, Typeface.BOLD);
        LinearLayout.LayoutParams fnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fnLp.topMargin = UiKit.dp(this, 12);
        fn.setLayoutParams(fnLp);
        card.addView(fn);
        if (sizeStr.length() > 0) {
            card.addView(UiKit.tv(this, sizeStr, 12, UiKit.TEXT2, Typeface.NORMAL));
        }

        Button dl = UiKit.btn(this, "下载", UiKit.ACCENT, Color.WHITE);
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlLp.topMargin = UiKit.dp(this, 14);
        dl.setLayoutParams(dlLp);
        dl.setOnClickListener(new SingleDownloadClick(downLink, name, panType, otherRaw));
        card.addView(dl);

        if (downLink.length() > 0) {
            Button copy = UiKit.ghostBtn(this, "复制直链");
            LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cpLp.topMargin = UiKit.dp(this, 8);
            copy.setLayoutParams(cpLp);
            copy.setOnClickListener(new CopyClick(downLink));
            card.addView(copy);
        }
        resultArea.addView(card);
    }

    // ================= 目录树 =================
    void renderTree(JSONArray items) {
        resultArea.removeAllViews();
        LinearLayout card = UiKit.card(this);
        card.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        LinearLayout head = UiKit.hbox(this);
        head.addView(UiKit.tv(this, "目录文件", 15, UiKit.TEXT, Typeface.BOLD), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (currentTreePanName.length() > 0) {
            head.addView(UiKit.chip(this, currentTreePanName, true));
        }
        card.addView(head);

        treeSummary = UiKit.tv(this, "", 12, UiKit.TEXT2, Typeface.NORMAL);
        card.addView(treeSummary);

        treeListBox = UiKit.vbox(this);
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = UiKit.dp(this, 6);
        treeListBox.setLayoutParams(tlLp);
        card.addView(treeListBox);

        // 批量下载条
        batchBar = UiKit.hbox(this);
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bbLp.topMargin = UiKit.dp(this, 10);
        batchBar.setLayoutParams(bbLp);
        batchBar.addView(UiKit.tv(this, "点击文件勾选，可批量下载", 12, UiKit.TEXT2, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button dlSel = UiKit.btn(this, "下载已选(0)", UiKit.ACCENT, Color.WHITE);
        dlSel.setEnabled(false);
        dlSel.setOnClickListener(new BatchDownloadClick());
        batchBar.addView(dlSel);
        card.addView(batchBar);

        resultArea.addView(card);
        renderTreeItems(items, treeListBox, 0);
        renderTreeSummary();
        renderBatchBar();
    }

    TextView treeSummary;
    LinearLayout treeListBox;
    LinearLayout batchBar;

    void renderTreeItems(JSONArray items, LinearLayout container, int depth) {
        container.removeAllViews();
        for (int i = 0; i < items.length(); i++) {
            try {
                final JSONObject item = items.getJSONObject(i);
                final String fileName = item.optString("fileName", "");
                final String fileType = item.optString("fileType", "");
                final boolean isFolder = fileType.equalsIgnoreCase("folder");
                final String idKey = item.optString("fileId", item.optString("parserUrl", ""));
                final String sizeStr = item.optString("sizeStr", "");
                final String parserUrl = item.optString("parserUrl", "");

                LinearLayout row = UiKit.hbox(this);
                row.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(isSelected(idKey) ? UiKit.round(UiKit.dp(this, 8), UiKit.ACCENT_D)
                        : UiKit.round(UiKit.dp(this, 8), 0));
                // 缩进
                if (depth > 0) row.setPadding(UiKit.dp(this, 4) + UiKit.dp(this, 16) * depth,
                        UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10));

                TextView icon = UiKit.tv(this, isFolder ? "▸" : "▤", 16, UiKit.TEXT2, Typeface.NORMAL);
                row.addView(icon);

                LinearLayout main = UiKit.vbox(this);
                main.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                main.setPadding(UiKit.dp(this, 10), 0, 0, 0);
                TextView nm = UiKit.tv(this, fileName, 14, UiKit.TEXT, Typeface.NORMAL);
                main.addView(nm);
                String tag = isFolder ? "目录" : fileTypeTag(fileType);
                if (sizeStr.length() > 0) tag = tag + " · " + sizeStr;
                if (tag.length() > 0) {
                    TextView sub = UiKit.tv(this, tag, 11, UiKit.TEXT2, Typeface.NORMAL);
                    main.addView(sub);
                }
                row.addView(main);

                Button dlBtn = UiKit.ghostBtn(this, "下载");
                dlBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                dlBtn.setOnClickListener(new TreeItemDownloadClick(item, parserUrl, fileName));
                row.addView(dlBtn);

                row.setOnClickListener(new TreeItemClick(isFolder, item, idKey, parserUrl, row));
                container.addView(row);
            } catch (Exception e) { }
        }
    }

    String fileTypeTag(String ft) {
        if (ft == null || ft.length() == 0) return "";
        String f = ft.toLowerCase(Locale.ROOT);
        if (f.contains("video") || f.equals("mp4") || f.equals("mkv") || f.equals("avi")
                || f.equals("mov") || f.equals("ts") || f.equals("rmvb") || f.equals("flv")) return "视频";
        if (f.contains("audio") || f.equals("mp3") || f.equals("flac") || f.equals("wav") || f.equals("m4a")) return "音频";
        if (f.equals("zip") || f.equals("rar") || f.equals("7z") || f.equals("tar") || f.equals("gz")) return "压缩包";
        if (f.contains("image") || f.equals("jpg") || f.equals("png") || f.equals("gif")
                || f.equals("webp") || f.equals("bmp") || f.equals("jpeg")) return "图片";
        if (f.equals("pdf")) return "PDF";
        if (f.contains("document") || f.equals("doc") || f.equals("docx")) return "文档";
        return f.toUpperCase(Locale.ROOT);
    }

    boolean isSelected(String idKey) {
        return idKey != null && selectedItems.containsKey(idKey);
    }

    void renderTreeSummary() {
        int count = 0;
        long total = 0;
        for (JSONObject it : selectedItems.values()) {
            if (!it.optString("fileType", "").equalsIgnoreCase("folder")) count++;
        }
        treeSummary.setText(count > 0 ? "已选 " + count + " 项" : "点击文件勾选，可批量下载");
    }

    void renderBatchBar() {
        if (batchBar == null) return;
        int count = 0;
        for (JSONObject it : selectedItems.values()) {
            if (!it.optString("fileType", "").equalsIgnoreCase("folder")) count++;
        }
        TextView info = (TextView) batchBar.getChildAt(0);
        info.setText(count > 0 ? "已选 " + count + " 项" : "点击文件勾选，可批量下载");
        Button b = (Button) batchBar.getChildAt(1);
        b.setText("下载已选(" + count + ")");
        b.setEnabled(count > 0);
        b.setBackground(UiKit.round(UiKit.dp(this, 12), count > 0 ? UiKit.ACCENT : UiKit.DISABLED_BG));
    }

    void toggleSelect(String idKey, JSONObject item) {
        if (idKey == null || idKey.length() == 0) return;
        if (selectedItems.containsKey(idKey)) selectedItems.remove(idKey);
        else selectedItems.put(idKey, item);
        renderTreeSummary();
        // 简单重渲染结果区（保持勾选高亮）
        refreshTreeSelection();
        renderBatchBar();
    }

    void refreshTreeSelection() {
        if (treeListBox == null) return;
        // 只刷新高亮：不重绘（重绘会丢失展开状态）。此处用简化方案：遍历行重新上色
        LinearLayout card = (LinearLayout) resultArea.getChildAt(0);
        // 直接重渲染整树（展开态丢失可接受，目录模式体验优先保证勾选可见）
        // 为保持简洁：重渲染当前已加载的 items 由调用方负责，这里仅更新 summary
    }

    // ================= Gopeed =================
    void createTask(String url, String name, JSONObject headers) {
        try {
            JSONObject extra = new JSONObject();
            extra.put("method", "");
            extra.put("header", headers == null ? new JSONObject() : headers);
            extra.put("body", "");
            JSONObject req = new JSONObject();
            req.put("url", url);
            req.put("extra", extra);
            JSONObject optsExtra = new JSONObject();
            optsExtra.put("connections", threads);
            JSONObject opts = new JSONObject();
            opts.put("name", name == null ? "" : name);
            opts.put("path", "");
            opts.put("extra", optsExtra);
            JSONObject body = new JSONObject();
            body.put("req", req);
            body.put("opts", opts);
            appendLog("[INFO] 创建下载任务: " + url);
            pool.execute(new CreateTaskTask(this, url, name, body.toString()));
        } catch (Exception e) {
            toast("创建任务失败：" + e.getMessage());
        }
    }

    void refreshTasks() {
        pool.execute(new FetchTasksTask(this));
    }

    void renderTasks() {
        taskListBox.removeAllViews();
        if (!gopeedOnline) {
            LinearLayout card = UiKit.card(this);
            card.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 24), UiKit.dp(this, 20), UiKit.dp(this, 24));
            card.setGravity(Gravity.CENTER);
            card.addView(UiKit.tv(this, "引擎未连接", 15, UiKit.TEXT, Typeface.BOLD));
            TextView sub = UiKit.tv(this, "请检查服务是否启动，可到「我的」查看日志", 12, UiKit.TEXT2, Typeface.NORMAL);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = UiKit.dp(this, 6);
            slp.gravity = Gravity.CENTER;
            sub.setLayoutParams(slp);
            card.addView(sub);
            taskListBox.addView(card);
            return;
        }
        if (taskList.length() == 0) {
            LinearLayout card = UiKit.card(this);
            card.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 24), UiKit.dp(this, 20), UiKit.dp(this, 24));
            card.setGravity(Gravity.CENTER);
            card.addView(UiKit.tv(this, "暂无下载任务", 14, UiKit.TEXT2, Typeface.NORMAL));
            taskListBox.addView(card);
            return;
        }
        try {
            // 按创建时间倒序
            java.util.List<JSONObject> sorted = new ArrayList<JSONObject>();
            for (int i = 0; i < taskList.length(); i++) sorted.add(taskList.getJSONObject(i));
            java.util.Collections.sort(sorted, new TaskSort());
            for (JSONObject t : sorted) {
                taskListBox.addView(renderTaskCard(t));
            }
        } catch (Exception e) { }
    }

    View renderTaskCard(JSONObject t) {
        final String id = t.optString("id", "");
        final String status = t.optString("status", "");
        String name = t.optString("name", "未命名");
        long total = 0, done = 0, speed = 0;
        try {
            JSONObject meta = t.optJSONObject("meta");
            if (meta != null) {
                JSONObject res = meta.optJSONObject("res");
                if (res != null) total = res.optLong("size", 0);
            }
            JSONObject prog = t.optJSONObject("progress");
            if (prog != null) {
                done = prog.optLong("downloaded", 0);
                speed = prog.optLong("speed", 0);
            }
        } catch (Exception e) { }
        long pct = total > 0 ? Math.min(100, Math.round(done * 100.0 / total)) : 0;
        String[] sm = statusMeta(status);

        LinearLayout card = UiKit.card(this);
        card.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.dp(this, 10);
        card.setLayoutParams(clp);

        LinearLayout head = UiKit.hbox(this);
        LinearLayout nameBox = UiKit.vbox(this);
        nameBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView nm = UiKit.tv(this, name, 14, UiKit.TEXT, Typeface.BOLD);
        nameBox.addView(nm);
        TextView src = UiKit.tv(this, shortUrl(t.optString("meta_req_url", "")), 10, UiKit.TEXT3, Typeface.NORMAL);
        src.setMaxLines(1);
        nameBox.addView(src);
        head.addView(nameBox);
        Button badge = UiKit.chip(this, sm[1], sm[0].equals("ok"));
        if (sm[0].equals("err")) badge.setTextColor(UiKit.ERR);
        head.addView(badge);
        card.addView(head);

        // 进度条
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) (status.equals("done") ? 100 : pct));
        bar.setPadding(0, UiKit.dp(this, 8), 0, 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 6));
        bar.setLayoutParams(blp);
        bar.getProgressDrawable().setColorFilter(
                sm[0].equals("err") ? UiKit.ERR : sm[0].equals("pause") ? UiKit.TEXT3 : UiKit.ACCENT,
                android.graphics.PorterDuff.Mode.SRC_IN);
        card.addView(bar);

        // 进度行
        LinearLayout progRow = UiKit.hbox(this);
        progRow.setGravity(Gravity.CENTER_VERTICAL);
        progRow.addView(UiKit.tv(this, fmtSize(done) + " / " + (total > 0 ? fmtSize(total) : "未知"),
                11, UiKit.TEXT2, Typeface.NORMAL), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progRow.addView(UiKit.tv(this, status.equals("done") ? "100%" : pct + "%",
                11, UiKit.TEXT, Typeface.BOLD));
        card.addView(progRow);

        // 底部操作
        LinearLayout foot = UiKit.hbox(this);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(UiKit.tv(this, fmtSpeed(speed), 11, UiKit.OK, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (status.equals("running") || status.equals("wait")) {
            Button pause = opBtn("暂停");
            pause.setOnClickListener(new TaskOpClick(id, "pause"));
            foot.addView(pause);
        }
        if (status.equals("pause") || status.equals("error")) {
            Button resume = opBtn("继续");
            resume.setOnClickListener(new TaskOpClick(id, "resume"));
            foot.addView(resume);
        }
        Button del = opBtn("删除");
        del.setTextColor(UiKit.ERR);
        del.setOnClickListener(new TaskOpClick(id, "delete"));
        foot.addView(del);
        card.addView(foot);
        return card;
    }

    Button opBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setTextColor(UiKit.TEXT2);
        b.setBackground(UiKit.round(UiKit.dp(this, 99), UiKit.SURFACE2));
        b.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 5), UiKit.dp(this, 12), UiKit.dp(this, 5));
        b.setMinWidth(0);
        return b;
    }

    String shortUrl(String u) {
        if (u == null || u.length() == 0) return "";
        String s = u.replace("http://127.0.0.1:18090/dl/?url=", "");
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    String[] statusMeta(String s) {
        if (s.equals("done")) return new String[]{"ok", "完成"};
        if (s.equals("running")) return new String[]{"ok", "下载中"};
        if (s.equals("wait")) return new String[]{"ok", "等待中"};
        if (s.equals("pause")) return new String[]{"pause", "已暂停"};
        if (s.equals("error")) return new String[]{"err", "错误"};
        return new String[]{"", s};
    }

    void startPolling() {
        if (polling) return;
        polling = true;
        ui.postDelayed(new PollRunnable(), 3000);
    }

    void stopPolling() {
        polling = false;
    }

    // ================= 工具 =================
    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    String now() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date());
    }

    String nowFull() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }

    void appendLog(String line) {
        try {
            FileWriter fw = new FileWriter(LOG_FILE, true);
            fw.write(nowFull() + " " + line + "\n");
            fw.close();
            Log.i(TAG, line);
        } catch (Exception e) {
            Log.i(TAG, line);
        }
    }

    String readLog() {
        try {
            File f = new File(LOG_FILE);
            if (!f.exists()) return "暂无日志";
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 200);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "读取日志失败：" + e.getMessage();
        }
    }

    void showLogDialog() {
        final android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setMessage(readLog())
                .setPositiveButton("关闭", null)
                .create();
        d.show();
        TextView tv = d.findViewById(android.R.id.message);
        if (tv != null) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTextIsSelectable(false);
        }
    }

    void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", s));
            toast("已复制");
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence cs = cd.getItemAt(0).coerceToText(this);
            return cs == null ? "" : cs.toString();
        } catch (Exception e) {
            return "";
        }
    }

    void gotoDownloads() {
        navigate("downloads");
        toast("已发送到 Gopeed");
    }

    // ================= JSON 工具 =================
    JSONObject antiHotlinkHeader(String panType, String otherRaw) {
        JSONObject h = new JSONObject();
        String pan = panType == null ? "" : panType.toLowerCase(Locale.ROOT);
        try {
            if (pan.equals("cow")) h.put("Referer", "https://cowtransfer.com/");
            else if (pan.equals("pcx")) h.put("Referer", "https://pan-yz.chaoxing.com");
            else if (pan.equals("qk")) h.put("Referer", "https://pan.quark.cn/");
            else if (pan.equals("uc")) h.put("Referer", "https://pan.uc.cn/");
            if (pan.equals("qk") || pan.equals("uc")) h.put("User-Agent", UA_CHROME);
            if (otherRaw != null && otherRaw.length() > 0 && !otherRaw.equals("null")) {
                JSONObject o = new JSONObject(otherRaw);
                if (o.has("Referer")) h.put("Referer", o.getString("Referer"));
                if (pan.equals("fj") && o.has("UA")) h.put("User-Agent", o.getString("UA"));
                if (o.has("header")) {
                    JSONObject oh = o.getJSONObject("header");
                    java.util.Iterator<String> keys = oh.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if (k.equalsIgnoreCase("cookie")) {
                            String v = oh.optString(k, "");
                            if (v.length() > 0 && v.contains("=")) h.put(k, v);
                        } else {
                            h.put(k, oh.getString(k));
                        }
                    }
                }
            }
        } catch (Exception e) { }
        return h;
    }

    String dlUrl(String url, String pan, String otherRaw) {
        String u = DL_BASE + "/?url=" + NetApi.enc(url);
        try {
            if (pan != null && pan.equals("fj") && otherRaw != null) {
                JSONObject o = new JSONObject(otherRaw);
                if (o.has("UA")) u += "&ua=" + NetApi.enc(o.getString("UA"));
            }
        } catch (Exception e) { }
        return u;
    }

    String fmtSize(long b) {
        if (b <= 0) return "0B";
        if (b < 1024) return b + "B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1fKB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1fMB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2fGB", gb);
    }

    String fmtSpeed(long bps) {
        if (bps <= 0) return "0B/s";
        return fmtSize(bps) + "/s";
    }

    // ================= 静态内部类：服务启动 =================
    static class ServiceStarter implements Runnable {
        private final String dataDir;
        ServiceStarter(String d) { dataDir = d; }
        public void run() {
            for (int i = 0; i < 5; i++) {
                try {
                    String r = StartServer(dataDir);
                    Log.i(TAG, "StartServer: " + r);
                    MainActivity.self.appendLog("[INFO] 内置服务已启动: " + r);
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "StartServer retry", t);
                    try { Thread.sleep(1500); } catch (InterruptedException e) { }
                }
            }
        }
    }

    static MainActivity self;
    // （self 在 onCreate 赋值）

    // ================= 点击监听（命名类） =================
    static class TabClick implements View.OnClickListener {
        private final String tab;
        TabClick(String t) { tab = t; }
        public void onClick(View v) {
            MainActivity a = self;
            if (a != null) a.navigate(tab);
        }
    }

    static class ReadClipClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            String clip = a.readClipboard();
            if (clip.length() == 0) {
                a.toast("剪贴板为空");
                return;
            }
            String[] info = a.extractShareInfo(clip);
            if (info != null && info[0].length() > 0) {
                a.linkInput.setText(info[0]);
                if (info[1].length() > 0) a.pwdInput.setText(info[1]);
                a.showClipHint("已识别：" + (info[2].length() > 0 ? info[2] : "网盘")
                        + "分享链接" + (info[1].length() > 0 ? "，提取码已自动填入" : ""));
            } else {
                a.linkInput.setText(clip);
                a.showClipHint("已粘贴剪贴板内容");
            }
        }
    }

    static class ClearClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            a.linkInput.setText("");
            a.pwdInput.setText("");
            a.showClipHint("");
            a.resultArea.removeAllViews();
            a.selectedItems.clear();
            a.currentResult = null;
        }
    }

    static class ParseClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a != null) a.onParse();
        }
    }

    static class SyncInputWatcher implements TextWatcher {
        public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
        public void onTextChanged(CharSequence s, int st, int c, int af) { }
        public void afterTextChanged(Editable s) {
            MainActivity a = self;
            if (a == null) return;
            boolean has = a.linkInput.getText().toString().trim().length() > 0;
            a.btnParse.setEnabled(!a.parseBusy && has);
            a.btnParse.setBackground(UiKit.round(UiKit.dp(a, 12),
                    (!a.parseBusy && has) ? UiKit.ACCENT : UiKit.DISABLED_BG));
            boolean hasAny = has || a.pwdInput.getText().toString().trim().length() > 0
                    || a.resultArea.getChildCount() > 0;
            a.btnClear.setEnabled(hasAny);
        }
    }

    static class ClearHistoryClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            a.history.clear();
            a.renderHistory();
            a.toast("历史已清空");
        }
    }

    static class HistoryRowClick implements View.OnClickListener {
        private final String url;
        HistoryRowClick(String u) { url = u; }
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            a.navigate("parse");
            a.linkInput.setText(url);
        }
    }

    static class RefreshClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a != null) a.refreshTasks();
        }
    }

    static class SaveSettingsClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            a.apiKey = a.apiKeyInput.getText().toString().trim();
            String th = a.threadsInput.getText().toString().trim();
            try {
                int t = Integer.parseInt(th);
                if (t < 1) t = 1;
                if (t > 16) t = 16;
                a.threads = t;
            } catch (Exception e) {
                a.threads = 4;
            }
            a.saveSettings();
            a.appendLog("[INFO] 设置已保存: threads=" + a.threads);
            a.toast("设置已保存");
        }
    }

    static class LogClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a != null) a.showLogDialog();
        }
    }

    static class ExternalLinkClick implements View.OnClickListener {
        private final String url;
        ExternalLinkClick(String u) { url = u; }
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                a.toast("无法打开浏览器");
            }
        }
    }

    static class CopyClick implements View.OnClickListener {
        private final String url;
        CopyClick(String u) { url = u; }
        public void onClick(View v) {
            MainActivity a = self;
            if (a != null) a.copyText(url);
        }
    }

    static class SingleDownloadClick implements View.OnClickListener {
        private final String downLink, name, pan, other;
        SingleDownloadClick(String d, String n, String p, String o) { downLink = d; name = n; pan = p; other = o; }
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null || downLink.length() == 0) return;
            JSONObject h = a.antiHotlinkHeader(pan, other);
            a.createTask(a.dlUrl(downLink, pan, other), name, h);
        }
    }

    static class TreeItemClick implements View.OnClickListener {
        private final boolean isFolder;
        private final JSONObject item;
        private final String idKey;
        private final String parserUrl;
        private final LinearLayout row;
        TreeItemClick(boolean f, JSONObject it, String id, String pu, LinearLayout r) {
            isFolder = f; item = it; idKey = id; parserUrl = pu; row = r;
        }
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            if (isFolder) {
                a.expandFolder(item, idKey, parserUrl);
            } else {
                a.toggleSelect(idKey, item);
                row.setBackground(a.isSelected(idKey)
                        ? UiKit.round(UiKit.dp(a, 8), UiKit.ACCENT_D)
                        : UiKit.round(UiKit.dp(a, 8), 0));
                a.renderBatchBar();
            }
        }
    }

    static class TreeItemDownloadClick implements View.OnClickListener {
        private final JSONObject item;
        private final String parserUrl, fileName;
        TreeItemDownloadClick(JSONObject it, String pu, String fn) { item = it; parserUrl = pu; fileName = fn; }
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            String pan = item.optString("panType", "");
            if (pan.length() == 0) pan = a.currentTreePan;
            JSONObject h = a.antiHotlinkHeader(pan, a.currentTreeOther);
            a.createTask(a.dlUrl(parserUrl, pan, a.currentTreeOther), fileName, h);
        }
    }

    static class TaskOpClick implements View.OnClickListener {
        private final String id, op;
        TaskOpClick(String i, String o) { id = i; op = o; }
        public void onClick(View v) {
            MainActivity a = self;
            if (a != null) a.taskOp(id, op);
        }
    }

    static class BatchDownloadClick implements View.OnClickListener {
        public void onClick(View v) {
            MainActivity a = self;
            if (a == null) return;
            int count = 0;
            for (JSONObject it : a.selectedItems.values()) {
                if (it.optString("fileType", "").equalsIgnoreCase("folder")) continue;
                String pan = it.optString("panType", "");
                if (pan.length() == 0) pan = a.currentTreePan;
                JSONObject h = a.antiHotlinkHeader(pan, a.currentTreeOther);
                a.createTask(a.dlUrl(it.optString("parserUrl", ""), pan, a.currentTreeOther),
                        it.optString("fileName", "file"), h);
                count++;
            }
            if (count == 0) a.toast("未选择文件");
            else a.toast("已发送 " + count + " 个任务到 Gopeed");
        }
    }

    void taskOp(String id, String op) {
        pool.execute(new TaskOpTask(this, id, op));
    }

    // ================= 目录展开 =================
    void expandFolder(final JSONObject item, final String idKey, final String parserUrl) {
        if (parserUrl == null || parserUrl.length() == 0) {
            toast("该目录无法展开");
            return;
        }
        pool.execute(new FolderLoadTask(this, item, idKey, parserUrl));
    }

    void onUiFolderLoaded(JSONObject item, String idKey, JSONArray children) {
        if (children == null || children.length() == 0) {
            toast("空目录或无法加载");
            return;
        }
        // 展开：替换当前列表为子项（简化交互，保证可用）
        if (treeListBox != null) {
            renderTreeItems(children, treeListBox, 0);
        }
    }

    // ================= 后台任务 =================
    static class ParseTask implements Runnable {
        final MainActivity a;
        final String url, pwd;
        ParseTask(MainActivity act, String u, String p) { a = act; url = u; pwd = p; }
        public void run() {
            try {
                JSONObject linkInfo = null;
                JSONArray fileList = null;
                try {
                    String q = "url=" + NetApi.enc(url);
                    if (a.apiKey.length() > 0) q += "&token=" + NetApi.enc(a.apiKey);
                    if (pwd.length() > 0) q += "&pwd=" + NetApi.enc(pwd);
                    linkInfo = new JSONObject(NetApi.get(PARSER_BASE + "/v2/linkInfo?" + q));
                    if (linkInfo.optInt("code", -1) != 200) linkInfo = null;
                    else linkInfo = linkInfo.optJSONObject("data");
                } catch (Exception e) { linkInfo = null; }
                try {
                    String q = "url=" + NetApi.enc(url);
                    if (a.apiKey.length() > 0) q += "&token=" + NetApi.enc(a.apiKey);
                    if (pwd.length() > 0) q += "&pwd=" + NetApi.enc(pwd);
                    String raw = NetApi.get(PARSER_BASE + "/v2/getFileList?" + q);
                    JSONObject j = new JSONObject(raw);
                    if (j.optInt("code", -1) == 200 && j.has("data") && j.get("data") instanceof JSONArray) {
                        fileList = j.getJSONArray("data");
                    }
                } catch (Exception e) { fileList = null; }
                a.ui.post(new UiTask(a, 0, linkInfo, fileList, url, pwd));
            } catch (Exception e) {
                a.ui.post(new UiTask(a, 3, "解析失败：" + e.getMessage()));
            }
        }
    }

    static class ParseJsonTask implements Runnable {
        final MainActivity a;
        final String url, pwd, panType, panName, otherRaw;
        ParseJsonTask(MainActivity act, String u, String p, String pt, String pn, String o) {
            a = act; url = u; pwd = p; panType = pt; panName = pn; otherRaw = o;
        }
        public void run() {
            try {
                String q = "url=" + NetApi.enc(url);
                if (a.apiKey.length() > 0) q += "&token=" + NetApi.enc(a.apiKey);
                if (pwd.length() > 0) q += "&pwd=" + NetApi.enc(pwd);
                String raw = NetApi.get(PARSER_BASE + "/json/parser?" + q);
                JSONObject j = new JSONObject(raw);
                if (j.optInt("code", -1) != 200) {
                    String msg = a.parseErrMsg(j.optInt("code", 0), j.optString("msg", ""));
                    a.ui.post(new UiTask(a, 3, "解析失败：" + msg));
                    return;
                }
                a.ui.post(new UiTask(a, 1, j.getJSONObject("data"), url, pwd, panType, panName, otherRaw));
            } catch (Exception e) {
                a.ui.post(new UiTask(a, 3, "解析失败：" + e.getMessage()));
            }
        }
    }

    String parseErrMsg(int code, String msg) {
        switch (code) {
            case 400: return "参数错误：请检查分享链接格式";
            case 401: return "API Key 无效，请在设置中检查";
            case 403: return "额度不足或无权限（403）";
            case 500: return "解析失败：分享链接可能已失效或网盘限制";
            default: return msg.length() > 0 ? msg : ("解析失败（" + code + "）");
        }
    }

    static class FolderLoadTask implements Runnable {
        final MainActivity a;
        final JSONObject item;
        final String idKey, parserUrl;
        FolderLoadTask(MainActivity act, JSONObject it, String id, String pu) { a = act; item = it; idKey = id; parserUrl = pu; }
        public void run() {
            try {
                // parserUrl 为解析站短链，转本地代理访问
                Uri u = Uri.parse(parserUrl);
                String local = PARSER_BASE + u.getPath()
                        + (u.getQuery() != null ? "?" + u.getQuery() : "");
                String raw = NetApi.get(local);
                JSONObject j = new JSONObject(raw);
                JSONArray children = null;
                if (j.optInt("code", -1) == 200 && j.has("data") && j.get("data") instanceof JSONArray) {
                    children = j.getJSONArray("data");
                }
                a.ui.post(new UiTask(a, 2, item, idKey, children));
            } catch (Exception e) {
                a.ui.post(new UiTask(a, 2, item, idKey, null));
            }
        }
    }

    static class CreateTaskTask implements Runnable {
        final MainActivity a;
        final String url, name, body;
        CreateTaskTask(MainActivity act, String u, String n, String b) { a = act; url = u; name = n; body = b; }
        public void run() {
            try {
                String raw = NetApi.request(BASE + "/api/v1/tasks", "POST", body, 15000);
                JSONObject j = new JSONObject(raw);
                boolean ok = j.optInt("code", -1) == 0;
                if (ok) {
                    a.ui.post(new UiTask(a, 5, Boolean.TRUE, name, ""));
                } else {
                    a.ui.post(new UiTask(a, 5, Boolean.FALSE, name,
                            j.optString("msg", "未知错误")));
                }
            } catch (Exception e) {
                a.ui.post(new UiTask(a, 6, e.getMessage()));
            }
        }
    }

    static class FetchTasksTask implements Runnable {
        final MainActivity a;
        FetchTasksTask(MainActivity act) { a = act; }
        public void run() {
            try {
                String raw = NetApi.get(BASE + "/api/v1/tasks");
                JSONObject j = new JSONObject(raw);
                if (j.optInt("code", -1) == 0 && j.has("data")) {
                    JSONObject d = j.getJSONObject("data");
                    JSONArray arr = d.has("tasks") ? d.getJSONArray("tasks") : new JSONArray();
                    a.taskList = arr;
                    a.gopeedOnline = true;
                }
            } catch (Exception e) {
                a.gopeedOnline = false;
            }
            a.ui.post(new UiTask(a, 7));
        }
    }

    static class TaskOpTask implements Runnable {
        final MainActivity a;
        final String id, op;
        TaskOpTask(MainActivity act, String i, String o) { a = act; id = i; op = o; }
        public void run() {
            try {
                String url = BASE + "/api/v1/tasks/" + id;
                if (op.equals("pause")) NetApi.put(url + "/pause");
                else if (op.equals("resume")) NetApi.put(url + "/continue");
                else NetApi.delete(url);
                a.refreshTasks();
            } catch (Exception e) {
                a.appendLog("[ERROR] 任务操作失败 " + op + " " + e.getMessage());
            }
        }
    }

    static class PollRunnable implements Runnable {
        public void run() {
            MainActivity a = self;
            if (a == null || !a.polling) return;
            a.refreshTasks();
            a.ui.postDelayed(new PollRunnable(), 3000);
        }
    }

    static class TaskSort implements java.util.Comparator {
        public int compare(Object o1, Object o2) {
            JSONObject a = (JSONObject) o1;
            JSONObject b = (JSONObject) o2;
            return b.optString("createdAt", "").compareTo(a.optString("createdAt", ""));
        }
    }

    /** 底部手势区避让（命名类） */
    static class InsetSetter implements Runnable {
        final MainActivity a;
        final View v;
        InsetSetter(MainActivity act, View view) { a = act; v = view; }
        public void run() {
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    android.graphics.Insets insets = v.getRootWindowInsets() == null ? null
                            : v.getRootWindowInsets().getInsets(
                                    Build.VERSION.SDK_INT >= 30
                                            ? android.view.WindowInsets.Type.navigationBars()
                                            : 0);
                    int bottom = 0;
                    if (insets != null && Build.VERSION.SDK_INT >= 30) {
                        bottom = insets.bottom;
                    } else if (v.getRootWindowInsets() != null) {
                        bottom = v.getRootWindowInsets().getStableInsetBottom();
                    }
                    v.setPadding(UiKit.dp(a, 6), UiKit.dp(a, 6), UiKit.dp(a, 6),
                            UiKit.dp(a, 6) + Math.max(0, bottom));
                }
            } catch (Exception e) { }
        }
    }

    /** 主线程统一分发（命名类，规避 d8 对匿名类的 NPE 问题） */
    static class UiTask implements Runnable {
        final MainActivity a;
        final int action;
        final Object[] args;
        UiTask(MainActivity act, int act0, Object... a0) { a = act; action = act0; args = a0; }
        public void run() {
            switch (action) {
                case 0: a.onUiParseResult((JSONObject) args[0], (JSONArray) args[1], (String) args[2], (String) args[3]); break;
                case 1: a.onUiSingleResult((JSONObject) args[0], (String) args[1], (String) args[2], (String) args[3], (String) args[4], (String) args[5]); break;
                case 2: a.onUiFolderLoaded((JSONObject) args[0], (String) args[1], (JSONArray) args[2]); break;
                case 3: a.renderError((String) args[0]); a.setParseLoading(false); break;
                case 5:
                    if (((Boolean) args[0]).booleanValue()) {
                        a.appendLog("[INFO] 任务创建成功: " + args[1]);
                        a.gotoDownloads();
                    } else {
                        a.appendLog("[ERROR] 任务创建失败: " + args[2]);
                        a.toast("创建下载任务失败：" + args[2]);
                    }
                    break;
                case 6:
                    a.appendLog("[ERROR] 创建任务异常: " + args[0]);
                    a.toast("下载引擎异常，请到「我的」查看日志");
                    break;
                case 7: a.renderEngineState(); a.renderTasks(); break;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab.equals("downloads")) refreshTasks();
        else refreshTasks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        pool.shutdownNow();
    }
}
