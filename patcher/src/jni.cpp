// SPDX-License-Identifier: GPL-3.0-only
#include "vendorpatch.hpp"
#include <jni.h>
extern "C" JNIEXPORT jstring JNICALL
Java_net_hawyn_vivootahelper_core_NativeBridge_patchVendor(JNIEnv* env,jobject,
 jstring input,jstring output,jstring scp,jlong capacity){
 if(!input||!output||!scp||capacity<=0)return env->NewStringUTF("{\"ok\":false,\"schema\":2,\"error\":\"invalid JNI arguments\"}");
 const char* a=env->GetStringUTFChars(input,nullptr);if(!a)return nullptr;
 const char* b=env->GetStringUTFChars(output,nullptr);if(!b){env->ReleaseStringUTFChars(input,a);return nullptr;}
 const char* c=env->GetStringUTFChars(scp,nullptr);if(!c){env->ReleaseStringUTFChars(input,a);env->ReleaseStringUTFChars(output,b);return nullptr;}
 try {
  std::string result=vendorpatch::patchFiles(a,b,c,uint64_t(capacity));
  env->ReleaseStringUTFChars(input,a);env->ReleaseStringUTFChars(output,b);env->ReleaseStringUTFChars(scp,c);
  return env->NewStringUTF(result.c_str());
 } catch(...) {
  env->ReleaseStringUTFChars(input,a);env->ReleaseStringUTFChars(output,b);env->ReleaseStringUTFChars(scp,c);
  return env->NewStringUTF("{\"ok\":false,\"schema\":2,\"error\":\"native allocation or unexpected failure\"}");
 }
}
