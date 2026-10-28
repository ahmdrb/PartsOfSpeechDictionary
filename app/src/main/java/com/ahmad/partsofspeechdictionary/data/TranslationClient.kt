package com.ahmad.partsofspeechdictionary.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

object TranslationClient {
    private val json = Json { ignoreUnknownKeys = true }

    fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String = ""
    ): String {
        if (text.isBlank() || targetLanguage.isBlank()) return ""

        val connection = URL("https://libretranslate.com/translate")
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
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

            return json.parseToJsonElement(response)
                .jsonObject["translatedText"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                .orEmpty()
        } catch (_: Exception) {
            return ""
        } finally {
            connection.disconnect()
        }
    }
}
