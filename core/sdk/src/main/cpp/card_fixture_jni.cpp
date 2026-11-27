// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

// Test-build only (libramflux_card_fixture.so, built with RAMFLUX_CARD_FIXTURE=ON by scripts/build-native.sh and packaged
// by the realrun build type). It binds the standalone card runtime (ramflux_card_* ABI 5) and the SDK's bundled trust
// package for the realrun card gallery. It is never linked into libramflux_android.so or the release APK.
//
// Every card call runs on the thread that calls this JNI (the Kotlin side uses one dedicated renderer thread). Callback
// JSON is borrowed by the runtime only during the callback, so it is copied into a Java byte array before returning.

#include <jni.h>
#include <cstdint>
#include <new>
#include <vector>
#include "ramflux_sdk.h"
#include "ramflux_card.h"

namespace {
constexpr size_t kMaxInput = 1024 * 1024;
constexpr size_t kMaxTrustPackage = 4 * 1024 * 1024;
constexpr int32_t kStatusInvalid = 1;
constexpr int32_t kStatusUnexpected = 16;

// One call's callback output. Success emits exactly one callback; anything else is an error for the caller.
struct Capture {
    std::vector<uint8_t> json;
    int calls = 0;
    bool copied = true;
};

void capture_callback(void* user, const uint8_t* json, size_t len) {
    auto* capture = static_cast<Capture*>(user);
    capture->calls++;
    if (json == nullptr && len > 0) {
        capture->copied = false;
        return;
    }
    try {
        capture->json.assign(json, json + len);
    } catch (const std::bad_alloc&) {
        capture->copied = false;
    }
}

// Copies one Java byte array into native memory for the duration of a call.
struct Bytes {
    std::vector<uint8_t> data;
    bool ok = false;

    Bytes(JNIEnv* env, jbyteArray array) {
        if (array == nullptr) return;
        const jsize length = env->GetArrayLength(array);
        if (length < 0 || static_cast<size_t>(length) > kMaxInput) return;
        try {
            data.resize(static_cast<size_t>(length));
        } catch (const std::bad_alloc&) {
            return;
        }
        if (length > 0) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(data.data()));
        ok = !env->ExceptionCheck();
    }

    RamfluxCardBytes span() const { return RamfluxCardBytes{data.empty() ? nullptr : data.data(), data.size()}; }
};

// A CardFixtureReply(status, handle, json): the json is null when the call emitted no output.
jobject make_reply(JNIEnv* env, int32_t status, uint64_t handle, const std::vector<uint8_t>* json) {
    jbyteArray body = nullptr;
    if (json != nullptr) {
        body = env->NewByteArray(static_cast<jsize>(json->size()));
        if (body == nullptr) return nullptr;
        if (!json->empty()) {
            env->SetByteArrayRegion(body, 0, static_cast<jsize>(json->size()), reinterpret_cast<const jbyte*>(json->data()));
        }
    }
    jclass cls = env->FindClass("org/ramflux/android/cardgallery/CardFixtureReply");
    if (cls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJ[B)V");
    if (ctor == nullptr) return nullptr;
    return env->NewObject(cls, ctor, static_cast<jint>(status), static_cast<jlong>(handle), body);
}

jobject finish(JNIEnv* env, int32_t code, uint64_t handle, const Capture& capture, bool expects_output) {
    if (code != 0) return make_reply(env, code, 0, nullptr);
    if (!expects_output) return make_reply(env, 0, handle, nullptr);
    if (capture.calls != 1 || !capture.copied) return make_reply(env, kStatusUnexpected, 0, nullptr);
    return make_reply(env, 0, handle, &capture.json);
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_abiVersion(JNIEnv*, jclass) {
    return static_cast<jint>(ramflux_card_abi_version());
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_trustPackage(JNIEnv* env, jclass) {
    RamfluxBuffer* out = nullptr;
    const int32_t code = ramflux_v2_card_trust_package(&out);
    if (code != 0 || out == nullptr) {
        if (out != nullptr) ramflux_v2_buffer_free(out);
        return make_reply(env, code != 0 ? code : kStatusUnexpected, 0, nullptr);
    }
    std::vector<uint8_t> copy;
    bool bounded = out->len <= kMaxTrustPackage && (out->len == 0 || out->ptr != nullptr);
    if (bounded && out->len > 0) {
        try {
            copy.assign(out->ptr, out->ptr + out->len);
        } catch (const std::bad_alloc&) {
            bounded = false;
        }
    }
    ramflux_v2_buffer_free(out);
    if (!bounded) return make_reply(env, kStatusInvalid, 0, nullptr);
    return make_reply(env, 0, 0, &copy);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_open(JNIEnv* env, jclass, jbyteArray spec, jbyteArray bytecode,
                                                           jbyteArray signature, jbyteArray trusted_key, jbyteArray descriptor,
                                                           jbyteArray policy, jbyteArray presentation) {
    Bytes s(env, spec), b(env, bytecode), sig(env, signature), key(env, trusted_key), desc(env, descriptor),
        pol(env, policy), pres(env, presentation);
    if (!(s.ok && b.ok && sig.ok && key.ok && desc.ok && pol.ok && pres.ok)) return make_reply(env, kStatusInvalid, 0, nullptr);
    const RamfluxCardOpen input{s.span(), b.span(), sig.span(), key.span(), desc.span(), pol.span(), pres.span()};
    Capture capture;
    uint64_t handle = 0;
    const int32_t code = ramflux_card_open(&input, capture_callback, &capture, &handle);
    return finish(env, code, handle, capture, true);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_tree(JNIEnv* env, jclass, jlong session) {
    Capture capture;
    const int32_t code = ramflux_card_tree(static_cast<uint64_t>(session), capture_callback, &capture);
    return finish(env, code, static_cast<uint64_t>(session), capture, true);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_present(JNIEnv* env, jclass, jlong session, jbyteArray input) {
    Bytes in(env, input);
    if (!in.ok) return make_reply(env, kStatusInvalid, 0, nullptr);
    Capture capture;
    const int32_t code = ramflux_card_present(static_cast<uint64_t>(session), in.span(), capture_callback, &capture);
    return finish(env, code, static_cast<uint64_t>(session), capture, true);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_event(JNIEnv* env, jclass, jlong session, jbyteArray input) {
    Bytes in(env, input);
    if (!in.ok) return make_reply(env, kStatusInvalid, 0, nullptr);
    Capture capture;
    const int32_t code = ramflux_card_event(static_cast<uint64_t>(session), in.span(), capture_callback, &capture);
    return finish(env, code, static_cast<uint64_t>(session), capture, true);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_suspendCard(JNIEnv* env, jclass, jlong session) {
    Capture capture;
    const int32_t code = ramflux_card_suspend(static_cast<uint64_t>(session), capture_callback, &capture);
    return finish(env, code, static_cast<uint64_t>(session), capture, true);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_resume(JNIEnv* env, jclass, jlong session) {
    Capture capture;
    const int32_t code = ramflux_card_resume(static_cast<uint64_t>(session), capture_callback, &capture);
    return finish(env, code, static_cast<uint64_t>(session), capture, true);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_android_cardgallery_CardFixtureNative_dispose(JNIEnv*, jclass, jlong session) {
    return static_cast<jint>(ramflux_card_dispose(static_cast<uint64_t>(session)));
}
