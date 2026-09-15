//go:build android

// Android 入口：将 runServer 导出为 JNI 函数，由 Java WebView 壳调用
package main

/*
#cgo LDFLAGS: -landroid -llog
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>

// 辅助：把 jstring 转成 C 字符串（调用后必须 release）
static char* jstring_to_c(JNIEnv* env, jstring s) {
    if (s == NULL) return NULL;
    jboolean isCopy;
    const char* str = (*env)->GetStringUTFChars(env, s, &isCopy);
    return (char*)str;
}
static void release_c(JNIEnv* env, jstring s, char* c) {
    if (s != NULL && c != NULL) {
        (*env)->ReleaseStringUTFChars(env, s, (const char*)c);
    }
}
*/
import "C"

import (
	"unsafe"
)

//export Java_com_netdisk_parser_MainActivity_StartServer
func Java_com_netdisk_parser_MainActivity_StartServer(env *C.JNIEnv, clazz C.jobject, dir C.jstring) {
	appLog("JNI StartServer 被调用")
	dataDir := ""
	if unsafe.Pointer(dir) != nil {
		cstr := C.jstring_to_c(env, dir)
		if cstr != nil {
			dataDir = C.GoString(cstr)
			C.release_c(env, dir, cstr)
		}
	}
	appLog("应用数据目录: %s", dataDir)
	if err := runServer(dataDir); err != nil {
		appLogError("启动失败: %v", err)
		return
	}
	appLog("服务启动成功")
}
