#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
namespace stellar_frame {
struct Request {
  std::string run, session;
  uint32_t width, height;
  uint64_t time;
  bool active;
};
std::optional<Request> parse_request(std::string_view);
// The caller serializes every method with the raw capture callback mutex.
// A RUN is never reclaimed or reset after its first native PID claim.
class Producer {
  struct Impl;
  std::unique_ptr<Impl> impl_;

public:
  Producer();
  ~Producer();
  Producer(const Producer &) = delete;
  Producer &operator=(const Producer &) = delete;
  bool open(const std::wstring &, const Request &, uint32_t, uint64_t);
  void heartbeat(uint64_t);
  bool active(uint64_t) const;
  bool ready() const;
  bool begin(std::string_view, uint64_t);
  bool plane(int, const void *, size_t, uint32_t, uint32_t);
  void fail(uint32_t);
  void stop();
};
} // namespace stellar_frame
