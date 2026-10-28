package com.ahmad.partsofspeechdictionary.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        val libreResult = translateWithLibreTranslate(
            text,
            targetLanguage,
            apiKey
        )

        if (libreResult.isNotBlank()) return libreResult

        return translateWithMyMemory(text, targetLanguage)
    }

    private fun translateWithLibreTranslate(
        text: String,
        targetLanguage: String,
        apiKey: String
    ): String {
        val connection = URL("https://libretranslate.com/translate")
            .openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.setRequestProperty("Accept", "application/json")

            val body = buildJsonObject {
                put("q", text)
                put("source", "en")
                put("target", targetLanguage)
                put("format", "text")
                if (apiKey.isNotBlank()) put("api_key", apiKey)
            }.toString()

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode !in 200..299) return ""

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            json.parseToJsonElement(response)
                .jsonObject["translatedText"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }

    private fun translateWithMyMemory(
        text: String,
        targetLanguage: String
    ): String {
        val query = URLEncoder.encode(text, "UTF-8")
        val languagePair = URLEncoder.encode("en|$targetLanguage", "UTF-8")
        val url = URL(
            "https://api.mymemory.translated.net/get?q=$query&langpair=$languagePair"
        )
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode !in 200..299) return ""

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val root = json.parseToJsonElement(response).jsonObject
            val status = root["responseStatus"]
                ?.jsonPrimitive
                ?.content
                ?: return ""

            if (status != "200") return ""

            root["responseData"]
                ?.jsonObject
                ?.get("translatedText")
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
                .orEmpty()
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }
}
