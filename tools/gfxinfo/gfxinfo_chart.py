#!/usr/bin/env python3
"""
PerfettoKit GfxInfo Chart — 帧率曲线图工具

定时采集 gfxinfo framestats，生成可交互的帧率曲线图。
每次采集作为一个索引点，可点击查看该次的帧率详情。

用法:
  python3 gfxinfo_chart.py [包名] [采集间隔秒数]
  python3 gfxinfo_chart.py com.hualai 3
"""

import subprocess
import json
import time
import sys
import os
import webbrowser
import signal
from datetime import datetime
from http.server import HTTPServer, SimpleHTTPRequestHandler
import threading

# --- 配置 ---
DEFAULT_PACKAGE = "com.hualai"
DEFAULT_INTERVAL = 3  # 秒
DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gfxinfo_history.json")
HTML_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gfxinfo_chart.html")
PORT = 8765


def run_adb(args):
    """执行 adb 命令"""
    try:
        result = subprocess.run(
            ["adb"] + args,
            capture_output=True, text=True, timeout=10
        )
        return result.stdout
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return ""


def parse_framestats(raw, budget_ms=8.33):
    """解析 framestats 原始数据，返回帧耗时列表"""
    lines = raw.split("\n")
    frame_times = []

    for line in lines:
        if line.startswith("0,") or line.startswith("1,"):
            parts = line.split(",")
            if len(parts) >= 14:
                try:
                    intended_vsync = int(parts[1])
                    frame_completed = int(parts[13])
                    total_ms = (frame_completed - intended_vsync) / 1_000_000.0
                    if 0 < total_ms < 500:  # 过滤异常值
                        frame_times.append(round(total_ms, 2))
                except (ValueError, IndexError):
                    continue

    return frame_times


def parse_summary(raw):
    """解析 gfxinfo 摘要数据"""
    import re
    summary = {}
    for line in raw.split("\n"):
        line = line.strip()
        if "Total frames rendered:" in line:
            m = re.search(r':\s*(\d+)', line)
            if m:
                summary["totalFrames"] = int(m.group(1))
        elif "Janky frames:" in line and "legacy" not in line:
            m = re.search(r':\s*(\d+)\s*\(([\d.]+)%\)', line)
            if m:
                summary["jankyFrames"] = int(m.group(1))
                summary["jankyPercent"] = float(m.group(2))
        elif "50th percentile:" in line:
            m = re.search(r':\s*(\d+)ms', line)
            if m:
                summary["p50"] = int(m.group(1))
        elif "90th percentile:" in line:
            m = re.search(r':\s*(\d+)ms', line)
            if m:
                summary["p90"] = int(m.group(1))
        elif "95th percentile:" in line:
            m = re.search(r':\s*(\d+)ms', line)
            if m:
                summary["p95"] = int(m.group(1))
        elif "99th percentile:" in line:
            m = re.search(r':\s*(\d+)ms', line)
            if m:
                summary["p99"] = int(m.group(1))
    return summary


def collect_once(package):
    """执行一次采集"""
    # 获取 framestats
    raw = run_adb(["shell", "dumpsys", "gfxinfo", package, "framestats"])
    if not raw:
        return None

    frame_times = parse_framestats(raw)
    summary = parse_summary(raw)

    if not frame_times:
        return None

    # 计算本次统计
    budget_ms = 8.33
    total = len(frame_times)
    jank = sum(1 for t in frame_times if t > budget_ms)
    avg_fps = 1000.0 / (sum(frame_times) / total) if total > 0 else 0

    return {
        "timestamp": datetime.now().strftime("%H:%M:%S"),
        "timestampFull": datetime.now().isoformat(),
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
        "summary": summary
    }


def load_history():
    """加载历史数据"""
    if os.path.exists(DATA_FILE):
        try:
            with open(DATA_FILE, "r") as f:
                return json.load(f)
        except (json.JSONDecodeError, IOError):
            pass
    return {"sessions": []}


def save_history(history):
    """保存历史数据"""
    with open(DATA_FILE, "w") as f:
        json.dump(history, f, ensure_ascii=False)


def generate_html():
    """生成图表 HTML 文件"""
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
  <h1>📊 PerfettoKit GfxInfo Chart</h1>
  <button class="btn-refresh" onclick="refresh()">🔄 刷新</button>
  <button class="btn-clear" onclick="clearData()">🗑 清空历史</button>
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
let autoRefreshTimer = null;

async function loadData() {
  try {
    const resp = await fetch('/gfxinfo_history.json?' + Date.now());
    history = await resp.json();
    renderCharts();
    renderSessions();
    document.getElementById('status').textContent =
      `共 ${history.sessions.length} 次采集 | 自动刷新中...`;
  } catch (e) {
    document.getElementById('status').textContent = '加载失败: ' + e.message;
  }
}

function renderCharts() {
  const labels = history.sessions.map((s, i) => `#${i + 1} ${s.timestamp}`);
  const fpsData = history.sessions.map(s => s.avgFps);
  const jankData = history.sessions.map(s => s.jankPercent);

  const commonOpts = {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'index', intersect: false },
    plugins: { legend: { display: false } },
    scales: {
      x: { ticks: { color: '#888', maxRotation: 45, font: { size: 10 } }, grid: { color: '#333' } },
      y: { ticks: { color: '#888' }, grid: { color: '#333' } }
    },
    onClick: (evt, elements) => {
      if (elements.length > 0) showDetail(elements[0].index);
    }
  };

  if (fpsChart) fpsChart.destroy();
  fpsChart = new Chart(document.getElementById('fpsChart'), {
    type: 'line',
    data: {
      labels,
      datasets: [{
        data: fpsData,
        borderColor: '#64ffda',
        backgroundColor: 'rgba(100,255,218,0.1)',
        fill: true,
        tension: 0.3,
        pointRadius: 4,
        pointHoverRadius: 7
      }]
    },
    options: { ...commonOpts, scales: { ...commonOpts.scales, y: { ...commonOpts.scales.y, suggestedMin: 0, suggestedMax: 120 } } }
  });

  if (jankChart) jankChart.destroy();
  jankChart = new Chart(document.getElementById('jankChart'), {
    type: 'line',
    data: {
      labels,
      datasets: [{
        data: jankData,
        borderColor: '#ff5252',
        backgroundColor: 'rgba(255,82,82,0.1)',
        fill: true,
        tension: 0.3,
        pointRadius: 4,
        pointHoverRadius: 7
      }]
    },
    options: { ...commonOpts, scales: { ...commonOpts.scales, y: { ...commonOpts.scales.y, suggestedMin: 0 } } }
  });
}

function renderSessions() {
  const container = document.getElementById('sessionItems');
  container.innerHTML = history.sessions.map((s, i) => {
    const color = s.jankPercent > 20 ? '#ff5252' : s.jankPercent > 10 ? '#ffd740' : '#64ffda';
    return `<span class="session-item" onclick="showDetail(${i})" style="border-left: 3px solid ${color}">
      #${i + 1} ${s.timestamp} | ${s.avgFps}fps | ${s.jankPercent}%卡</span>`;
  }).join('');
}

function showDetail(index) {
  const s = history.sessions[index];
  if (!s) return;

  document.querySelectorAll('.session-item').forEach((el, i) => {
    el.classList.toggle('active', i === index);
  });

  const panel = document.getElementById('detailPanel');
  panel.classList.add('show');
  document.getElementById('detailTitle').textContent = `第 ${index + 1} 次采集 — ${s.timestamp}`;

  document.getElementById('detailStats').innerHTML = `
    <div class="stat-box"><div class="value">${s.avgFps}</div><div class="label">平均 FPS</div></div>
    <div class="stat-box"><div class="value">${s.frameCount}</div><div class="label">总帧数</div></div>
    <div class="stat-box"><div class="value">${s.jankCount}</div><div class="label">掉帧数</div></div>
    <div class="stat-box"><div class="value">${s.jankPercent}%</div><div class="label">掉帧率</div></div>
    <div class="stat-box"><div class="value">${s.avgFrameTime}ms</div><div class="label">平均帧耗时</div></div>
    <div class="stat-box"><div class="value">${s.p90}ms</div><div class="label">P90</div></div>
    <div class="stat-box"><div class="value">${s.p99}ms</div><div class="label">P99</div></div>
    <div class="stat-box"><div class="value">${s.maxFrameTime}ms</div><div class="label">最大帧耗时</div></div>
  `;

  // 帧耗时分布图
  if (detailChart) detailChart.destroy();
  const frameTimes = s.frameTimes || [];
  detailChart = new Chart(document.getElementById('detailChart'), {
    type: 'bar',
    data: {
      labels: frameTimes.map((_, i) => i + 1),
      datasets: [{
        data: frameTimes,
        backgroundColor: frameTimes.map(t => t > 16.67 ? '#ff5252' : t > 8.33 ? '#ffd740' : '#64ffda'),
        borderWidth: 0
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: ctx => ctx.raw + ' ms' } }
      },
      scales: {
        x: { display: false },
        y: {
          ticks: { color: '#888' },
          grid: { color: '#333' }
        }
      }
    }
  });

  panel.scrollIntoView({ behavior: 'smooth' });
}

function refresh() { loadData(); }

function clearData() {
  if (confirm('确定清空所有历史记录？')) {
    fetch('/gfxinfo_history.json', { method: 'PUT', body: JSON.stringify({ sessions: [] }) });
    history = { sessions: [] };
    renderCharts();
    renderSessions();
    document.getElementById('detailPanel').classList.remove('show');
  }
}

// 自动刷新
loadData();
autoRefreshTimer = setInterval(loadData, 2000);
</script>
</body>
</html>'''

    with open(HTML_FILE, "w") as f:
        f.write(html)


class ChartHandler(SimpleHTTPRequestHandler):
    """简单的 HTTP handler，支持 PUT 来清空数据"""

    def __init__(self, *args, directory=None, **kwargs):
        self.directory = directory or os.path.dirname(os.path.abspath(__file__))
        super().__init__(*args, directory=self.directory, **kwargs)

    def do_PUT(self):
        if self.path == '/gfxinfo_history.json':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
            with open(DATA_FILE, 'w') as f:
                f.write(body.decode())
            self.send_response(200)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass  # 静默


def start_server():
    """启动 HTTP 服务器"""
    server = HTTPServer(("127.0.0.1", PORT), ChartHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def main():
    package = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PACKAGE
    interval = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_INTERVAL

    print(f"📊 PerfettoKit GfxInfo Chart")
    print(f"   包名: {package}")
    print(f"   采集间隔: {interval}s")
    print(f"   数据文件: {DATA_FILE}")
    print()

    # 生成 HTML
    generate_html()

    # 加载历史
    history = load_history()

    # 启动 HTTP 服务器
    server = start_server()
    url = f"http://127.0.0.1:{PORT}/gfxinfo_chart.html"
    print(f"   图表地址: {url}")
    print(f"   Ctrl+C 停止采集")
    print()

    # 打开浏览器
    webbrowser.open(url)

    # 重置 gfxinfo
    run_adb(["shell", "dumpsys", "gfxinfo", package, "reset"])
    print("   ✓ 已重置 gfxinfo 计数器")
    print()

    # 主循环
    count = len(history["sessions"])
    try:
        while True:
            time.sleep(interval)

            # 采集
            data = collect_once(package)
            if data is None:
                print(f"   ⚠️  采集失败（设备断开或进程不在前台）")
                continue

            count += 1
            data["index"] = count
            history["sessions"].append(data)
            save_history(history)

            # 重置计数器（每次采集后重置，确保是增量数据）
            run_adb(["shell", "dumpsys", "gfxinfo", package, "reset"])

            # 打印简要信息
            icon = "🔴" if data["jankPercent"] > 20 else "🟡" if data["jankPercent"] > 10 else "🟢"
            print(f"   {icon} #{count} [{data['timestamp']}] "
                  f"{data['avgFps']}fps | "
                  f"{data['frameCount']}帧 | "
                  f"掉帧{data['jankCount']}({data['jankPercent']}%) | "
                  f"P90={data['p90']}ms")

    except KeyboardInterrupt:
        print(f"\n\n   ✓ 采集结束，共 {count} 次记录")
        print(f"   图表: {url}")
        server.shutdown()


if __name__ == "__main__":
    main()
