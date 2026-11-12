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
#endif
''', 1)
    anchor = "unsigned gdirect_ifindex = 0;"
    assert s.count(anchor) == 1
    s = s.replace(anchor, anchor + '''
#if defined(__ANDROID__)
uint64_t gdirect_android_network = 0;
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
    src.write_text(s)

bridge_src = (ROOT / "native/bridge.cc").read_text()
anchor = "unsigned ifindex) noexcept {"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "unsigned ifindex, uint64_t network_handle) noexcept {", 1)
anchor = "    gdirect_ifindex = ifindex;"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "    gdirect_ifindex = ifindex; gdirect_android_network = network_handle;", 1)
anchor = "gdirect_ifindex = 0; return -1;"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "gdirect_ifindex = 0; gdirect_android_network = 0; return -1;", 1)
anchor = "  gdirect_ifindex = 0;\n"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "  gdirect_ifindex = 0; gdirect_android_network = 0;\n", 1)
anchor = "nullptr, nullptr, true, 0);"
assert bridge_src.count(anchor) == 1
bridge_src = bridge_src.replace(anchor, "nullptr, nullptr, true, 0, 0);", 1)
destination = (ROOT / "android/native/src/aria2_android_bridge.cc"
               if BRIDGE_ONLY else Path("android-bridge.cc"))
destination.write_text(bridge_src)
