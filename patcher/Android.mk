LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := vendorpatch
LOCAL_CPP_FEATURES := exceptions rtti
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_CFLAGS := -DZ_SOLO
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include $(LOCAL_PATH)/src $(LOCAL_PATH)/third_party/libfdt $(LOCAL_PATH)/third_party/lz4 $(LOCAL_PATH)/third_party/zlib
LOCAL_SRC_FILES := src/ramdisk.cpp src/dtb.cpp src/vendor.cpp src/file_api.cpp src/jni.cpp \
 third_party/lz4/lz4.c third_party/lz4/lz4hc.c \
 third_party/zlib/adler32.c third_party/zlib/crc32.c third_party/zlib/deflate.c third_party/zlib/inflate.c third_party/zlib/inffast.c third_party/zlib/inftrees.c third_party/zlib/trees.c third_party/zlib/zutil.c \
 third_party/libfdt/fdt.c third_party/libfdt/fdt_ro.c third_party/libfdt/fdt_wip.c third_party/libfdt/fdt_sw.c third_party/libfdt/fdt_rw.c third_party/libfdt/fdt_strerror.c third_party/libfdt/fdt_empty_tree.c third_party/libfdt/fdt_addresses.c third_party/libfdt/fdt_overlay.c third_party/libfdt/fdt_check.c
include $(BUILD_SHARED_LIBRARY)
