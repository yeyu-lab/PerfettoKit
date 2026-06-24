package io.github.perfettokit.i18n

import java.util.Locale

object I18n {
    fun isChinese(): Boolean = Locale.getDefault().language.startsWith("zh")

    fun tr(zh: String, en: String): String = if (isChinese()) zh else en
}
