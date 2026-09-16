package com.netdisk.parser2;

import android.Manifest;
import android.app.Activity;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.MimeTypeMap;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String TAG = "NetDiskParser2";
    private static final int REQ_STORAGE = 1001;
    private static final int REQ_DIR = 1003;

    static {
        try {
            System.loadLibrary("netdiskparser");
            Log.i(TAG, "native库加载成功");
        } catch (Throwable t) {
            Log.e(TAG, "native库加载失败: " + t, t);
        }
    }

    // 由内置 Go 库导出的 JNI 函数：启动解析服务 + Gopeed 下载引擎
    // dataDir 为 App 私有目录，用作配置/下载数据存储
    // tzOffsetMillis 为设备时区相对 UTC 的偏移（毫秒），Go 侧强制用本地时区写日志
    public static native void StartServer(String dataDir, int tzOffsetMillis);

    /** 崩溃日志写入私有目录（d8 需静态嵌套类，避免匿名内部类） */
    static class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Context app;
        CrashHandler(Context c) {
            app = c.getApplicationContext();
        }
        @Override
        public void uncaughtException(Thread thread, Throwable t) {
            try {
                File f = new File(app.getFilesDir(), "crash.log");
                FileOutputStream fos = new FileOutputStream(f, true);
                String head = "\n==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " ====\n" + thread + "\n";
                fos.write(head.getBytes("UTF-8"));
                fos.write(Log.getStackTraceString(t).getBytes("UTF-8"));
                fos.write("\n".getBytes("UTF-8"));
                fos.close();
            } catch (Exception ignored) {
            }
        }
    }

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全局崩溃日志：Java 层异常写入私有目录 crash.log，便于定位问题
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        // 剔除系统标题栏（应用名横幅）
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 沉浸式状态栏：状态栏透明，内容延伸至状态栏下，图标深色适配浅色界面
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
        Log.i(TAG, "onCreate: 开始启动内置服务");
        registerNetworkMonitor();
        ensureStoragePermission();
        requestNotifyPermission();
        startKeepAlive();

        try {
            StartServer(getFilesDir().getAbsolutePath(),
                    TimeZone.getDefault().getOffset(System.currentTimeMillis()));
            Log.i(TAG, "StartServer 调用完成（异步）");
        } catch (Throwable t) {
            Log.e(TAG, "StartServer 抛出异常: " + t, t);
            // Go 运行时启动失败时继续，由重试逻辑兜底
        }

        // 节点代理：默认常启内置 dnode 节点（123 网盘下载走节点加速）
        DnodeBridge.start(this);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // 强制禁止用户缩放（双指/双击）与缩放控件
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 隐藏滚动条
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        // 本地服务继续在 WebView 内加载；外部链接（如"我的"页的 189.qaiu.top）用系统浏览器打开
        webView.setWebViewClient(new ExtWebViewClient(this));
        webView.setWebChromeClient(new WebChromeClient());
        // 剪贴板桥：前端 JS 通过 window.AndroidBridge.getClipboard() 读取剪贴板文本
        webView.addJavascriptInterface(new JsBridge(this), "AndroidBridge");

        setContentView(webView);

        // 等待本地服务器就绪后加载界面
        new RetryLoadThread(this).start();
    }

    // ── 网络状态监听：切换网络/断开/恢复等关键事件写入会话日志（/logs 可查看）──
    static String lastNetType = "";
    private void registerNetworkMonitor(){
        try{
            final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm == null || Build.VERSION.SDK_INT < 24) return;
            lastNetType = currentNetType(cm);
            postNetLog("网络监听启动，当前网络: " + lastNetType);
            cm.registerDefaultNetworkCallback(new NetMonitorCallback(cm));
        }catch(Throwable t){
            Log.w(TAG, "网络监听注册失败: " + t);
        }
    }

    static String currentNetType(ConnectivityManager cm){
        try{
            Network n = cm.getActiveNetwork();
            if(n == null) return "无网络";
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if(caps != null) return describeNetwork(caps);
        }catch(Throwable ignored){}
        return "未知";
    }

    static String describeNetwork(NetworkCapabilities caps){
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动网络";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "有线";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        return "其他";
    }

    /** 网络事件写入会话日志（经 Go 侧 /app/pylog?tag=网络）；Go 服务未就绪时降级 Logcat */
    static void postNetLog(String msg){
        try{
            new Thread(new NetLogRunnable(msg)).start();
        }catch(Throwable ignored){}
    }

    static class NetMonitorCallback extends ConnectivityManager.NetworkCallback {
        final ConnectivityManager cm;
        NetMonitorCallback(ConnectivityManager cm){ this.cm = cm; }
        @Override public void onAvailable(Network n){
            postNetLog("网络已连接: " + currentNetType(cm));
        }
        @Override public void onLost(Network n){
            postNetLog("网络断开");
        }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps){
            String type = describeNetwork(caps);
            String last = lastNetType;
            if(!type.equals(last)){
                lastNetType = type;
                postNetLog("网络切换: " + (last.isEmpty() ? "无" : last) + " → " + type);
            }
        }
    }

    static class NetLogRunnable implements Runnable {
        final String msg;
        NetLogRunnable(String msg){ this.msg = msg; }
        @Override public void run(){
            try{
                URL u = new URL("http://127.0.0.1:18091/app/pylog?tag="
                        + URLEncoder.encode("网络", "UTF-8")
                        + "&msg=" + URLEncoder.encode(msg, "UTF-8"));
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(800);
                c.getInputStream().close();
            }catch(Throwable t){
                Log.i(TAG, "[网络日志] " + msg + "（Go 服务未就绪，降级 Logcat）");
            }
        }
    }

    // 申请公共下载目录写权限：
    // - Android 11+（API 30+）：需要"所有文件访问"权限，跳系统设置页引导开启
    // - Android 10 及以下：动态请求 WRITE_EXTERNAL_STORAGE
    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                Log.w(TAG, "需要所有文件访问权限，引导用户前往设置");
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                Log.i(TAG, "已授予所有文件访问权限");
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "请求 WRITE_EXTERNAL_STORAGE 权限");
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            } else {
                Log.i(TAG, "已授予存储权限");
            }
        }
    }

    // Android 13+ 通知权限（前台保活通知需要，拒绝则通知不显示但保活仍生效）
    private static final int REQ_NOTIFY = 1002;

    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "请求通知权限");
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            } else {
                Log.i(TAG, "已授予通知权限");
            }
        }
    }

    // 启动前台保活服务（常驻通知 + 后台下载进度），提升进程优先级防止下载被杀
    private void startKeepAlive() {
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Log.i(TAG, "前台保活服务已启动");
        } catch (Throwable t) {
            Log.e(TAG, "启动前台服务失败: " + t, t);
        }
    }

    // 外部链接打开器：非本机地址交给系统浏览器（静态嵌套类，规避 d8 匿名类解析问题）
    private static class ExtWebViewClient extends WebViewClient {
        private final Activity activity;
        ExtWebViewClient(Activity a){ activity = a; }
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url != null && (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) {
                return false;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                activity.startActivity(i);
            } catch (Exception e) {
                Log.e(TAG, "打开外部链接失败: " + e);
            }
            return true;
        }
    }

    // 剪贴板桥（静态嵌套类，绕开 d8 对匿名类的 NPE 问题）
    private static class JsBridge {
        private final MainActivity activity;

        JsBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void updateNotification(String title, String text, int progress) {
            KeepAliveService.update(title, text, progress);
        }

        @JavascriptInterface
        public String getClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String text = item.getText().toString();
                        if (text != null && text.length() > 0) {
                            return text;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "读取剪贴板失败: " + t);
            }
            return "";
        }

        /** 节点代理开关（下载配置）：启停内置 dnode 节点，状态持久化；返回 ok */
        @JavascriptInterface
        public void setNodeProxy(boolean enable) {
            activity.getSharedPreferences("ndp", MODE_PRIVATE)
                    .edit().putBoolean("node_proxy", enable).apply();
            try {
                if (enable) DnodeBridge.start(activity);
                else DnodeNode.stop();
                Log.i(TAG, "节点代理开关: " + (enable ? "开" : "关"));
            } catch (Throwable t) {
                Log.w(TAG, "节点启停失败: " + t);
            }
        }

        @JavascriptInterface
        public boolean getNodeProxy() {
            return activity.getSharedPreferences("ndp", MODE_PRIVATE)
                    .getBoolean("node_proxy", false);
        }

        /** 打开/安装已下载文件：content:// FileProvider 授权给外部应用，返回 ok / needInstallPermission / err:xxx */
        @JavascriptInterface
        public String openFile(String path) {
            try {
                java.io.File f = new java.io.File(path);
                if (!f.exists()) {
                    return "err:\u6587\u4ef6\u4e0d\u5b58\u5728";
                }
                Uri uri = Uri.parse("content://com.netdisk.parser2.fileprovider/?path=" + Uri.encode(path));
                Intent intent;
                if (path.toLowerCase().endsWith(".apk")) {
                    // APK 走系统安装器专属入口（ACTION_INSTALL_PACKAGE），避免被文件管理器等应用拦截弹"打开方式"
                    intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                    intent.setDataAndType(uri, "application/vnd.android.package-archive");
                    intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
                } else {
                    intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, guessMime(path));
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(activity.getPackageManager()) == null) {
                    return "err:\u6ca1\u6709\u53ef\u6253\u5f00\u8be5\u6587\u4ef6\u7684\u5e94\u7528";
                }
                activity.runOnUiThread(new OpenFileRun(activity, intent));
                return "ok";
            } catch (Throwable t) {
                Log.w(TAG, "打开文件失败: " + t);
                return "err:" + t.getMessage();
            }
        }

        /** 打开系统目录选择器（SAF）：用户选文件夹后回调 window.__dirPicked(真实路径)；取消则无回调 */
        @JavascriptInterface
        public void chooseDir() {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                activity.startActivityForResult(intent, REQ_DIR);
            } catch (Throwable t) {
                Log.w(TAG, "打开目录选择器失败: " + t);
            }
        }
    }

    /** 主线程启动外部打开 Intent（静态嵌套类，绕开 d8 匿名类限制） */
    private static class OpenFileRun implements Runnable {
        private final MainActivity activity;
        private final Intent intent;

        OpenFileRun(MainActivity activity, Intent intent) {
            this.activity = activity;
            this.intent = intent;
        }

        @Override
        public void run() {
            try {
                activity.startActivity(intent);
            } catch (Throwable t) {
                Log.w(TAG, "调起外部应用失败: " + t);
            }
        }
    }

    /** 按扩展名猜 MIME 类型 */
    private static String guessMime(String path) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(path);
        if (ext != null) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    // 轮询等待内置服务器就绪（最多约 18 秒）
    private static class RetryLoadThread extends Thread {
        private final MainActivity activity;

        RetryLoadThread(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            for (int i = 0; i < 60; i++) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
                if (canConnect()) {
                    Log.i(TAG, "第 " + i + " 次探测: 本地服务已就绪，加载界面");
                    activity.runOnUiThread(new UiLoad(activity));
                    return;
                }
                if (i == 15 || i == 40) {
                    Log.w(TAG, "第 " + i + " 次探测: 本地服务尚未就绪");
                }
            }
            Log.e(TAG, "60 次探测超时，本地服务始终未就绪");
        }

        private boolean canConnect() {
            try {
                java.net.Socket socket = new java.net.Socket("127.0.0.1", 18091);
                socket.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private static class UiLoad implements Runnable {
        private final MainActivity activity;

        UiLoad(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            Log.i(TAG, "加载 http://127.0.0.1:18091/");
            activity.webView.loadUrl("http://127.0.0.1:18091/");
        }
    }

    /** 目录选择结果：SAF tree Uri → 真实路径 → 回填前端 window.__dirPicked */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DIR || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        String path = treeUriToPath(tree);
        if (path == null) {
            Log.w(TAG, "目录选择器: 无法解析路径");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            Log.w(TAG, "持久授权失败: " + t);
        }
        String js = "window.__dirPicked && window.__dirPicked(" + org.json.JSONObject.quote(path) + ");";
        if (webView != null) {
            runOnUiThread(new DirPickRun(webView, js));
        }
    }

    /** SAF tree Uri → 真实文件路径：primary:相对路径 → /storage/emulated/0/相对路径 */
    private String treeUriToPath(Uri tree) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            if (docId == null) {
                return null;
            }
            String[] parts = docId.split(":", 2);
            String volume = parts[0];
            String rel = parts.length > 1 ? parts[1] : "";
            String base;
            if ("primary".equals(volume)) {
                base = Environment.getExternalStorageDirectory().getAbsolutePath();
            } else {
                base = "/storage/" + volume;
            }
            if (rel.isEmpty()) {
                return base;
            }
            return base + "/" + rel;
        } catch (Throwable t) {
            Log.w(TAG, "treeUriToPath 失败: " + t);
            return null;
        }
    }

    /** 主线程把选中的目录路径回填给前端（静态嵌套类，绕开 d8 匿名类限制） */
    private static class DirPickRun implements Runnable {
        private final WebView webView;
        private final String js;

        DirPickRun(WebView webView, String js) {
            this.webView = webView;
            this.js = js;
        }

        @Override
        public void run() {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Throwable t) {
                Log.w(TAG, "目录回填失败: " + t);
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            stopService(new Intent(this, KeepAliveService.class));
        } catch (Throwable t) {
            Log.w(TAG, "停止保活服务失败: " + t);
        }
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}
