package com.netdisk.parser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 前台保活服务：常驻通知栏提升进程优先级（后台下载不中断），
 * 每 2 秒轮询内置 Gopeed 任务列表，把下载进度实时更新到通知栏。
 */
public class KeepAliveService extends Service {
    private static final String TAG = "NetDiskParser";
    private static final String CHANNEL_ID = "keepalive";
    private static final int NOTIFY_ID = 1001;

    private static KeepAliveService inst;
    private Handler handler;
    private PollTask pollTask;
    private String lastTitle = "云盘解析下载器";
    private String lastText = "后台运行中";
    private int lastPct = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotify(lastTitle, lastText, lastPct);
        startForeground(NOTIFY_ID, n);
        startPolling();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPolling();
        if (inst == this) {
            inst = null;
        }
        super.onDestroy();
    }

    /** 前端 JS 可即时更新通知（备用，正常由轮询驱动） */
    public static void update(String title, String text, int pct) {
        final KeepAliveService s = inst;
        if (s == null || s.handler == null) {
            return;
        }
        final String t = (title != null && title.length() > 0) ? title : s.lastTitle;
        final String x = (text != null && text.length() > 0) ? text : s.lastText;
        final int p = pct;
        s.handler.post(new NotifyRunnable(s, t, x, p));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "下载进度", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void startPolling() {
        stopPolling();
        pollTask = new PollTask(this);
        handler.post(pollTask);
    }

    private void stopPolling() {
        if (pollTask != null) {
            handler.removeCallbacks(pollTask);
            pollTask = null;
        }
    }

    /** 主线程调度：每 2 秒派发一次网络轮询 */
    private static class PollTask implements Runnable {
        private final KeepAliveService svc;

        PollTask(KeepAliveService svc) {
            this.svc = svc;
        }

        @Override
        public void run() {
            new Thread(new FetchRunnable(svc)).start();
            svc.handler.postDelayed(new PollTask(svc), 2000);
        }
    }

    /** 子线程：请求内置 Gopeed 任务列表并计算进度 */
    private static class FetchRunnable implements Runnable {
        private final KeepAliveService svc;

        FetchRunnable(KeepAliveService svc) {
            this.svc = svc;
        }

        @Override
        public void run() {
            String body = httpGet("http://127.0.0.1:18090/api/v1/tasks");
            if (body == null || body.length() == 0) {
                return;
            }
            try {
                JSONArray list = new JSONArray(body);
                int running = 0;
                int pctSum = 0;
                String name = "";
                double best = -1;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject t = list.optJSONObject(i);
                    if (t == null) {
                        continue;
                    }
                    String status = t.optString("status", "");
                    if (!"running".equals(status)) {
                        continue;
                    }
                    running++;
                    JSONObject meta = t.optJSONObject("meta");
                    JSONObject res = meta != null ? meta.optJSONObject("res") : null;
                    JSONObject prog = t.optJSONObject("progress");
                    long total = res != null ? res.optLong("size", 0) : 0;
                    long done = prog != null ? prog.optLong("downloaded", 0) : 0;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (pct > 100) {
                        pct = 100;
                    }
                    pctSum += pct;
                    double speed = prog != null ? prog.optDouble("speed", 0) : 0;
                    if (speed > best) {
                        best = speed;
                        name = t.optString("name", "");
                    }
                }
                if (running > 0) {
                    final String title = "正在下载（" + running + " 个任务）";
                    final String text = trunc(name, 16) + " " + (pctSum / running) + "%";
                    final int pct = pctSum / running;
                    svc.handler.post(new NotifyRunnable(svc, title, text, pct));
                } else {
                    svc.handler.post(new NotifyRunnable(svc, "云盘解析下载器", "后台运行中", -1));
                }
            } catch (Throwable t) {
                Log.w(TAG, "解析任务列表失败: " + t);
            }
        }

        private static String trunc(String s, int max) {
            if (s == null) {
                return "";
            }
            if (s.length() <= max) {
                return s;
            }
            return s.substring(0, max) + "…";
        }

        private static String httpGet(String url) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code != 200) {
                    return null;
                }
                InputStream is = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                is.close();
                return new String(bos.toByteArray(), "UTF-8");
            } catch (Throwable t) {
                return null;
            } finally {
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable t) {
                        // ignore
                    }
                }
            }
        }
    }

    /** 主线程：更新常驻通知 */
    private static class NotifyRunnable implements Runnable {
        private final KeepAliveService svc;
        private final String title;
        private final String text;
        private final int pct;

        NotifyRunnable(KeepAliveService svc, String title, String text, int pct) {
            this.svc = svc;
            this.title = title;
            this.text = text;
            this.pct = pct;
        }

        @Override
        public void run() {
            svc.lastTitle = title;
            svc.lastText = text;
            svc.lastPct = pct;
            try {
                NotificationManager nm = (NotificationManager) svc.getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.notify(NOTIFY_ID, svc.buildNotify(title, text, pct));
                }
            } catch (Throwable t) {
                Log.w(TAG, "通知更新失败: " + t);
            }
        }
    }

    private Notification buildNotify(String title, String text, int pct) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false);
        if (pct >= 0) {
            b.setProgress(100, pct, false);
        } else {
            b.setProgress(0, 0, false);
        }
        return b.build();
    }
}
