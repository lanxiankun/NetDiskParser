package com.netdisk.parser;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

/** NetDiskParser Windows 原生版入口：JavaFX 窗口 + 内嵌 HTTP 服务 */
public class Main extends Application {

    @Override
    public void start(Stage stage) {
        // 启动内嵌服务（解析/下载/节点），端口 18090
        try { Server.start(); } catch (Exception e) { e.printStackTrace(); }

        WebView web = new WebView();
        web.getEngine().load("http://127.0.0.1:18090/");

        Scene scene = new Scene(web, 1100, 760);
        stage.setTitle("NetDiskParser 网盘解析下载");
        stage.setScene(scene);
        stage.show();

        // 调试截图：环境变量 NDP_SCREENSHOT=/path/x.png 时，2 秒后自动截图（无显示环境验证用）
        String shot = System.getenv("NDP_SCREENSHOT");
        if (shot != null) {
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException e) {}
                Platform.runLater(() -> {
                    try {
                        WritableImage img = scene.snapshot(null);
                        javafx.scene.image.PixelReader pr = img.getPixelReader();
                        int w = (int) img.getWidth(), h = (int) img.getHeight();
                        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
                        java.io.File f = new java.io.File(shot);
                        javax.imageio.ImageIO.write(bi, "png", f);
                        System.out.println("[Main] 截图已保存: " + f.getAbsolutePath());
                        Platform.exit();
                    } catch (Exception e) { e.printStackTrace(); }
                });
            }).start();
        }
    }

    public static void main(String[] args) { launch(args); }
}
