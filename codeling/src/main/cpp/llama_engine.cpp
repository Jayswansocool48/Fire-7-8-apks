// The small AI model that runs on the tablet itself, through llama.cpp.
// Kotlin calls load() once, then complete() for each answer, streaming the text back
// in pieces through a callback. One answer runs at a time.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "CodelingAI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> stop{false};
};

void log_errors_only(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Format the conversation with the model's own chat template.
std::string format_chat(const llama_model * model, const std::vector<std::string> & roles, const std::vector<std::string> & texts) {
    std::vector<llama_chat_message> msgs;
    size_t total = 0;
    for (size_t i = 0; i < roles.size(); i++) {
        msgs.push_back({roles[i].c_str(), texts[i].c_str()});
        total += texts[i].size();
    }
    const char * tmpl = llama_model_chat_template(model, nullptr);
    std::vector<char> buf(total * 2 + 512);
    int n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        // no usable template: fall back to a plain transcript
        std::string plain;
        for (size_t i = 0; i < roles.size(); i++) plain += roles[i] + ": " + texts[i] + "\n";
        return plain + "assistant: ";
    }
    return std::string(buf.data(), n);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_jayswansocool48_codeling_LlamaEngine_nativeLoad(JNIEnv * env, jobject, jstring path, jint threads, jint n_ctx) {
    llama_log_set(log_errors_only, nullptr);
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto * e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_jayswansocool48_codeling_LlamaEngine_nativeStop(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_jayswansocool48_codeling_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    auto * e = reinterpret_cast<Engine *>(handle);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

// Answers one conversation. roles/texts are parallel arrays ("system", "user", ...).
// grammar is a GBNF grammar (e.g. JSON) or null. For each new piece of text, calls
// callback.onBytes(byte[]); returns 0 when done, 1 when stopped, negative on errors.
extern "C" JNIEXPORT jint JNICALL
Java_io_github_jayswansocool48_codeling_LlamaEngine_nativeComplete(JNIEnv * env, jobject, jlong handle,
        jobjectArray roles, jobjectArray texts, jstring grammar, jint max_tokens, jfloat temperature, jobject callback) {
    if (!handle) return -1;
    auto * e = reinterpret_cast<Engine *>(handle);
    e->stop = false;

    std::vector<std::string> rs, ts;
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto t = (jstring) env->GetObjectArrayElement(texts, i);
        rs.push_back(jstr(env, r));
        ts.push_back(jstr(env, t));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(t);
    }
    const std::string prompt = format_chat(e->model, rs, ts);

    // each answer starts fresh
    llama_memory_clear(llama_get_memory(e->ctx), true);

    const int n_prompt = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), (int32_t) tokens.size(), true, true) < 0) return -2;
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if ((int) tokens.size() + 16 >= n_ctx) return -3;  // too long for this model
    const int budget = std::min((int) max_tokens, n_ctx - (int) tokens.size() - 1);

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const std::string g = jstr(env, grammar);
    if (!g.empty()) {
        llama_sampler * gs = llama_sampler_init_grammar(e->vocab, g.c_str(), "root");
        if (gs) llama_sampler_chain_add(smpl, gs);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = env->GetObjectClass(callback);
    jmethodID on_bytes = env->GetMethodID(cls, "onBytes", "([B)V");

    int result = 0;
    // feed the prompt in chunks that fit the batch size
    for (size_t i = 0; i < tokens.size(); i += 512) {
        if (e->stop) { result = 1; break; }
        const int32_t len = (int32_t) std::min<size_t>(512, tokens.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, len)) != 0) { result = -4; break; }
    }
    for (int produced = 0; result == 0 && produced < budget; produced++) {
        if (e->stop) { result = 1; break; }
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        char buf[256];
        const int len = llama_token_to_piece(e->vocab, tok, buf, sizeof(buf), 0, false);
        if (len > 0) {
            jbyteArray arr = env->NewByteArray(len);
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(buf));
            env->CallVoidMethod(callback, on_bytes, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) { env->ExceptionClear(); result = -5; break; }
        }
        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) { result = -4; break; }
    }
    llama_sampler_free(smpl);
    return result;
}
