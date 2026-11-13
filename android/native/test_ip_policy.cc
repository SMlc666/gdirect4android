#include "gdirect_ip_policy.h"
#include <cassert>
#include <vector>

int main() {
  using gdirect::IpLoad;
  using gdirect::choose_ip;
  assert(choose_ip({}, 0)==0);
  assert(choose_ip({IpLoad{300,2}}, 99)==0);
  const std::vector<IpLoad> peers{{600,2},{100,2},{200,2}};
  for (size_t i=0; i<6; ++i)
    assert(choose_ip(peers,i)==i%3);
  assert(choose_ip(peers,6)==0);
  const std::vector<IpLoad> loaded{{600,16},{200,1},{100,1}};
  assert(choose_ip(loaded,6)==1);
  const std::vector<IpLoad> idle{{600,16},{200,1},{100,0}};
  assert(choose_ip(idle,6)==2);
  const std::vector<IpLoad> equal{{300,2},{300,2},{300,2}};
  assert(choose_ip(equal,6)==0);
  assert(!gdirect::retire_after_segment(1024,100,1000,10));
  assert(!gdirect::retire_after_segment(4*1024*1024,100,1000,2));
  assert(!gdirect::retire_after_segment(4*1024*1024,450,600,4));
  assert(gdirect::retire_after_segment(4*1024*1024,100,1000,3));
  // Regression: aria2 resolves googleapis.com to a numeric address BEFORE
  // SocketCore::establishConnection. The resolved IP is only a hint; the
  // selected verified Google frontend must be used for each new dial.
  std::vector<std::string> pool{"142.251.102.113","142.251.122.100"};
  std::vector<IpLoad> current{{0,0},{0,0}};
  size_t choice=99, counts[2]={0,0};
  for (uint64_t dial=0;dial<8;++dial) {
    auto dest=gdirect::choose_dial_address(
        pool[0],true,pool,current,dial,&choice);
    assert(dest==pool[choice]);
    ++counts[choice];
    ++current[choice].open_sockets;
  }
  assert(counts[0]==4 && counts[1]==4);
  assert(gdirect::choose_dial_address(
      pool[0],false,pool,current,8)==pool[0]);
  assert(gdirect::choose_dial_address(
      pool[0],true,{pool[0]},{{0,0}},8)==pool[0]);
  std::vector<std::string> lookup{pool[0]};
  gdirect::order_pooled_addresses(lookup,pool,1);
  assert(lookup.size()==2 && lookup[0]==pool[1] && lookup[1]==pool[0]);
  gdirect::order_pooled_addresses(lookup,pool,2);
  assert(lookup.size()==2 && lookup[0]==pool[0] && lookup[1]==pool[1]);
  return 0;
}
