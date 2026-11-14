// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

#include "custody_jni.h"

#include <jni.h>

#include <mutex>

#include "ramflux_sdk.h"

namespace {
JavaVM* vm = nullptr;
jobject provider = nullptr;
jmethodID invoke_method = nullptr;
std::mutex guard;
int retains = 0;

JNIEnv* attach(bool* attached) {
    *attached = false;
    if (vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    const jint state = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_OK) return env;
    if (state != JNI_EDETACHED) return nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    *attached = true;
    return env;
}

void detach(bool attached) {
    if (attached && vm != nullptr) vm->DetachCurrentThread();
}

// C++ linkage matches RamfluxV2CustodyProvider: that struct is outside extern "C".
void ramflux_custody_retain(uint64_t) {
    std::lock_guard<std::mutex> lock(guard);
    if (provider != nullptr) retains++;
}

void ramflux_custody_release(uint64_t) {
    jobject dropped = nullptr;
    {
        std::lock_guard<std::mutex> lock(guard);
        if (retains > 0) retains--;
        if (retains == 0 && provider != nullptr) {
            dropped = provider;
            provider = nullptr;
            invoke_method = nullptr;
        }
    }
    if (dropped == nullptr) return;
    bool attached = false;
    JNIEnv* env = attach(&attached);
    if (env != nullptr) env->DeleteGlobalRef(dropped);
    detach(attached);
}

int32_t ramflux_custody_invoke(uint64_t, RamfluxV2CustodyRequest* request) {
    if (request == nullptr) return 6;
    request->output_len = 0;
    if (request->reference_len > 128 || request->input_len > 4096 || request->output_capacity > 4096) return 2;
    if ((request->reference_len > 0 && request->reference == nullptr) ||
        (request->input_len > 0 && request->input == nullptr) ||
        (request->output_capacity > 0 && request->output == nullptr)) return 2;
    bool attached = false;
    JNIEnv* env = attach(&attached);
    if (env == nullptr) return 6;
    int32_t status = 6;
    jobject local = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(guard);
        if (provider != nullptr && invoke_method != nullptr) {
            local = env->NewLocalRef(provider);
            method = invoke_method;
        }
    }
    jbyteArray reference = nullptr;
    jbyteArray input = nullptr;
    jbyteArray output = nullptr;
    jintArray written = nullptr;
    if (local != nullptr && method != nullptr) {
        reference = env->NewByteArray(static_cast<jsize>(request->reference_len));
        input = env->NewByteArray(static_cast<jsize>(request->input_len));
        output = env->NewByteArray(static_cast<jsize>(request->output_capacity));
        written = env->NewIntArray(1);
        if (reference != nullptr && input != nullptr && output != nullptr && written != nullptr && !env->ExceptionCheck()) {
            if (request->reference_len > 0) {
                env->SetByteArrayRegion(reference, 0, static_cast<jsize>(request->reference_len),
                    reinterpret_cast<const jbyte*>(request->reference));
            }
            if (request->input_len > 0) {
                env->SetByteArrayRegion(input, 0, static_cast<jsize>(request->input_len),
                    reinterpret_cast<const jbyte*>(request->input));
            }
            status = env->CallIntMethod(local, method,  static_cast<jint>(request->operation), reference, input, output, written);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                status = 6;
            } else if (status == 0) {
                jint length = 0;
                env->GetIntArrayRegion(written, 0, 1, &length);
                if (length < 0 || static_cast<size_t>(length) > request->output_capacity) status = 2;
                else if (length > 0) {
                    env->GetByteArrayRegion(output, 0, length, reinterpret_cast<jbyte*>(request->output));
                    request->output_len = static_cast<size_t>(length);
                }
            }
        }
    }
    auto erase = [&](jbyteArray array, size_t len) {
        if (array == nullptr || len > 4096) return;
        jbyte zeros[4096] = {};
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(len), zeros);
    };
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        status = 6;
    }
    erase(reference, request->reference_len);
    erase(input, request->input_len);
    erase(output, request->output_capacity);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        status = 6;
    }
    if (reference != nullptr) env->DeleteLocalRef(reference);
    if (input != nullptr) env->DeleteLocalRef(input);
    if (output != nullptr) env->DeleteLocalRef(output);
    if (written != nullptr) env->DeleteLocalRef(written);
    if (local != nullptr) env->DeleteLocalRef(local);
    detach(attached);
    return status;
}
}

extern "C" JNIEXPORT void JNICALL
Java_org_ramflux_sdk_vault_HostCustodyBridge_installNative(JNIEnv* env, jclass, jobject session) {
    env->GetJavaVM(&vm);
    jclass type = env->GetObjectClass(session);
    jmethodID method = env->GetMethodID(type, "invoke", "(I[B[B[B[I)I");
    if (method == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    jobject global = env->NewGlobalRef(session);
    std::lock_guard<std::mutex> lock(guard);
    if (provider != nullptr) env->DeleteGlobalRef(provider);
    provider = global;
    invoke_method = method;
    retains = 1;
}

int32_t ramflux_android_open_with_custody(const uint8_t* config, size_t config_len, uint64_t* handle) {
    if (handle == nullptr) return 6;
    {
        std::lock_guard<std::mutex> lock(guard);
        if (provider == nullptr || invoke_method == nullptr) return 6;
    }
    RamfluxV2CustodyProvider table{};
    table.abi_version = 1;
    table.context = 1;
    table.retain = ramflux_custody_retain;
    table.release = ramflux_custody_release;
    table.invoke = ramflux_custody_invoke;
    return ramflux_v2_client_open_with_custody(config, config_len, &table, handle);
}
