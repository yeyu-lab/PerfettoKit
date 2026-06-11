import SwiftUI
import Foundation

// MARK: - App Entry

@main
struct GfxInfoApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .windowStyle(.titleBar)
        .defaultSize(width: 750, height: 700)
    }
}

// MARK: - Main View

struct ContentView: View {
    @StateObject private var viewModel = GfxInfoViewModel()
    @State private var showInfoPopover = false

    var body: some View {
        VStack(spacing: 0) {
            // 顶部：包名 + 设备
            HStack(spacing: 12) {
                Text("包名:")
                    .foregroundColor(.secondary)
                TextField("com.hualai", text: $viewModel.packageName)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 240)

                Button("🔍 检测设备") { viewModel.detectDevice() }
                    .buttonStyle(.bordered)

                Text(viewModel.deviceInfo)
                    .foregroundColor(viewModel.deviceConnected ? .green : .red)
                    .font(.caption)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            // 按钮区
            HStack(spacing: 12) {
                ActionButton(title: "① Reset", subtitle: "清除历史", color: .orange) {
                    viewModel.reset()
                }
                ActionButton(title: "② Capture", subtitle: "抓取数据", color: .green) {
                    viewModel.capture()
                }
                ActionButton(title: "⚡ 一键测试", subtitle: "Reset+5s+抓取", color: .blue) {
                    viewModel.oneClick()
                }
                ActionButton(title: "📊 Framestats", subtitle: "逐帧分析", color: .purple) {
                    viewModel.framestats()
                }

                // 说明按钮
                Button(action: { showInfoPopover.toggle() }) {
                    Image(systemName: "info.circle.fill")
                        .font(.title2)
                        .foregroundColor(.blue)
                }
                .buttonStyle(.plain)
                .popover(isPresented: $showInfoPopover, arrowEdge: .bottom) {
                    InfoPopoverView()
                }

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            // 状态栏
            HStack {
                Text(viewModel.statusText)
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                if viewModel.isLoading {
                    ProgressView()
                        .scaleEffect(0.7)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)

            Divider()

            // 输出区
            ScrollViewReader { proxy in
                ScrollView {
                    Text(viewModel.output)
                        .font(.system(.body, design: .monospaced))
                        .foregroundColor(.green)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .id("output_bottom")
                }
                .background(Color.black.opacity(0.9))
                .onChange(of: viewModel.output) { _ in
                    proxy.scrollTo("output_bottom", anchor: .bottom)
                }
            }

            // 底部操作
            HStack(spacing: 12) {
                Button("📋 复制") { viewModel.copyToClipboard() }
                    .buttonStyle(.bordered)
                Button("🗑 清空") { viewModel.clearOutput() }
                    .buttonStyle(.bordered)
                Button("📱 前台应用") { viewModel.detectForegroundApp() }
                    .buttonStyle(.bordered)

                Button("📈 曲线图") { viewModel.openChart() }
                    .buttonStyle(.borderedProminent)
                    .tint(.teal)

                Button(viewModel.autoCollecting ? "⏹ 停止采集" : "🔄 自动采集") {
                    viewModel.toggleAutoCollect()
                }
                .buttonStyle(.borderedProminent)
                .tint(viewModel.autoCollecting ? .red : .gray)

                if viewModel.chartCount > 0 {
                    Text("图表: \(viewModel.chartCount)次")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                Spacer()
            }
            .padding(12)
        }
    }
}

// MARK: - Info Popover

struct InfoPopoverView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("📌 掉帧阈值说明")
                .font(.headline)

            Divider()

            Group {
                InfoRow(icon: "🖥", title: "VSYNC 间隔", desc: "8.33ms (120Hz 设备)")
                InfoRow(icon: "⏱", title: "系统 FrameDeadline", desc: "≈16.7ms (2个VSYNC, double-buffer pipeline)")
                InfoRow(icon: "📊", title: "Janky frames (legacy)", desc: "渲染耗时 > 单个VSYNC(8.33ms)\n= 实际刷新率标准，最严格")
                InfoRow(icon: "📈", title: "Janky frames (非legacy)", desc: "超过 FrameDeadline(≈16.7ms)\n= 真正呈现迟到，用户可感知")
            }

            Divider()

            VStack(alignment: .leading, spacing: 4) {
                Text("本工具 Framestats 分析:")
                    .font(.subheadline.bold())
                Text("• Total > 8.33ms 即标记掉帧 (单VSYNC粒度)")
                    .font(.caption)
                Text("• 与 legacy 相同标准，比系统 Janky frames 更严格")
                    .font(.caption)
                Text("• 能发现被三重缓冲兜住的潜在卡顿")
                    .font(.caption)
                    .foregroundColor(.orange)
            }

            Divider()

            VStack(alignment: .leading, spacing: 4) {
                Text("为什么 legacy(39%) > 非legacy(8%)?")
                    .font(.subheadline.bold())
                Text("大部分帧在 8~16ms 之间：\n对 120Hz 算掉帧(legacy)，但没 miss deadline(非legacy)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(16)
        .frame(width: 380)
    }
}

struct InfoRow: View {
    let icon: String
    let title: String
    let desc: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(icon).font(.body)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.bold())
                Text(desc).font(.caption).foregroundColor(.secondary)
            }
        }
    }
}

// MARK: - Action Button

struct ActionButton: View {
    let title: String
    let subtitle: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(title).font(.system(size: 13, weight: .bold))
                Text(subtitle).font(.caption2)
            }
            .frame(width: 110, height: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(color)
    }
}

// MARK: - ViewModel

class GfxInfoViewModel: ObservableObject {
    @Published var packageName = "com.hualai"
    @Published var output = ""
    @Published var statusText = "就绪"
    @Published var deviceInfo = ""
    @Published var deviceConnected = false
    @Published var isLoading = false
    @Published var autoCollecting = false
    @Published var chartCount = 0

    private var chartHistory: [[String: Any]] = []
    private var autoTimer: Timer?
    private let autoInterval: TimeInterval = 3
    private var httpServer: ChartHTTPServer?

    private var adbPath: String {
        // 查找 adb
        let paths = [
            "\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb",
            "/usr/local/bin/adb",
            "/opt/homebrew/bin/adb"
        ]
        for p in paths {
            if FileManager.default.fileExists(atPath: p) { return p }
        }
        return "adb" // fallback to PATH
    }

    private static let dataDir: URL = {
        let url = URL(fileURLWithPath: #file).deletingLastPathComponent()
        // If running from build dir, use source dir
        if url.lastPathComponent == "MacOS" {
            // find tools/gfxinfo relative to executable
            let bundlePath = Bundle.main.bundlePath
            let toolsDir = URL(fileURLWithPath: bundlePath).deletingLastPathComponent().deletingLastPathComponent()
            if FileManager.default.fileExists(atPath: toolsDir.appendingPathComponent("gfxinfo_chart.html").path) {
                return toolsDir
            }
        }
        return url
    }()

    private var chartDataFile: URL {
        // Store in same dir as the script/executable's parent
        let dir = URL(fileURLWithPath: Bundle.main.bundlePath).deletingLastPathComponent()
        return dir.appendingPathComponent("gfxinfo_history.json")
    }

    private var chartHTMLFile: URL {
        let dir = URL(fileURLWithPath: Bundle.main.bundlePath).deletingLastPathComponent()
        return dir.appendingPathComponent("gfxinfo_chart.html")
    }

    init() {
        loadChartHistory()
        startHTTPServer()
        // 确保 JSON 文件存在（浏览器需要）
        if !FileManager.default.fileExists(atPath: chartDataFile.path) {
            try? "{\"sessions\":[]}".write(to: chartDataFile, atomically: true, encoding: .utf8)
        }
    }

    func detectDevice() {
        runAsync(["devices"]) { [weak self] output in
            let lines = output.split(separator: "\n").filter { $0.contains("\tdevice") }
            if let first = lines.first {
                let deviceId = String(first.split(separator: "\t")[0])
                self?.runAsync(["shell", "getprop", "ro.product.model"]) { model in
                    DispatchQueue.main.async {
                        self?.deviceInfo = "✅ \(model.trimmingCharacters(in: .whitespacesAndNewlines)) (\(deviceId))"
                        self?.deviceConnected = true
                        self?.statusText = "设备已连接"
                    }
                }
            } else {
                DispatchQueue.main.async {
                    self?.deviceInfo = "❌ 未检测到设备"
                    self?.deviceConnected = false
                }
            }
        }
    }

    func reset() {
        runAsync(["shell", "dumpsys", "gfxinfo", packageName, "reset"]) { [weak self] _ in
            DispatchQueue.main.async {
                self?.appendOutput("✅ [\(self?.timeStr ?? "")] Reset 完成 — 现在去操作手机，然后点 Capture\n")
                self?.statusText = "已 Reset，等待操作..."
            }
        }
    }

    func capture() {
        isLoading = true
        runAsync(["shell", "dumpsys", "gfxinfo", packageName]) { [weak self] raw in
            let parsed = self?.parseGfxInfo(raw) ?? ""
            DispatchQueue.main.async {
                self?.appendOutput("\n" + String(repeating: "═", count: 60) + "\n")
                self?.appendOutput("📊 [\(self?.timeStr ?? "")] \(self?.packageName ?? "")\n")
                self?.appendOutput(String(repeating: "═", count: 60) + "\n")
                self?.appendOutput(parsed)
                self?.appendOutput(String(repeating: "═", count: 60) + "\n\n")
                self?.statusText = "抓取完成"
                self?.isLoading = false
                // 加入图表
                self?.addToChart(raw: raw)
            }
        }
    }

    func oneClick() {
        reset()
        statusText = "⏱ 请操作手机... 5 秒后自动抓取"
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            self?.capture()
        }
    }

    func framestats() {
        isLoading = true
        runAsync(["shell", "dumpsys", "gfxinfo", packageName, "framestats"]) { [weak self] raw in
            let parsed = self?.parseFramestats(raw) ?? ""
            DispatchQueue.main.async {
                self?.appendOutput(parsed)
                self?.statusText = "Framestats 分析完成"
                self?.isLoading = false
                // 加入图表
                self?.addToChart(raw: raw)
            }
        }
    }

    func detectForegroundApp() {
        runAsync(["shell", "dumpsys", "activity", "activities"]) { [weak self] output in
            if let match = output.range(of: #"topResumedActivity.*?([a-z][a-z0-9_.]+)/"#, options: .regularExpression) {
                let substr = output[match]
                if let pkgMatch = substr.range(of: #"[a-z][a-z0-9_.]+(?=/)"#, options: .regularExpression) {
                    let pkg = String(substr[pkgMatch])
                    DispatchQueue.main.async {
                        self?.packageName = pkg
                        self?.appendOutput("📱 前台应用: \(pkg)\n")
                    }
                    return
                }
            }
            DispatchQueue.main.async {
                self?.appendOutput("⚠️ 无法检测前台应用\n")
            }
        }
    }

    func copyToClipboard() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(output, forType: .string)
        statusText = "✅ 已复制到剪贴板"
    }

    func clearOutput() { output = "" }

    // MARK: - Parsing

    private func parseGfxInfo(_ raw: String) -> String {
        var result: [String] = []
        for line in raw.split(separator: "\n", omittingEmptySubsequences: false) {
            let l = line.trimmingCharacters(in: .whitespaces)
            let keywords = ["Total frames", "Janky frames", "percentile",
                           "Missed Vsync", "High input", "Slow UI",
                           "Slow bitmap", "Slow issue", "Frame deadline"]
            guard keywords.contains(where: { l.contains($0) }) else { continue }

            if l.contains("Janky frames:") && !l.contains("legacy") {
                if let pct = l.range(of: #"\(([\d.]+)%\)"#, options: .regularExpression) {
                    let val = Double(l[pct].dropFirst().dropLast().replacingOccurrences(of: "%", with: "")) ?? 0
                    let icon = val > 20 ? "🔴" : val > 10 ? "🟡" : "🟢"
                    result.append("\(icon) \(l)")
                } else {
                    result.append("  \(l)")
                }
            } else if l.contains("Slow") {
                if let numMatch = l.range(of: #":\s*(\d+)"#, options: .regularExpression) {
                    let numStr = l[numMatch].trimmingCharacters(in: .whitespaces).dropFirst() // drop ':'
                    let num = Int(numStr.trimmingCharacters(in: .whitespaces)) ?? 0
                    result.append(num > 5 ? "  ⚠️  \(l)" : "  ✓  \(l)")
                } else {
                    result.append("  \(l)")
                }
            } else if l.contains("percentile") {
                if let msMatch = l.range(of: #"(\d+)ms"#, options: .regularExpression) {
                    let val = Int(l[msMatch].replacingOccurrences(of: "ms", with: "")) ?? 0
                    let icon = val > 33 ? "🔴" : val > 16 ? "🟡" : "🟢"
                    result.append("  \(icon) \(l)")
                } else {
                    result.append("  \(l)")
                }
            } else {
                result.append("  \(l)")
            }
        }
        return result.isEmpty ? "  ⚠️  未找到帧数据。\n" : result.joined(separator: "\n") + "\n"
    }

    private func parseFramestats(_ raw: String) -> String {
        struct FrameInfo {
            var total: Double; var input: Double; var anim: Double
            var traversal: Double; var draw: Double; var sync: Double; var command: Double
        }

        var jankFrames: [FrameInfo] = []
        var totalFrames = 0
        let budgetMs = 8.33

        // 动态解析列头位置 (不同 Android 版本列数不同)
        var colMap: [String: Int] = [:]
        let lines = raw.split(separator: "\n", omittingEmptySubsequences: false)

        for line in lines {
            let l = line.trimmingCharacters(in: .whitespaces)
            // 列头行格式: Flags,IntendedVsync,Vsync,...
            if l.hasPrefix("Flags,") {
                let headers = l.split(separator: ",")
                for (i, h) in headers.enumerated() {
                    colMap[String(h)] = i
                }
                break
            }
        }

        // 如果没找到列头，用默认索引 (Android 10-11)
        let iFlags = colMap["Flags"] ?? 0
        let iIntendedVsync = colMap["IntendedVsync"] ?? 1
        let iHandleInput = colMap["HandleInputStart"] ?? 5
        let iAnimStart = colMap["AnimationStart"] ?? 6
        let iTraversalStart = colMap["PerformTraversalsStart"] ?? 7
        let iDrawStart = colMap["DrawStart"] ?? 8
        let iSyncStart = colMap["SyncStart"] ?? 10
        let iIssueStart = colMap["IssueDrawCommandsStart"] ?? 11
        let iSwap = colMap["SwapBuffers"] ?? 12
        let iFrameCompleted = colMap["FrameCompleted"] ?? 13

        let minCols = max(iFrameCompleted, iSwap, iIssueStart, iSyncStart) + 1

        for line in lines {
            let l = line.trimmingCharacters(in: .whitespaces)
            // 数据行以数字开头 (Flags 值)
            guard let firstChar = l.first, firstChar.isNumber else { continue }
            guard !l.hasPrefix("Flags") else { continue }
            let parts = l.split(separator: ",")
            guard parts.count >= minCols else { continue }

            guard let intendedVsync = Int64(parts[iIntendedVsync]),
                  let frameCompleted = Int64(parts[iFrameCompleted]),
                  let handleInput = Int64(parts[iHandleInput]),
                  let animStart = Int64(parts[iAnimStart]),
                  let traversalStart = Int64(parts[iTraversalStart]),
                  let drawStart = Int64(parts[iDrawStart]),
                  let syncStart = Int64(parts[iSyncStart]),
                  let issueStart = Int64(parts[iIssueStart]),
                  let swap = Int64(parts[iSwap]) else { continue }

            // 基本校验: intendedVsync 和 frameCompleted 都应该是有效时间戳
            guard intendedVsync > 0 && frameCompleted > intendedVsync else { continue }

            let totalMs = Double(frameCompleted - intendedVsync) / 1_000_000.0

            // 过滤明显无效帧 (>5秒的帧是系统挂起或解析错误)
            guard totalMs > 0 && totalMs < 5000 else { continue }
            totalFrames += 1

            if totalMs > budgetMs {
                let inputMs = Double(animStart - handleInput) / 1e6
                let animMs = Double(traversalStart - animStart) / 1e6
                let traversalMs = Double(drawStart - traversalStart) / 1e6
                let drawMs = Double(syncStart - drawStart) / 1e6
                let syncMs = Double(issueStart - syncStart) / 1e6
                let commandMs = Double(swap - issueStart) / 1e6

                // 校验各阶段非负且合理
                let phases = [inputMs, animMs, traversalMs, drawMs, syncMs, commandMs]
                guard phases.allSatisfy({ $0 >= -1 && $0 < 5000 }) else { continue }

                jankFrames.append(FrameInfo(
                    total: totalMs,
                    input: max(0, inputMs),
                    anim: max(0, animMs),
                    traversal: max(0, traversalMs),
                    draw: max(0, drawMs),
                    sync: max(0, syncMs),
                    command: max(0, commandMs)
                ))
            }
        }

        guard totalFrames > 0 else { return "  ⚠️  无 framestats 数据\n" }

        var result: [String] = []
        let jankPct = Double(jankFrames.count) * 100 / Double(totalFrames)
        result.append("\n📊 Framestats 分析 (budget=\(String(format: "%.2f", budgetMs))ms)")
        result.append("  总帧数: \(totalFrames) | 掉帧: \(jankFrames.count) (\(String(format: "%.1f", jankPct))%)")
        result.append("")

        if !jankFrames.isEmpty {
            result.append("  掉帧 Top 10:")
            result.append("  Total    Input    Anim     Layout   Draw     SYNC     CMD      瓶颈")
            result.append("  " + String(repeating: "─", count: 72))

            let sorted = jankFrames.sorted { $0.total > $1.total }.prefix(10)
            for f in sorted {
                let phases: [(String, Double)] = [
                    ("IN", f.input), ("AN", f.anim), ("LY", f.traversal),
                    ("DR", f.draw), ("SY", f.sync), ("CM", f.command)
                ]
                let bottleneck = phases.max(by: { $0.1 < $1.1 })?.0 ?? "?"
                let line = String(format: "  %-8.1f %-8.1f %-8.1f %-8.1f %-8.1f %-8.1f %-8.1f",
                    f.total, f.input, f.anim, f.traversal, f.draw, f.sync, f.command)
                result.append("\(line) ← \(bottleneck)")
            }

            // 瓶颈统计
            var bottleneckCount: [String: Int] = [:]
            for f in jankFrames {
                let phases: [(String, Double)] = [
                    ("UI Thread", f.traversal + f.draw),
                    ("SYNC (texture)", f.sync),
                    ("COMMAND (GPU)", f.command),
                    ("INPUT", f.input),
                    ("ANIMATION", f.anim)
                ]
                let bn = phases.max(by: { $0.1 < $1.1 })?.0 ?? "?"
                bottleneckCount[bn, default: 0] += 1
            }

            result.append("")
            result.append("  瓶颈分布:")
            for (bn, count) in bottleneckCount.sorted(by: { $0.value > $1.value }) {
                let pct = Double(count) * 100 / Double(jankFrames.count)
                let bar = String(repeating: "█", count: Int(pct / 5))
                let bnPadded = bn.padding(toLength: 20, withPad: " ", startingAt: 0)
                result.append("    \(bnPadded) \(String(format: "%3d", count)) (\(String(format: "%4.1f", pct))%) \(bar)")
            }
        }

        return result.joined(separator: "\n") + "\n"
    }

    // MARK: - Helpers

    private func runAsync(_ args: [String], completion: @escaping (String) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let process = Process()
            process.executableURL = URL(fileURLWithPath: self.adbPath)
            process.arguments = args
            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe
            do {
                try process.run()
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                completion(String(data: data, encoding: .utf8) ?? "")
            } catch {
                completion("ERROR: \(error.localizedDescription)")
            }
        }
    }

    private func appendOutput(_ text: String) {
        output += text
    }

    private var timeStr: String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f.string(from: Date())
    }

    // MARK: - Chart

    func openChart() {
        generateChartHTML()
        let url = URL(string: "http://127.0.0.1:8765/gfxinfo_chart.html")!
        NSWorkspace.shared.open(url)
        statusText = "图表已打开"
    }

    func toggleAutoCollect() {
        if autoCollecting {
            autoCollecting = false
            autoTimer?.invalidate()
            autoTimer = nil
            statusText = "自动采集已停止"
        } else {
            autoCollecting = true
            statusText = "自动采集中... (每\(Int(autoInterval))秒)"
            appendOutput("🔄 自动采集已开启 (每\(Int(autoInterval))秒)\n")
            // 先 reset
            runAsync(["shell", "dumpsys", "gfxinfo", packageName, "reset"]) { _ in }
            let timer = Timer(timeInterval: autoInterval, repeats: true) { [weak self] _ in
                self?.autoCollectOnce()
            }
            RunLoop.main.add(timer, forMode: .common)
            autoTimer = timer
        }
    }

    private func autoCollectOnce() {
        guard autoCollecting else { return }
        runAsync(["shell", "dumpsys", "gfxinfo", packageName]) { [weak self] raw in
            DispatchQueue.main.async {
                // 判断 adb 是否失败
                if raw.contains("ERROR") || raw.contains("no devices") || raw.isEmpty {
                    // 设备断开，静默跳过不刷屏
                    self?.statusText = "自动采集中... 等待设备连接"
                    return
                }
                let before = self?.chartCount ?? 0
                self?.addToChart(raw: raw)
                let after = self?.chartCount ?? 0
                if after > before {
                    let sessions = self?.chartHistory ?? []
                    if let last = sessions.last,
                       let fps = last["avgFps"] as? Double,
                       let jankPct = last["jankPercent"] as? Double,
                       let count = last["frameCount"] as? Int {
                        let icon = jankPct > 20 ? "🔴" : jankPct > 10 ? "🟡" : "🟢"
                        self?.appendOutput("\(icon) #\(after) [\(self?.timeStr ?? "")] \(fps)fps | \(count)帧 | 掉帧\(jankPct)%\n")
                    }
                }
                // Total frames = 0 时静默跳过（刚 reset 后正常现象）
                self?.runAsync(["shell", "dumpsys", "gfxinfo", self?.packageName ?? "", "reset"]) { _ in }
                self?.statusText = "自动采集中... #\(self?.chartCount ?? 0) (每\(Int(self?.autoInterval ?? 3))秒)"
            }
        }
    }

    private func addToChart(raw: String) {
        // 尝试从 framestats 原始数据提取
        let frameTimes = extractFrameTimes(from: raw)

        if !frameTimes.isEmpty {
            // 有原始帧数据，精确计算
            let total = frameTimes.count
            let budgetMs = 8.33
            let jank = frameTimes.filter { $0 > budgetMs }.count
            let sum = frameTimes.reduce(0, +)
            let avgFps = sum > 0 ? 1000.0 / (sum / Double(total)) : 0
            let sorted = frameTimes.sorted()

            let entry: [String: Any] = [
                "timestamp": timeStr,
                "frameTimes": frameTimes,
                "frameCount": total,
                "jankCount": jank,
                "jankPercent": round(Double(jank) * 1000 / Double(total)) / 10,
                "avgFps": round(avgFps * 10) / 10,
                "avgFrameTime": round(sum / Double(total) * 100) / 100,
                "p50": sorted[total / 2],
                "p90": sorted[Int(Double(total) * 0.9)],
                "p99": sorted[min(Int(Double(total) * 0.99), total - 1)],
                "maxFrameTime": sorted.last ?? 0
            ]
            chartHistory.append(entry)
            chartCount = chartHistory.count
            saveChartHistory()
        } else {
            // 无原始帧数据，从摘要统计中提取
            addToChartFromSummary(raw: raw)
        }
    }

    private func addToChartFromSummary(raw: String) {
        var totalFrames = 0
        var jankyFrames = 0
        var jankyPercent = 0.0
        var p50 = 0.0
        var p90 = 0.0
        var p95 = 0.0
        var p99 = 0.0

        for line in raw.split(separator: "\n") {
            let l = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if l.contains("Total frames rendered:") {
                if let m = l.range(of: #"(\d+)"#, options: .regularExpression) {
                    totalFrames = Int(l[m]) ?? 0
                }
            } else if l.contains("Janky frames:") && !l.contains("legacy") {
                if let m = l.range(of: #"(\d+)\s*\("#, options: .regularExpression) {
                    let numStr = l[m].trimmingCharacters(in: CharacterSet(charactersIn: " ("))
                    jankyFrames = Int(numStr) ?? 0
                }
                if let m = l.range(of: #"\(([\d.]+)%\)"#, options: .regularExpression) {
                    let pctStr = l[m].trimmingCharacters(in: CharacterSet(charactersIn: "(%)"))
                    jankyPercent = Double(pctStr) ?? 0
                }
            } else if l.contains("50th percentile:") {
                if let m = l.range(of: #"(\d+)ms"#, options: .regularExpression) {
                    p50 = Double(l[m].replacingOccurrences(of: "ms", with: "")) ?? 0
                }
            } else if l.contains("90th percentile:") {
                if let m = l.range(of: #"(\d+)ms"#, options: .regularExpression) {
                    p90 = Double(l[m].replacingOccurrences(of: "ms", with: "")) ?? 0
                }
            } else if l.contains("95th percentile:") {
                if let m = l.range(of: #"(\d+)ms"#, options: .regularExpression) {
                    p95 = Double(l[m].replacingOccurrences(of: "ms", with: "")) ?? 0
                }
            } else if l.contains("99th percentile:") {
                if let m = l.range(of: #"(\d+)ms"#, options: .regularExpression) {
                    p99 = Double(l[m].replacingOccurrences(of: "ms", with: "")) ?? 0
                }
            }
        }

        guard totalFrames > 0 else { return }

        // 从百分位估算 FPS（用 p50 作为典型帧耗时）
        let avgFrameTime = p50 > 0 ? p50 : 8.33
        let avgFps = min(120, 1000.0 / avgFrameTime)

        let entry: [String: Any] = [
            "timestamp": timeStr,
            "frameTimes": [] as [Double],  // 无逐帧数据
            "frameCount": totalFrames,
            "jankCount": jankyFrames,
            "jankPercent": jankyPercent,
            "avgFps": round(avgFps * 10) / 10,
            "avgFrameTime": avgFrameTime,
            "p50": p50,
            "p90": p90,
            "p99": p99,
            "maxFrameTime": p99  // 用 p99 近似
        ]

        chartHistory.append(entry)
        chartCount = chartHistory.count
        saveChartHistory()
    }

    private func extractFrameTimes(from raw: String) -> [Double] {
        var frameTimes: [Double] = []
        // 动态解析列头位置
        var iIntendedVsync = 1
        var iFrameCompleted = 13
        var minCols = 14

        let lines = raw.split(separator: "\n", omittingEmptySubsequences: false)
        for line in lines {
            let l = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if l.hasPrefix("Flags,") {
                let headers = l.split(separator: ",")
                for (i, h) in headers.enumerated() {
                    let name = h.trimmingCharacters(in: .whitespacesAndNewlines)
                    if name == "IntendedVsync" { iIntendedVsync = i }
                    else if name == "FrameCompleted" { iFrameCompleted = i }
                }
                minCols = max(iIntendedVsync, iFrameCompleted) + 1
                break
            }
        }

        for line in lines {
            let l = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let first = l.first, first.isNumber else { continue }
            let parts = l.split(separator: ",")
            guard parts.count >= minCols else { continue }
            guard let intendedVsync = Int64(parts[iIntendedVsync].trimmingCharacters(in: .whitespacesAndNewlines)),
                  let frameCompleted = Int64(parts[iFrameCompleted].trimmingCharacters(in: .whitespacesAndNewlines)),
                  intendedVsync > 0, frameCompleted > intendedVsync else { continue }
            let totalMs = Double(frameCompleted - intendedVsync) / 1_000_000.0
            if totalMs > 0 && totalMs < 500 {
                frameTimes.append(round(totalMs * 100) / 100)
            }
        }
        return frameTimes
    }

    private func loadChartHistory() {
        guard let data = try? Data(contentsOf: chartDataFile),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sessions = json["sessions"] as? [[String: Any]] else {
            chartHistory = []
            chartCount = 0
            return
        }
        chartHistory = sessions
        chartCount = sessions.count
    }

    private func saveChartHistory() {
        let json: [String: Any] = ["sessions": chartHistory]
        guard let data = try? JSONSerialization.data(withJSONObject: json, options: [.prettyPrinted]) else { return }
        try? data.write(to: chartDataFile)
    }

    private func startHTTPServer() {
        generateChartHTML()
        httpServer = ChartHTTPServer(port: 8765, directory: chartDataFile.deletingLastPathComponent())
        httpServer?.start()
    }

    private func generateChartHTML() {
        let html = """
        <!DOCTYPE html>
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
          <div class="chart-card"><h3>帧率趋势 (FPS)</h3><div class="chart-container"><canvas id="fpsChart"></canvas></div></div>
          <div class="chart-card"><h3>掉帧率趋势 (%)</h3><div class="chart-container"><canvas id="jankChart"></canvas></div></div>
        </div>
        <div class="session-list"><h3>采集记录 (点击查看详情)</h3><div id="sessionItems"></div></div>
        <div class="detail-panel" id="detailPanel">
          <h3 id="detailTitle">第 N 次采集</h3>
          <div class="detail-stats" id="detailStats"></div>
          <div class="chart-container detail-chart"><canvas id="detailChart"></canvas></div>
        </div>
        <script>
        let history={sessions:[]},fpsChart,jankChart,detailChart,lastCount=0;
        async function loadData(){try{const r=await fetch('/gfxinfo_history.json?'+Date.now());const d=await r.json();if(d.sessions.length===lastCount)return;history=d;lastCount=history.sessions.length;renderCharts();renderSessions();document.getElementById('status').textContent=history.sessions.length+' 次采集'}catch(e){document.getElementById('status').textContent='加载失败'}}
        function renderCharts(){const labels=history.sessions.map((s,i)=>'#'+(i+1)+' '+s.timestamp);const fpsData=history.sessions.map(s=>s.avgFps);const jankData=history.sessions.map(s=>s.jankPercent);const opts={responsive:true,maintainAspectRatio:false,animation:false,interaction:{mode:'index',intersect:false},plugins:{legend:{display:false}},scales:{x:{ticks:{color:'#888',maxRotation:45,font:{size:10}},grid:{color:'#333'}},y:{ticks:{color:'#888'},grid:{color:'#333'}}},onClick:(evt,el)=>{if(el.length>0)showDetail(el[0].index)}};if(fpsChart)fpsChart.destroy();fpsChart=new Chart(document.getElementById('fpsChart'),{type:'line',data:{labels,datasets:[{data:fpsData,borderColor:'#64ffda',backgroundColor:'rgba(100,255,218,0.1)',fill:true,tension:0.3,pointRadius:4,pointHoverRadius:7}]},options:{...opts,scales:{...opts.scales,y:{...opts.scales.y,suggestedMin:0,suggestedMax:120}}}});if(jankChart)jankChart.destroy();jankChart=new Chart(document.getElementById('jankChart'),{type:'line',data:{labels,datasets:[{data:jankData,borderColor:'#ff5252',backgroundColor:'rgba(255,82,82,0.1)',fill:true,tension:0.3,pointRadius:4,pointHoverRadius:7}]},options:{...opts,scales:{...opts.scales,y:{...opts.scales.y,suggestedMin:0}}}})}
        function renderSessions(){const c=document.getElementById('sessionItems');c.innerHTML=history.sessions.map((s,i)=>{const color=s.jankPercent>20?'#ff5252':s.jankPercent>10?'#ffd740':'#64ffda';return '<span class="session-item" onclick="showDetail('+i+')" style="border-left:3px solid '+color+'">#'+(i+1)+' '+s.timestamp+' | '+s.avgFps+'fps | '+s.jankPercent+'%卡</span>'}).join('')}
        function showDetail(i){const s=history.sessions[i];if(!s)return;document.querySelectorAll('.session-item').forEach((el,j)=>el.classList.toggle('active',j===i));const p=document.getElementById('detailPanel');p.classList.add('show');document.getElementById('detailTitle').textContent='第 '+(i+1)+' 次采集 — '+s.timestamp;document.getElementById('detailStats').innerHTML='<div class="stat-box"><div class="value">'+s.avgFps+'</div><div class="label">平均FPS</div></div><div class="stat-box"><div class="value">'+s.frameCount+'</div><div class="label">总帧数</div></div><div class="stat-box"><div class="value">'+s.jankCount+'</div><div class="label">掉帧数</div></div><div class="stat-box"><div class="value">'+s.jankPercent+'%</div><div class="label">掉帧率</div></div><div class="stat-box"><div class="value">'+s.avgFrameTime+'ms</div><div class="label">平均帧耗时</div></div><div class="stat-box"><div class="value">'+s.p90+'ms</div><div class="label">P90</div></div><div class="stat-box"><div class="value">'+s.p99+'ms</div><div class="label">P99</div></div><div class="stat-box"><div class="value">'+s.maxFrameTime+'ms</div><div class="label">最大帧耗时</div></div>';if(detailChart)detailChart.destroy();const ft=s.frameTimes||[];detailChart=new Chart(document.getElementById('detailChart'),{type:'bar',data:{labels:ft.map((_,j)=>j+1),datasets:[{data:ft,backgroundColor:ft.map(t=>t>16.67?'#ff5252':t>8.33?'#ffd740':'#64ffda'),borderWidth:0}]},options:{responsive:true,maintainAspectRatio:false,animation:false,plugins:{legend:{display:false}},scales:{x:{display:false},y:{ticks:{color:'#888'},grid:{color:'#333'}}}}});p.scrollIntoView({behavior:'smooth'})}
        function refresh(){lastCount=0;loadData()}
        function clearData(){if(confirm('确定清空？')){fetch('/gfxinfo_history.json',{method:'PUT',body:JSON.stringify({sessions:[]})});history={sessions:[]};lastCount=0;renderCharts();renderSessions();document.getElementById('detailPanel').classList.remove('show')}}
        loadData();setInterval(loadData,2000);
        </script>
        </body>
        </html>
        """
        try? html.write(to: chartHTMLFile, atomically: true, encoding: .utf8)
    }
}

// MARK: - Simple HTTP Server for Chart

import Network

class ChartHTTPServer {
    private var listener: NWListener?
    private let port: UInt16
    private let directory: URL

    init(port: UInt16, directory: URL) {
        self.port = port
        self.directory = directory
    }

    func start() {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return }
        do {
            listener = try NWListener(using: .tcp, on: nwPort)
        } catch {
            return
        }

        listener?.newConnectionHandler = { [weak self] connection in
            self?.handleConnection(connection)
        }
        listener?.start(queue: DispatchQueue.global(qos: .utility))
    }

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: DispatchQueue.global(qos: .utility))
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, _, _ in
            guard let self = self, let data = data, let request = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            self.processRequest(request, connection: connection)
        }
    }

    private func processRequest(_ request: String, connection: NWConnection) {
        let lines = request.split(separator: "\r\n")
        guard let firstLine = lines.first else { connection.cancel(); return }
        let parts = firstLine.split(separator: " ")
        guard parts.count >= 2 else { connection.cancel(); return }

        let method = String(parts[0])
        var path = String(parts[1])

        // Strip query params
        if let qIdx = path.firstIndex(of: "?") {
            path = String(path[..<qIdx])
        }

        if method == "PUT" && path == "/gfxinfo_history.json" {
            // Handle PUT for clearing data
            if let bodyRange = request.range(of: "\r\n\r\n") {
                let body = String(request[bodyRange.upperBound...])
                let fileURL = directory.appendingPathComponent("gfxinfo_history.json")
                try? body.write(to: fileURL, atomically: true, encoding: .utf8)
            }
            let response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            connection.send(content: response.data(using: .utf8), completion: .contentProcessed { _ in
                connection.cancel()
            })
            return
        }

        // GET
        if path == "/" { path = "/gfxinfo_chart.html" }
        let fileName = String(path.dropFirst()) // remove leading /
        let fileURL = directory.appendingPathComponent(fileName)

        guard FileManager.default.fileExists(atPath: fileURL.path),
              let fileData = try? Data(contentsOf: fileURL) else {
            let response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            connection.send(content: response.data(using: .utf8), completion: .contentProcessed { _ in
                connection.cancel()
            })
            return
        }

        let contentType: String
        if fileName.hasSuffix(".html") { contentType = "text/html; charset=utf-8" }
        else if fileName.hasSuffix(".json") { contentType = "application/json" }
        else { contentType = "application/octet-stream" }

        let header = "HTTP/1.1 200 OK\r\nContent-Type: \(contentType)\r\nContent-Length: \(fileData.count)\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        var responseData = header.data(using: .utf8)!
        responseData.append(fileData)

        connection.send(content: responseData, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }
}
