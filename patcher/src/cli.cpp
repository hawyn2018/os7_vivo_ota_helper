// SPDX-License-Identifier: GPL-3.0-only
#include "vendorpatch.hpp"
#include <iostream>
int main(int argc,char**argv){if(argc!=5){std::cerr<<"usage: vendorpatch_cli INPUT OUTPUT SCP_BACKUP CAPACITY_BYTES\n";return 2;}try{auto json=vendorpatch::patchFiles(argv[1],argv[2],argv[3],std::stoull(argv[4]));std::cout<<json<<'\n';return json.find("\"ok\":true")!=std::string::npos?0:1;}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 2;}}
