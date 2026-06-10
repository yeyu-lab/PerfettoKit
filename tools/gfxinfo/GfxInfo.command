#!/bin/bash
# PerfettoKit GfxInfo Tool — 双击启动
# 确保 adb 在 PATH 中

DIR="$(cd "$(dirname "$0")" && pwd)"

# 添加常见的 adb 路径
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
export PATH="$PATH:/usr/local/bin"

python3 "$DIR/gfxinfo.py"
