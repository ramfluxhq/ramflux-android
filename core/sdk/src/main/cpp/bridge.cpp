// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>
#include <new>
#include <chrono>
#include <thread>
#include "ramflux_sdk.h"

namespace {
constexpr size_t kMaxFrame = 1024 * 1024;

// Erase temporary native copies before their storage is released.
void wipe(void* bytes, size_t len) {
    volatile auto* p = static_cast<volatile unsigned char*>(bytes);
    while (len-- > 0) *p++ = 0;
}

struct Input {
    std::vector<uint8_t> bytes;
    bool valid = false;
    Input(JNIEnv* env, jbyteArray input) {
        if (input == nullptr) return;
        const jsize length = env->GetArrayLength(input);
        if (length <= 0 || static_cast<size_t>(length) > kMaxFrame) return;
        try { bytes.resize(static_cast<size_t>(length)); }
        catch (const std::bad_alloc&) {
            jclass error = env->FindClass("java/lang/OutOfMemoryError");
            if (error != nullptr) env->ThrowNew(error, "Unable to allocate bounded JNI input");
            return;
        }
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        valid = !env->ExceptionCheck();
    }
    ~Input() { wipe(bytes.data(), bytes.size()); }
};

// The Java object was never delivered. Keep the token until shutdown is confirmed.
void close_orphan_client(uint64_t handle) {
    for (;;) {
        const int32_t code = ramflux_v2_client_close(handle);
        if (code == 0) return;
        if (code != 10 && code != 24 && ramflux_v2_client_release(handle) == 0) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

jobject reply(JNIEnv* env, int32_t code, RamfluxBuffer* buffer = nullptr, uint64_t handle = 0) {
    const bool valid = buffer == nullptr ||
        (buffer->len <= kMaxFrame && (buffer->len == 0 || buffer->ptr != nullptr));
    jbyteArray body = env->NewByteArray(valid && buffer != nullptr ? static_cast<jsize>(buffer->len) : 0);
    if (body != nullptr && valid && buffer != nullptr && buffer->len > 0) {
        env->SetByteArrayRegion(body, 0, static_cast<jsize>(buffer->len), reinterpret_cast<jbyte*>(buffer->ptr));
    }
    if (buffer != nullptr) {
        if (buffer->ptr != nullptr && buffer->len <= kMaxFrame) wipe(buffer->ptr, buffer->len);
        ramflux_v2_buffer_free(buffer);
    }
    if (env->ExceptionCheck() || body == nullptr) return nullptr;
    jclass cls = env->FindClass("org/ramflux/sdk/NativeReply");
    if (cls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(I[BJ)V");
    if (ctor == nullptr) return nullptr;
    return env->NewObject(cls, ctor, valid ? code : 16, body, static_cast<jlong>(handle));
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_sdk_JniCore_negotiate(JNIEnv* env, jobject) {
    RamfluxBuffer* out = nullptr;
    const int32_t code = ramflux_v2_negotiate(2, 0, &out);
    return reply(env, code, out);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_sdk_JniCore_open(JNIEnv* env, jobject, jbyteArray config) {
    Input input(env, config);
    if (!input.valid) return env->ExceptionCheck() ? nullptr : reply(env, 1);
    uint64_t handle = 0;
    const int32_t code = ramflux_v2_client_open(input.bytes.data(), input.bytes.size(), &handle);
    jobject result = reply(env, code, nullptr, handle);
    // A failed Java allocation must not strand a live core handle.
    if (result == nullptr && handle != 0) close_orphan_client(handle);
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_sdk_JniCore_close(JNIEnv* env, jobject, jlong core) {
    return reply(env, ramflux_v2_client_close(static_cast<uint64_t>(core)));
}

// Pin the caller-owned output slot before admission. No Java allocation follows execute.
extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_sdk_JniCore_submitInto(JNIEnv* env, jobject, jlong core, jbyteArray request, jlongArray ownership) {
    if (ownership == nullptr || env->GetArrayLength(ownership) != 1) return 1;
    Input input(env, request);
    if (!input.valid) return 1;
    jlong* slot = env->GetLongArrayElements(ownership, nullptr);
    if (slot == nullptr) return 16; // Pending OOM; no operation was admitted.
    uint64_t operation = 0;
    const int32_t code = ramflux_v2_execute(static_cast<uint64_t>(core), input.bytes.data(), input.bytes.size(), &operation);
    slot[0] = static_cast<jlong>(operation);
    env->ReleaseLongArrayElements(ownership, slot, 0);
    return code;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_sdk_JniCore_operationPoll(JNIEnv* env, jobject, jlong core, jlong operation) {
    RamfluxBuffer* out = nullptr;
    const int32_t code = ramflux_v2_operation_poll(static_cast<uint64_t>(core), static_cast<uint64_t>(operation), &out);
    return reply(env, code, out);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_sdk_JniCore_operationCancel(JNIEnv*, jobject, jlong core, jlong operation) {
    return ramflux_v2_operation_cancel(static_cast<uint64_t>(core), static_cast<uint64_t>(operation));
}

extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_sdk_JniCore_operationRelease(JNIEnv*, jobject, jlong core, jlong operation) {
    return ramflux_v2_operation_release(static_cast<uint64_t>(core), static_cast<uint64_t>(operation));
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_ramflux_sdk_JniCore_eventPoll(JNIEnv* env, jobject, jlong core) {
    RamfluxBuffer* out = nullptr;
    const int32_t code = ramflux_v2_event_poll(static_cast<uint64_t>(core), &out);
    return reply(env, code, out);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_sdk_JniCore_eventReset(JNIEnv*, jobject, jlong core, jlong generation) {
    return ramflux_v2_event_reset(static_cast<uint64_t>(core), static_cast<uint64_t>(generation));
}

extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_sdk_JniCore_releaseClosed(JNIEnv*, jobject, jlong core) {
    return ramflux_v2_client_release(static_cast<uint64_t>(core));
}
