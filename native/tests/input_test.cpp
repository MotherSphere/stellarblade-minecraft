#ifdef NDEBUG
#undef NDEBUG
#endif
#include "input.hpp"
#include <cassert>
#include <iostream>
using namespace stellar_input;
static const std::string run(32, 'a'), session(32, 'b');
static std::string permit(uint64_t seq = 1, uint64_t seconds = 10,
                          uint64_t pawn = 11, uint64_t generation = 1) {
  return "SBMC_CONTROL 1\nRUN " + run + "\nSESSION " + session + "\nGEN " +
         std::to_string(generation) + "\nSEQ " + std::to_string(seq) +
         "\nTIME " + std::to_string(seconds) + "\nSTATE active\nCONTEXT " +
         std::to_string(pawn) + " 12\nEND " + std::to_string(seq) + "\n";
}
static std::string packet(uint64_t epoch, uint64_t seq, std::string state,
                          std::string edges = "", uint64_t base = 0,
                          uint32_t mask = 0, uint64_t time = 10000,
                          std::string move = "0 0") {
  size_t count = 0;
  for (char c : edges)
    if (c == '\n')
      ++count;
  return "SBMC_INPUT 1\nRUN " + run + "\nSESSION " + session + "\nEPOCH " +
         std::to_string(epoch) + "\nSEQ " + std::to_string(seq) + "\nTIME " +
         std::to_string(time) + "\nSTATE " + state + "\nMOVE " + move +
         "\nLOOK 0 0\nBASE " + std::to_string(base) + " " +
         std::to_string(mask) + "\nEDGES " + std::to_string(count) + "\n" +
         edges + "END " + std::to_string(seq) + "\n";
}
static void update(Core &c, const std::string &input, const std::string &native,
                   uint64_t wall = 10000, uint64_t mono = 0) {
  c.update(parse_input(input), parse_permit(native), input, native, wall, mono);
}
static void arm(Core &c, uint64_t epoch = 1, uint64_t mono = 0,
                uint64_t wall = 10000, uint64_t permit_seq = 1) {
  update(c, packet(epoch, 1, "neutral", "", 0, 0, wall),
         permit(permit_seq, wall / 1000), wall, mono);
  assert(!c.receipt(wall));
  auto q = c.poll(true, wall, mono);
  assert(q.override && q.pad == Pad{});
  auto a = c.receipt(wall);
  assert(a && a->state == "ready" && a->edge == 0);
}
int main() {
  auto neutral = packet(1, 1, "neutral");
  assert(parse_input(neutral));
  assert(parse_permit(permit()));
  for (auto bad : {neutral + "\n", neutral.substr(0, neutral.size() - 1),
                   packet(1, 1, "neutral", "EDGE 1 4096 10000 0 0 0 0\n"),
                   packet(1, 1, "active", "EDGE 2 4096 10000 0 0 0 0\n"),
                   packet(1, 1, "active", "EDGE 1 12288 10000 0 0 0 0\n"),
                   packet(1, 1, "active", "", 0, 0, 10000, "32768 0"),
                   packet(1, 1, "active", "EDGE 1 4096 10001 0 0 0 0\n")})
    assert(!parse_input(bad));
  assert(!parse_input(std::string(4097, 'x')) &&
         !parse_permit(std::string(513, 'x')));
  assert(!select_permit(permit(2), permit(2, 10, 99)));
  assert(select_permit(permit(1), permit(2))->seq == 2);
  assert(select_permit("partial", permit(1))->seq == 1);
  Core waiting;
  update(waiting, packet(1, 1, "neutral"), permit());
  update(waiting, packet(1, 2, "neutral"), permit());
  assert(!waiting.receipt(10000));
  waiting.poll(true, 10000, 0);
  assert(waiting.receipt(10000)->state == "ready");
  update(waiting, packet(1, 3, "neutral"), permit());
  assert(waiting.receipt(10000)->seq == 3);
  update(waiting, packet(1, 4, "active"), permit());
  assert(waiting.receipt(10000)->seq == 4 &&
         waiting.receipt(10000)->state == "ready");
  update(waiting, packet(1, 5, "neutral"), permit());
  assert(waiting.receipt(10000)->state == "blocked");
  // The permit's run outlives forwarded input (the wardrobe's gate), until its lease or a stop.
  assert(waiting.forwarding_run().empty() && waiting.permit_run(0) == run && waiting.permit_run(lease_ms - 1) == run &&
         waiting.permit_run(lease_ms).empty());
  auto stopped = permit(2);
  stopped.replace(stopped.find("active"), 6, "stopped");
  update(waiting, packet(1, 6, "neutral"), stopped);
  assert(waiting.permit_run(0).empty() && "A stopped permit grants nothing");
  assert(Core().permit_run(0).empty());
  Core c;
  arm(c);
  const std::string edges =
      "EDGE 1 4096 10000 100 200 300 400\nEDGE 2 0 10000 -100 0 0 0\n";
  update(c, packet(1, 2, "active", edges, 0, 0, 10000, "500 600"), permit());
  for (int i = 0; i < 4; ++i)
    assert(c.poll(false, 10000, 0).pad == Pad{});
  assert(c.receipt(10000)->edge == 0);
  auto p = c.poll(true, 10000, 0);
  assert(p.override && p.pad.buttons == 4096 && p.pad.lx == 100 &&
         p.pad.ry == 400);
  assert(c.poll(false, 10000, 0).pad == p.pad && c.receipt(10000)->edge == 1);
  p = c.poll(true, 10000, 0);
  assert(p.pad.buttons == 0 && p.pad.lx == -100 && c.receipt(10000)->edge == 2);
  p = c.poll(true, 10000, 0);
  assert(p.pad.lx == 500 && p.pad.ly == 600);
  update(c, packet(1, 3, "active", "", 2, 0), permit());
  assert(c.receipt(10000)->seq == 3);
  update(c, packet(1, 4, "active", "", 3, 0), permit());
  assert(c.receipt(10000)->state == "blocked");
  assert(c.poll(false, 10000, 0).pad == Pad{} &&
         c.receipt(10000)->state == "blocked");
  assert(c.poll(true, 10000, 0).override &&
         c.receipt(10000)->state == "released");
  assert(!c.poll(true, 10000, 0).override);
  // A greater sequence never revives the blocked epoch; a new neutral epoch
  // does.
  update(c, packet(1, 5, "active"), permit());
  assert(c.receipt(10000)->state != "ready");
  update(c, packet(2, 1, "neutral"), permit());
  assert(c.poll(true, 10000, 0).override && c.receipt(10000)->state == "ready");
  for (std::string failure :
       {"changed duplicate", "rollback", "missing prefix", "mutated overlap",
        "native context", "native generation", "missing permit", "stopped"}) {
    Core x;
    arm(x);
    update(x, packet(1, 2, "active", edges), permit());
    x.poll(true, 10000, 0);
    if (failure == "changed duplicate")
      update(x, packet(1, 2, "active", edges, 0, 0, 10000, "1 0"), permit());
    else if (failure == "rollback")
      update(x, packet(1, 1, "neutral"), permit());
    else if (failure == "missing prefix")
      update(x, packet(1, 3, "active"), permit());
    else if (failure == "mutated overlap")
      update(x,
             packet(1, 3, "active",
                    "EDGE 1 4096 10000 0 0 0 0\nEDGE 2 0 10000 0 0 0 0\n"),
             permit());
    else if (failure == "native context")
      update(x, packet(1, 3, "active", edges), permit(2, 10, 99));
    else if (failure == "native generation")
      update(x, packet(1, 3, "active", edges), permit(2, 10, 11, 2));
    else if (failure == "missing permit")
      update(x, packet(1, 3, "active", edges), "");
    else
      update(x, packet(1, 3, "stopped"), permit());
    assert(x.receipt(10000)->state == "blocked");
    auto release = x.poll(true, 10000, 0);
    assert(release.override && release.pad == Pad{});
  }
  // Local lease expiry precedes accepting a newly-arrived sequence.
  Core timeout;
  arm(timeout);
  update(timeout, packet(1, 2, "active", edges), permit());
  timeout.poll(true, 10000, 0);
  update(timeout, packet(1, 3, "active", edges, 0, 0, 10501), permit(2, 10),
         10501, 501);
  assert(timeout.receipt(10501)->state == "blocked");
  assert(timeout.poll(true, 10501, 501).pad == Pad{});
  // Identical input or permit snapshots do not refresh either local lease.
  Core repeated;
  arm(repeated);
  update(repeated, neutral, permit(), 10400, 400);
  repeated.poll(false, 10501, 501);
  assert(repeated.receipt(10501)->state == "blocked");
  Core aged;
  arm(aged);
  update(aged, packet(1, 2, "active", edges), permit());
  update(aged, packet(1, 3, "active", edges, 0, 0, 10400), permit(2, 10), 10400,
         400);
  assert(aged.poll(true, 10501, 501).pad == Pad{} &&
         aged.receipt(10501)->state == "released");
  Core stale;
  arm(stale);
  update(stale, packet(1, 2, "active", "", 0, 0, 8000), permit(), 10000, 1);
  assert(stale.receipt(10000)->state == "blocked");
  Core future;
  arm(future);
  update(future, packet(1, 2, "active", "", 0, 0, 10101), permit(), 10000, 1);
  assert(future.receipt(10000)->state == "blocked");
  Core input_expiry;
  arm(input_expiry);
  update(input_expiry, neutral, permit(2), 10400, 400);
  assert(input_expiry.poll(true, 10500, 500).pad == Pad{});
  assert(input_expiry.receipt(10500)->state == "released");
  Core permit_expiry;
  arm(permit_expiry);
  update(permit_expiry, packet(1, 2, "neutral", "", 0, 0, 10400), permit(),
         10400, 400);
  assert(permit_expiry.poll(true, 10500, 500).pad == Pad{});
  assert(permit_expiry.receipt(10500)->state == "released");
  Core stale_permit;
  arm(stale_permit);
  update(stale_permit, packet(1, 2, "active"), permit(2, 8), 10000, 1);
  assert(stale_permit.receipt(10000)->state == "blocked");
  Core future_permit;
  arm(future_permit);
  update(future_permit, packet(1, 2, "active"), permit(2, 11), 10000, 1);
  assert(future_permit.receipt(10000)->state == "blocked");
  for (auto badmask : {1024u, 2048u, 262144u})
    assert(!parse_input(packet(1, 2, "active", "", 0, badmask)));
  std::string oversized_edges;
  for (int id = 1; id <= 33; ++id)
    oversized_edges += "EDGE " + std::to_string(id) + " " +
                       std::to_string(id % 2 ? 4096 : 0) + " 10000 0 0 0 0\n";
  assert(!parse_input(packet(1, 2, "active", oversized_edges)));
  auto a = c.receipt(10000);
  assert(a && encode_ack(*a).find("SBMC_INPUT_ACK 1\n") == 0);
  std::cout << "PASS strict framing, dual leases, neutral handshake, ordered "
               "edges, observer isolation, overlap, context and release\n";
}
