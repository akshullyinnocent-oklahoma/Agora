# Build Agora APK in Debian trixie (same base as F-Droid build server)
# Run this from YOUR OWN TERMINAL, not through Claude
$ErrorActionPreference = "Stop"
Set-Location "F:\workspace\repo\Agora"

# Build Docker image
Write-Host "Building Docker image..." -ForegroundColor Yellow
docker build -t agora-fdroid -f Dockerfile.fdroid .

# Build APK in container (SDK mounted directly from WSL)
Write-Host "Building APK..." -ForegroundColor Yellow
docker run --rm `
  -v "F:\workspace\repo\Agora:/build" `
  -v "\\wsl$\Arch\home\newoether\android-sdk:/opt/android-sdk" `
  -e ANDROID_HOME=/opt/android-sdk `
  -e ANDROID_SDK_ROOT=/opt/android-sdk `
  -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 `
  agora-fdroid `
  bash -c "rm -rf app/.cxx app/build && ./gradlew assembleRelease"

Write-Host "Done! APK: F:\workspace\repo\Agora\app\build\outputs\apk\release\app-release.apk" -ForegroundColor Green
