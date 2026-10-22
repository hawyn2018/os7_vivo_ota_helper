// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <cstdint>
#include <string>
#include <vector>
namespace vendorpatch {
using Bytes = std::vector<uint8_t>;
struct PatchOptions { uint32_t scpDramSize; uint64_t partitionCapacity; };
struct PatchResult { Bytes image; std::string detailsJson; bool changed; };
uint32_t scpDramSize(const Bytes& scp);
PatchResult patchVendorBoot(const Bytes& input, const PatchOptions& options);
// Ordinary backup files only. No block-device writing, flashing or slot selection.
std::string patchFiles(const std::string& input, const std::string& output,
                       const std::string& scp, uint64_t capacity);
}
