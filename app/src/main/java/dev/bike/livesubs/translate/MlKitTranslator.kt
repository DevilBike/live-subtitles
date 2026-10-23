package dev.bike.livesubs.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import com.google.mlkit.nl.translate.Translator as MlKitClient

/**
 * Google ML Kit's on-device translator into English. Each source language is
 * a ~30 MB model, downloaded once (see [download]); translation then runs
 * offline and takes tens of milliseconds per sentence.
 */
class MlKitTranslator : Translator, Closeable {

    private val clients = HashMap<String, MlKitClient>()

    override suspend fun translate(text: String, sourceLanguage: String): String {
        val client = clients.getOrPut(sourceLanguage) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitLanguage(sourceLanguage))
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options).also {
                // A no-op when the model is already on the phone, which ModelStore makes sure of.
                it.downloadModelIfNeeded().await()
            }
        }
        return client.translate(text).await()
    }

    override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    companion object {
        private fun mlKitLanguage(code: String): String =
            requireNotNull(TranslateLanguage.fromLanguageTag(code)) { "No ML Kit translation from $code" }

        private fun model(code: String) = TranslateRemoteModel.Builder(mlKitLanguage(code)).build()

        /** Downloads the translation models for [languages] (codes like "ja"), if missing. */
        suspend fun download(languages: List<String>) {
            val manager = RemoteModelManager.getInstance()
            for (code in languages) manager.download(model(code), DownloadConditions.Builder().build()).await()
        }

        /** Whether the models for all [languages] are on the phone. */
        suspend fun isDownloaded(languages: List<String>): Boolean {
            val manager = RemoteModelManager.getInstance()
            return languages.all { manager.isModelDownloaded(model(it)).await() }
        }
    }
}
