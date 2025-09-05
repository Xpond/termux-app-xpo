LOCAL_PATH:= $(call my-dir)

# Build libxport-bootstrap (existing)
include $(CLEAR_VARS)
LOCAL_MODULE := libxport-bootstrap
LOCAL_SRC_FILES := xport-bootstrap.c
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)


