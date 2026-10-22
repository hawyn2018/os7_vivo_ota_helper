// SPDX-License-Identifier: MIT
#include "internal.hpp"
namespace vendorpatch {
namespace {
struct Entry { uint32_t offset,size;Bytes record; };
struct Layout { uint32_t version,page,rs,ds,ts=0,bs=0,es=0;size_t ro,dt,to,bo,end;std::vector<Entry> entries;Bytes config; };
Layout layout(const Bytes& b){
 need(magic(b,0,"VNDRBOOT",8),"not vendor_boot");bounds(b,0,2112);Layout l{};l.version=le32(b,8);l.page=le32(b,12);l.rs=le32(b,24);l.ds=le32(b,2100);uint32_t hs=le32(b,2096);
 need((l.version==3||l.version==4)&&l.page>=2048&&l.page<=65536&&!(l.page&(l.page-1))&&hs>=(l.version==4?2128u:2112u)&&hs<=l.page,"vendor_boot header format");
 bounds(b,0,l.page);l.ro=align(hs,l.page);need(l.rs>0&&l.ds>0,"empty vendor ramdisk/DTB");bounds(b,l.ro,l.rs);l.dt=add(l.ro,align(l.rs,l.page));bounds(b,l.dt,l.ds);need(zero(b,l.ro+l.rs,l.dt),"opaque ramdisk padding");l.to=add(l.dt,align(l.ds,l.page));need(l.to<=b.size()&&zero(b,l.dt+l.ds,l.to),"opaque DTB padding");uint32_t count=0;
 if(l.version==4){bounds(b,0,2128);l.ts=le32(b,2112);count=le32(b,2116);l.es=le32(b,2120);l.bs=le32(b,2124);need(count<=4096&&(!count?!l.ts:(l.es>=108&&uint64_t(l.es)*count==l.ts)),"ramdisk table shape");bounds(b,l.to,l.ts);}
 l.bo=add(l.to,align(l.ts,l.page));need(l.bo<=b.size()&&zero(b,l.to+l.ts,l.bo),"opaque ramdisk-table padding");bounds(b,l.bo,l.bs);l.config=slice(b,l.bo,l.bs);l.end=align(l.bo+l.bs,l.page);need(l.end<=b.size()&&zero(b,l.bo+l.bs,l.end),"opaque bootconfig padding");
 if(!count)l.entries.push_back({0,l.rs,{}});else for(uint32_t i=0;i<count;i++){Bytes rec=slice(b,l.to+size_t(i)*l.es,l.es);uint32_t sz=le32(rec,0),off=le32(rec,4);need(sz&&uint64_t(off)+sz<=l.rs,"fragment bounds");l.entries.push_back({off,sz,std::move(rec)});}
 auto sorted=l.entries;std::sort(sorted.begin(),sorted.end(),[](const Entry&a,const Entry&c){return a.offset<c.offset;});size_t end=0;for(const auto& e:sorted){need(e.offset>=end&&zero(b,l.ro+end,l.ro+e.offset),"overlapping/opaque fragment gaps");end=e.offset+e.size;}need(zero(b,l.ro+end,l.ro+l.rs),"opaque fragment suffix");return l;
}
struct Avb { bool present=false;size_t original=0,off=0,size=0;Bytes blob,footer; };
Avb avb(const Bytes& b,size_t body){Avb a;if(b.size()>=64&&magic(b,b.size()-64,"AVBf",4)){
 a.present=true;a.footer=slice(b,b.size()-64,64);need(be32(a.footer,4)==1,"AVB footer major version");a.original=size_t(be64(a.footer,12));a.off=size_t(be64(a.footer,20));a.size=size_t(be64(a.footer,28));need(a.original>=body&&a.original<=a.off&&a.off<=b.size()-64&&a.size<=b.size()-64-a.off,"AVB footer bounds");need(zero(b,body,a.off)&&zero(b,a.off+a.size,b.size()-64),"opaque AVB gap/suffix");a.blob=slice(b,a.off,a.size);need(a.blob.size()>=256&&magic(a.blob,0,"AVB0",4),"AVB metadata header");uint64_t auth=be64(a.blob,12),aux=be64(a.blob,20);need(auth<=a.blob.size()-256&&aux<=a.blob.size()-256-auth,"AVB block bounds");
 }else need(zero(b,body,b.size()),"unknown vendor_boot trailer");return a;
}
void validateDescriptors(const Avb& avb,size_t imageSize){if(!avb.present)return;uint64_t auth=be64(avb.blob,12),off=be64(avb.blob,96),len=be64(avb.blob,104),aux=be64(avb.blob,20);need(off<=aux&&len<=aux-off,"AVB descriptor bounds");size_t p=size_t(256+auth+off),end=p+size_t(len);
 while(p<end){need(end-p>=16,"short AVB descriptor header");bounds(avb.blob,p,16);uint64_t tag=be64(avb.blob,p),n=be64(avb.blob,p+8);need(n<=end-p-16&&!(n&7),"AVB descriptor length");size_t size=size_t(n)+16;if(tag==2){need(size>=132,"short AVB hash descriptor");uint64_t bytes=be64(avb.blob,p+16);uint32_t pn=be32(avb.blob,p+56),sn=be32(avb.blob,p+60),dn=be32(avb.blob,p+64);need(uint64_t(pn)+sn+dn<=size-132&&bytes<=imageSize,"AVB hash descriptor data bounds");}p+=size;
 }need(p==end,"AVB descriptor traversal");
}
}
uint32_t scpDramSize(const Bytes& scp){
 need(scp.size()>=512&&scp.size()<=kMaxImage,"SCP backup size");size_t count=0;uint32_t found=0;const std::string expected="tinysys-scp-RV55_A_dram";
 for(size_t p=0;p+512<=scp.size();p+=4)if(le32(scp,p)==0x58881688){const char* q=reinterpret_cast<const char*>(scp.data()+p+8);size_t n=0;while(n<32&&q[n])n++;if(n!=expected.size()||memcmp(q,expected.data(),n))continue;need(n<32&&zero(scp,p+8+n,p+40),"SCP DRAM name padding");need(le32(scp,p+0x30)==0x58891689&&le32(scp,p+0x34)==512,"SCP DRAM MTK header");uint32_t sz=le32(scp,p+4),a=le32(scp,p+0x44);need(sz>0&&sz<=256*1024*1024&&uint64_t(p)+512+sz<=scp.size(),"SCP DRAM payload bounds");need(a&&!(a&(a-1))&&a<=65536,"SCP DRAM alignment");need(le32(scp,p+0x40)==0,"SCP DRAM high-size unsupported");size_t rounded=align(sz,65536);need(rounded<=0xffffffffu,"SCP DRAM overflow");found=uint32_t(rounded);count++;}
 need(count==1,"SCP DRAM header missing or ambiguous");return found;
}
PatchResult patchVendorBoot(const Bytes& input,const PatchOptions& o){
 need(input.size()<=kMaxImage&&o.partitionCapacity>=input.size()&&o.partitionCapacity<=kMaxImage,"input/partition capacity bounds");need(o.scpDramSize&&!(o.scpDramSize&65535),"SCP DRAM must be 64K aligned");Layout old=layout(input);Avb metadata=avb(input,old.end);validateDescriptors(metadata,input.size());
 std::vector<Bytes> fragments;std::vector<std::string> reports;for(size_t i=0;i<old.entries.size();i++){const auto& e=old.entries[i];auto r=scrubFragment(slice(input,old.ro+e.offset,e.size),i);reports.push_back(r.json);fragments.push_back(std::move(r.data));}
 Bytes ramdisk,table;std::vector<size_t> indices(old.entries.size());for(size_t i=0;i<indices.size();i++)indices[i]=i;std::sort(indices.begin(),indices.end(),[&](size_t a,size_t b){return old.entries[a].offset<old.entries[b].offset;});std::vector<uint32_t> offsets(indices.size());for(size_t i:indices){need(ramdisk.size()<=0xffffffffu,"ramdisk offset overflow");offsets[i]=uint32_t(ramdisk.size());append(ramdisk,fragments[i]);}
 for(size_t i=0;i<old.entries.size();i++)if(!old.entries[i].record.empty()){Bytes rec=old.entries[i].record;putle32(rec,0,uint32_t(fragments[i].size()));putle32(rec,4,offsets[i]);append(table,rec);}
 auto dtbs=patchDtbRegion(slice(input,old.dt,old.ds),o.scpDramSize);need(ramdisk.size()<=0xffffffffu&&dtbs.data.size()<=0xffffffffu,"vendor size fields overflow");
 size_t dt=add(old.ro,align(ramdisk.size(),old.page)),to=add(dt,align(dtbs.data.size(),old.page)),bo=add(to,align(table.size(),old.page)),body=align(add(bo,old.config.size()),old.page);size_t meta=metadata.present?std::max(body,metadata.off):body;
 need(body<=o.partitionCapacity&&(!metadata.present||meta+metadata.size<=o.partitionCapacity-64),"patched image exceeds inactive partition capacity");Bytes output(size_t(o.partitionCapacity),0);store(output,0,slice(input,0,old.ro));putle32(output,24,uint32_t(ramdisk.size()));putle32(output,2100,uint32_t(dtbs.data.size()));store(output,old.ro,ramdisk);store(output,dt,dtbs.data);store(output,to,table);store(output,bo,old.config);
 if(metadata.present){store(output,meta,metadata.blob);Bytes footer=metadata.footer;putbe64(footer,12,std::max(body,metadata.original));putbe64(footer,20,meta);store(output,output.size()-64,footer);}
 Layout verified=layout(output);Avb after=avb(output,verified.end);validateDescriptors(after,output.size());need(verified.entries.size()==old.entries.size()&&verified.config==old.config,"output structural invariants");for(size_t i=0;i<verified.entries.size();i++){need(slice(output,verified.ro+verified.entries[i].offset,verified.entries[i].size)==fragments[i],"fragment output mismatch");auto r=scrubFragment(fragments[i],i);need(!r.changed&&r.data==fragments[i],"ramdisk not idempotent");}
 bool changed=output!=input;std::string details="\"headerVersion\":"+std::to_string(old.version)+",\"fragmentCount\":"+std::to_string(fragments.size())+",\"fragments\":"+array(reports)+",\"dtbs\":"+array(dtbs.reports)+",\"avb\":{\"present\":"+boolean(metadata.present)+",\"metadataStatus\":"+jquote(metadata.present?"preserved":"absent")+",\"imageStatus\":"+jquote(changed?"modified":"unchanged")+",\"verificationStatus\":"+jquote(metadata.present?"unverified":"not_present")+",\"resigned\":false},\"fullMetadataRepair\":false,\"warnings\":[\"Legacy LK keeps enlarged ID22 and does not program new SCP IDs23/24/25; low-power network offload remains unverified.\",\"Original AVB metadata is retained when present; digest/signature verification is not performed.\"]";
 return {std::move(output),std::move(details),changed};
}
}
