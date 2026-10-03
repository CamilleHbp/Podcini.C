#include <jni.h>
#include <vector>
#include "metadata_core.h"

namespace {
TagLib::String fromJava(JNIEnv *env, jstring value) {
    const auto *chars = env->GetStringChars(value, nullptr);
    if(!chars) throw std::runtime_error("Could not read string");
    // TagLib's UTF16 constructor requires a BOM, unlike JNI's UTF-16 code units.
    std::wstring wide(1, 0xfeff);
    for(jsize i = 0; i < env->GetStringLength(value); ++i) wide.push_back(chars[i]);
    env->ReleaseStringChars(value, chars);
    return TagLib::String(wide, TagLib::String::UTF16);
}
jstring toJava(JNIEnv *env, const TagLib::String &value) {
    const auto wide = value.toWString();
    std::vector<jchar> chars(wide.begin(), wide.end());
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}
void fail(JNIEnv *env, const char *message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ac_mdiq_podcini_storage_tags_NativeTagCodec_readFields(JNIEnv *env, jobject, jstring path) {
    try {
        const auto fields = PodciniTags::read(fromJava(env, path).to8Bit(true));
        auto result = env->NewObjectArray(4, env->FindClass("[Ljava/lang/String;"), nullptr);
        for(size_t i = 0; i < fields.size(); ++i) {
            auto values = env->NewObjectArray(fields[i].size(), env->FindClass("java/lang/String"), nullptr);
            int index = 0;
            for(const auto &value : fields[i]) {
                auto text = toJava(env, value);
                env->SetObjectArrayElement(values, index++, text);
                env->DeleteLocalRef(text);
            }
            env->SetObjectArrayElement(result, i, values);
            env->DeleteLocalRef(values);
        }
        return result;
    } catch(const std::exception &e) { fail(env, e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_ac_mdiq_podcini_storage_tags_NativeTagCodec_writeFields(JNIEnv *env, jobject, jstring path, jobjectArray input) {
    try {
        if(env->GetArrayLength(input) != 4) throw std::runtime_error("Invalid tag fields");
        PodciniTags::Fields fields;
        for(size_t i = 0; i < fields.size(); ++i) {
            auto values = static_cast<jobjectArray>(env->GetObjectArrayElement(input, i));
            for(jsize j = 0; j < env->GetArrayLength(values); ++j) {
                auto value = static_cast<jstring>(env->GetObjectArrayElement(values, j));
                fields[i].append(fromJava(env, value));
                env->DeleteLocalRef(value);
            }
            env->DeleteLocalRef(values);
        }
        PodciniTags::write(fromJava(env, path).to8Bit(true), fields);
    } catch(const std::exception &e) { fail(env, e.what()); }
}
