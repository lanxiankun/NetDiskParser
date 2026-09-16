#!/bin/bash
set -e
export ANDROID_HOME=/home/user/Doubao/chats/38441058725195778/android-toolchain/sdk
export NDK=$ANDROID_HOME/ndk/25.2.9519653
export CLANG=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang
export CLANGPP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++
export PATH=/home/user/Doubao/chats/38441058725195778/go/bin:/home/user/Doubao/chats/38441058725195778/android-toolchain/jdk21/bin:$PATH
export GOTOOLCHAIN=local
export BT=$ANDROID_HOME/build-tools/34.0.0
export PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
ROOT=$(cd "$(dirname "$0")/.." && pwd)

cd $ROOT
GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=$CLANG CXX=$CLANGPP go build -trimpath -ldflags="-checklinkname=0 -s -w -extld=$CLANGPP -extldflags=-static-libstdc++" -buildmode=c-shared -o libnetdiskparser.so .
echo "[1/8] Go so OK"

cd $ROOT/android-shell
rm -rf build && mkdir -p build/classes build/gen
cp debug.keystore build/

$BT/aapt2 compile --dir res -o build/res.zip
echo "[2/8] aapt2 compile OK"

$BT/aapt2 link -o build/app-unsigned.apk -I $PLATFORM --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 -R build/res.zip --java build/gen -A assets
echo "[3/8] aapt2 link(含assets) OK"

javac -source 8 -target 8 -classpath $PLATFORM -d build/classes \
  MainActivity.java KeepAliveService.java LocalFileProvider.java DnodeBridge.java DnodeNode.java \
  build/gen/com/netdisk/parser2/R.java 2>&1 | grep -E "error" || true
echo "[4/8] javac OK"

$BT/d8 --release --lib $PLATFORM --output build/ $(find build/classes -name '*.class')
echo "[5/8] d8 OK"

ROOT="$ROOT" python3 <<'PYEOF'
import zipfile, shutil, os
root = os.environ["ROOT"] + "/android-shell"
src = root + "/build/app-unsigned.apk"
out = root + "/build/app-packed.apk"

zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
# 原条目（res/manifest/assets）保持压缩方式
for item in zin.infolist():
    data = zin.read(item.filename)
    if item.filename == "AndroidManifest.xml":
        zout.writestr(item, data)
    else:
        zout.writestr(item.filename, data, compress_type=item.compress_type)

# 我们的 classes.dex（纯 Java 节点，无 chaquopy）
zout.write(root + "/build/classes.dex", "classes.dex")

# 合并 arm64 so（STORED，页对齐由 zipalign -p 处理）
def add_so(srcpath, dst):
    with open(srcpath, "rb") as f:
        data = f.read()
    info = zipfile.ZipInfo(dst)
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o755 << 16
    zout.writestr(info, data)

add_so(root + "/../libnetdiskparser.so", "lib/arm64-v8a/libnetdiskparser.so")
zout.close(); zin.close()
print("[6/8] 合并OK: classes.dex + libnetdiskparser.so（已去除 Python/chaquopy）")
PYEOF

$BT/zipalign -f 16384 build/app-packed.apk build/app-aligned.apk
echo "[7/8] zipalign OK"
$BT/apksigner sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/NetDiskParser2-android-arm64.apk build/app-aligned.apk
$BT/apksigner verify build/NetDiskParser2-android-arm64.apk
echo "[8/8] SIGN OK"
cp build/NetDiskParser2-android-arm64.apk $ROOT/dist/
ls -la $ROOT/dist/NetDiskParser2-android-arm64.apk
