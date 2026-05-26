package io.github.perfettokit.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容 API Provider — 支持 DeepSeek、Qwen、OpenAI 等 OpenAI 格式的 API。
 *
 * 用法:
 *   val provider = OpenAICompatProvider(
 *       apiKey = "sk-xxx",
 *       baseUrl = "https://api.deepseek.com/v1",  // 或 OpenAI/Qwen
 *       model = "deepseek-chat"
 *   )
 */
class OpenAICompatProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini",
    private val maxTokens: Int = 1024
) : AIProvider {

    companion object {
        private const val TAG = "PerfettoKit.AI"
    }

    override fun isAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun enhance(request: AIRequest): AIResponse? {
        if (!isAvailable()) return null

        return try {
            val response = callAPI(request.toPrompt())
            parseResponse(response)
        } catch (e: Exception) {
            Log.w(TAG, "AI enhance failed: ${e.message}")
            null
        }
    }

    private fun callAPI(prompt: String): String {
        val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val error = conn.errorStream?.bufferedReader()?.readText() ?: "unknown"
            throw RuntimeException("API returned $responseCode: $error")
        }

        return BufferedReader(InputStreamReader(conn.inputStream)).readText()
    }

    private fun parseResponse(raw: String): AIResponse {
        val json = JSONObject(raw)
        val content = json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        // 简单解析 LLM 回复，提取结构化部分
        val lines = content.lines()
        val suggestions = lines.filter { it.trimStart().startsWith("-") || it.trimStart().matches(Regex("^\\d+\\..*")) }
        val summary = lines.firstOrNull { it.isNotBlank() } ?: ""

        // 提取代码块
        val codeRegex = Regex("```\\w*\\n([\\s\\S]*?)```")
        val codeMatch = codeRegex.find(content)

        return AIResponse(
            summary = summary,
            suggestions = suggestions,
            codeSnippet = codeMatch?.groupValues?.get(1)?.trim(),
            rawResponse = content
        )
    }
}
