#!/usr/bin/env python3
"""Android-only adaptation of gdirect's patched aria2 1.37.0 SocketCore."""
from pathlib import Path

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

bridge_src = Path("android-bridge.cc.original").read_text()
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
Path("android-bridge.cc").write_text(bridge_src)
