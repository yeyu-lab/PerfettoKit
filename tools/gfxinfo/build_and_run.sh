#!/bin/bash
# 编译并运行 GfxInfo macOS app
# 需要 Xcode Command Line Tools

DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$DIR/build/GfxInfo.app/Contents/MacOS"
EXECUTABLE="$APP_DIR/GfxInfo"

echo "🔨 编译中..."

mkdir -p "$APP_DIR"
mkdir -p "$DIR/build/GfxInfo.app/Contents"

# Info.plist
cat > "$DIR/build/GfxInfo.app/Contents/Info.plist" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>GfxInfo</string>
    <key>CFBundleIdentifier</key>
    <string>io.github.perfettokit.gfxinfo</string>
    <key>CFBundleName</key>
    <string>GfxInfo</string>
    <key>CFBundleVersion</key>
    <string>1.0</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>NSHighResolutionCapable</key>
    <true/>
</dict>
</plist>
EOF

# 编译
swiftc -o "$EXECUTABLE" \
    -parse-as-library \
    -framework SwiftUI \
    -framework AppKit \
    -framework Foundation \
    "$DIR/GfxInfoApp.swift" 2>&1

if [ $? -eq 0 ]; then
    echo "✅ 编译成功"
    echo "🚀 启动 GfxInfo..."
    open "$DIR/build/GfxInfo.app"
else
    echo "❌ 编译失败"
    exit 1
fi
