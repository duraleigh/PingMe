// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.service.links

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * Strips tracking parameters from links with the ClearURLs rule set (UI_DESIGN.md 10.11,
 * BUILD_PLAN.md P4.3). The rules ship in `assets/clearurls/` under the LGPL-3.0, with the
 * licence beside them; `./gradlew :core:service:updateClearUrls` fetches a newer copy.
 * Per provider whose pattern matches and no exception does: unwrap redirections, drop the
 * raw-rule matches, and remove query parameters named by its rules.
 */
class CleanLinks(
    rulesJson: String,
) {
    private class Provider(
        val pattern: Regex,
        val exceptions: List<Regex>,
        val redirections: List<Regex>,
        val rawRules: List<Regex>,
        val params: List<Regex>,
    )

    private val providers: List<Provider> =
        Json
            .parseToJsonElement(rulesJson)
            .jsonObject["providers"]
            ?.jsonObject
            ?.values
            ?.mapNotNull { it as? JsonObject }
            ?.map { p ->
                Provider(
                    pattern = regex(p["urlPattern"]?.jsonPrimitive?.content ?: ".*"),
                    exceptions = p.list("exceptions").map(::regex),
                    redirections = p.list("redirections").map(::regex),
                    rawRules = p.list("rawRules").map(::regex),
                    params = (p.list("rules") + p.list("referralMarketing")).map { regex("^(?:$it)$") },
                )
            }.orEmpty()

    /** [url] with its tracking removed; unchanged when nothing applies or it is not a link. */
    fun clean(
        url: String,
        depth: Int = 0,
    ): String {
        var current = url
        for (provider in providers) {
            val applies =
                provider.pattern.containsMatchIn(current) && provider.exceptions.none { it.containsMatchIn(current) }
            if (!applies) continue
            val target = provider.redirections.firstNotNullOfOrNull { it.find(current)?.groupValues?.getOrNull(1) }
            if (target != null && depth < MAX_REDIRECTIONS) return clean(decode(target), depth + 1)
            val stripped = provider.rawRules.fold(current) { link, rule -> rule.replace(link, "") }
            current = dropParams(stripped, provider.params)
        }
        return current
    }

    /** Every link in [text] cleaned in place; the rest of the text is untouched. */
    fun cleanText(text: String): String = LINK.replace(text) { clean(it.value) }

    private fun dropParams(
        url: String,
        rules: List<Regex>,
    ): String {
        if (rules.isEmpty()) return url
        val hashAt = url.indexOf('#')
        val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
        val withoutFragment = if (hashAt >= 0) url.substring(0, hashAt) else url
        val queryAt = withoutFragment.indexOf('?')
        if (queryAt < 0) return url
        val base = withoutFragment.substring(0, queryAt)
        val kept =
            withoutFragment
                .substring(queryAt + 1)
                .split('&')
                .filter { part -> part.isNotEmpty() && rules.none { it.containsMatchIn(part.substringBefore('=')) } }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun regex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private fun JsonObject.list(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    companion object {
        private const val MAX_REDIRECTIONS = 3

        /** Links in plain text, as the chat finds them. */
        val LINK = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")

        /** The bundled rules, read once from the app's assets. */
        fun fromAssets(context: android.content.Context): CleanLinks =
            CleanLinks(
                context.assets
                    .open("clearurls/data.minify.json")
                    .bufferedReader()
                    .use { it.readText() },
            )
    }
}
