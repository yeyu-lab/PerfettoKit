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
from datetime import datetime


class GfxInfoApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("PerfettoKit GfxInfo")
        self.root.geometry("720x680")
        self.root.configure(bg="#1e1e1e")

        # 默认包名
        self.package_var = tk.StringVar(value="com.hualai")
        self.status_var = tk.StringVar(value="就绪")
        self.is_recording = False

        self._build_ui()

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

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    app = GfxInfoApp()
    app.run()
