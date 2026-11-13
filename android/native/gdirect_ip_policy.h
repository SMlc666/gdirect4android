#pragma once
// Deterministic, dependency-free policy shared by Android socket routing and
// the host unit test. Never changes aria2's chunk ownership or file writes.
#include <cstddef>
#include <cstdint>
#include <vector>

namespace gdirect {
struct IpLoad {
  double smooth_kib_per_second = 0.0;
  unsigned open_sockets = 0;
};

// Probe each verified endpoint twice before applying a conservative
// speed/load preference to NEW connections. Existing TCP/TLS connections
// remain open and are reused normally by aria2.
inline size_t choose_ip(const std::vector<IpLoad>& peers, uint64_t sequence) {
  if (peers.empty()) return 0;
  if (sequence < peers.size() * 2) return sequence % peers.size();
  size_t selected = 0;
  double best_score = -1.0;
  for (size_t i=0; i<peers.size(); ++i) {
    // +128 KiB/s prior prevents a new/idle endpoint being locked out
    // forever; open-socket cost avoids concentrating new work needlessly.
    const double score = (peers[i].smooth_kib_per_second + 128.0)
                         / (1.0 + peers[i].open_sockets);
    if (score > best_score) {
      best_score = score;
      selected = i;
    }
  }
  return selected;
}
}  // namespace gdirect
