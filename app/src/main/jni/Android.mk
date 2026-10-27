MAMETV_JNI_PATH := $(call my-dir)
include $(MAMETV_JNI_PATH)/../../../../.native/mame2003-plus/jni/Android.mk

LOCAL_PATH := $(MAMETV_JNI_PATH)
include $(CLEAR_VARS)
LOCAL_MODULE := mametv
LOCAL_SRC_FILES := bridge.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../../.native/mame2003-plus/src/libretro-common/include
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
LOCAL_REQUIRED_MODULES := retro
LOCAL_LDLIBS := -landroid -llog -ldl
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
