#!/usr/bin/env python3
"""
PerfettoKit GfxInfo Tool — macOS 桌面端
一键获取 Android 帧率统计，等价于 dumpsys gfxinfo。

用法: 双击运行，或 python3 gfxinfo.py
"""

import subprocess
import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import threading
import re
import json
import os
import webbrowser
from datetime import datetime
from http.server import HTTPServer, SimpleHTTPRequestHandler


class GfxInfoApp:
    CHART_PORT = 8765
    DATA_DIR = os.path.dirname(os.path.abspath(__file__))
    DATA_FILE = os.path.join(DATA_DIR, "gfxinfo_history.json")
    HTML_FILE = os.path.join(DATA_DIR, "gfxinfo_chart.html")

    def __init__(self):
        self.root = tk.Tk()
        self.root.title("PerfettoKit GfxInfo")
        self.root.geometry("720x680")
        self.root.configure(bg="#1e1e1e")

        # 默认包名
        self.package_var = tk.StringVar(value="com.hualai")
        self.status_var = tk.StringVar(value="就绪")
        self.is_recording = False
        self.auto_collecting = False
        self.auto_interval = 3  # 秒
        self.chart_server = None
        self.chart_history = self._load_chart_history()

        self._build_ui()
        self._start_chart_server()

    def _build_ui(self):
        # 顶部：包名输入
        top_frame = tk.Frame(self.root, bg="#1e1e1e")
        top_frame.pack(fill=tk.X, padx=10, pady=(10, 5))

        tk.Label(top_frame, text="包名:", fg="#ccc", bg="#1e1e1e",
                 font=("SF Pro", 13)).pack(side=tk.LEFT)
        pkg_entry = tk.Entry(top_frame, textvariable=self.package_var,
                             font=("SF Mono", 13), bg="#2d2d2d", fg="#fff",
                             insertbackground="#fff", width=30)
        pkg_entry.pack(side=tk.LEFT, padx=(5, 10))

        # 检测设备按钮
        tk.Button(top_frame, text="🔍 检测设备", command=self._detect_device,
                  bg="#3a3a3a", fg="#fff", font=("SF Pro", 12),
                  highlightbackground="#3a3a3a").pack(side=tk.LEFT)

        self.device_label = tk.Label(top_frame, text="", fg="#888", bg="#1e1e1e",
                                     font=("SF Pro", 11))
        self.device_label.pack(side=tk.LEFT, padx=10)

        # 按钮区
        btn_frame = tk.Frame(self.root, bg="#1e1e1e")
        btn_frame.pack(fill=tk.X, padx=10, pady=5)

        btn_style = {"font": ("SF Pro", 13, "bold"), "width": 14, "height": 2}

        self.reset_btn = tk.Button(btn_frame, text="① Reset\n清除历史数据",
                                   command=self._reset, bg="#e67e22", fg="#fff",
                                   highlightbackground="#e67e22", **btn_style)
        self.reset_btn.pack(side=tk.LEFT, padx=5)

        self.capture_btn = tk.Button(btn_frame, text="② Capture\n抓取帧数据",
                                     command=self._capture, bg="#27ae60", fg="#fff",
                                     highlightbackground="#27ae60", **btn_style)
        self.capture_btn.pack(side=tk.LEFT, padx=5)

        self.one_click_btn = tk.Button(btn_frame, text="⚡ 一键测试\nReset+等5秒+抓取",
                                        command=self._one_click, bg="#2980b9", fg="#fff",
                                        highlightbackground="#2980b9", **btn_style)
        self.one_click_btn.pack(side=tk.LEFT, padx=5)

        self.framestats_btn = tk.Button(btn_frame, text="📊 Framestats\n逐帧原始数据",
                                         command=self._framestats, bg="#8e44ad", fg="#fff",
                                         highlightbackground="#8e44ad", **btn_style)
        self.framestats_btn.pack(side=tk.LEFT, padx=5)

        # 状态栏
        status_frame = tk.Frame(self.root, bg="#1e1e1e")
        status_frame.pack(fill=tk.X, padx=10, pady=2)
        self.status_label = tk.Label(status_frame, textvariable=self.status_var,
                                     fg="#888", bg="#1e1e1e", font=("SF Pro", 11),
                                     anchor=tk.W)
        self.status_label.pack(fill=tk.X)

        # 输出区
        self.output = scrolledtext.ScrolledText(
            self.root, font=("SF Mono", 12), bg="#0d0d0d", fg="#00ff88",
            insertbackground="#fff", wrap=tk.WORD, height=25
        )
        self.output.pack(fill=tk.BOTH, expand=True, padx=10, pady=(5, 10))

        # 底部：快捷操作
        bottom_frame = tk.Frame(self.root, bg="#1e1e1e")
        bottom_frame.pack(fill=tk.X, padx=10, pady=(0, 10))

        tk.Button(bottom_frame, text="📋 复制结果", command=self._copy_result,
                  bg="#3a3a3a", fg="#fff", font=("SF Pro", 11),
                  highlightbackground="#3a3a3a").pack(side=tk.LEFT, padx=5)
        tk.Button(bottom_frame, text="🗑 清空", command=self._clear,
                  bg="#3a3a3a", fg="#fff", font=("SF Pro", 11),
                  highlightbackground="#3a3a3a").pack(side=tk.LEFT, padx=5)
        tk.Button(bottom_frame, text="📱 进程列表", command=self._list_packages,
                  bg="#3a3a3a", fg="#fff", font=("SF Pro", 11),
                  highlightbackground="#3a3a3a").pack(side=tk.LEFT, padx=5)
        tk.Button(bottom_frame, text="📈 曲线图", command=self._open_chart,
                  bg="#16a085", fg="#fff", font=("SF Pro", 11),
                  highlightbackground="#16a085").pack(side=tk.LEFT, padx=5)
        self.auto_btn = tk.Button(bottom_frame, text="🔄 自动采集", command=self._toggle_auto_collect,
                  bg="#3a3a3a", fg="#fff", font=("SF Pro", 11),
                  highlightbackground="#3a3a3a")
        self.auto_btn.pack(side=tk.LEFT, padx=5)

        # 启动时检测设备
        self.root.after(500, self._detect_device)

    def _run_adb(self, args):
        """执行 adb 命令，返回输出"""
        try:
            result = subprocess.run(
                ["adb"] + args,
                capture_output=True, text=True, timeout=10
            )
            if result.returncode != 0:
                return f"ERROR: {result.stderr.strip()}"
            return result.stdout.strip()
        except FileNotFoundError:
            return "ERROR: adb 未找到。请确保 Android SDK platform-tools 在 PATH 中。"
        except subprocess.TimeoutExpired:
            return "ERROR: 命令超时"

    def _detect_device(self):
        output = self._run_adb(["devices"])
        lines = [l for l in output.split("\n") if "\tdevice" in l]
        if lines:
            device_id = lines[0].split("\t")[0]
            # 获取设备型号
            model = self._run_adb(["shell", "getprop", "ro.product.model"])
            self.device_label.config(text=f"✅ {model} ({device_id})", fg="#27ae60")
            self._set_status(f"设备已连接: {model}")
        else:
            self.device_label.config(text="❌ 未检测到设备", fg="#e74c3c")
            self._set_status("请连接 Android 设备并开启 USB 调试")

    def _reset(self):
        pkg = self.package_var.get().strip()
        output = self._run_adb(["shell", "dumpsys", "gfxinfo", pkg, "reset"])
        if "ERROR" in output:
            self._append_output(f"❌ {output}\n")
        else:
            self._append_output(f"✅ [{self._time()}] Reset 完成 — 现在去操作手机，然后点 Capture\n")
            self._set_status("已 Reset，等待操作...")

    def _capture(self):
        pkg = self.package_var.get().strip()
        self._set_status("正在抓取...")
        output = self._run_adb(["shell", "dumpsys", "gfxinfo", pkg])
        if "ERROR" in output:
            self._append_output(f"❌ {output}\n")
            return

        # 解析关键数据
        parsed = self._parse_gfxinfo(output)
        self._append_output(f"\n{'═' * 60}\n")
        self._append_output(f"📊 [{self._time()}] {pkg}\n")
        self._append_output(f"{'═' * 60}\n")
        self._append_output(parsed)
        self._append_output(f"{'═' * 60}\n\n")
        self._set_status("抓取完成")

        # 加入图表历史
        self._add_to_chart(output)

    def _one_click(self):
        """Reset → 等 5 秒 → Capture"""
        self._reset()
        self._set_status("⏱ 请操作手机... 5 秒后自动抓取")
        self._disable_buttons()

        def countdown(sec):
            if sec > 0:
                self._set_status(f"⏱ 请操作手机... {sec} 秒后自动抓取")
                self.root.after(1000, countdown, sec - 1)
            else:
                self._capture()
                self._enable_buttons()

        countdown(5)

    def _framestats(self):
        """获取逐帧数据并分析掉帧帧"""
        pkg = self.package_var.get().strip()
        self._set_status("正在获取 framestats...")
        output = self._run_adb(["shell", "dumpsys", "gfxinfo", pkg, "framestats"])
        if "ERROR" in output:
            self._append_output(f"❌ {output}\n")
            return

        # 解析 framestats 逐帧数据
        parsed = self._parse_framestats(output)
        self._append_output(parsed)
        self._set_status("Framestats 分析完成")

        # 加入图表历史
        self._add_to_chart(output)

    def _parse_gfxinfo(self, raw):
        """解析 gfxinfo 输出为可读格式"""
        lines = raw.split("\n")
        result = []

        for line in lines:
            line = line.strip()
            if any(k in line for k in [
                "Total frames", "Janky frames", "percentile",
                "Missed Vsync", "High input", "Slow UI",
                "Slow bitmap", "Slow issue", "Frame deadline"
            ]):
                # 高亮 Janky 和百分位
                if "Janky frames:" in line and "legacy" not in line:
                    pct = re.search(r'\(([\d.]+)%\)', line)
                    if pct:
                        val = float(pct.group(1))
                        icon = "🔴" if val > 20 else "🟡" if val > 10 else "🟢"
                        result.append(f"{icon} {line}")
                    else:
                        result.append(f"  {line}")
                elif "Slow" in line:
                    num = re.search(r':\s*(\d+)', line)
                    if num and int(num.group(1)) > 5:
                        result.append(f"  ⚠️  {line}")
                    else:
                        result.append(f"  ✓  {line}")
                elif "percentile" in line:
                    ms = re.search(r':\s*(\d+)ms', line)
                    if ms:
                        val = int(ms.group(1))
                        icon = "🔴" if val > 33 else "🟡" if val > 16 else "🟢"
                        result.append(f"  {icon} {line}")
                    else:
                        result.append(f"  {line}")
                else:
                    result.append(f"  {line}")

        if not result:
            return "  ⚠️  未找到帧数据。进程可能未在前台运行。\n"

        return "\n".join(result) + "\n"

    def _parse_framestats(self, raw):
        """解析 framestats 原始数据，列出掉帧帧"""
        lines = raw.split("\n")
        jank_frames = []
        total_frames = 0
        budget_ms = 8.33  # 120Hz

        for line in lines:
            if line.startswith("0,") or line.startswith("1,"):
                parts = line.split(",")
                if len(parts) >= 14:
                    try:
                        intended_vsync = int(parts[1])
                        frame_completed = int(parts[13])
                        total_ms = (frame_completed - intended_vsync) / 1_000_000.0
                        total_frames += 1

                        if total_ms > budget_ms:
                            # 计算各阶段
                            handle_input = int(parts[5])
                            anim_start = int(parts[6])
                            traversal_start = int(parts[7])
                            draw_start = int(parts[8])
                            sync_start = int(parts[10])
                            issue_start = int(parts[11])
                            swap = int(parts[12])

                            input_ms = (anim_start - handle_input) / 1e6
                            anim_ms = (traversal_start - anim_start) / 1e6
                            traversal_ms = (draw_start - traversal_start) / 1e6
                            draw_ms = (sync_start - draw_start) / 1e6
                            sync_ms = (issue_start - sync_start) / 1e6
                            command_ms = (swap - issue_start) / 1e6

                            jank_frames.append({
                                "total": total_ms,
                                "input": input_ms,
                                "anim": anim_ms,
                                "traversal": traversal_ms,
                                "draw": draw_ms,
                                "sync": sync_ms,
                                "command": command_ms
                            })
                    except (ValueError, IndexError):
                        continue

        if total_frames == 0:
            return "  ⚠️  无 framestats 数据\n"

        result = []
        result.append(f"\n📊 Framestats 分析 (budget={budget_ms:.2f}ms)")
        result.append(f"  总帧数: {total_frames} | 掉帧: {len(jank_frames)} ({len(jank_frames)*100/total_frames:.1f}%)")
        result.append("")

        if jank_frames:
            result.append("  掉帧帧详情 (Top 10):")
            result.append("  %-8s %-8s %-8s %-8s %-8s %-8s %-8s" % (
                "Total", "Input", "Anim", "Layout", "Draw", "SYNC", "CMD"))
            result.append("  " + "-" * 56)

            for f in sorted(jank_frames, key=lambda x: x["total"], reverse=True)[:10]:
                # 标记最大阶段
                phases = {"input": f["input"], "anim": f["anim"],
                          "traversal": f["traversal"], "draw": f["draw"],
                          "sync": f["sync"], "command": f["command"]}
                bottleneck = max(phases, key=phases.get)
                marker = {"input": "IN", "anim": "AN", "traversal": "LY",
                          "draw": "DR", "sync": "SY", "command": "CM"}

                result.append("  %-8.1f %-8.1f %-8.1f %-8.1f %-8.1f %-8.1f %-8.1f ← %s" % (
                    f["total"], f["input"], f["anim"], f["traversal"],
                    f["draw"], f["sync"], f["command"], marker[bottleneck]))

            # 统计瓶颈分布
            bottleneck_count = {}
            for f in jank_frames:
                phases = {"UI Thread (layout+draw)": f["traversal"] + f["draw"],
                          "SYNC (texture upload)": f["sync"],
                          "COMMAND (GPU ops)": f["command"],
                          "INPUT": f["input"],
                          "ANIMATION": f["anim"]}
                bn = max(phases, key=phases.get)
                bottleneck_count[bn] = bottleneck_count.get(bn, 0) + 1

            result.append("")
            result.append("  瓶颈分布:")
            for bn, count in sorted(bottleneck_count.items(), key=lambda x: -x[1]):
                pct = count * 100 / len(jank_frames)
                bar = "█" * int(pct / 5)
                result.append(f"    {bn:25s} {count:3d} ({pct:4.1f}%) {bar}")

        return "\n".join(result) + "\n"

    def _list_packages(self):
        """列出正在运行的包名"""
        output = self._run_adb(["shell", "dumpsys", "activity", "activities"])
        # 提取 topResumedActivity
        match = re.search(r'topResumedActivity.*?([a-z][a-z0-9_.]+)/[^\s]+', output)
        if match:
            pkg = match.group(1)
            self.package_var.set(pkg)
            self._append_output(f"📱 当前前台应用: {pkg}\n")
        else:
            self._append_output("⚠️ 无法检测前台应用\n")

    def _copy_result(self):
        content = self.output.get("1.0", tk.END).strip()
        self.root.clipboard_clear()
        self.root.clipboard_append(content)
        self._set_status("✅ 已复制到剪贴板")

    def _clear(self):
        self.output.delete("1.0", tk.END)

    def _append_output(self, text):
        self.output.insert(tk.END, text)
        self.output.see(tk.END)

    def _set_status(self, text):
        self.status_var.set(text)

    def _time(self):
        return datetime.now().strftime("%H:%M:%S")

    def _disable_buttons(self):
        for btn in [self.reset_btn, self.capture_btn, self.one_click_btn, self.framestats_btn]:
            btn.config(state=tk.DISABLED)

    def _enable_buttons(self):
        for btn in [self.reset_btn, self.capture_btn, self.one_click_btn, self.framestats_btn]:
            btn.config(state=tk.NORMAL)

    # ───── 图表相关 ─────

    def _load_chart_history(self):
        if os.path.exists(self.DATA_FILE):
            try:
                with open(self.DATA_FILE, "r") as f:
                    return json.load(f)
            except (json.JSONDecodeError, IOError):
                pass
        return {"sessions": []}

    def _save_chart_history(self):
        with open(self.DATA_FILE, "w") as f:
            json.dump(self.chart_history, f, ensure_ascii=False)

    def _add_to_chart(self, raw_output):
        """从 gfxinfo 原始输出中提取帧数据并加入图表历史"""
        frame_times = self._extract_frame_times(raw_output)
        if not frame_times:
            return

        budget_ms = 8.33
        total = len(frame_times)
        jank = sum(1 for t in frame_times if t > budget_ms)
        avg_fps = 1000.0 / (sum(frame_times) / total) if total > 0 else 0

        entry = {
            "timestamp": datetime.now().strftime("%H:%M:%S"),
            "frameTimes": frame_times,
            "frameCount": total,
            "jankCount": jank,
            "jankPercent": round(jank * 100.0 / total, 1) if total > 0 else 0,
            "avgFps": round(avg_fps, 1),
            "avgFrameTime": round(sum(frame_times) / total, 2) if total > 0 else 0,
            "p50": sorted(frame_times)[total // 2] if total > 0 else 0,
            "p90": sorted(frame_times)[int(total * 0.9)] if total > 0 else 0,
            "p99": sorted(frame_times)[int(total * 0.99)] if total > 0 else 0,
            "maxFrameTime": max(frame_times) if frame_times else 0,
        }

        self.chart_history["sessions"].append(entry)
        self._save_chart_history()
        count = len(self.chart_history["sessions"])
        self._set_status(f"抓取完成 | 图表已更新 (#{count})")

    def _extract_frame_times(self, raw):
        """从 gfxinfo/framestats 输出提取每帧耗时"""
        frame_times = []
        for line in raw.split("\n"):
            if line.startswith("0,") or line.startswith("1,"):
                parts = line.split(",")
                if len(parts) >= 14:
                    try:
                        intended_vsync = int(parts[1])
                        frame_completed = int(parts[13])
                        total_ms = (frame_completed - intended_vsync) / 1_000_000.0
                        if 0 < total_ms < 500:
                            frame_times.append(round(total_ms, 2))
                    except (ValueError, IndexError):
                        continue
        return frame_times

    def _open_chart(self):
        """生成 HTML 并打开浏览器"""
        self._generate_chart_html()
        url = f"http://127.0.0.1:{self.CHART_PORT}/gfxinfo_chart.html"
        webbrowser.open(url)
        self._set_status(f"图表已打开: {url}")

    def _toggle_auto_collect(self):
        """切换自动采集模式"""
        if self.auto_collecting:
            self.auto_collecting = False
            self.auto_btn.config(text="🔄 自动采集", bg="#3a3a3a")
            self._set_status("自动采集已停止")
        else:
            self.auto_collecting = True
            self.auto_btn.config(text="⏹ 停止采集", bg="#c0392b")
            self._set_status(f"自动采集中... (每{self.auto_interval}秒)")
            # 先 reset
            pkg = self.package_var.get().strip()
            self._run_adb(["shell", "dumpsys", "gfxinfo", pkg, "reset"])
            self._schedule_auto_collect()

    def _schedule_auto_collect(self):
        """定时自动采集"""
        if not self.auto_collecting:
            return

        def do_collect():
            if not self.auto_collecting:
                return
            pkg = self.package_var.get().strip()
            output = self._run_adb(["shell", "dumpsys", "gfxinfo", pkg, "framestats"])
            if output and "ERROR" not in output:
                self._add_to_chart(output)
                # reset 后下次采集是增量
                self._run_adb(["shell", "dumpsys", "gfxinfo", pkg, "reset"])
                count = len(self.chart_history["sessions"])
                self._set_status(f"自动采集中... #{count} (每{self.auto_interval}秒)")
            # 继续下一次
            if self.auto_collecting:
                self.root.after(self.auto_interval * 1000, do_collect)

        self.root.after(self.auto_interval * 1000, do_collect)

    def _start_chart_server(self):
        """启动 HTTP 服务器用于图表展示"""
        self._generate_chart_html()
        data_dir = self.DATA_DIR

        class Handler(SimpleHTTPRequestHandler):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, directory=data_dir, **kwargs)
            def do_PUT(self_handler):
                if self_handler.path == '/gfxinfo_history.json':
                    length = int(self_handler.headers.get('Content-Length', 0))
                    body = self_handler.rfile.read(length)
                    with open(GfxInfoApp.DATA_FILE, 'w') as f:
                        f.write(body.decode())
                    self.chart_history = {"sessions": []}
                    self_handler.send_response(200)
                    self_handler.end_headers()
                else:
                    self_handler.send_response(404)
                    self_handler.end_headers()
            def log_message(self, format, *args):
                pass

        try:
            server = HTTPServer(("127.0.0.1", self.CHART_PORT), Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            self.chart_server = server
        except OSError:
            pass  # 端口被占用，可能是另一个实例

    def _generate_chart_html(self):
        """生成图表 HTML"""
        html = '''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>PerfettoKit GfxInfo Chart</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, 'SF Pro', sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }
h1 { font-size: 20px; margin-bottom: 15px; color: #64ffda; }
.top-bar { display: flex; align-items: center; gap: 15px; margin-bottom: 20px; }
.top-bar button { padding: 8px 16px; border: none; border-radius: 6px; cursor: pointer; font-size: 13px; }
.btn-refresh { background: #64ffda; color: #1a1a2e; font-weight: 600; }
.btn-clear { background: #ff5252; color: #fff; }
.status { color: #888; font-size: 12px; }
.charts { display: grid; grid-template-columns: 1fr; gap: 20px; }
.chart-card { background: #16213e; border-radius: 12px; padding: 20px; }
.chart-card h3 { font-size: 14px; color: #aaa; margin-bottom: 10px; }
.chart-container { position: relative; height: 250px; }
.session-list { margin-top: 20px; }
.session-list h3 { font-size: 14px; color: #aaa; margin-bottom: 10px; }
.session-item { display: inline-block; padding: 6px 12px; margin: 3px; border-radius: 6px;
  background: #0f3460; cursor: pointer; font-size: 12px; transition: all 0.2s; }
.session-item:hover { background: #1a5276; transform: scale(1.05); }
.session-item.active { background: #64ffda; color: #1a1a2e; font-weight: 600; }
.detail-panel { margin-top: 20px; background: #16213e; border-radius: 12px; padding: 20px; display: none; }
.detail-panel.show { display: block; }
.detail-panel h3 { color: #64ffda; margin-bottom: 10px; }
.detail-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; margin-bottom: 15px; }
.stat-box { background: #0f3460; padding: 10px; border-radius: 8px; text-align: center; }
.stat-box .value { font-size: 20px; font-weight: 700; color: #64ffda; }
.stat-box .label { font-size: 11px; color: #888; margin-top: 3px; }
.detail-chart { height: 200px; }
</style>
</head>
<body>
<div class="top-bar">
  <h1>PerfettoKit GfxInfo Chart</h1>
  <button class="btn-refresh" onclick="refresh()">刷新</button>
  <button class="btn-clear" onclick="clearData()">清空历史</button>
  <span class="status" id="status">加载中...</span>
</div>
<div class="charts">
  <div class="chart-card">
    <h3>帧率趋势 (FPS)</h3>
    <div class="chart-container"><canvas id="fpsChart"></canvas></div>
  </div>
  <div class="chart-card">
    <h3>掉帧率趋势 (%)</h3>
    <div class="chart-container"><canvas id="jankChart"></canvas></div>
  </div>
</div>
<div class="session-list">
  <h3>采集记录 (点击查看详情)</h3>
  <div id="sessionItems"></div>
</div>
<div class="detail-panel" id="detailPanel">
  <h3 id="detailTitle">第 N 次采集</h3>
  <div class="detail-stats" id="detailStats"></div>
  <div class="chart-container detail-chart"><canvas id="detailChart"></canvas></div>
</div>
<script>
let history = { sessions: [] };
let fpsChart, jankChart, detailChart;
async function loadData() {
  try {
    const resp = await fetch('/gfxinfo_history.json?' + Date.now());
    history = await resp.json();
    renderCharts();
    renderSessions();
    document.getElementById('status').textContent = history.sessions.length + ' 次采集';
  } catch (e) {
    document.getElementById('status').textContent = '加载失败';
  }
}
function renderCharts() {
  const labels = history.sessions.map((s, i) => '#' + (i+1) + ' ' + s.timestamp);
  const fpsData = history.sessions.map(s => s.avgFps);
  const jankData = history.sessions.map(s => s.jankPercent);
  const opts = {
    responsive: true, maintainAspectRatio: false,
    interaction: { mode: 'index', intersect: false },
    plugins: { legend: { display: false } },
    scales: {
      x: { ticks: { color: '#888', maxRotation: 45, font: { size: 10 } }, grid: { color: '#333' } },
      y: { ticks: { color: '#888' }, grid: { color: '#333' } }
    },
    onClick: (evt, elements) => { if (elements.length > 0) showDetail(elements[0].index); }
  };
  if (fpsChart) fpsChart.destroy();
  fpsChart = new Chart(document.getElementById('fpsChart'), {
    type: 'line',
    data: { labels, datasets: [{ data: fpsData, borderColor: '#64ffda', backgroundColor: 'rgba(100,255,218,0.1)', fill: true, tension: 0.3, pointRadius: 4, pointHoverRadius: 7 }] },
    options: { ...opts, scales: { ...opts.scales, y: { ...opts.scales.y, suggestedMin: 0, suggestedMax: 120 } } }
  });
  if (jankChart) jankChart.destroy();
  jankChart = new Chart(document.getElementById('jankChart'), {
    type: 'line',
    data: { labels, datasets: [{ data: jankData, borderColor: '#ff5252', backgroundColor: 'rgba(255,82,82,0.1)', fill: true, tension: 0.3, pointRadius: 4, pointHoverRadius: 7 }] },
    options: { ...opts, scales: { ...opts.scales, y: { ...opts.scales.y, suggestedMin: 0 } } }
  });
}
function renderSessions() {
  const c = document.getElementById('sessionItems');
  c.innerHTML = history.sessions.map((s, i) => {
    const color = s.jankPercent > 20 ? '#ff5252' : s.jankPercent > 10 ? '#ffd740' : '#64ffda';
    return '<span class="session-item" onclick="showDetail(' + i + ')" style="border-left:3px solid ' + color + '">#' + (i+1) + ' ' + s.timestamp + ' | ' + s.avgFps + 'fps | ' + s.jankPercent + '%卡</span>';
  }).join('');
}
function showDetail(index) {
  const s = history.sessions[index];
  if (!s) return;
  document.querySelectorAll('.session-item').forEach((el, i) => el.classList.toggle('active', i === index));
  const panel = document.getElementById('detailPanel');
  panel.classList.add('show');
  document.getElementById('detailTitle').textContent = '第 ' + (index+1) + ' 次采集 — ' + s.timestamp;
  document.getElementById('detailStats').innerHTML =
    '<div class="stat-box"><div class="value">' + s.avgFps + '</div><div class="label">平均 FPS</div></div>' +
    '<div class="stat-box"><div class="value">' + s.frameCount + '</div><div class="label">总帧数</div></div>' +
    '<div class="stat-box"><div class="value">' + s.jankCount + '</div><div class="label">掉帧数</div></div>' +
    '<div class="stat-box"><div class="value">' + s.jankPercent + '%</div><div class="label">掉帧率</div></div>' +
    '<div class="stat-box"><div class="value">' + s.avgFrameTime + 'ms</div><div class="label">平均帧耗时</div></div>' +
    '<div class="stat-box"><div class="value">' + s.p90 + 'ms</div><div class="label">P90</div></div>' +
    '<div class="stat-box"><div class="value">' + s.p99 + 'ms</div><div class="label">P99</div></div>' +
    '<div class="stat-box"><div class="value">' + s.maxFrameTime + 'ms</div><div class="label">最大帧耗时</div></div>';
  if (detailChart) detailChart.destroy();
  const ft = s.frameTimes || [];
  detailChart = new Chart(document.getElementById('detailChart'), {
    type: 'bar',
    data: { labels: ft.map((_, i) => i+1), datasets: [{ data: ft, backgroundColor: ft.map(t => t > 16.67 ? '#ff5252' : t > 8.33 ? '#ffd740' : '#64ffda'), borderWidth: 0 }] },
    options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { x: { display: false }, y: { ticks: { color: '#888' }, grid: { color: '#333' } } } }
  });
  panel.scrollIntoView({ behavior: 'smooth' });
}
function refresh() { loadData(); }
function clearData() {
  if (confirm('确定清空所有历史记录？')) {
    fetch('/gfxinfo_history.json', { method: 'PUT', body: JSON.stringify({ sessions: [] }) });
    history = { sessions: [] }; renderCharts(); renderSessions();
    document.getElementById('detailPanel').classList.remove('show');
  }
}
loadData();
setInterval(loadData, 2000);
</script>
</body>
</html>'''
        with open(self.HTML_FILE, "w") as f:
            f.write(html)

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    app = GfxInfoApp()
    app.run()
