package com.ahmad.partsofspeechdictionary.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TranslationClient {
    private val json = Json { ignoreUnknownKeys = true }

    fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String = ""
    ): String {
        if (text.isBlank() || targetLanguage.isBlank()) return ""

        val encodedWord = URLEncoder.encode(text.trim(), "UTF-8")
        val url = URL(
            "https://api.wiktapi.dev/v1/en/word/$encodedWord/translations"
        )
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode !in 200..299) return ""

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val root = json.parseToJsonElement(response).jsonObject
            val groups = root["translations"]?.jsonArray ?: return ""

            val wantedCodes = when (targetLanguage.lowercase()) {
                "zh" -> setOf("zh", "cmn", "zh-hans", "zh-hant")
                else -> setOf(targetLanguage.lowercase())
            }

            val results = mutableListOf<String>()

            for (group in groups) {
                val groupObject = group.jsonObject
                val partOfSpeech = groupObject["pos"]
                    ?.jsonPrimitive?.contentOrNull.orEmpty()

                val translations = groupObject["translations"]?.jsonArray
                    ?: continue

                for (item in translations) {
                    val entry = item.jsonObject
                    val languageCode = (
                        entry["lang_code"]?.jsonPrimitive?.contentOrNull
                            ?: entry["code"]?.jsonPrimitive?.contentOrNull
                        ).orEmpty().lowercase()

                    if (languageCode !in wantedCodes) continue

                    val translatedWord = entry["word"]
                        ?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        .orEmpty()

                    if (translatedWord.isBlank()) continue

                    val sense = entry["sense"]
                        ?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        .orEmpty()

                    val romanization = entry["roman"]
                        ?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        .orEmpty()

                    val details = listOfNotNull(
                        sense.takeIf { it.isNotBlank() },
                        partOfSpeech.takeIf { it.isNotBlank() },
                        romanization.takeIf { it.isNotBlank() }
                            ?.let { "romanization: $it" }
                    ).joinToString("; ")

                    val result = if (details.isNotBlank()) {
                        "$translatedWord ($details)"
                    } else {
                        translatedWord
                    }

                    results.add(result)
                }
            }

            results.distinct().joinToString("\n")
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }
}
