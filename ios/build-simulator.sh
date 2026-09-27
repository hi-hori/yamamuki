#!/bin/bash
# Run with: bash ios/build-simulator.sh
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
  echo "iOS builds require macOS and Xcode. On Windows, use the iOS GitHub Actions workflow." >&2
  exit 1
fi

cd "$(dirname "$0")"
if ! xcrun --sdk iphonesimulator --show-sdk-path >/dev/null 2>&1; then
  echo "Install Xcode and its iOS platform, then select Xcode in Settings > Locations > Command Line Tools." >&2
  echo "Open Xcode once to complete first-launch setup." >&2
  exit 1
fi
if ! command -v xcodegen >/dev/null 2>&1; then
  echo "Install XcodeGen with: brew install xcodegen" >&2
  exit 1
fi

xcodebuild -version
swift --version
xcodegen --version
(cd YamamukiCore && swift test)
xcodegen generate
xcodebuild build \
  -project Yamamuki.xcodeproj \
  -scheme Yamamuki \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO \
  ONLY_ACTIVE_ARCH=NO \
  'ARCHS=arm64 x86_64'

echo "Built: $(pwd)/build/Build/Products/Debug-iphonesimulator/Yamamuki.app"
