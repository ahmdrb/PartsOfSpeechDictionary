package com.ahmad.partsofspeechdictionary.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineDefinition(
    val partOfSpeech: String,
    val definition: String,
    val example: String
)

data class OnlineDictionaryResult(
    val word: String,
    val pronunciation: String,
    val definitions: List<OnlineDefinition>,
    val synonyms: String = "",
    val antonyms: String = "",
    val origin: String = "",
    val translation: String = ""
)

object OnlineDictionaryClient {
    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(word: String): OnlineDictionaryResult {
        val cleanedWord = word.trim()
        require(cleanedWord.isNotBlank()) { "Enter a word first." }

        val encodedWord = URLEncoder.encode(cleanedWord, "UTF-8")
        val url = URL(
            "https://api.dictionaryapi.dev/api/v2/entries/en/$encodedWord"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode

            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw NoSuchElementException(
                    "No online dictionary entry was found for \"$cleanedWord\"."
                )
            }

            if (status !in 200..299) {
                throw IllegalStateException(
                    "Dictionary service returned HTTP $status."
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val entries = json.parseToJsonElement(response).jsonArray
            val definitions = mutableListOf<OnlineDefinition>()
            val synonyms = linkedSetOf<String>()
            val antonyms = linkedSetOf<String>()
            var pronunciation = ""
            var origin = ""

            fun readWords(value: kotlinx.serialization.json.JsonElement?, target: MutableSet<String>) {
                val array = value as? JsonArray ?: return
                array.forEach { item ->
                    val wordValue = item.jsonPrimitive.contentOrNull.orEmpty().trim()
                    if (wordValue.isNotBlank()) target.add(wordValue)
                }
            }

            for (entry in entries) {
                val obj = entry.jsonObject

                if (pronunciation.isBlank()) {
                    pronunciation = obj["phonetic"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                }

                if (origin.isBlank()) {
                    origin = obj["origin"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                }

                readWords(obj["synonyms"], synonyms)
                readWords(obj["antonyms"], antonyms)

                val meanings = obj["meanings"] as? JsonArray ?: continue

                for (meaningElement in meanings) {
                    val meaning = meaningElement.jsonObject

                    readWords(meaning["synonyms"], synonyms)
                    readWords(meaning["antonyms"], antonyms)

                    val partOfSpeech = meaning["partOfSpeech"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()

                    val definitionArray =
                        meaning["definitions"] as? JsonArray ?: continue

                    for (definitionElement in definitionArray) {
                        val definitionObject = definitionElement.jsonObject

                        val definition = definitionObject["definition"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()

                        val example = definitionObject["example"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()

                        readWords(definitionObject["synonyms"], synonyms)
                        readWords(definitionObject["antonyms"], antonyms)

                        if (definition.isNotBlank()) {
                            definitions.add(
                                OnlineDefinition(
                                    partOfSpeech = partOfSpeech,
                                    definition = definition,
                                    example = example
                                )
                            )
                        }
                    }
                }
            }

            if (definitions.isEmpty()) {
                throw IllegalStateException(
                    "The dictionary returned no definitions for this word."
                )
            }

            return OnlineDictionaryResult(
                word = cleanedWord,
                pronunciation = pronunciation,
                definitions = definitions,
                synonyms = synonyms.joinToString(", "),
                antonyms = antonyms.joinToString(", "),
                origin = origin
            )
        } finally {
            connection.disconnect()
        }
    }
}
