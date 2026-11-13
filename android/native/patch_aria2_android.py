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
static std::atomic<unsigned> gdirect_frontend_cursor{0};
extern "C" void gdirect_android_set_ip_pool(const char* csv) {
  gdirect_frontend_pool.clear();
  gdirect_frontend_cursor.store(0, std::memory_order_relaxed);
  if (!csv || !*csv) return;
  const std::string source(csv);
  size_t start = 0;
  while (start < source.size() && gdirect_frontend_pool.size() < 4) {
    const size_t end = source.find(',', start);
    const std::string ip = source.substr(start,
        end == std::string::npos ? std::string::npos : end-start);
    // Rust checked these IPs against Google's IPv4 allowlist before JNI.
    if (!ip.empty() && ip.size() <= 15 &&
        ip.find_first_not_of("0123456789.") == std::string::npos)
      gdirect_frontend_pool.push_back(ip);
    if (end == std::string::npos) break;
    start = end+1;
  }
}
static const char* gdirect_android_next_ip() {
  if (gdirect_frontend_pool.empty()) return gdirect_ip;
  const unsigned index =
      gdirect_frontend_cursor.fetch_add(1, std::memory_order_relaxed);
  return gdirect_frontend_pool[index % gdirect_frontend_pool.size()].c_str();
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
