package com.ahmad.partsofspeechdictionary.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class PronunciationHelper(
    context: Context
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var isReady = false

    init {
        textToSpeech = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            isReady = result != null &&
                result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    fun speak(word: String) {
        val text = word.trim()
        if (isReady && text.isNotEmpty()) {
            textToSpeech?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "wordwise_pronunciation"
            )
        }
    }

    fun stop() {
        textToSpeech?.stop()
    }

    fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isReady = false
    }
}
