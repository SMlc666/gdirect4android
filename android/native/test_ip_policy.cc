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
  return 0;
}
