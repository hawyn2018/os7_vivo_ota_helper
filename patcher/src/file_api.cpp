// SPDX-License-Identifier: GPL-3.0-only
#include "internal.hpp"
#include <filesystem>
#include <fstream>
#include <cerrno>
#include <cstring>
#include <cstdio>
#include <fcntl.h>
#include <sys/stat.h>
#ifdef _WIN32
#include <io.h>
#include <process.h>
#else
#include <unistd.h>
#if defined(__linux__)
#include <sys/syscall.h>
#include <linux/fs.h>
#endif
#endif
namespace vendorpatch {
namespace fs=std::filesystem;
namespace {
Bytes readFile(const fs::path& path){need(fs::is_regular_file(path),"input must be an ordinary backup file");uint64_t n=fs::file_size(path);need(n>0&&n<=kMaxImage,"backup file size limit");std::ifstream f(path,std::ios::binary);need(bool(f),"open backup failed");Bytes b(size_t(n),0);f.read(reinterpret_cast<char*>(b.data()),std::streamsize(b.size()));need(bool(f)&&f.peek()==EOF,"backup read changed/truncated");return b;}
void outputFile(const fs::path& path,const Bytes& data){
 need(!fs::exists(path),"output already exists");need(fs::is_directory(path.parent_path()),"output parent missing");
#ifdef _WIN32
 auto tmp=path;tmp+=L".partial."+std::to_wstring(_getpid());int fd=_wopen(tmp.c_str(),_O_WRONLY|_O_CREAT|_O_EXCL|_O_BINARY,_S_IREAD|_S_IWRITE);
#else
 auto tmp=path;tmp+=".partial."+std::to_string(getpid());int fd=open(tmp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_CLOEXEC,0600);
#endif
 need(fd>=0,"create independent output temporary file failed");bool openFd=true;
 try {size_t p=0;while(p<data.size()){size_t n=std::min<size_t>(1024*1024,data.size()-p);
#ifdef _WIN32
  int wrote=_write(fd,data.data()+p,unsigned(n));
#else
  ssize_t wrote=write(fd,data.data()+p,n);
#endif
  if(wrote<0&&errno==EINTR)continue;need(wrote>0,"write output failed");p+=size_t(wrote);}
#ifdef _WIN32
  need(_commit(fd)==0,"flush output failed");_close(fd);openFd=false;
#else
  need(fsync(fd)==0,"flush output failed");close(fd);openFd=false;
#endif
  need(fs::is_regular_file(tmp)&&fs::file_size(tmp)==data.size(),"output temporary length mismatch");
#ifdef _WIN32
  need(!fs::exists(path),"output appeared during patch");fs::rename(tmp,path);
#else
#if defined(__linux__) && defined(SYS_renameat2)
  if(syscall(SYS_renameat2,AT_FDCWD,tmp.c_str(),AT_FDCWD,path.c_str(),RENAME_NOREPLACE)!=0){
   const int commitErrno=errno;
   throw std::runtime_error("atomic no-replace output commit failed: renameat2 errno="+std::to_string(commitErrno)+" ("+std::strerror(commitErrno)+")");
  }
#else
  throw std::runtime_error("atomic no-replace output commit unavailable: renameat2 errno="+std::to_string(ENOSYS)+" ("+std::strerror(ENOSYS)+")");
#endif
#endif
 }catch(...){if(openFd){
#ifdef _WIN32
 _close(fd);
#else
 close(fd);
#endif
 }std::error_code ignored;fs::remove(tmp,ignored);throw;}
}
}
std::string patchFiles(const std::string& input,const std::string& output,const std::string& scp,uint64_t capacity){
 try {fs::path in=fs::u8path(input),out=fs::u8path(output),firmware=fs::u8path(scp);need(fs::absolute(in).lexically_normal()!=fs::absolute(out).lexically_normal(),"input/output must differ");need(fs::absolute(firmware).lexically_normal()!=fs::absolute(out).lexically_normal(),"SCP/output must differ");need(!fs::exists(out),"output already exists");Bytes scpBytes=readFile(firmware);uint32_t dram=scpDramSize(scpBytes);scpBytes.clear();scpBytes.shrink_to_fit();Bytes original=readFile(in);auto patched=patchVendorBoot(original,{dram,capacity});
 std::string report="{\"ok\":true,\"schema\":2,\"structureValidated\":true,\"outputPath\":"+jquote(output)+",\"outputSize\":"+std::to_string(patched.image.size())+",\"scpDramSize\":"+std::to_string(dram)+",\"changed\":"+boolean(patched.changed)+","+patched.detailsJson+"}";
 outputFile(out,patched.image);return report;
 }catch(const std::exception& e){return "{\"ok\":false,\"schema\":2,\"error\":"+jquote(e.what())+"}";}
}
}
