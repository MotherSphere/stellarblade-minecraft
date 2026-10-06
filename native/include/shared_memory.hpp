#pragma once
// File-backed mappings shared with the Minecraft reader and the atomic header
// words of their v1 ownership protocol (see protocol/README.md).
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <filesystem>

namespace stellar_shared {
inline uint64_t wall_now() {
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::system_clock::now().time_since_epoch())
          .count());
}
inline uint64_t monotonic_now() {
#ifdef _WIN32
  return GetTickCount64();
#else
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now().time_since_epoch())
          .count());
#endif
}
template <class T> T plain(const uint8_t *base, size_t off) {
  T value;
  std::memcpy(&value, base + off, sizeof value);
  return value;
}
template <class T> void put(uint8_t *base, size_t off, T value) {
  std::memcpy(base + off, &value, sizeof value);
}
inline uint32_t load32(const uint8_t *base, size_t offset) {
#ifdef _WIN32
  return static_cast<uint32_t>(InterlockedCompareExchange(
      reinterpret_cast<volatile LONG *>(const_cast<uint8_t *>(base) + offset),
      0, 0));
#else
  return __atomic_load_n(reinterpret_cast<const uint32_t *>(base + offset),
                         __ATOMIC_ACQUIRE);
#endif
}
inline uint64_t load64(const uint8_t *base, size_t offset) {
#ifdef _WIN32
  return static_cast<uint64_t>(InterlockedCompareExchange64(
      reinterpret_cast<volatile LONG64 *>(const_cast<uint8_t *>(base) + offset),
      0, 0));
#else
  return __atomic_load_n(reinterpret_cast<const uint64_t *>(base + offset),
                         __ATOMIC_ACQUIRE);
#endif
}
inline uint32_t exchange32(uint8_t *base, size_t offset, uint32_t value) {
#ifdef _WIN32
  return static_cast<uint32_t>(
      InterlockedExchange(reinterpret_cast<volatile LONG *>(base + offset),
                          static_cast<LONG>(value)));
#else
  return __atomic_exchange_n(reinterpret_cast<uint32_t *>(base + offset), value,
                             __ATOMIC_ACQ_REL);
#endif
}
inline void store64(uint8_t *base, size_t offset, uint64_t value) {
#ifdef _WIN32
  InterlockedExchange64(reinterpret_cast<volatile LONG64 *>(base + offset),
                        static_cast<LONG64>(value));
#else
  __atomic_store_n(reinterpret_cast<uint64_t *>(base + offset), value,
                   __ATOMIC_RELEASE);
#endif
}
// The producer claim and the consumer's cancellation race on one word.
inline bool claim(uint8_t *base, size_t offset, uint32_t pid) {
#ifdef _WIN32
  return InterlockedCompareExchange(
             reinterpret_cast<volatile LONG *>(base + offset),
             static_cast<LONG>(pid), 0) == 0;
#else
  uint32_t expected = 0;
  return __atomic_compare_exchange_n(reinterpret_cast<uint32_t *>(base + offset),
                                     &expected, pid, false, __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE);
#endif
}

// An existing regular file of exactly the expected size, mapped read/write.
struct Mapping {
  uint8_t *base = nullptr;
  size_t bytes = 0;
#ifdef _WIN32
  HANDLE file = INVALID_HANDLE_VALUE, mapping = nullptr;
#else
  int file = -1;
#endif
  Mapping() = default;
  Mapping(const Mapping &) = delete;
  Mapping &operator=(const Mapping &) = delete;
  ~Mapping() { close(); }
  void close() {
#ifdef _WIN32
    if (base)
      UnmapViewOfFile(base);
    if (mapping)
      CloseHandle(mapping);
    if (file != INVALID_HANDLE_VALUE)
      CloseHandle(file);
    file = INVALID_HANDLE_VALUE;
    mapping = nullptr;
#else
    if (base)
      munmap(base, bytes);
    if (file >= 0)
      ::close(file);
    file = -1;
#endif
    base = nullptr;
  }
  bool open(const std::filesystem::path &target, size_t size) {
    close();
    bytes = size;
#ifdef _WIN32
    file = CreateFileW(target.c_str(), GENERIC_READ | GENERIC_WRITE,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                       nullptr, OPEN_EXISTING,
                       FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT,
                       nullptr);
    if (file == INVALID_HANDLE_VALUE)
      return false;
    BY_HANDLE_FILE_INFORMATION info{};
    LARGE_INTEGER length{};
    if (!GetFileInformationByHandle(file, &info) ||
        (info.dwFileAttributes &
         (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) ||
        !GetFileSizeEx(file, &length) ||
        static_cast<uint64_t>(length.QuadPart) != size) {
      close();
      return false;
    }
    mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, 0, 0, nullptr);
    if (!mapping) {
      close();
      return false;
    }
    base = static_cast<uint8_t *>(
        MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, size));
#else
    file = ::open(target.c_str(), O_RDWR | O_NOFOLLOW | O_CLOEXEC);
    if (file < 0)
      return false;
    struct stat info{};
    if (fstat(file, &info) != 0 || !S_ISREG(info.st_mode) ||
        static_cast<uint64_t>(info.st_size) != size) {
      close();
      return false;
    }
    auto memory = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
    base = memory == MAP_FAILED ? nullptr : static_cast<uint8_t *>(memory);
#endif
    if (!base) {
      close();
      return false;
    }
    return true;
  }
};
} // namespace stellar_shared
