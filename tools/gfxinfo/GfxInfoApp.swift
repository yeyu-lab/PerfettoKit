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
}
