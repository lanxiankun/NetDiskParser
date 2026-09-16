//go:build android

// 安卓版日志：写入 logcat（adb logcat -s NetDiskParser2 查看）
package main

/*
#include <android/log.h>
#include <stdlib.h>
static void logcat_info(const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, "NetDiskParser2", "%s", msg);
}
static void logcat_error(const char* msg) {
    __android_log_print(ANDROID_LOG_ERROR, "NetDiskParser2", "%s", msg);
}
*/
import "C"

import (
	"fmt"
	"unsafe"
)

func appLog(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	writeLogFile("INFO", msg)
	cmsg := C.CString(msg)
	defer C.free(unsafe.Pointer(cmsg))
	C.logcat_info(cmsg)
}

func appLogError(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	writeLogFile("ERROR", msg)
	cmsg := C.CString(msg)
	defer C.free(unsafe.Pointer(cmsg))
	C.logcat_error(cmsg)
}
