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
    val translation: String = "",
    val collocations: String = ""
)

object OnlineDictionaryClient {
    private fun fetchRelatedWords(
        relation: String,
        word: String
    ): List<String> {
        val encodedWord = URLEncoder.encode(word, "UTF-8")
        val pattern = if (relation == "lc" || relation == "rc") {
            "sp=*&"
        } else {
            ""
        }
        val url = URL(
            "https://api.datamuse.com/words?$pattern$relation=$encodedWord&max=10"
        )

        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode !in 200..299) {
                return emptyList()
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            json.parseToJsonElement(response).jsonArray.mapNotNull { item ->
                item.jsonObject["word"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchCollocations(word: String): String {
        val commonWords = setOf(
            "a", "an", "the", "and", "or", "but", "if", "of", "in",
            "on", "at", "by", "for", "from", "as", "is", "are", "was",
            "were", "be", "been", "being", "it", "its", "not", "."
        )

        fun usefulContextWords(relation: String): List<String> =
            fetchRelatedWords(relation, word)
                .asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .filter { it.matches(Regex("[a-z][a-z'-]*")) }
                .filter { it !in commonWords }
                .filter { it != word.lowercase() }
                .distinct()
                .take(5)
                .toList()

        val preceding = usefulContextWords("lc").map { "$it $word" }
        val following = usefulContextWords("rc").map { "$word $it" }

        return (following + preceding)
            .distinct()
            .joinToString(", ")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun fetchOriginFromWiktionary(word: String): String {
        val encodedWord = URLEncoder.encode(word, "UTF-8")
        val url = URL(
            "https://en.wiktionary.org/w/api.php?action=parse&page=$encodedWord&prop=wikitext&format=json&formatversion=2"
        )
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty(
                "User-Agent",
                "WordwiseDictionary/1.0 (educational dictionary app)"
            )

            if (connection.responseCode !in 200..299) {
                return ""
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val root = json.parseToJsonElement(response).jsonObject
            val wikitext = root["parse"]
                ?.jsonObject
                ?.get("wikitext")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return ""

            val etymology = Regex(
                "(?is)==+\\s*Etymology\\s*==+\\s*(.*?)(?=\\n==+\\s*[^=]|$)"
            ).find(wikitext)?.groupValues?.get(1) ?: return ""

            etymology
                .replace(Regex("<ref[^>]*>.*?</ref>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<ref[^>]*/>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\[\\[([^]|]+)\\|([^]]+)]]"), "$2")
                .replace(Regex("\\[\\[([^]]+)]]"), "$1")
                .replace(Regex("'{2,5}"), "")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\{\\{([^{}]*)}}")) { match ->
                    val parts = match.groupValues[1].split("|")
                    val preferred = parts.drop(1)
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.contains("=") }
                        .lastOrNull()
                    preferred ?: ""
                }
                .replace(Regex("\\[\\[([^]]+)]]"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(500)
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }

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

                    if (pronunciation.isBlank()) {
                        val phonetics = obj["phonetics"] as? JsonArray
                        pronunciation = phonetics
                            ?.firstNotNullOfOrNull { phonetic ->
                                phonetic.jsonObject["text"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                            }
                            .orEmpty()
                    }
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

            // Fallback: ask Datamuse when the dictionary has no synonyms.
            if (synonyms.isEmpty()) {
                synonyms.addAll(fetchRelatedWords("rel_syn", cleanedWord))
            }

            // Fallback: ask Datamuse when the dictionary has no antonyms.
            if (antonyms.isEmpty()) {
                antonyms.addAll(fetchRelatedWords("rel_ant", cleanedWord))
            }

            return OnlineDictionaryResult(
                word = cleanedWord,
                pronunciation = pronunciation,
                definitions = definitions,
                synonyms = synonyms.joinToString(", "),
                antonyms = antonyms.joinToString(", "),
                origin = origin.ifBlank {
                    fetchOriginFromWiktionary(cleanedWord)
                },
                collocations = fetchCollocations(cleanedWord)
            )
        } finally {
            connection.disconnect()
        }
    }
}
