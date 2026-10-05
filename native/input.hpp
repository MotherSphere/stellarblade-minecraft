#pragma once
#include <algorithm>
#include <charconv>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace stellar_input {
constexpr uint64_t max_integer = 9007199254740991ULL, lease_ms = 500;
constexpr uint32_t allowed_buttons = 0x3f3ff;
struct Pad {
  uint32_t buttons = 0;
  int16_t lx = 0, ly = 0, rx = 0, ry = 0;
  bool operator==(const Pad &p) const {
    return buttons == p.buttons && lx == p.lx && ly == p.ly && rx == p.rx &&
           ry == p.ry;
  }
  bool operator!=(const Pad &p) const { return !(*this == p); }
};
struct Edge {
  uint64_t id = 0, time = 0;
  Pad pad;
  bool operator==(const Edge &e) const {
    return id == e.id && time == e.time && pad == e.pad;
  }
};
struct Packet {
  std::string run, session, state;
  uint64_t epoch = 0, seq = 0, time = 0, base = 0;
  uint32_t base_mask = 0;
  Pad latest;
  std::vector<Edge> edges;
};
struct Permit {
  std::string run, session, state;
  uint64_t generation = 0, seq = 0, time = 0, pawn = 0, world = 0;
};
struct Ack {
  std::string run, session, state;
  uint64_t epoch = 0, seq = 0, time = 0, edge = 0;
};
namespace detail {
inline bool identity(std::string_view s) {
  return s.size() == 32 && std::all_of(s.begin(), s.end(), [](char c) {
           return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
         });
}
inline bool number(std::string_view s, uint64_t &n) {
  if (s.empty() || s.front() < '0' || s.front() > '9')
    return false;
  auto r = std::from_chars(s.data(), s.data() + s.size(), n);
  return r.ec == std::errc{} && r.ptr == s.data() + s.size() &&
         n <= max_integer;
}
inline bool axis(std::string_view s, int16_t &n) {
  int v = 0;
  if (s.empty())
    return false;
  auto r = std::from_chars(s.data(), s.data() + s.size(), v);
  if (r.ec != std::errc{} || r.ptr != s.data() + s.size() || v < -32767 ||
      v > 32767)
    return false;
  n = static_cast<int16_t>(v);
  return true;
}
struct Lines {
  std::vector<std::string_view> rows;
  size_t at = 0;
  bool good = true;
  Lines(std::string_view text, size_t bound) {
    if (text.empty() || text.size() > bound || text.back() != '\n') {
      good = false;
      return;
    }
    size_t start = 0;
    for (size_t i = 0; i < text.size(); ++i) {
      unsigned char c = text[i];
      if (c == '\n') {
        rows.push_back(text.substr(start, i - start));
        start = i + 1;
      } else if (c < 32 || c > 126) {
        good = false;
        return;
      }
    }
  }
  bool literal(std::string_view s) {
    return good && at < rows.size() && rows[at++] == s;
  }
  std::vector<std::string_view> field(std::string_view key, size_t count) {
    std::vector<std::string_view> parts;
    if (!good || at >= rows.size())
      return parts;
    auto line = rows[at++];
    size_t p = 0;
    while (p <= line.size()) {
      size_t e = line.find(' ', p);
      if (e == std::string_view::npos)
        e = line.size();
      parts.push_back(line.substr(p, e - p));
      if (e == line.size())
        break;
      p = e + 1;
    }
    if (parts.size() != count + 1 || parts[0] != key)
      return {};
    parts.erase(parts.begin());
    return parts;
  }
  bool text(std::string_view key, std::string &out) {
    auto f = field(key, 1);
    if (f.empty())
      return false;
    out = f[0];
    return true;
  }
  bool num(std::string_view key, uint64_t &out) {
    auto f = field(key, 1);
    return !f.empty() && number(f[0], out);
  }
  bool end(uint64_t seq) {
    uint64_t n;
    return num("END", n) && n == seq && at == rows.size();
  }
};
inline bool mask(std::string_view s, uint32_t &out) {
  uint64_t n;
  if (!number(s, n) || (n & ~uint64_t(allowed_buttons)))
    return false;
  out = static_cast<uint32_t>(n);
  return true;
}
} // namespace detail
inline std::optional<Packet> parse_input(std::string_view s) {
  detail::Lines l(s, 4096);
  Packet p;
  uint64_t count;
  if (!l.literal("SBMC_INPUT 1") || !l.text("RUN", p.run) ||
      !detail::identity(p.run) || !l.text("SESSION", p.session) ||
      !detail::identity(p.session) || !l.num("EPOCH", p.epoch) || !p.epoch ||
      !l.num("SEQ", p.seq) || !p.seq || !l.num("TIME", p.time) ||
      !l.text("STATE", p.state) ||
      (p.state != "active" && p.state != "neutral" && p.state != "stopped"))
    return {};
  auto move = l.field("MOVE", 2), look = l.field("LOOK", 2),
       base = l.field("BASE", 2);
  if (move.empty() || look.empty() || base.empty() ||
      !detail::axis(move[0], p.latest.lx) ||
      !detail::axis(move[1], p.latest.ly) ||
      !detail::axis(look[0], p.latest.rx) ||
      !detail::axis(look[1], p.latest.ry) || !detail::number(base[0], p.base) ||
      !detail::mask(base[1], p.base_mask) || !l.num("EDGES", count) ||
      count > 32)
    return {};
  uint32_t previous = p.base_mask;
  for (uint64_t i = 0; i < count; ++i) {
    auto f = l.field("EDGE", 7);
    Edge e;
    if (f.empty() || !detail::number(f[0], e.id) || e.id != p.base + i + 1 ||
        e.id > max_integer || !detail::mask(f[1], e.pad.buttons) ||
        !detail::number(f[2], e.time) || e.time > p.time ||
        !detail::axis(f[3], e.pad.lx) || !detail::axis(f[4], e.pad.ly) ||
        !detail::axis(f[5], e.pad.rx) || !detail::axis(f[6], e.pad.ry))
      return {};
    uint32_t change = e.pad.buttons ^ previous;
    if (!change || (change & (change - 1)))
      return {};
    previous = e.pad.buttons;
    p.edges.push_back(e);
  }
  if (!l.end(p.seq))
    return {};
  if (p.state != "active" &&
      (p.base || p.base_mask || !p.edges.empty() || p.latest != Pad{}))
    return {};
  return p;
}
inline std::optional<Permit> parse_permit(std::string_view s) {
  detail::Lines l(s, 512);
  Permit p;
  if (!l.literal("SBMC_CONTROL 1") || !l.text("RUN", p.run) ||
      !detail::identity(p.run) || !l.text("SESSION", p.session) ||
      !detail::identity(p.session) || !l.num("GEN", p.generation) ||
      !p.generation || !l.num("SEQ", p.seq) || !p.seq ||
      !l.num("TIME", p.time) || !l.text("STATE", p.state) ||
      (p.state != "active" && p.state != "stopped"))
    return {};
  auto f = l.field("CONTEXT", 2);
  if (f.empty() || !detail::number(f[0], p.pawn) ||
      !detail::number(f[1], p.world) || !l.end(p.seq) ||
      (p.state == "active" && (!p.pawn || !p.world)))
    return {};
  return p;
}
inline std::optional<Permit> select_permit(std::string_view a,
                                           std::string_view b) {
  auto x = parse_permit(a), y = parse_permit(b);
  if (x && y) {
    // A torn older slot is harmless; conflicting copies of one sequence are
    // not.
    if (x->seq == y->seq && a != b)
      return {};
    return x->seq >= y->seq ? x : y;
  }
  return x ? x : y;
}
inline std::string encode_ack(const Ack &a) {
  return "SBMC_INPUT_ACK 1\nRUN " + a.run + "\nSESSION " + a.session +
         "\nEPOCH " + std::to_string(a.epoch) + "\nSEQ " +
         std::to_string(a.seq) + "\nTIME " + std::to_string(a.time) +
         "\nEDGE " + std::to_string(a.edge) + "\nSTATE " + a.state + "\nEND " +
         std::to_string(a.seq) + "\n";
}

// The worker owns parsing/I/O. Callers serialize Core access with one small
// lock.
class Core {
  struct TimedEdge {
    Edge edge;
    uint64_t deadline;
  };
  std::optional<Permit> permit_;
  std::string permit_bytes_, input_bytes_;
  uint64_t permit_deadline_ = 0, input_deadline_ = 0;
  std::optional<Packet> input_;
  std::vector<TimedEdge> history_;
  uint64_t delivered_ = 0;
  uint32_t delivered_mask_ = 0;
  Pad current_;
  bool enabled_ = false, ready_ = false, release_pending_ = false,
       has_receipt_ = false, saw_active_ = false;
  std::string state_;
  void block() {
    if (input_) {
      enabled_ = ready_ = false;
      current_ = {};
      if (state_ != "released") {
        release_pending_ = true;
        state_ = "blocked";
        has_receipt_ = true;
      }
    }
  }
  void expire(uint64_t now) {
    if (enabled_ && (now >= input_deadline_ || now >= permit_deadline_))
      block();
  }
  static bool fresh(uint64_t stamp, uint64_t wall) {
    return stamp <= wall ? wall - stamp <= 1000 : stamp - wall <= 100;
  }

public:
  void update(const std::optional<Packet> &packet,
              const std::optional<Permit> &permit,
              const std::string &input_bytes, const std::string &permit_bytes,
              uint64_t wall, uint64_t now) {
    expire(now); // A later snapshot cannot resurrect a lease which already
                 // expired.
    if (!permit) {
      block();
      return;
    }
    if (permit_ && permit->seq <= permit_->seq) {
      if (permit->seq != permit_->seq || permit_bytes != permit_bytes_) {
        block();
        return;
      }
    } else {
      uint64_t seconds = wall / 1000;
      if (permit->time > seconds || seconds - permit->time > 1) {
        block();
        return;
      }
      if (permit_ && permit->generation < permit_->generation) {
        block();
        return;
      }
      bool changed =
          permit_ &&
          (permit->generation != permit_->generation ||
           permit->run != permit_->run || permit->session != permit_->session ||
           permit->pawn != permit_->pawn || permit->world != permit_->world);
      permit_ = permit;
      permit_bytes_ = permit_bytes;
      permit_deadline_ = now + lease_ms;
      if (changed) {
        block();
        return;
      }
    }
    if (permit_->state != "active" || now >= permit_deadline_) {
      block();
      return;
    }
    if (!packet || packet->run != permit_->run ||
        packet->session != permit_->session) {
      block();
      return;
    }
    bool same = input_ && packet->run == input_->run &&
                packet->session == input_->session;
    bool new_epoch = !same || packet->epoch > input_->epoch;
    if (new_epoch) {
      if (packet->state != "neutral" || !fresh(packet->time, wall)) {
        block();
        return;
      }
      input_ = packet;
      input_bytes_ = input_bytes;
      input_deadline_ = now + lease_ms;
      history_.clear();
      delivered_ = 0;
      delivered_mask_ = 0;
      current_ = {};
      enabled_ = true;
      ready_ = false;
      release_pending_ = false;
      has_receipt_ = false;
      saw_active_ = false;
      state_.clear();
      return;
    }
    if (packet->epoch != input_->epoch) {
      block();
      return;
    }
    if (packet->seq <= input_->seq) {
      if (packet->seq != input_->seq || input_bytes != input_bytes_)
        block();
      return;
    }
    if (!enabled_)
      return; // An epoch remains blocked until a fresh neutral epoch.
    if (!fresh(packet->time, wall)) {
      block();
      return;
    }
    // Neutral heartbeats keep an unarmed epoch alive while waiting for a real
    // gameplay poll or physical key release. They never arm it themselves.
    if (packet->state == "neutral" && !saw_active_) {
      input_ = packet;
      input_bytes_ = input_bytes;
      input_deadline_ = now + lease_ms;
      return;
    }
    if (!ready_ || packet->state != "active") {
      block();
      return;
    }
    if (packet->base < input_->base || packet->base > delivered_) {
      block();
      return;
    }
    uint32_t base_mask = input_->base_mask;
    if (packet->base != input_->base) {
      auto it = std::find_if(
          history_.begin(), history_.end(),
          [&](const TimedEdge &e) { return e.edge.id == packet->base; });
      if (it == history_.end()) {
        block();
        return;
      }
      base_mask = it->edge.pad.buttons;
    }
    if (base_mask != packet->base_mask) {
      block();
      return;
    }
    std::vector<TimedEdge> next;
    for (const auto &e : history_)
      if (e.edge.id > packet->base) {
        size_t i = static_cast<size_t>(e.edge.id - packet->base - 1);
        if (i >= packet->edges.size() || !(packet->edges[i] == e.edge)) {
          block();
          return;
        }
      }
    for (const auto &e : packet->edges) {
      auto found = std::find_if(
          history_.begin(), history_.end(),
          [&](const TimedEdge &old) { return old.edge.id == e.id; });
      if (found != history_.end())
        next.push_back(*found);
      else {
        uint64_t age = wall > e.time ? wall - e.time : 0;
        next.push_back({e, now + (age >= lease_ms ? 0 : lease_ms - age)});
      }
    }
    saw_active_ = true;
    history_ = std::move(next);
    input_ = packet;
    input_bytes_ = input_bytes;
    input_deadline_ = now + lease_ms;
  }
  struct Result {
    bool override = false;
    Pad pad;
  };
  Result poll(bool gameplay, uint64_t /*wall*/, uint64_t now) {
    expire(now);
    if (release_pending_) {
      if (gameplay) {
        release_pending_ = false;
        state_ = "released";
      }
      return {true, {}};
    }
    if (!enabled_)
      return {};
    if (!gameplay)
      return {true, current_};
    if (!ready_) {
      ready_ = true;
      has_receipt_ = true;
      state_ = "ready";
      current_ = {};
      return {true, current_};
    }
    auto edge =
        std::find_if(history_.begin(), history_.end(), [&](const TimedEdge &e) {
          return e.edge.id > delivered_;
        });
    if (edge != history_.end()) {
      if (now >= edge->deadline) {
        block();
        release_pending_ = false;
        state_ = "released";
        return {true, {}};
      }
      delivered_ = edge->edge.id;
      delivered_mask_ = edge->edge.pad.buttons;
      current_ = edge->edge.pad;
    } else {
      current_ = input_->latest;
      current_.buttons = delivered_mask_;
    }
    return {true, current_};
  }
  bool owns_remote() const { return enabled_ || release_pending_; }
  std::optional<Ack> receipt(uint64_t wall) const {
    if (!has_receipt_ || !input_)
      return {};
    return Ack{input_->run, input_->session, state_, input_->epoch, input_->seq,
               wall,        delivered_};
  }
};
} // namespace stellar_input
