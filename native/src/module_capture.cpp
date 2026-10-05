#include "module_capture.hpp"

#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <unordered_map>
#include <vector>
#include <cstring>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#include <link.h>
#include <unistd.h>
#endif

namespace fs = std::filesystem;

namespace j2c::module_capture {
namespace {
std::string g_capture_dir;
std::mutex g_mu;
std::unordered_map<uintptr_t, ModuleInfo> g_cache;

std::string sanitize(std::string s) {
    for (char& c : s) {
        if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') &&
            !(c >= '0' && c <= '9') && c != '.' && c != '_' && c != '-') c = '_';
    }
    if (s.empty()) s = "module";
    return s;
}

std::string hexu(uintptr_t v) {
    std::ostringstream os;
    os << std::hex << v;
    return os.str();
}

bool copy_file_now(const std::string& src, const fs::path& dst) {
    if (src.empty()) return false;
    std::error_code ec;
    if (!fs::is_regular_file(fs::path(src), ec)) return false;
    fs::copy_file(fs::path(src), dst, fs::copy_options::overwrite_existing, ec);
    return !ec;
}

#ifdef _WIN32
bool readable_protect(DWORD p) {
    if (p & PAGE_GUARD) return false;
    const DWORD base = p & 0xff;
    return base == PAGE_READONLY || base == PAGE_READWRITE || base == PAGE_WRITECOPY ||
           base == PAGE_EXECUTE_READ || base == PAGE_EXECUTE_READWRITE || base == PAGE_EXECUTE_WRITECOPY;
}

size_t pe_image_size(uintptr_t base) {
    if (!base) return 0;
    SIZE_T got = 0;
    IMAGE_DOS_HEADER dos{};
    if (!ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<LPCVOID>(base),
                           &dos, sizeof(dos), &got) || got != sizeof(dos) ||
        dos.e_magic != IMAGE_DOS_SIGNATURE) return 0;
    IMAGE_NT_HEADERS nt{};
    uintptr_t ntAddr = base + static_cast<uintptr_t>(dos.e_lfanew);
    if (!ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<LPCVOID>(ntAddr),
                           &nt, sizeof(nt), &got) || got < sizeof(DWORD) + sizeof(IMAGE_FILE_HEADER) ||
        nt.Signature != IMAGE_NT_SIGNATURE) return 0;
    return static_cast<size_t>(nt.OptionalHeader.SizeOfImage);
}

bool dump_memory_image(uintptr_t base, size_t size, const fs::path& dst) {
    if (!base || !size) return false;
    std::ofstream out(dst, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    std::vector<char> buf(1u << 20);
    std::vector<char> zeros(64 * 1024, 0);
    uintptr_t cur = base;
    const uintptr_t end = base + size;
    while (cur < end) {
        MEMORY_BASIC_INFORMATION mbi{};
        if (!VirtualQuery(reinterpret_cast<LPCVOID>(cur), &mbi, sizeof(mbi))) return false;
        uintptr_t regionBase = reinterpret_cast<uintptr_t>(mbi.BaseAddress);
        uintptr_t regionEnd = regionBase + mbi.RegionSize;
        uintptr_t from = cur;
        uintptr_t to = regionEnd < end ? regionEnd : end;
        size_t remaining = static_cast<size_t>(to - from);
        const bool regionReadable = mbi.State == MEM_COMMIT && readable_protect(mbi.Protect);
        while (remaining) {
            size_t part = remaining < buf.size() ? remaining : buf.size();
            bool ok = false;
            if (regionReadable) {
                SIZE_T got = 0;
                ok = ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<LPCVOID>(from),
                                       buf.data(), part, &got) && got == part;
            }
            if (ok) {
                out.write(buf.data(), static_cast<std::streamsize>(part));
            } else {
                size_t left = part;
                while (left) {
                    size_t z = left < zeros.size() ? left : zeros.size();
                    out.write(zeros.data(), static_cast<std::streamsize>(z));
                    left -= z;
                }
            }
            from += part;
            remaining -= part;
        }
        cur = to;
    }
    return static_cast<bool>(out);
}
#endif

} // namespace

void configure(const std::string& capture_dir) {
    std::lock_guard<std::mutex> lock(g_mu);
    g_capture_dir = capture_dir;
    if (!g_capture_dir.empty()) {
        std::error_code ec;
        fs::create_directories(fs::path(g_capture_dir), ec);
    }
}

ModuleInfo resolve_and_capture(void* address) {
    ModuleInfo info;
    if (!address) return info;

#ifdef _WIN32
    HMODULE mod = nullptr;
    if (!GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                            GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCSTR>(address), &mod) || !mod) {
        return info;
    }
    info.base = reinterpret_cast<uintptr_t>(mod);
    info.fn_rva = reinterpret_cast<uintptr_t>(address) - info.base;
    info.size = pe_image_size(info.base);
    char path[MAX_PATH * 4]{};
    DWORD n = GetModuleFileNameA(mod, path, static_cast<DWORD>(sizeof(path)));
    if (n > 0 && n < sizeof(path)) info.path.assign(path, n);
    info.resolved = true;
#else
    Dl_info di{};
    if (dladdr(address, &di) == 0 || di.dli_fbase == nullptr) return info;
    info.base = reinterpret_cast<uintptr_t>(di.dli_fbase);
    info.fn_rva = reinterpret_cast<uintptr_t>(address) - info.base;
    if (di.dli_fname) info.path = di.dli_fname;
    info.resolved = true;
#endif

    std::lock_guard<std::mutex> lock(g_mu);
    auto cached = g_cache.find(info.base);
    if (cached != g_cache.end()) {
        ModuleInfo hit = cached->second;
        hit.fn_rva = info.fn_rva;
        return hit;
    }

    if (!g_capture_dir.empty()) {
        std::error_code ec;
        fs::create_directories(fs::path(g_capture_dir), ec);
        std::string leaf = info.path.empty() ? "anonymous" : fs::path(info.path).filename().string();
        leaf = sanitize(leaf);
        const std::string tag = "module_" + hexu(info.base) + "_" + leaf;
        fs::path diskDst = fs::path(g_capture_dir) / (tag + ".disk.bin");
        if (copy_file_now(info.path, diskDst)) info.disk_snapshot = diskDst.string();
#ifdef _WIN32
        if (info.size > 0) {
            fs::path memDst = fs::path(g_capture_dir) / (tag + ".memory.bin");
            if (dump_memory_image(info.base, info.size, memDst)) info.memory_snapshot = memDst.string();
        }
#endif
    }

    g_cache[info.base] = info;
    return info;
}

} // namespace j2c::module_capture
