// SPDX-License-Identifier: MIT
#include "internal.hpp"
extern "C" {
#include "libfdt.h"
}
#include <map>
#include <set>
namespace vendorpatch {
namespace {
void check(int r,const char* what){need(r>=0,std::string(what)+": "+fdt_strerror(r));}
int path(const Bytes& b,const std::string& p){return fdt_path_offset(b.data(),p.c_str());}
std::string nodepath(const Bytes& b,int n){char s[8192];check(fdt_get_path(b.data(),n,s,sizeof(s)),"FDT node path");return s;}
std::map<std::string,Bytes> properties(const Bytes& b,int n){std::map<std::string,Bytes> result;int p;
 fdt_for_each_property_offset(p,b.data(),n){const char* name=nullptr;int len=0;auto value=static_cast<const uint8_t*>(fdt_getprop_by_offset(b.data(),p,&name,&len));need(value&&name&&len>=0,"FDT property");need(result.emplace(name,Bytes(value,value+len)).second,"duplicate FDT property");}need(p==-FDT_ERR_NOTFOUND,"FDT property traversal");return result;
}
void set(Bytes& b,const std::string& p,const std::string& key,const Bytes& data){int n=path(b,p);check(n,"FDT target path");check(fdt_setprop(b.data(),n,key.c_str(),data.empty()?nullptr:data.data(),int(data.size())),"FDT setprop");}
Bytes u32(uint32_t v){Bytes b(4);putbe32(b,0,v);return b;}
std::string text(const Bytes& b){need(!b.empty()&&b.back()==0,"FDT string property");need(std::find(b.begin(),b.end()-1,0)==b.end()-1,"FDT single string expected");return std::string(b.begin(),b.end()-1);}
Bytes patchOne(const Bytes& input,uint32_t dram,std::string& report){
 need(input.size()<=64*1024*1024,"DTB size limit");check(fdt_check_full(input.data(),input.size()),"FDT validation");need(fdt_totalsize(input.data())==input.size(),"DTB size mismatch");
 size_t capacity=add(input.size(),256*1024);Bytes b(capacity);check(fdt_open_into(input.data(),b.data(),int(b.size())),"FDT expand");
 int aliases=path(b,"/aliases");check(aliases,"DSI aliases missing");auto ap=properties(b,aliases);need(ap.count("dsi0"),"dsi0 alias missing");std::string old=text(ap.at("dsi0"));int dsi=path(b,old);check(dsi,"dsi0 alias target");const char* name=fdt_get_name(b.data(),dsi,nullptr);need(name,"DSI node name");std::string basename=name,newPath=old;
 if(basename=="dsi0"||basename.rfind("dsi0@",0)==0){std::string nn="dsi"+basename.substr(4);newPath=old.substr(0,old.rfind('/')+1)+nn;need(path(b,newPath)<0,"DSI rename conflict");check(fdt_set_name(b.data(),dsi,nn.c_str()),"DSI rename");}
 else need(basename=="dsi"||basename.rfind("dsi@",0)==0,"unknown primary DSI node schema");
 size_t updated=0;
 if(newPath!=old){
  struct Update{std::string path,key;Bytes data;};std::vector<Update> changes;
  int depth=0,n=-1;
  while((n=fdt_next_node(b.data(),n,&depth))>=0){std::string np=nodepath(b,n);auto props=properties(b,n);for(const auto& item:props){const Bytes& v=item.second;if(v.size()<=old.size()||v.back()!=0)continue;if(!std::equal(old.begin(),old.end(),v.begin()))continue;uint8_t boundary=v[old.size()];if(boundary!=0&&boundary!='/')continue;need(std::find(v.begin(),v.end()-1,0)==v.end()-1,"ambiguous DSI path property");std::string value(v.begin(),v.end()-1);changes.push_back({np,item.first,strbytes(newPath+value.substr(old.size()))});}}
  need(n==-FDT_ERR_NOTFOUND,"FDT node traversal");for(const auto& c:changes){set(b,c.path,c.key,c.data);updated++;}
 }
 check(path(b,"/soc/dsi"),"legacy /soc/dsi path unresolved");need(path(b,"/soc/mipi-tx-config")<0,"legacy PHY rewrite path exposed");
 std::vector<std::string> realNodes;int n=-1;
 while((n=fdt_node_offset_by_compatible(b.data(),n,"mediatek,scp"))>=0){std::string np=nodepath(b,n);std::string nm=fdt_get_name(b.data(),n,nullptr);if(nm.rfind("scp-lk-compat@",0)!=0)realNodes.push_back(np);}
 need(n==-FDT_ERR_NOTFOUND&&realNodes.size()==1,"expected one real SCP node");std::string real=realNodes[0];need(real.rfind("/soc/scp@",0)==0,"SCP parent/name schema");auto realProps=properties(b,path(b,real));need(realProps.count("scp-mem-tbl")&&realProps.count("reg")&&realProps.count("interrupts")&&realProps.count("scp-mem-key"),"SCP contract properties missing");need(realProps.count("status")&&(text(realProps.at("status"))=="okay"||text(realProps.at("status"))=="ok"),"real SCP node not enabled");
 Bytes full=realProps.at("scp-mem-tbl");need(!full.empty()&&full.size()%12==0&&full.size()/12<=26,"SCP table length/schema");std::set<uint32_t> ids;uint64_t tail=0;bool id22=false,id0=false;Bytes legacy;
 uint32_t prev=0;for(size_t p=0;p<full.size();p+=12){uint32_t id=be32(full,p),size=be32(full,p+4),alignment=be32(full,p+8);need(id<=25&&ids.insert(id).second&&(!p||id>prev)&&!alignment,"unsupported SCP ID/order/alignment");prev=id;if(id==0){id0=true;need(size==0,"SCP dynamic dump row must be zero");}if(id==22){id22=true;need(size>0,"SCP ID22 empty");}if(id>=23)tail+=size;else legacy.insert(legacy.end(),full.begin()+p,full.begin()+p+12);}
 need(id0&&id22&&legacy.size()/12<=23&&tail<=0xffffffffu,"SCP legacy fold contract");for(size_t p=0;p<legacy.size();p+=12)if(be32(legacy,p)==22){uint64_t size=uint64_t(be32(legacy,p+4))+tail;need(size<=0xffffffffu,"SCP legacy size overflow");putbe32(legacy,p+4,uint32_t(size));}
 need(realProps.count("scp-dram-size")&&realProps.at("scp-dram-size").size()==4,"real SCP DRAM configuration missing/malformed");
 uint32_t helperDram=be32(realProps.at("scp-dram-size"),0);std::string helper="/soc/scp-lk-compat"+real.substr(real.find('@'));int existing=path(b,helper);
 if(existing>=0){auto hp=properties(b,existing);need(!hp.count("phandle")&&!hp.count("linux,phandle"),"helper phandle conflict");need(hp.count("status")&&hp.at("status").empty(),"existing helper status must be present and empty");
  for(const auto& key:{"compatible","reg","reg-names","interrupts","interrupt-names","scp-mem-key"}){need(hp.count(key)==realProps.count(key),"existing helper contract property missing/unexpected");if(realProps.count(key))need(hp.at(key)==realProps.at(key),"existing helper IRQ/register contract mismatch");}
  need(hp.count("scp-dram-size")&&hp.at("scp-dram-size").size()==4,"existing helper DRAM configuration missing/malformed");helperDram=be32(hp.at("scp-dram-size"),0);
  check(fdt_del_node(b.data(),existing),"remove previous helper");}
 need(helperDram&&!(helperDram&65535)&&helperDram<=kMaxImage&&helperDram>=dram,"helper configured DRAM capacity invalid/insufficient");
 set(b,real,"scp-dram-size",u32(dram));realProps["scp-dram-size"]=u32(helperDram);
 int soc=path(b,"/soc");check(soc,"SCP parent");check(fdt_add_subnode(b.data(),soc,helper.substr(5).c_str()),"create SCP helper");
 for(const auto& item:realProps)if(item.first!="phandle"&&item.first!="linux,phandle"&&item.first!="name")set(b,helper,item.first,item.first=="status"?Bytes{}:item.first=="scp-mem-tbl"?legacy:item.second);
 set(b,helper,"status",{});need(fdt_node_offset_by_compatible(b.data(),-1,"mediatek,scp")==path(b,helper),"helper is not LK first-compatible");
 int chosen=path(b,"/chosen");check(chosen,"bt_log requires /chosen node");auto cp=properties(b,chosen);bool btAdded=!cp.count("bt_log");Bytes btOriginal=btAdded?Bytes{}:cp.at("bt_log");
 need(btAdded||btOriginal.size()>=4,"existing /chosen/bt_log must contain at least 4 bytes; got "+std::to_string(btOriginal.size()));
 Bytes btExpected=btAdded?Bytes{0x11,0x10,0,0}:btOriginal;btExpected[0]=0x11;btExpected[1]=uint8_t((btExpected[1]&0x0f)|0x10);bool btChanged=btAdded||btExpected!=btOriginal;set(b,"/chosen","bt_log",btExpected);
 size_t removedMarkers=0;for(const auto& item:cp)if(item.first.rfind("scp-compat-",0)==0){check(fdt_delprop(b.data(),path(b,"/chosen"),item.first.c_str()),"remove diagnostic marker");removedMarkers++;}
 check(fdt_pack(b.data()),"FDT pack");b.resize(fdt_totalsize(b.data()));check(fdt_check_full(b.data(),b.size()),"output FDT");auto rp=properties(b,path(b,real)),hp=properties(b,path(b,helper));need(rp.at("scp-mem-tbl")==full&&hp.at("scp-mem-tbl")==legacy&&hp.at("status").empty()&&rp.at("scp-dram-size")==u32(dram)&&hp.at("scp-dram-size")==u32(helperDram),"SCP output invariant");
 for(const auto& key:{"reg","reg-names","interrupts","interrupt-names","scp-mem-key"})if(rp.count(key))need(hp.at(key)==rp.at(key),"helper IRQ/register clone mismatch");
 auto finalChosen=properties(b,path(b,"/chosen"));need(finalChosen.count("bt_log")&&finalChosen.at("bt_log")==btExpected,"bt_log output length/header/tail invariant");const Bytes& btFinal=finalChosen.at("bt_log");need(btFinal.size()>=4&&btFinal[0]==0x11&&(btFinal[1]&0xf0)==0x10,"bt_log compatibility invariant");std::string btPrefix;const char* hex="0123456789abcdef";for(size_t i=0;i<4;i++){btPrefix+=hex[btFinal[i]>>4];btPrefix+=hex[btFinal[i]&0x0f];}
 report="{\"beforeSize\":"+std::to_string(input.size())+",\"afterSize\":"+std::to_string(b.size())+",\"dsiPath\":"+jquote(newPath)+",\"updatedPathProperties\":"+std::to_string(updated)+",\"realScpPath\":"+jquote(real)+",\"helperPath\":"+jquote(helper)+",\"helperDramSize\":"+std::to_string(helperDram)+",\"realDramSize\":"+std::to_string(dram)+",\"helperStatusLength\":0,\"kernelTablePreserved\":true,\"legacyRows\":"+std::to_string(legacy.size()/12)+",\"foldedTailBytes\":"+std::to_string(tail)+",\"removedDiagnosticMarkers\":"+std::to_string(removedMarkers)+",\"btLogCompatible\":true,\"btLogLength\":"+std::to_string(btFinal.size())+",\"btLogPrefix\":"+jquote(btPrefix)+",\"btLogAdded\":"+boolean(btAdded)+",\"btLogChanged\":"+boolean(btChanged)+",\"fullMetadataRepair\":false}";
 return b;
}
DtbResult sequence(const Bytes& data,uint32_t dram){DtbResult out;size_t p=0;while(p<data.size()){if(data[p]==0){while(p<data.size()&&data[p]==0)p++;if(p==data.size())break;need((p&3)==0,"unaligned concatenated DTB");}need(be32(data,p)==0xd00dfeed,"unknown DTB region content");uint32_t size=be32(data,p+4);need(size>=40,"short DTB");Bytes dt=slice(data,p,size);std::string report;if(!out.reports.empty())out.data.resize(align(out.data.size(),4));append(out.data,patchOne(dt,dram,report));out.reports.push_back(report);p+=size;}need(!out.reports.empty(),"no DTB found");return out;}
}
DtbResult patchDtbRegion(const Bytes& region,uint32_t dram){
 need(!region.empty(),"empty DTB region");if(be32(region,0)!=0xd7b7ab1e)return sequence(region,dram);
 bounds(region,0,32);uint32_t total=be32(region,4),header=be32(region,8),es=be32(region,12),count=be32(region,16),eo=be32(region,20),version=be32(region,28);
 need(total<=region.size()&&header>=32&&header<=total&&es>=32&&count>0&&count<=4096&&version==0,"DT table header");need(eo>=header&&uint64_t(eo)+uint64_t(es)*count<=total,"DT table entries");need(zero(region,total,region.size()),"unknown data after DT table");size_t prefix=eo+size_t(es)*count;DtbResult result;result.data=slice(region,0,prefix);std::vector<std::pair<size_t,size_t>> intervals;
 for(uint32_t i=0;i<count;i++){size_t record=eo+size_t(i)*es;uint32_t size=be32(region,record),off=be32(region,record+4);need(off>=prefix&&uint64_t(off)+size<=total,"DT table payload bounds");intervals.emplace_back(off,off+size);auto entry=sequence(slice(region,off,size),dram);result.data.resize(align(result.data.size(),4));putbe32(result.data,record,uint32_t(entry.data.size()));putbe32(result.data,record+4,uint32_t(result.data.size()));append(result.data,entry.data);result.reports.insert(result.reports.end(),entry.reports.begin(),entry.reports.end());}
 std::sort(intervals.begin(),intervals.end());size_t end=prefix;for(auto interval:intervals){need(interval.first>=end&&zero(region,end,interval.first),"overlapping/opaque DT table gap");end=interval.second;}need(zero(region,end,total),"opaque DT table suffix");putbe32(result.data,4,uint32_t(result.data.size()));return result;
}
}
