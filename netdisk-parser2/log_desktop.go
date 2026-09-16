//go:build !android

// 桌面版日志：输出到标准输出
package main

import (
	"fmt"
	"os"
)

func appLog(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	writeLogFile("INFO", msg)
	fmt.Printf("[%s] %s\n", AppName, msg)
}

func appLogError(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	writeLogFile("ERROR", msg)
	fmt.Fprintf(os.Stderr, "[%s] %s\n", AppName, msg)
}
