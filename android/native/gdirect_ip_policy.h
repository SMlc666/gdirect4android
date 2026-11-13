#pragma once
// Deterministic, dependency-free policy shared by Android socket routing and
// the host unit test. Never changes aria2's chunk ownership or file writes.
#include <cstddef>
#include <cstdint>
#include <vector>
#include <string>
#include <algorithm>

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
// Decide the actual numeric TCP dial destination AFTER hostname resolution.
// This is intentionally keyed on the original, allowlisted Google Request
// host. A numeric 'resolved' string is NOT itself evidence of authorization.
// Unlike the old getaddrinfo hook, this is invoked once for every fresh
// direct socket, not during an earlier DNS lookup.
inline std::string choose_dial_address(
    const std::string& resolved, bool authorized_google_request,
    const std::vector<std::string>& verified_ips,
    const std::vector<IpLoad>& load, uint64_t sequence, size_t* picked = nullptr) {
  if (!authorized_google_request || verified_ips.size()<2 ||
      verified_ips.size()!=load.size()) return resolved;
  const size_t idx=choose_ip(load,sequence);
  if (picked) *picked=idx;
  return verified_ips[idx];
}

// HTTP keepalive sockets are pooled under the ACTUAL peer IP. Enumerate the
// full verified pool when looking for reusable sockets, rotate the probe
// order independently of the new-dial cursor, and preserve the normal
// resolver candidates at the back (same request host, same TLS identity).
inline void order_pooled_addresses(
    std::vector<std::string>& addresses,
    const std::vector<std::string>& verified_ips, uint64_t sequence) {
  if (verified_ips.size()<2) return;
  std::vector<std::string> ordered;
  ordered.reserve(verified_ips.size()+addresses.size());
  const size_t start=sequence%verified_ips.size();
  for (size_t i=0;i<verified_ips.size();++i)
    ordered.push_back(verified_ips[(start+i)%verified_ips.size()]);
  for (const auto& address:addresses)
    if (std::find(ordered.begin(),ordered.end(),address)==ordered.end())
      ordered.push_back(address);
  addresses.swap(ordered);
}

// Only retire keepalive at an HTTP response boundary, after several
// consecutive slow windows and a statistically useful byte sample.
inline bool retire_after_segment(uint64_t bytes, double own_kib,
                                 double fastest_kib, unsigned slow_windows) {
  return bytes > 2u * 1024u * 1024u && fastest_kib > 512.0 &&
         own_kib < 0.55 * fastest_kib && slow_windows >= 3;
}

}  // namespace gdirect
