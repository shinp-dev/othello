#include "edax_android_bridge.h"

#include <jni.h>
#include <cstdint>

namespace {

class UtfChars final {
public:
    UtfChars(JNIEnv *env, jstring value)
        : env_(env), value_(value), chars_(value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr)) {}
    ~UtfChars() { if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_); }
    const char *get() const { return chars_; }

private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

jstring validate(JNIEnv *env, jstring path, bool evaluation_data) {
    if (path == nullptr) return env->NewStringUTF("File path is empty");
    UtfChars chars(env, path);
    if (chars.get() == nullptr) return env->NewStringUTF("Cannot read file path");
    char message[256] = {};
    int status = evaluation_data
        ? edax_android_validate_eval(chars.get(), message, sizeof message)
        : edax_android_validate_book(chars.get(), message, sizeof message);
    return status == EDAX_ANDROID_OK ? nullptr : env->NewStringUTF(message);
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_othello_analysis_edax_NativeEdax_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(edax_android_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_othello_analysis_edax_NativeEdax_nativeValidateEvaluationData(JNIEnv *env, jobject, jstring path) {
    return validate(env, path, true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_othello_analysis_edax_NativeEdax_nativeValidateBook(JNIEnv *env, jobject, jstring path) {
    return validate(env, path, false);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_othello_analysis_edax_NativeEdax_nativeAnalyze(
    JNIEnv *env,
    jobject,
    jlong player,
    jlong opponent,
    jint side,
    jint level,
    jstring eval_path,
    jstring book_path,
    jlong request_id
) {
    UtfChars eval_chars(env, eval_path);
    UtfChars book_chars(env, book_path);
    if (eval_chars.get() == nullptr) {
        jclass exception = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exception, "Evaluation data path is required");
        return nullptr;
    }

    EdaxAndroidResult result = {};
    int status = edax_android_analyze(
        static_cast<uint64_t>(player),
        static_cast<uint64_t>(opponent),
        side,
        level,
        eval_chars.get(),
        book_chars.get(),
        request_id,
        &result
    );
    if (status == EDAX_ANDROID_CANCELLED) return env->NewIntArray(0);
    if (status != EDAX_ANDROID_OK) {
        jclass exception = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exception, result.message[0] == '\0' ? "Edax analysis failed" : result.message);
        return nullptr;
    }

    constexpr int fields = 5;
    jintArray encoded = env->NewIntArray(result.count * fields);
    if (encoded == nullptr) return nullptr;
    jint values[33 * fields];
    for (int index = 0; index < result.count; ++index) {
        values[index * fields] = result.moves[index].square;
        values[index * fields + 1] = result.moves[index].score;
        values[index * fields + 2] = result.moves[index].kind;
        values[index * fields + 3] = result.moves[index].depth;
        values[index * fields + 4] = result.moves[index].selectivity_percent;
    }
    env->SetIntArrayRegion(encoded, 0, result.count * fields, values);
    return encoded;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_othello_analysis_edax_NativeEdax_nativeCancel(JNIEnv *, jobject, jlong request_id) {
    edax_android_cancel(request_id);
}
