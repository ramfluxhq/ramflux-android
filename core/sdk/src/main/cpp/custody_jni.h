// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

#pragma once

#include <cstddef>
#include <cstdint>

// Opens the supervisor with the installed host custody table. Returns 6 when none is installed.
int32_t ramflux_android_open_with_custody(const uint8_t* config, size_t config_len, uint64_t* handle);
