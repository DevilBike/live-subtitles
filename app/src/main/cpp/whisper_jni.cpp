// JNI bridge between WhisperNative.kt and whisper.cpp.
//
// Deliberately thin: one call per whisper.cpp operation, and the streaming
// logic (VAD decisions, committed vs tentative text) lives in Kotlin where it
// can be unit tested. All calls on one context must come from one thread.

#include <jni.h>

#include <cstdio>
#include <string>
#include <vector>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "whisper.cpp"
#endif

namespace {

// whisper.cpp logs a line per call at INFO level; only pass on warnings and errors.
void log_callback(ggml_log_level level, const char * text, void *) {
    if (level < GGML_LOG_LEVEL_WARN) return;
#ifdef __ANDROID__
    __android_log_write(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, LOG_TAG, text);
#else
    fputs(text, stderr);
#endif
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

whisper_context * ctx_of(jlong ptr) { return reinterpret_cast<whisper_context *>(ptr); }

// Timestamp and other special tokens have ids from EOT upwards; only text tokens are returned.
bool is_text_token(whisper_context * ctx, whisper_token id) { return id < whisper_token_eot(ctx); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_initContext(JNIEnv * env, jobject, jstring path) {
    whisper_log_set(log_callback, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    return reinterpret_cast<jlong>(whisper_init_from_file_with_params(to_string(env, path).c_str(), cparams));
}

JNIEXPORT void JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_freeContext(JNIEnv *, jobject, jlong ctx) {
    if (ctx != 0) whisper_free(ctx_of(ctx));
}

JNIEXPORT jlong JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_initVad(JNIEnv * env, jobject, jstring path, jint threads) {
    whisper_log_set(log_callback, nullptr);
    whisper_vad_context_params vparams = whisper_vad_default_context_params();
    vparams.n_threads = threads;
    vparams.use_gpu = false;
    return reinterpret_cast<jlong>(whisper_vad_init_from_file_with_params(to_string(env, path).c_str(), vparams));
}

JNIEXPORT void JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_freeVad(JNIEnv *, jobject, jlong vad) {
    if (vad != 0) whisper_vad_free(reinterpret_cast<whisper_vad_context *>(vad));
}

// Speech probability for each 32 ms (512 sample) chunk of `samples`, or null on failure.
JNIEXPORT jfloatArray JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_vadProbs(JNIEnv * env, jobject, jlong vad, jfloatArray samples) {
    auto * vctx = reinterpret_cast<whisper_vad_context *>(vad);
    const jsize n = env->GetArrayLength(samples);
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    const bool ok = whisper_vad_detect_speech(vctx, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (!ok) return nullptr;

    const int n_probs = whisper_vad_n_probs(vctx);
    jfloatArray out = env->NewFloatArray(n_probs);
    env->SetFloatArrayRegion(out, 0, n_probs, whisper_vad_probs(vctx));
    return out;
}

// One recognition pass over `samples`. Returns 0 on success.
JNIEXPORT jint JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_full(
        JNIEnv * env, jobject, jlong ctx, jfloatArray samples, jstring language, jboolean translate,
        jint threads, jint audio_ctx, jbyteArray prompt_utf8) {
    std::string lang = to_string(env, language);
    std::string prompt;
    if (prompt_utf8 != nullptr) {
        const jsize len = env->GetArrayLength(prompt_utf8);
        prompt.resize(len);
        env->GetByteArrayRegion(prompt_utf8, 0, len, reinterpret_cast<jbyte *>(prompt.data()));
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads        = threads;
    params.translate        = translate;
    params.language         = lang.empty() ? "auto" : lang.c_str();
    params.detect_language  = false;
    // Each pass is independent; continuity comes from the prompt of committed text.
    params.no_context       = true;
    params.initial_prompt   = prompt.empty() ? nullptr : prompt.c_str();
    params.no_timestamps    = false;
    params.token_timestamps = true;
    params.single_segment   = false;
    params.print_special    = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.suppress_blank   = true;
    params.suppress_nst     = true;  // no "[Music]" / "(applause)" style tokens
    // The encoder normally processes 30 s of audio however short the input is.
    // Shrinking its context to the window length is the biggest speed win.
    params.audio_ctx        = audio_ctx;
    // No temperature fallback: a retry would double the pass time, and the next
    // pass 0.5 s later is a retry anyway.
    params.temperature_inc  = 0.0f;
    params.greedy.best_of   = 1;

    const jsize n = env->GetArrayLength(samples);
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    const int result = whisper_full(ctx_of(ctx), params, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_detectedLanguage(JNIEnv * env, jobject, jlong ctx) {
    const char * lang = whisper_lang_str(whisper_full_lang_id(ctx_of(ctx)));
    return env->NewStringUTF(lang != nullptr ? lang : "");
}

JNIEXPORT jint JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_segmentCount(JNIEnv *, jobject, jlong ctx) {
    return whisper_full_n_segments(ctx_of(ctx));
}

JNIEXPORT jfloat JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_segmentNoSpeechProb(JNIEnv *, jobject, jlong ctx, jint segment) {
    return whisper_full_get_segment_no_speech_prob(ctx_of(ctx), segment);
}

// Text tokens of a segment as [t0, t1] pairs in centiseconds from the start of the pass audio.
JNIEXPORT jlongArray JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_segmentTokenTimes(JNIEnv * env, jobject, jlong ptr, jint segment) {
    whisper_context * ctx = ctx_of(ptr);
    std::vector<jlong> times;
    const int n = whisper_full_n_tokens(ctx, segment);
    for (int i = 0; i < n; i++) {
        const whisper_token_data data = whisper_full_get_token_data(ctx, segment, i);
        if (!is_text_token(ctx, data.id)) continue;
        times.push_back(data.t0);
        times.push_back(data.t1);
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(times.size()));
    env->SetLongArrayRegion(out, 0, static_cast<jsize>(times.size()), times.data());
    return out;
}

// Text tokens of a segment as raw UTF-8 bytes, matching segmentTokenTimes. Bytes,
// not Strings: one character can be split across tokens, and invalid modified
// UTF-8 would abort the VM.
JNIEXPORT jobjectArray JNICALL
Java_dev_bike_livesubs_asr_WhisperNative_segmentTokenTexts(JNIEnv * env, jobject, jlong ptr, jint segment) {
    whisper_context * ctx = ctx_of(ptr);
    std::vector<const char *> texts;
    const int n = whisper_full_n_tokens(ctx, segment);
    for (int i = 0; i < n; i++) {
        if (!is_text_token(ctx, whisper_full_get_token_id(ctx, segment, i))) continue;
        texts.push_back(whisper_full_get_token_text(ctx, segment, i));
    }
    jclass byte_array = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(texts.size()), byte_array, nullptr);
    for (size_t i = 0; i < texts.size(); i++) {
        const auto len = static_cast<jsize>(std::char_traits<char>::length(texts[i]));
        jbyteArray bytes = env->NewByteArray(len);
        env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(texts[i]));
        env->SetObjectArrayElement(out, static_cast<jsize>(i), bytes);
        env->DeleteLocalRef(bytes);
    }
    return out;
}

}  // extern "C"
