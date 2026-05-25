#!/bin/bash
set -e

echo "=== F-Droid Buildserver Environment ==="
cat /etc/os-release | head -3

echo "=== Removing EOL backports repo ==="
rm -f /etc/apt/sources.list.d/backports.list
sed -i '/bullseye-backports/d' /etc/apt/sources.list
sed -i '/bullseye-updates/d' /etc/apt/sources.list
apt-get update -qq 2>&1 | tail -3

echo "=== Installing Java 17 ==="
apt-get install -y -qq openjdk-17-jdk-headless 2>&1 | tail -3
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
java -version 2>&1

echo "=== Using pre-installed SDK from host ==="
ls /opt/android-sdk/ndk/
ls /opt/android-sdk/platforms/
ls /opt/android-sdk/build-tools/
ls /opt/android-sdk/cmake/

echo "=== Accepting SDK licenses ==="
yes | /opt/android-sdk/tools/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses 2>&1 | tail -5 || true

echo "=== Setting up environment ==="
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/28.2.13676358
export SOURCE_DATE_EPOCH=0

# Fix git dubious ownership for the mounted repo
git config --global --add safe.directory /agora
git config --global --add safe.directory /agora/thirdparty/llama.cpp

cat > /agora/local.properties << 'PROPEOF'
sdk.dir=/opt/android-sdk
PROPEOF

echo "=== Building APK ==="
cd /agora
./gradlew assembleRelease --no-daemon --stacktrace 2>&1

echo "=== Build complete ==="
echo "APK:"
ls -lh /agora/app/build/outputs/apk/release/
echo ""
echo ".so hashes (stripped):"
find /agora/app/build/intermediates/stripped_native_libs -name "*.so" -type f 2>/dev/null | while read f; do
    echo "$(sha256sum "$f" | cut -d' ' -f1)  $(basename $(dirname $f))/$(basename $f)"
done
echo ""
echo ".so hashes (unstripped):"
find /agora/app/build/intermediates/cxx -name "*.so" -type f 2>/dev/null | while read f; do
    echo "$(sha256sum "$f" | cut -d' ' -f1)  $(basename $(dirname $f))/$(basename $f)"
done
