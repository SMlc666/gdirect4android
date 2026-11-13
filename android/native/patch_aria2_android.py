#!/usr/bin/env python3
"""Android adaptation of gdirect's patched aria2 SocketCore and C++ bridge.

--bridge-only rebuilds the Android bridge from current native/bridge.cc even
when the libaria2 and OpenSSL archives were restored from GitHub cache.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
BRIDGE_ONLY = sys.argv[1:] == ["--bridge-only"]

if not BRIDGE_ONLY:
    src = Path("src/SocketCore.cc")
    s = src.read_text()
    anchor = '#include "gdirect_route.h"'
    assert s.count(anchor) == 1
    s = s.replace(anchor, anchor + '''
#if defined(__ANDROID__)
#include <android/multinetwork.h>
#include <cstdint>
#include <cerrno>
#include <atomic>
#include <vector>
#include <string>
#include <mutex>
#include <unordered_map>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include "gdirect_ip_policy.h"
#endif
''', 1)
    anchor = "unsigned gdirect_ifindex = 0;"
    assert s.count(anchor) == 1
    s = s.replace(anchor, anchor + '''
#if defined(__ANDROID__)
uint64_t gdirect_android_network = 0;

// One libaria2 session is active at a time. This pool is only reconfigured
// between sessions. SocketCore::establishConnection resolves every new TCP
// connection and the atomic cursor chooses a verified Google frontend.
static std::vector<std::string> gdirect_frontend_pool;
struct GdirectRouteSample {
  std::string ip;
  uint64_t bytes = 0, window_bytes = 0;
  double smooth_kib = 0.0;
  unsigned open_sockets = 0;
  unsigned created_sockets = 0;
};
static std::vector<GdirectRouteSample> gdirect_ip_samples;
static std::unordered_map<int, size_t> gdirect_socket_owner;
static std::mutex gdirect_ip_mutex;
static uint64_t gdirect_ip_cursor = 0;
static std::chrono::steady_clock::time_point gdirect_sample_clock =
  std::chrono::steady_clock::now();

static void gdirect_update_rate_locked() {
  const auto now = std::chrono::steady_clock::now();
  const double elapsed =
    std::chrono::duration<double>(now-gdirect_sample_clock).count();
  if (elapsed < 1.25) return;
  for (auto& item : gdirect_ip_samples) {
    const double fresh = static_cast<double>(item.window_bytes)
                         / (1024.0*elapsed);
    item.smooth_kib = item.created_sockets <= 1
      ? fresh : (0.65*item.smooth_kib + 0.35*fresh);
    item.window_bytes = 0;
  }
  gdirect_sample_clock = now;
}

// This function is called only between complete aria2 sessions; the mutex
// also protects an overlapping JNI telemetry request on the UI thread.
extern "C" void gdirect_android_set_ip_pool(const char* csv) {
  std::lock_guard<std::mutex> guard(gdirect_ip_mutex);
  gdirect_frontend_pool.clear();
  gdirect_ip_samples.clear();
  gdirect_socket_owner.clear();
  gdirect_ip_cursor = 0;
  gdirect_sample_clock = std::chrono::steady_clock::now();
  if (!csv || !*csv) return;
  const std::string source(csv);
  size_t start = 0;
  while (start < source.size() && gdirect_frontend_pool.size() < 4) {
    const size_t end = source.find(',', start);
    const std::string ip = source.substr(start,
        end == std::string::npos ? std::string::npos : end-start);
    // Rust validates the actual Google IPv4 ranges. Reject anything else
    // at this FFI boundary, never touch SNI or the certificate hostname.
    if (!ip.empty() && ip.size() <= 15 &&
        ip.find_first_not_of("0123456789.") == std::string::npos) {
      gdirect_frontend_pool.push_back(ip);
      GdirectRouteSample sample;
      sample.ip = ip;
      gdirect_ip_samples.push_back(sample);
    }
    if (end == std::string::npos) break;
    start = end + 1;
  }
}

// Called from aria2's synchronous getaddrinfo path for each new connection.
// Reuse of an existing keepalive connection never invokes this chooser.
static const char* gdirect_android_next_ip() {
  std::lock_guard<std::mutex> guard(gdirect_ip_mutex);
  if (gdirect_frontend_pool.empty()) return gdirect_ip;
  gdirect_update_rate_locked();
  std::vector<gdirect::IpLoad> loads;
  for (const auto& item : gdirect_ip_samples) {
    loads.push_back({item.smooth_kib, item.open_sockets});
  }
  const size_t index = gdirect::choose_ip(loads, gdirect_ip_cursor++);
  return gdirect_frontend_pool[index].c_str();
}

// At connect(), record the numeric peer IP. This does not perform DNS;
// the addresses come from Rust's verified Google frontend allowlist.
static void gdirect_android_track_socket(
    int fd, const struct sockaddr* address, socklen_t addrlen) {
  char host[NI_MAXHOST] = {};
  if (getnameinfo(address, addrlen, host, sizeof(host), nullptr, 0,
                  NI_NUMERICHOST) != 0) return;
  std::lock_guard<std::mutex> guard(gdirect_ip_mutex);
  for (size_t i=0; i<gdirect_ip_samples.size(); ++i) {
    if (gdirect_ip_samples[i].ip == host) {
      gdirect_socket_owner[fd] = i;
      ++gdirect_ip_samples[i].open_sockets;
      ++gdirect_ip_samples[i].created_sockets;
      break;
    }
  }
}
static void gdirect_android_count_read(int fd, ssize_t bytes) {
  if (bytes <= 0) return;
  std::lock_guard<std::mutex> guard(gdirect_ip_mutex);
  auto it = gdirect_socket_owner.find(fd);
  if (it == gdirect_socket_owner.end()) return;
  auto& sample = gdirect_ip_samples[it->second];
  sample.bytes += static_cast<uint64_t>(bytes);
  sample.window_bytes += static_cast<uint64_t>(bytes);
}
static void gdirect_android_release_socket(int fd) {
  std::lock_guard<std::mutex> guard(gdirect_ip_mutex);
  auto it = gdirect_socket_owner.find(fd);
  if (it == gdirect_socket_owner.end()) return;
  auto& count = gdirect_ip_samples[it->second].open_sockets;
  if (count > 0) --count;
  gdirect_socket_owner.erase(it);
}

// No OAuth material. All figures are sampled from actual TLS application
// reads, not inferred from the short scan benchmark or the configured IP list.
extern "C" size_t gdirect_android_ip_stats(char* out, size_t capacity) {
  if (!out || capacity == 0) return 0;
  std::lock_guard<std::mutex> guard(gdirect_ip_mutex);
  gdirect_update_rate_locked();
  std::string report;
  for (size_t i=0; i<gdirect_ip_samples.size(); ++i) {
    const auto& item = gdirect_ip_samples[i];
    char part[128];
    std::snprintf(part, sizeof(part), "%s%s %.0f KiB/s (%u连接, %llu KiB)",
                  i ? " | " : "", item.ip.c_str(), item.smooth_kib,
                  item.open_sockets,
                  static_cast<unsigned long long>(item.bytes / 1024));
    report += part;
  }
  const size_t count = std::min(capacity-1,report.size());
  if (count) std::memcpy(out,report.data(),count);
  out[count] = '\0';
  return count;
}
#endif
''', 1)
    anchor = """  if (gdirect_ifindex == 0 || !gdirect_ip || !*gdirect_ip) {
    return;
  }"""
    assert s.count(anchor) == 1
    s = s.replace(anchor, '''#if defined(__ANDROID__)
  if (gdirect_android_network != 0 && gdirect_ip && *gdirect_ip) {
    if (android_setsocknetwork(
          static_cast<net_handle_t>(gdirect_android_network), fd) != 0) {
      // Never silently fall back to a VPN when the selected network refused.
      throw DL_ABORT_EX(fmt(
          "gdirect Android network bind refused: errno=%d", errno));
    }
    return;
  }
#endif
''' + anchor, 1)
    # Keep the HTTPS Host/SNI unchanged, altering only the numeric connect IP.
    anchor = "    host = gdirect_ip;"
    assert s.count(anchor) == 1
    s = s.replace(anchor, "    host = gdirect_android_next_ip();", 1)
    # Attribute connected sockets and TLS application bytes to the selected
    # Google frontend; never alter aria2's existing piece ownership.
    anchor = "    // TODO at this point, connection may not be established"
    assert s.count(anchor) == 1
    s = s.replace(anchor,
        "    gdirect_android_track_socket(fd, rp->ai_addr, rp->ai_addrlen);\n"
        + anchor, 1)
    anchor = "  len = ret;\n}"
    assert s.count(anchor) == 1
    s = s.replace(anchor,
        "  if (ret > 0) gdirect_android_count_read(sockfd_, ret);\n"
        + anchor, 1)
    anchor = "    shutdown(sockfd_, SHUT_WR);"
    assert s.count(anchor) == 1
    s = s.replace(anchor,
        "    gdirect_android_release_socket(sockfd_);\n" + anchor, 1)
    src.write_text(s)

bridge_src = (ROOT / "native/bridge.cc").read_text()
anchor = '#include "../vendor/gdirect_route.h"'
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, anchor +
    '\nextern "C" void gdirect_android_set_ip_pool(const char* csv);', 1)
anchor = "unsigned ifindex) noexcept {"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "unsigned ifindex, uint64_t network_handle, const char* ip_pool) noexcept {", 1)
anchor = "    gdirect_ifindex = ifindex;"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "    gdirect_ifindex = ifindex; gdirect_android_network = network_handle;", 1)
anchor = "gdirect_ifindex = 0; return -1;"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "gdirect_ifindex = 0; gdirect_android_network = 0; return -1;", 1)
anchor = '    if (!library_ready()) { gdirect_ip = gdirect_sni = nullptr; gdirect_ifindex = 0; gdirect_android_network = 0; return -1; }'
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, anchor + "\n    gdirect_android_set_ip_pool(ip_pool);", 1)
anchor = "  gdirect_ifindex = 0;\n"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "  gdirect_ifindex = 0; gdirect_android_network = 0;\n  gdirect_android_set_ip_pool(nullptr);\n", 1)
anchor = "nullptr, nullptr, true, 0);"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "nullptr, nullptr, true, 0, 0, nullptr);", 1)
destination = (ROOT / "android/native/src/aria2_android_bridge.cc"
               if BRIDGE_ONLY else Path("android-bridge.cc"))
destination.write_text(bridge_src)
