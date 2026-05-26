package io.github.perfettokit.report

/**
 * 报告输出接口。
 */
interface Reporter {
    fun report(report: DiagnosisReport)
}
