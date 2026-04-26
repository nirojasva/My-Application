#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>
#include "llama.h"
#include "mtmd.h"
#include <vector>
#include <mutex>
#include <dirent.h>

#define LOG_TAG "MiAgenteIA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global pointers and safety mutex
struct llama_model* ai_model = nullptr;
struct llama_context* ai_context = nullptr;
struct mtmd_context* ai_vision = nullptr;
std::mutex model_mutex;
bool ai_stop_requested = false;

extern "C" JNIEXPORT void JNICALL
Java_com_nicolas_llm_MainActivity_detenerGeneracionNative(JNIEnv* env, jobject thiz) {
    ai_stop_requested = true;
}

// Helper to clear KV cache
void clear_kv_cache(struct llama_context * ctx) {
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) {
        llama_memory_seq_rm(mem, -1, -1, -1);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nicolas_llm_MainActivity_obtenerMetadataNative(JNIEnv* env, jobject thiz, jstring ruta_archivo) {
    std::lock_guard<std::mutex> lock(model_mutex);
    const char* ruta = env->GetStringUTFChars(ruta_archivo, nullptr);
    llama_model_params params = llama_model_default_params();
    params.vocab_only = true;
    struct llama_model* temp_model = llama_model_load_from_file(ruta, params);
    if (temp_model == nullptr) {
        env->ReleaseStringUTFChars(ruta_archivo, ruta);
        return env->NewStringUTF("Error: Could not read metadata.");
    }
    std::string info = "--- Model Metadata ---\n";
    char buf[256];
    int n_params = llama_model_n_params(temp_model);
    snprintf(buf, sizeof(buf), "Parameters: %.2fB\n", (float)n_params / 1e9);
    info += buf;
    const char* keys[] = { "general.architecture", "general.name", "general.author" };
    for (const char* key : keys) {
        char val[256];
        int res = llama_model_meta_val_str(temp_model, key, val, sizeof(val));
        if (res != -1) { info += key; info += ": "; info += val; info += "\n"; }
    }
    llama_model_free(temp_model);
    env->ReleaseStringUTFChars(ruta_archivo, ruta);
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nicolas_llm_MainActivity_cargarModeloNative(JNIEnv* env, jobject thiz, jstring ruta_archivo) {
    std::lock_guard<std::mutex> lock(model_mutex);
    const char* ruta = env->GetStringUTFChars(ruta_archivo, nullptr);
    LOGI("SWAP START: %s", ruta);
    if (ai_vision != nullptr) { mtmd_free(ai_vision); ai_vision = nullptr; }
    if (ai_context != nullptr) { llama_free(ai_context); ai_context = nullptr; }
    if (ai_model != nullptr) { llama_model_free(ai_model); ai_model = nullptr; }
    llama_backend_init();
    llama_model_params m_params = llama_model_default_params();
    m_params.use_mmap = true;
    m_params.use_mlock = false;
    ai_model = llama_model_load_from_file(ruta, m_params);
    if (ai_model == nullptr) {
        env->ReleaseStringUTFChars(ruta_archivo, ruta);
        return env->NewStringUTF("Error: RAM Full or Invalid File");
    }
    llama_context_params c_params = llama_context_default_params();
    c_params.n_ctx = 2048; // Increased for vision
    c_params.n_threads = 4;
    ai_context = llama_init_from_model(ai_model, c_params);
    if (ai_context == nullptr) {
        llama_model_free(ai_model); ai_model = nullptr;
        env->ReleaseStringUTFChars(ruta_archivo, ruta);
        return env->NewStringUTF("Error: Context memory failure");
    }
    std::string model_path(ruta);
    std::string base_path = model_path.substr(0, model_path.find_last_of("/\\") + 1);
    std::string model_filename = model_path.substr(model_path.find_last_of("/\\") + 1);
    if (model_filename.find("stable-diffusion") != std::string::npos || model_filename.find("sd-v1") != std::string::npos) {
        env->ReleaseStringUTFChars(ruta_archivo, ruta);
        return env->NewStringUTF("Engine: [TEXT-TO-IMAGE] Stable Diffusion detected.");
    }
    DIR *dir; struct dirent *ent;
    if ((dir = opendir(base_path.c_str())) != NULL) {
        while ((ent = readdir(dir)) != NULL) {
            std::string fname = ent->d_name;
            if (fname.find("mmproj") != std::string::npos) {
                std::string full_v_path = base_path + fname;
                mtmd_context_params v_params = mtmd_context_params_default();
                v_params.n_threads = 4;
                ai_vision = mtmd_init_from_file(full_v_path.c_str(), ai_model, v_params);
                if (ai_vision) break;
            }
        }
        closedir(dir);
    }
    env->ReleaseStringUTFChars(ruta_archivo, ruta);
    return env->NewStringUTF(ai_vision ? "Engine ready: [TEXT+IMAGE-TO-TEXT]" : "Engine ready: [TEXT-TO-TEXT]");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nicolas_llm_MainActivity_generarRespuestaNative(JNIEnv* env, jobject thiz, jstring mensaje_usuario, jint max_tokens) {
    std::lock_guard<std::mutex> lock(model_mutex);
    ai_stop_requested = false;
    if (ai_model == nullptr || ai_context == nullptr) return env->NewStringUTF("Error: Engine inactive");
    const char* user_text = env->GetStringUTFChars(mensaje_usuario, nullptr);
    std::string formatted_prompt = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n" + std::string(user_text) + "<|im_end|>\n<|im_start|>assistant\n";
    const char* prompt = formatted_prompt.c_str();
    jclass clase_main = env->GetObjectClass(thiz);
    jmethodID metodo_recibir = env->GetMethodID(clase_main, "recibirPalabra", "(Ljava/lang/String;)V");
    jmethodID metodo_recibir_pensamiento = env->GetMethodID(clase_main, "recibirPensamiento", "(Ljava/lang/String;)V");

    const struct llama_vocab* vocab = llama_model_get_vocab(ai_model);
    std::vector<llama_token> tokens_prompt;
    tokens_prompt.resize(formatted_prompt.length() + 8);
    int n_tokens = llama_tokenize(vocab, prompt, static_cast<int32_t>(formatted_prompt.length()), tokens_prompt.data(), static_cast<int32_t>(tokens_prompt.size()), true, true);
    if (n_tokens < 0) { env->ReleaseStringUTFChars(mensaje_usuario, user_text); return env->NewStringUTF("Tokenization error"); }
    tokens_prompt.resize(n_tokens);
    llama_batch batch = llama_batch_init(n_tokens > 512 ? n_tokens : 512, 0, 1);
    for (int i = 0; i < tokens_prompt.size(); i++) {
        batch.token[batch.n_tokens] = tokens_prompt[i]; batch.pos[batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1; batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = false; batch.n_tokens++;
    }
    if (batch.n_tokens > 0) batch.logits[batch.n_tokens - 1] = true;
    if (llama_decode(ai_context, batch) != 0) { llama_batch_free(batch); env->ReleaseStringUTFChars(mensaje_usuario, user_text); return env->NewStringUTF("Decoding failure"); }

    std::string respuesta_final;
    int n_vocab = llama_vocab_n_tokens(vocab);
    bool is_thinking = false;
    for (int i = 0; i < max_tokens; i++) {
        if (ai_stop_requested) break;
        auto* logits = llama_get_logits_ith(ai_context, batch.n_tokens - 1);
        float max_val = logits[0]; llama_token nuevo_token_id = 0;
        for(int j = 1; j < n_vocab; j++) { if(logits[j] > max_val) { max_val = logits[j]; nuevo_token_id = j; } }
        if (nuevo_token_id == llama_vocab_eos(vocab)) break;
        char buf[128];
        int n_chars = llama_token_to_piece(vocab, nuevo_token_id, buf, sizeof(buf), 0, false);
        if (n_chars > 0) {
            std::string token_texto(buf, n_chars);
            if (token_texto.find("<think>") != std::string::npos) { is_thinking = true; continue; }
            if (token_texto.find("</think>") != std::string::npos) { is_thinking = false; continue; }
            jstring t_jstr = env->NewStringUTF(token_texto.c_str());
            if (is_thinking) { env->CallVoidMethod(thiz, metodo_recibir_pensamiento, t_jstr); }
            else {
                if (token_texto.find("<|im_end|>") != std::string::npos) break;
                respuesta_final += token_texto;
                env->CallVoidMethod(thiz, metodo_recibir, t_jstr);
            }
            env->DeleteLocalRef(t_jstr);
        }
        batch.n_tokens = 1; batch.token[0] = nuevo_token_id; batch.pos[0] = tokens_prompt.size() + i;
        batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = true;
        if (llama_decode(ai_context, batch) != 0) break;
    }
    llama_batch_free(batch); env->ReleaseStringUTFChars(mensaje_usuario, user_text);
    return env->NewStringUTF(respuesta_final.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nicolas_llm_MainActivity_generarRespuestaConImagenNative(JNIEnv* env, jobject thiz, jstring mensaje_usuario, jobject bitmap, jint max_tokens) {
    std::lock_guard<std::mutex> lock(model_mutex);
    if (ai_model == nullptr || ai_context == nullptr || ai_vision == nullptr) return env->NewStringUTF("Error: Vision engine not ready.");
    AndroidBitmapInfo info; void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return env->NewStringUTF("Error: Image processing failed.");
    std::vector<unsigned char> rgb_data; rgb_data.reserve(info.width * info.height * 3);
    uint32_t* argb = (uint32_t*)pixels;
    for (uint32_t i = 0; i < info.width * info.height; i++) {
        rgb_data.push_back((argb[i] >> 16) & 0xFF); rgb_data.push_back((argb[i] >> 8) & 0xFF); rgb_data.push_back(argb[i] & 0xFF);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    mtmd_bitmap* mt_bmp = mtmd_bitmap_init(info.width, info.height, rgb_data.data());
    const char* user_text = env->GetStringUTFChars(mensaje_usuario, nullptr);

    // Improved Prompt structure for LLaVA
    std::string prompt_str = "USER: <__media__>\n" + std::string(user_text) + "\nASSISTANT:";

    mtmd_input_text input_text = { prompt_str.c_str(), true, true };
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    const mtmd_bitmap* bmps[1] = { mt_bmp };
    if (mtmd_tokenize(ai_vision, chunks, &input_text, bmps, 1) != 0) { mtmd_bitmap_free(mt_bmp); mtmd_input_chunks_free(chunks); env->ReleaseStringUTFChars(mensaje_usuario, user_text); return env->NewStringUTF("Error: Failed to encode image."); }

    clear_kv_cache(ai_context);

    for (size_t i = 0; i < mtmd_input_chunks_size(chunks); i++) {
        mtmd_encode_chunk(ai_vision, mtmd_input_chunks_get(chunks, i));
    }

    jclass clase_main = env->GetObjectClass(thiz);
    jmethodID metodo_recibir = env->GetMethodID(clase_main, "recibirPalabra", "(Ljava/lang/String;)V");
    std::string description; const struct llama_vocab* vocab = llama_model_get_vocab(ai_model);
    int n_vocab = llama_vocab_n_tokens(vocab);

    llama_memory_t mem = llama_get_memory(ai_context);
    llama_pos n_past = llama_memory_seq_pos_max(mem, 0) + 1;

    llama_batch batch = llama_batch_init(1, 0, 1);
    for (int i = 0; i < max_tokens; i++) {
        auto* logits = llama_get_logits(ai_context);
        llama_token next_token = 0; float max_logit = logits[0];
        for (int j = 1; j < n_vocab; j++) { if (logits[j] > max_logit) { max_logit = logits[j]; next_token = j; } }
        if (next_token == llama_vocab_eos(vocab)) break;
        char buf[128]; int n_chars = llama_token_to_piece(vocab, next_token, buf, sizeof(buf), 0, false);
        if (n_chars > 0) {
            std::string word(buf, n_chars); if (word.find("<|im_end|>") != std::string::npos) break;
            description += word; jstring jword = env->NewStringUTF(word.c_str());
            env->CallVoidMethod(thiz, metodo_recibir, jword); env->DeleteLocalRef(jword);
        }
        batch.n_tokens = 1; batch.token[0] = next_token; batch.pos[0] = n_past + i;
        batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = true;
        if (llama_decode(ai_context, batch) != 0) break;
    }
    llama_batch_free(batch); mtmd_bitmap_free(mt_bmp); mtmd_input_chunks_free(chunks); env->ReleaseStringUTFChars(mensaje_usuario, user_text);
    return env->NewStringUTF(description.c_str());
}
