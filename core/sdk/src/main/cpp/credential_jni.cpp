// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string_view>
#include <vector>

#include "ramflux_sdk.h"

// Attended account-code callback for `account.app_lock.configure`. The core calls it on a native thread; it asks the
// host for the code through AttendedCredentialBridge and waits (at most 60 s) for credentialReply. The code is copied
// into the core's output buffer and every native copy is erased. Nothing here creates a jstring or a std::string.
namespace {
constexpr size_t kPassphraseCapacity = 1024;
constexpr auto kAnswerWait = std::chrono::seconds(60);
constexpr std::string_view kActionKey = "\"action\"";
constexpr std::string_view kUnlockAction = "\"action\":\"unlock\"";

// Erase temporary native copies before their storage is released.
void wipe(void* bytes, size_t len) {
    volatile auto* p = static_cast<volatile unsigned char*>(bytes);
    while (len-- > 0) *p++ = 0;
}

struct Slot {
    std::mutex guard;
    std::condition_variable answered_cv;
    bool waiting = false;
    bool answered = false;
    std::vector<uint8_t> answer;
};

std::mutex registry_guard;
std::map<uint64_t, std::shared_ptr<Slot>> slots;
JavaVM* vm = nullptr;
jclass bridge_class = nullptr;
jmethodID on_request = nullptr;

std::shared_ptr<Slot> find_slot(uint64_t handle) {
    std::lock_guard<std::mutex> lock(registry_guard);
    const auto found = slots.find(handle);
    return found == slots.end() ? nullptr : found->second;
}

// Only the unlock action is answered. The metadata is core JSON with one action field; anything else is refused.
bool is_unlock_request(const uint8_t* metadata, size_t length) {
    if (metadata == nullptr || length == 0 || length > 4096) return false;
    const std::string_view text(reinterpret_cast<const char*>(metadata), length);
    const auto first = text.find(kActionKey);
    return first != std::string_view::npos && first == text.rfind(kActionKey) &&
        text.find(kUnlockAction) != std::string_view::npos;
}

// Calls the host's bridge on this thread (attaching it when needed). Returns false when the host cannot be reached.
bool notify_host(uint64_t handle) {
    JavaVM* machine = nullptr;
    jclass target = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(registry_guard);
        machine = vm;
        target = bridge_class;
        method = on_request;
    }
    if (machine == nullptr || target == nullptr || method == nullptr) return false;
    JNIEnv* env = nullptr;
    bool attached = false;
    const jint state = machine->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (machine->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    } else if (state != JNI_OK) {
        return false;
    }
    env->CallStaticVoidMethod(target, method, static_cast<jlong>(handle), static_cast<jint>(1));
    const bool delivered = !env->ExceptionCheck();
    if (!delivered) env->ExceptionClear();
    if (attached) machine->DetachCurrentThread();
    return delivered;
}

int32_t account_code_callback(const uint8_t* metadata, size_t metadata_len, uint8_t* out_pass, size_t cap_pass,
    size_t* pass_len, uint8_t*, size_t, size_t* confirm_len, void* user_data) {
    if (confirm_len != nullptr) *confirm_len = 0;
    if (pass_len == nullptr) return 1;
    *pass_len = 0;
    if (out_pass == nullptr || cap_pass < kPassphraseCapacity || !is_unlock_request(metadata, metadata_len)) return 1;
    const auto handle = static_cast<uint64_t>(reinterpret_cast<uintptr_t>(user_data));
    const auto slot = find_slot(handle);
    if (!slot) return 1;
    try {
        {
            std::lock_guard<std::mutex> lock(slot->guard);
            slot->waiting = true;
            slot->answered = false;
            slot->answer.clear();
        }
        if (!notify_host(handle)) {
            std::lock_guard<std::mutex> lock(slot->guard);
            slot->waiting = false;
            return 1;
        }
        std::vector<uint8_t> answer;
        bool answered = false;
        {
            std::unique_lock<std::mutex> lock(slot->guard);
            answered = slot->answered_cv.wait_for(lock, kAnswerWait, [&] { return slot->answered; });
            slot->waiting = false;
            slot->answered = false;
            answer.swap(slot->answer);
        }
        int32_t status = 1;
        if (answered && !answer.empty() && answer.size() <= cap_pass) {
            std::memcpy(out_pass, answer.data(), answer.size());
            *pass_len = answer.size();
            status = 0;
        }
        wipe(answer.data(), answer.size());
        return status;
    } catch (...) {
        return 1;
    }
}
}  // namespace

// Registers (enabled) or clears the callback for one core handle. Clearing drains an active callback in the core.
extern "C" JNIEXPORT jint JNICALL
Java_org_ramflux_sdk_JniCore_setCredentialProvider(JNIEnv* env, jobject, jlong core, jboolean enabled) {
    const auto handle = static_cast<uint64_t>(core);
    if (!enabled) {
        const int32_t code = ramflux_v2_set_credential_provider(handle, nullptr, nullptr);
        std::lock_guard<std::mutex> lock(registry_guard);
        slots.erase(handle);
        return code;
    }
    {
        std::lock_guard<std::mutex> lock(registry_guard);
        if (bridge_class == nullptr) {
            jclass local = env->FindClass("org/ramflux/sdk/AttendedCredentialBridge");
            if (local == nullptr) {
                env->ExceptionClear();
                return 6;
            }
            bridge_class = static_cast<jclass>(env->NewGlobalRef(local));
            env->DeleteLocalRef(local);
            on_request = env->GetStaticMethodID(bridge_class, "onRequest", "(JI)V");
            if (on_request == nullptr) {
                env->ExceptionClear();
                return 6;
            }
        }
        if (env->GetJavaVM(&vm) != JNI_OK) return 6;
        slots[handle] = std::make_shared<Slot>();
    }
    const int32_t code = ramflux_v2_set_credential_provider(handle, account_code_callback,
        reinterpret_cast<void*>(static_cast<uintptr_t>(handle)));
    if (code != 0) {
        std::lock_guard<std::mutex> lock(registry_guard);
        slots.erase(handle);
    }
    return code;
}

// Hands the host's answer (the UTF-8 code, or null to refuse) to the waiting callback. Ignored when none is waiting.
extern "C" JNIEXPORT void JNICALL
Java_org_ramflux_sdk_JniCore_credentialReply(JNIEnv* env, jobject, jlong core, jbyteArray passphrase) {
    const auto slot = find_slot(static_cast<uint64_t>(core));
    if (!slot) return;
    std::vector<uint8_t> copy;
    if (passphrase != nullptr) {
        const jsize length = env->GetArrayLength(passphrase);
        if (length > 0 && static_cast<size_t>(length) <= kPassphraseCapacity) {
            copy.resize(static_cast<size_t>(length));
            env->GetByteArrayRegion(passphrase, 0, length, reinterpret_cast<jbyte*>(copy.data()));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                wipe(copy.data(), copy.size());
                copy.clear();
            }
        }
    }
    {
        std::lock_guard<std::mutex> lock(slot->guard);
        if (slot->waiting && !slot->answered) {
            slot->answer.swap(copy);
            slot->answered = true;
            slot->answered_cv.notify_all();
        }
    }
    wipe(copy.data(), copy.size());
}
