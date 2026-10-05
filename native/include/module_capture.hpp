#pragma once

#include <cstdint>
#include <string>

namespace j2c::module_capture {

struct ModuleInfo {
    bool resolved = false;
    uintptr_t base = 0;
    size_t size = 0;
    uintptr_t fn_rva = 0;
    std::string path;
    std::string disk_snapshot;
    std::string memory_snapshot;
};

void configure(const std::string& capture_dir);
ModuleInfo resolve_and_capture(void* address);

} // namespace j2c::module_capture
