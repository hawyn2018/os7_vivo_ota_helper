// SPDX-License-Identifier: MIT
#pragma once
#include "vendorpatch.hpp"
#include <algorithm>
#include <cstring>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <utility>
namespace vendorpatch {
constexpr size_t kMaxImage = 1024ull * 1024 * 1024;
constexpr size_t kMaxExpanded = 512ull * 1024 * 1024;
inline void need(bool value, const std::string& error) { if (!value) throw std::runtime_error(error); }
inline size_t add(size_t a,size_t b) { need(b<=std::numeric_limits<size_t>::max()-a,"integer overflow");return a+b; }
inline size_t align(size_t n,size_t a) { need(a && !(a&(a-1)),"invalid alignment");return add(n,a-1)&~(a-1); }
inline void bounds(const Bytes& b,size_t p,size_t n) { need(p<=b.size()&&n<=b.size()-p,"image bounds"); }
inline uint32_t le32(const Bytes& b,size_t p) { bounds(b,p,4);return uint32_t(b[p])|(uint32_t(b[p+1])<<8)|(uint32_t(b[p+2])<<16)|(uint32_t(b[p+3])<<24); }
inline uint32_t be32(const Bytes& b,size_t p) { bounds(b,p,4);return (uint32_t(b[p])<<24)|(uint32_t(b[p+1])<<16)|(uint32_t(b[p+2])<<8)|b[p+3]; }
inline uint64_t be64(const Bytes& b,size_t p) { return (uint64_t(be32(b,p))<<32)|be32(b,p+4); }
inline void putle32(Bytes& b,size_t p,uint32_t n) { bounds(b,p,4);for(int i=0;i<4;i++)b[p+i]=uint8_t(n>>(8*i)); }
inline void putbe32(Bytes& b,size_t p,uint32_t n) { bounds(b,p,4);for(int i=0;i<4;i++)b[p+i]=uint8_t(n>>(24-8*i)); }
inline void putbe64(Bytes& b,size_t p,uint64_t n) { putbe32(b,p,uint32_t(n>>32));putbe32(b,p+4,uint32_t(n)); }
inline Bytes slice(const Bytes& b,size_t p,size_t n) { bounds(b,p,n);return Bytes(b.begin()+p,b.begin()+p+n); }
inline bool magic(const Bytes& b,size_t p,const char* s,size_t n) { return p<=b.size()&&n<=b.size()-p&&!memcmp(b.data()+p,s,n); }
inline bool zero(const Bytes& b,size_t a,size_t z) { need(a<=z&&z<=b.size(),"padding bounds");return std::all_of(b.begin()+a,b.begin()+z,[](uint8_t c){return c==0;}); }
inline void append(Bytes& a,const Bytes& b) { need(add(a.size(),b.size())<=kMaxImage,"buffer limit");a.insert(a.end(),b.begin(),b.end()); }
inline void store(Bytes& a,size_t p,const Bytes& b) { bounds(a,p,b.size());std::copy(b.begin(),b.end(),a.begin()+p); }
inline std::string jquote(const std::string& s) { std::ostringstream o;o<<'"';for(unsigned char c:s){if(c=='"'||c=='\\')o<<'\\'<<char(c);else if(c<32){const char*h="0123456789abcdef";o<<"\\u00"<<h[c>>4]<<h[c&15];}else o<<char(c);}o<<'"';return o.str(); }
inline std::string array(const std::vector<std::string>& a) { std::string r="[";for(size_t i=0;i<a.size();i++){if(i)r+=',';r+=a[i];}return r+"]"; }
inline std::string boolean(bool b){return b?"true":"false";}
inline Bytes strbytes(const std::string& s) { Bytes b(s.begin(),s.end());b.push_back(0);return b; }
struct FragmentResult { Bytes data; std::string json; bool changed; };
FragmentResult scrubFragment(const Bytes& compressed,size_t index);
struct DtbResult { Bytes data; std::vector<std::string> reports; };
DtbResult patchDtbRegion(const Bytes& region,uint32_t dram);
}
