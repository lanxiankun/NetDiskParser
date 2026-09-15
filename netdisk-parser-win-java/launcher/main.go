// NetDiskParser Windows 启动器：定位自身目录，调用内置 JRE 启动应用
package main

import (
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	exe, _ := os.Executable()
	dir := filepath.Dir(exe)
	runtime := filepath.Join(dir, "runtime", "bin", "java.exe")

	cmd := exec.Command(runtime, "-cp", filepath.Join(dir, "lib", "netdisk-parser.jar"), "com.netdisk.parser.Main")
	cmd.Dir = dir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Run()
}
