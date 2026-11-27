#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "VeritasLlama"
#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::fprintf(stderr, LOG_TAG ": " __VA_ARGS__); std::fputc(10, stderr); } while (0)
#define LOGE(...) do { std::fprintf(stderr, LOG_TAG " ERROR: " __VA_ARGS__); std::fputc(10, stderr); } while (0)
#endif

namespace {
struct LlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 4096;
    std::atomic<bool> cancel_flag{false};
};

// Lossless conversion from Java UTF-16 (jstring) to standard UTF-8.
// Avoids Modified UTF-8 issues with 4-byte Unicode characters (emojis, etc.).
std::string jstring_to_utf8(JNIEnv * env, jstring jstr) {
    if (jstr == nullptr) return "";
    jsize len = env->GetStringLength(jstr);
    if (len <= 0) return "";
    const jchar * chars = env->GetStringChars(jstr, nullptr);
    if (!chars) return "";

    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + (((cp - 0xD800) << 10) | (low - 0xDC00));
                ++i;
            }
        }
        if (cp <= 0x7F) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(jstr, chars);
    return out;
}

// Crash-proof conversion from standard UTF-8 (including malformed bytes or 4-byte emojis)
// to Java UTF-16 jstring via NewString, avoiding ART CheckJNI NewStringUTF aborts.
jstring utf8_to_jstring(JNIEnv * env, const std::string & utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());

    size_t i = 0;
    const size_t n = utf8.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(utf8[i]);
        uint32_t cp = 0xFFFD;
        if (c < 0x80) {
            cp = c;
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < n) {
            unsigned char c1 = static_cast<unsigned char>(utf8[i + 1]);
            if ((c1 & 0xC0) == 0x80) {
                cp = ((c & 0x1F) << 6) | (c1 & 0x3F);
                i += 2;
            } else {
                i += 1;
            }
        } else if ((c & 0xF0) == 0xE0 && i + 2 < n) {
            unsigned char c1 = static_cast<unsigned char>(utf8[i + 1]);
            unsigned char c2 = static_cast<unsigned char>(utf8[i + 2]);
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80) {
                cp = ((c & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                i += 3;
            } else {
                i += 1;
            }
        } else if ((c & 0xF8) == 0xF0 && i + 3 < n) {
            unsigned char c1 = static_cast<unsigned char>(utf8[i + 1]);
            unsigned char c2 = static_cast<unsigned char>(utf8[i + 2]);
            unsigned char c3 = static_cast<unsigned char>(utf8[i + 3]);
            if ((c1 & 0xC0) == 0x80 && (c2 & 0xC0) == 0x80 && (c3 & 0xC0) == 0x80) {
                cp = ((c & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
                i += 4;
            } else {
                i += 1;
            }
        } else {
            i += 1;
        }

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 | (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 | (cp & 0x3FF)));
        } else {
            utf16.push_back(static_cast<jchar>(0xFFFD));
        }
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

// Returns the length of the longest prefix of `s` that ends on a complete UTF-8 sequence.
size_t utf8_complete_prefix_len(const std::string & s) {
    if (s.empty()) return 0;
    size_t n = s.size();
    // Inspect up to the last 3 bytes to see if a multi-byte sequence is incomplete.
    size_t check_start = (n >= 3) ? (n - 3) : 0;
    for (size_t i = n; i > check_start; --i) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0x80) == 0) {
            return n; // Ends with ASCII byte -> complete
        }
        if ((c & 0xC0) != 0x80) {
            // Found start byte of the final sequence at index (i - 1)
            size_t expected = 1;
            if ((c & 0xE0) == 0xC0) expected = 2;
            else if ((c & 0xF0) == 0xE0) expected = 3;
            else if ((c & 0xF8) == 0xF0) expected = 4;
            size_t available = n - (i - 1);
            return (available < expected) ? (i - 1) : n;
        }
    }
    return n;
}

// Returns number of trailing bytes in `s` that could be a prefix of any stop sequence.
size_t stop_tag_holdback_len(const std::string & s) {
    static const char * STOP_TAGS[] = {"<|im_end|>", "<end_of_turn>"};
    size_t max_hold = 0;
    for (const char * tag : STOP_TAGS) {
        size_t tlen = std::strlen(tag);
        size_t limit = std::min(s.size(), tlen - 1);
        for (size_t k = limit; k >= 1; --k) {
            if (s.compare(s.size() - k, k, tag, k) == 0) {
                max_hold = std::max(max_hold, k);
                break;
            }
        }
    }
    return max_hold;
}

std::string token_to_piece(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::vector<char> large(-n);
        n = llama_token_to_piece(vocab, token, large.data(), large.size(), 0, true);
        if (n < 0) return "";
        return std::string(large.data(), n);
    }
    return std::string(buf, n);
}

std::vector<llama_token> tokenize_string(
        const llama_vocab * vocab, const std::string & text, bool add_special) {
    if (text.empty()) return {};
    int n_max = static_cast<int>(text.size()) + 16;
    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            n_max,
            add_special,
            true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(
                vocab,
                text.c_str(),
                static_cast<int32_t>(text.size()),
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                add_special,
                true);
    }
    tokens.resize(std::max(0, n));
    return tokens;
}

bool emit_callback(JNIEnv * env, jobject callback, jmethodID method, const std::string & text) {
    if (callback == nullptr || method == nullptr || text.empty()) return true;
    jstring jPiece = utf8_to_jstring(env, text);
    if (!jPiece) return false;
    env->CallVoidMethod(callback, method, jPiece);
    env->DeleteLocalRef(jPiece);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}
} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeInitBackend(JNIEnv * env, jobject, jstring jLibDir) {
    // Laedt die passende CPU-Variante (dotprod, fp16, i8mm, SVE ...) fuer dieses Geraet
    std::string lib_dir = jstring_to_utf8(env, jLibDir);
    if (!lib_dir.empty()) ggml_backend_load_all_from_path(lib_dir.c_str());
    if (ggml_backend_dev_count() == 0) ggml_backend_load_all();
    LOGI("ggml-Backends geladen: %zu", ggml_backend_dev_count());
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeLoadModel(
        JNIEnv * env, jobject, jstring jPath, jint nCtx, jint nThreads, jint nThreadsBatch, jint nGpuLayers) {
    std::string path = jstring_to_utf8(env, jPath);
    auto * session = new LlamaSession();
    session->n_ctx = nCtx;

    llama_model_params mparams = llama_model_default_params();
    // 0 = nur Prozessor (Android); auf dem Desktop alle Schichten auf die Grafikkarte, wenn ein Vulkan-Geraet da ist
    mparams.n_gpu_layers = nGpuLayers;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    session->model = llama_model_load_from_file(path.c_str(), mparams);
    if (!session->model) {
        LOGE("Konnte GGUF-Modell nicht laden.");
        delete session;
        return 0L;
    }

    session->vocab = llama_model_get_vocab(session->model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_batch = 512;
    cparams.n_threads = std::max(1, static_cast<int>(nThreads));
    cparams.n_threads_batch = std::max(1, static_cast<int>(nThreadsBatch));

    session->ctx = llama_init_from_model(session->model, cparams);
    if (!session->ctx) {
        LOGE("Konnte llama_context nicht initialisieren.");
        llama_model_free(session->model);
        delete session;
        return 0L;
    }

    LOGI("Modell geladen (n_ctx=%d, threads=%d, batch_threads=%d, gpu_layers=%d)",
         nCtx, cparams.n_threads, cparams.n_threads_batch, (int) nGpuLayers);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jstring JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeGetModelArch(
        JNIEnv * env, jobject, jlong handle) {
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (!session || !session->model) return utf8_to_jstring(env, "");
    char buf[128] = {0};
    int32_t res = llama_model_meta_val_str(session->model, "general.architecture", buf, sizeof(buf));
    if (res <= 0) return utf8_to_jstring(env, "");
    return utf8_to_jstring(env, std::string(buf));
}

// Metadaten-Wert aus der GGUF-Datei (z. B. tokenizer.chat_template); leer, wenn nicht vorhanden
JNIEXPORT jstring JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeGetMeta(
        JNIEnv * env, jobject, jlong handle, jstring jKey) {
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (!session || !session->model) return utf8_to_jstring(env, "");
    const char * key = env->GetStringUTFChars(jKey, nullptr);
    std::vector<char> buf(64 * 1024);
    int32_t res = llama_model_meta_val_str(session->model, key, buf.data(), buf.size());
    env->ReleaseStringUTFChars(jKey, key);
    if (res <= 0) return utf8_to_jstring(env, "");
    return utf8_to_jstring(env, std::string(buf.data(), std::min<size_t>(res, buf.size() - 1)));
}

// Prompt im Chatformat des Modells (Vorlage aus der GGUF-Datei); leer, wenn llama.cpp die Vorlage nicht kennt
JNIEXPORT jstring JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeApplyTemplate(
        JNIEnv * env, jobject, jlong handle, jstring jSystem, jstring jUser) {
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (!session || !session->model) return utf8_to_jstring(env, "");
    const char * tmpl = llama_model_chat_template(session->model, nullptr);
    if (!tmpl) return utf8_to_jstring(env, "");
    const char * sys = env->GetStringUTFChars(jSystem, nullptr);
    const char * usr = env->GetStringUTFChars(jUser, nullptr);
    llama_chat_message msgs[2] = { {"system", sys}, {"user", usr} };
    std::vector<char> buf(strlen(sys) + strlen(usr) + 1024);
    int32_t n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int32_t) buf.size());
    }
    env->ReleaseStringUTFChars(jSystem, sys);
    env->ReleaseStringUTFChars(jUser, usr);
    if (n <= 0) return utf8_to_jstring(env, "");
    return utf8_to_jstring(env, std::string(buf.data(), n));
}

// Name der ersten Grafikkarte, die ein ggml-Backend (Vulkan) anbietet; leer, wenn keine da ist
JNIEXPORT jstring JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeGpuName(JNIEnv * env, jobject) {
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            return utf8_to_jstring(env, ggml_backend_dev_description(dev));
        }
    }
    return utf8_to_jstring(env, "");
}

JNIEXPORT void JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (session) session->cancel_flag.store(true);
}

JNIEXPORT void JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (!session) return;
    if (session->ctx) llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

JNIEXPORT jstring JNICALL
Java_com_veritas_localresearch_llm_LlamaEngine_nativeGenerate(
        JNIEnv * env,
        jobject,
        jlong handle,
        jstring jPrompt,
        jstring jGrammar,
        jfloat temperature,
        jint maxTokens,
        jobject callback) {
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    if (!session || !session->ctx || !session->vocab) {
        return utf8_to_jstring(env, "");
    }
    session->cancel_flag.store(false);

    std::string prompt = jstring_to_utf8(env, jPrompt);
    std::string grammar_str = jstring_to_utf8(env, jGrammar);

    // 1. KV-Memory komplett leeren fuer deterministische, isolierte Pipeline-Schritte
    llama_memory_clear(llama_get_memory(session->ctx), true);

    // 2. Prompt tokenisieren & Kontext-Ueberlauf schuetzen OHNE das Chat-Template-Ende abzuschneiden
    int safe_max_tokens = std::clamp(static_cast<int>(maxTokens), 16, std::max(16, session->n_ctx / 2));
    int max_prompt_tokens = std::max(64, session->n_ctx - safe_max_tokens - 32);

    std::vector<llama_token> tokens = tokenize_string(session->vocab, prompt, true);
    if (static_cast<int>(tokens.size()) > max_prompt_tokens) {
        // Letzten Turn-Uebergang (<|im_end|> oder <end_of_turn>) erhalten, damit das Modell sauber antwortet
        size_t split_pos = prompt.rfind("<|im_end|>");
        if (split_pos == std::string::npos) {
            split_pos = prompt.rfind("<end_of_turn>");
        }
        if (split_pos != std::string::npos && split_pos > 0) {
            std::string body_str = prompt.substr(0, split_pos);
            std::string suffix_str = prompt.substr(split_pos);
            std::vector<llama_token> suffix_tokens = tokenize_string(session->vocab, suffix_str, false);
            int body_budget = std::max(32, max_prompt_tokens - static_cast<int>(suffix_tokens.size()));
            std::vector<llama_token> body_tokens = tokenize_string(session->vocab, body_str, true);
            if (static_cast<int>(body_tokens.size()) > body_budget) {
                body_tokens.resize(body_budget);
            }
            tokens = std::move(body_tokens);
            tokens.insert(tokens.end(), suffix_tokens.begin(), suffix_tokens.end());
        } else {
            tokens.resize(max_prompt_tokens);
        }
    }

    int effective_max_tokens = std::min(
            safe_max_tokens,
            std::max(1, session->n_ctx - static_cast<int>(tokens.size()) - 4));

    // 3. Prompt in Chunks (n_batch = 512) decodieren
    const int batch_size = 512;
    for (int i = 0; i < static_cast<int>(tokens.size()); i += batch_size) {
        if (session->cancel_flag.load()) return utf8_to_jstring(env, "");
        int cur_len = std::min(batch_size, static_cast<int>(tokens.size()) - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, cur_len);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("Prompt llama_decode fehlgeschlagen");
            return utf8_to_jstring(env, "");
        }
    }

    // 4. Sampler-Chain mit optionaler GBNF-Grammatik & geringer Temperatur
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);

    if (!grammar_str.empty()) {
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(
                session->vocab, grammar_str.c_str(), "root");
        if (grammar_sampler) {
            llama_sampler_chain_add(chain, grammar_sampler);
        } else {
            LOGE("GBNF-Grammatik konnte nicht geparst werden!");
        }
    }

    if (temperature <= 0.01f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.90f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(42));
    }

    // Optionales Token-Streaming-Callback aufloesen
    jmethodID onTokenMethod = nullptr;
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        if (cls != nullptr) {
            onTokenMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cls);
        }
    }

    // 5. Generierungsschleife mit UTF-8- und Stop-Tag-Pufferung
    std::string output;
    std::string stream_pending;
    bool hit_stop_tag = false;

    for (int step = 0; step < effective_max_tokens; ++step) {
        if (session->cancel_flag.load()) break;

        llama_token new_token = llama_sampler_sample(chain, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, new_token)) {
            break;
        }

        std::string piece = token_to_piece(session->vocab, new_token);
        output += piece;
        stream_pending += piece;

        // Stop-Sequenzen fuer Qwen / Gemma Chat-Templates abfangen, bevor sie gestreamt werden
        size_t stop_pos = stream_pending.find("<|im_end|>");
        if (stop_pos == std::string::npos) {
            stop_pos = stream_pending.find("<end_of_turn>");
        }
        if (stop_pos != std::string::npos) {
            std::string before_stop = stream_pending.substr(0, stop_pos);
            emit_callback(env, callback, onTokenMethod, before_stop);
            stream_pending.clear();
            hit_stop_tag = true;
            break;
        }

        if (callback != nullptr && onTokenMethod != nullptr && !stream_pending.empty()) {
            size_t valid_utf8 = utf8_complete_prefix_len(stream_pending);
            std::string complete_part = stream_pending.substr(0, valid_utf8);
            size_t holdback = stop_tag_holdback_len(complete_part);
            size_t emit_len = (complete_part.size() > holdback) ? (complete_part.size() - holdback) : 0;
            if (emit_len > 0) {
                std::string to_emit = stream_pending.substr(0, emit_len);
                stream_pending.erase(0, emit_len);
                if (!emit_callback(env, callback, onTokenMethod, to_emit)) {
                    break;
                }
            }
        }

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(session->ctx, next_batch) != 0) {
            break;
        }
    }

    // Verbleibenden Puffer flushen (falls kein Stop-Tag getroffen wurde)
    if (!hit_stop_tag && !stream_pending.empty() && !session->cancel_flag.load()) {
        emit_callback(env, callback, onTokenMethod, stream_pending);
    }

    llama_sampler_free(chain);

    // Chat-Stop-Tags sauber entfernen
    auto strip_tag = [&](const std::string & tag) {
        size_t pos = output.find(tag);
        if (pos != std::string::npos) output.erase(pos);
    };
    strip_tag("<|im_end|>");
    strip_tag("<end_of_turn>");

    return utf8_to_jstring(env, output);
}

} // extern "C"
