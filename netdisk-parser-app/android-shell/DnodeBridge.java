package com.netdisk.parser;

import android.content.Context;

/**
 * 节点桥（Java 版）：直接启动纯 Java dnode 节点（DnodeNode），
 * 无 Python/chaquopy 依赖，协议与官方 node_clientv4.py 完全等价。
 */
public class DnodeBridge {
    public static void start(Context ctx) {
        DnodeNode.start(ctx);
    }
}
