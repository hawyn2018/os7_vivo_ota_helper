// SPDX-License-Identifier: MIT
#include "internal.hpp"
#include "lz4.h"
#include "lz4hc.h"
#include "zlib.h"
#include <cctype>
#include <cstdlib>
#include <set>
namespace vendorpatch {
namespace {
constexpr size_t Block=8*1024*1024;
struct Decoded { Bytes raw; std::string kind; bool terminator=false; };
void allocator(z_stream& z) {
 z.zalloc=[](void*,uInt items,uInt size)->void* {if(size&&items>std::numeric_limits<size_t>::max()/size)return nullptr;return std::calloc(items,size);};
 z.zfree=[](void*,void* p){std::free(p);};
}
Decoded decode(const Bytes& data) {
 Decoded d;
 if(magic(data,0,"070701",6)||magic(data,0,"070702",6)){d.kind="cpio";d.raw=data;return d;}
 if(magic(data,0,"\x02\x21\x4c\x18",4)) {
  d.kind="lz4-legacy";size_t p=4;Bytes block(Block);bool shortBlock=false;
  while(p<data.size()) {
   uint32_t n=le32(data,p);p+=4;
   if(!n){d.terminator=true;need(p==data.size(),"LZ4 trailing data");break;}
   need(!shortBlock&&n<=size_t(LZ4_compressBound(int(Block))),"LZ4 invalid block");bounds(data,p,n);
   int out=LZ4_decompress_safe(reinterpret_cast<const char*>(data.data()+p),reinterpret_cast<char*>(block.data()),int(n),int(Block));
   need(out>0,"LZ4 decompression failed");need(add(d.raw.size(),size_t(out))<=kMaxExpanded,"expanded ramdisk limit");
   d.raw.insert(d.raw.end(),block.begin(),block.begin()+out);shortBlock=size_t(out)<Block;p+=n;
  }
  need(!d.raw.empty(),"empty LZ4 stream");return d;
 }
 if(magic(data,0,"\x1f\x8b",2)) {
  d.kind="gzip";z_stream z{};allocator(z);need(inflateInit2(&z,31)==Z_OK,"gzip init");
  z.next_in=const_cast<Bytef*>(data.data());z.avail_in=uInt(data.size());Bytes block(1024*1024);int status=Z_OK;
  try { while(status!=Z_STREAM_END){z.next_out=block.data();z.avail_out=uInt(block.size());status=inflate(&z,Z_NO_FLUSH);need(status==Z_OK||status==Z_STREAM_END,"gzip corrupt/unsupported stream");size_t n=block.size()-z.avail_out;need(add(d.raw.size(),n)<=kMaxExpanded,"expanded ramdisk limit");d.raw.insert(d.raw.end(),block.begin(),block.begin()+n);need(n||status==Z_STREAM_END,"truncated gzip");}
   need(z.total_in==data.size(),"gzip trailing/concatenated stream unsupported");inflateEnd(&z);
  } catch(...) {inflateEnd(&z);throw;}return d;
 }
 throw std::runtime_error("unknown ramdisk compression");
}
Bytes encode(const Decoded& d,const Bytes& raw) {
 if(d.kind=="cpio")return raw;
 Bytes result;
 if(d.kind=="lz4-legacy") {
  result={2,0x21,0x4c,0x18};Bytes block(size_t(LZ4_compressBound(int(Block))),0);
  for(size_t p=0;p<raw.size();p+=Block){size_t n=std::min(Block,raw.size()-p);int out=LZ4_compress_HC(reinterpret_cast<const char*>(raw.data()+p),reinterpret_cast<char*>(block.data()),int(n),int(block.size()),9);need(out>0,"LZ4 compression failed");size_t at=result.size();result.resize(at+4);putle32(result,at,uint32_t(out));result.insert(result.end(),block.begin(),block.begin()+out);}
  if(d.terminator)result.resize(result.size()+4);return result;
 }
 z_stream z{};allocator(z);need(deflateInit2(&z,9,Z_DEFLATED,31,8,Z_DEFAULT_STRATEGY)==Z_OK,"gzip deflate init");
 z.next_in=const_cast<Bytef*>(raw.data());z.avail_in=uInt(raw.size());Bytes block(1024*1024);int status=Z_OK;
 try {while(status!=Z_STREAM_END){z.next_out=block.data();z.avail_out=uInt(block.size());status=deflate(&z,Z_FINISH);need(status==Z_OK||status==Z_STREAM_END,"gzip compression failed");result.insert(result.end(),block.begin(),block.begin()+block.size()-z.avail_out);}deflateEnd(&z);}catch(...){deflateEnd(&z);throw;}
 return result;
}
void utf8(const std::string& s) {
 for(size_t i=0;i<s.size();) {uint8_t c=uint8_t(s[i++]);if(c<0x80)continue;int n=c>=0xc2&&c<=0xdf?1:c>=0xe0&&c<=0xef?2:c>=0xf0&&c<=0xf4?3:-1;need(n>0&&i+size_t(n)<=s.size(),"invalid UTF-8");uint32_t cp=c&((1u<<(6-n))-1);for(int j=0;j<n;j++){uint8_t a=uint8_t(s[i++]);need((a&0xc0)==0x80,"invalid UTF-8 continuation");cp=(cp<<6)|(a&63);}need(!(cp>=0xd800&&cp<=0xdfff)&&cp<=0x10ffff&&cp>=(n==1?0x80:n==2?0x800:0x10000),"invalid UTF-8 scalar");}
}
std::string trim(std::string s) {size_t a=s.find_first_not_of(" \t\r\n"),b=s.find_last_not_of(" \t\r\n");return a==std::string::npos?"":s.substr(a,b-a+1);}
std::vector<std::string> tokens(const std::string& s) {std::istringstream in(s);std::vector<std::string> r;std::string x;while(in>>x)r.push_back(x);return r;}
std::string joined(const std::vector<std::string>& a){std::string r;for(const auto&s:a){if(!r.empty())r+=' ';r+=s;}return r;}
std::string normalized(std::string s){while(!s.empty()&&s[0]=='/')s.erase(0,1);while(s.rfind("./",0)==0)s.erase(0,2);return s;}
std::string stem(std::string s,bool requireKo=false){s=trim(s);while(!s.empty()&&(s.front()==':'||s.front()==','))s.erase(0,1);while(!s.empty()&&(s.back()==':'||s.back()==','))s.pop_back();size_t p=s.rfind('/');if(p!=std::string::npos)s=s.substr(p+1);for(const auto&ext:{".gz",".xz",".zst",".lz4"}){size_t n=strlen(ext);if(s.size()>=n&&s.compare(s.size()-n,n,ext)==0){s.resize(s.size()-n);break;}}if(s.size()>=3&&s.compare(s.size()-3,3,".ko")==0)s.resize(s.size()-3);else if(requireKo)return "";for(char&c:s){if(c>='A'&&c<='Z')c=char(c-'A'+'a');if(c=='-')c='_';}return s;}
bool target(const std::string&s){auto t=stem(s);return t=="vr"||t=="vklp";}
bool indexPath(const std::string& path){if(path.rfind("lib/modules/",0)!=0)return false;auto p=path.substr(path.rfind('/')+1);return p=="modules.dep"||p=="modules.softdep"||p=="modules.alias"||p=="modules.options"||p=="modules.blocklist"||p=="modules.load"||p=="modules.order"||p.rfind("modules.load.",0)==0;}
std::pair<bool,std::string> lineRewrite(const std::string& kind,const std::string& line){
 auto c=line.find('#');std::string text=line.substr(0,c),comment=c==std::string::npos?"":line.substr(c);auto ts=tokens(text);if(ts.empty())return {true,line};
 auto attach=[&](std::string s){return s+(comment.empty()?"":(s.empty()?"":" ")+comment);};
 if(kind=="modules.dep") {auto p=text.find(':');if(p==std::string::npos)return {true,line};std::string own=text.substr(0,p);if(target(own))return {false,""};auto deps=tokens(text.substr(p+1)),keep=deps;keep.erase(std::remove_if(keep.begin(),keep.end(),target),keep.end());if(keep!=deps)return {true,attach(trim(own)+":"+(keep.empty()?"":" "+joined(keep)))};}
 else if(kind=="modules.softdep") {if(ts.size()>1&&target(ts[1]))return {false,""};auto keep=ts;keep.erase(std::remove_if(keep.begin(),keep.end(),[](const std::string&s){return s!="softdep"&&s!="pre:"&&s!="post:"&&target(s);}),keep.end());if(keep!=ts)return {true,attach(joined(keep))};}
 else if(kind=="modules.alias") {if(ts[0]=="alias"&&target(ts.back()))return {false,""};}
 else if(kind=="modules.options"||kind=="modules.blocklist") {if(ts.size()>1&&target(ts[1]))return {false,""};}
 else if(kind=="modules.load"||kind=="modules.order"||kind.rfind("modules.load.",0)==0) {if(target(ts[0])||((kind=="modules.load"||kind.rfind("modules.load.",0)==0)&&stem(ts[0])=="vklp_smaps_rollup"))return {false,""};}
 return {true,line};
}
Bytes rewrite(const std::string& path,const Bytes& data,size_t& changed) {
 std::string text(data.begin(),data.end());utf8(text);need(text.find('\0')==std::string::npos,"binary module index");std::string result,kind=path.substr(path.rfind('/')+1);size_t p=0;
 while(p<text.size()){size_t end=text.find('\n',p);bool lf=end!=std::string::npos;if(!lf)end=text.size();std::string part=text.substr(p,end-p),ending=lf?"\n":"";if(lf&&!part.empty()&&part.back()=='\r'){part.pop_back();ending="\r\n";}auto r=lineRewrite(kind,part);if(!r.first||r.second!=part)changed++;if(r.first)result+=r.second+ending;p=lf?end+1:end;}
 return Bytes(result.begin(),result.end());
}
uint32_t hexfield(const Bytes& d,size_t p){bounds(d,p,8);uint32_t n=0;for(size_t j=0;j<8;j++){char c=char(d[p+j]);int v=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;need(v>=0,"invalid CPIO hex field");n=(n<<4)|uint32_t(v);}return n;}
void puthex(Bytes& d,size_t p,uint32_t n){const char*h="0123456789abcdef";bounds(d,p,8);for(int j=7;j>=0;j--){d[p+j]=uint8_t(h[n&15]);n>>=4;}}
}
FragmentResult scrubFragment(const Bytes& compressed,size_t index) {
 auto d=decode(compressed);const Bytes& raw=d.raw;Bytes out;size_t p=0,removed=0,lines=0,records=0;bool trailer=false;
 while(p<raw.size()){
  size_t start=p;bounds(raw,p,110);bool crc=magic(raw,p,"070702",6);need(crc||magic(raw,p,"070701",6),"unsupported CPIO archive");uint32_t f[13];for(int j=0;j<13;j++)f[j]=hexfield(raw,p+6+j*8);
  uint32_t n=f[11],sz=f[6];need(n>0&&n<1024*1024,"CPIO name length");bounds(raw,p+110,n);need(raw[p+110+n-1]==0,"CPIO name terminator");std::string name(reinterpret_cast<const char*>(raw.data()+p+110),n-1);need(name.find('\0')==std::string::npos,"CPIO embedded NUL");utf8(name);size_t dataAt=align(p+110+n,4),end=align(add(dataAt,sz),4);bounds(raw,dataAt,sz);need(end<=raw.size(),"CPIO padding bounds");Bytes data=slice(raw,dataAt,sz);if(crc){uint32_t sum=0;for(uint8_t b:data)sum+=b;need(sum==f[12],"CPIO CRC mismatch");}
  if(name=="TRAILER!!!"){need(!sz&&zero(raw,end,raw.size()),"CPIO trailer/concat unsupported");append(out,slice(raw,start,raw.size()-start));trailer=true;break;}
  std::string norm=normalized(name);bool rm=norm.rfind("lib/modules/",0)==0&&target(stem(norm,true))&&!stem(norm,true).empty(),idx=indexPath(norm);size_t before=lines;Bytes replacement=idx?rewrite(norm,data,lines):data;
  if(rm||lines!=before){need(name==norm,"noncanonical module/index path");need((f[1]&0170000)==0100000&&f[4]==1,"changed CPIO record is not independent regular file");}
  if(rm)removed++;else if(lines!=before){Bytes prefix=slice(raw,start,dataAt-start);puthex(prefix,54,uint32_t(replacement.size()));if(crc){uint32_t sum=0;for(uint8_t b:replacement)sum+=b;puthex(prefix,102,sum);}append(out,prefix);append(out,replacement);out.resize(align(out.size(),4));}else append(out,slice(raw,start,end-start));
  records++;p=end;
 }
 need(trailer,"missing CPIO trailer");bool changed=removed||lines;Bytes result=changed?encode(d,out):compressed;if(changed)need(decode(result).raw==out,"ramdisk roundtrip failed");
 std::string report="{\"index\":"+std::to_string(index)+",\"compression\":"+jquote(d.kind)+",\"changed\":"+boolean(changed)+",\"removedModules\":"+std::to_string(removed)+",\"changedIndexLines\":"+std::to_string(lines)+",\"records\":"+std::to_string(records)+",\"orphanModulePreserved\":true,\"beforeSize\":"+std::to_string(compressed.size())+",\"afterSize\":"+std::to_string(result.size())+"}";
 return {std::move(result),std::move(report),changed};
}
}
