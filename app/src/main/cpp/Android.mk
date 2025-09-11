LOCAL_PATH:= $(call my-dir)

# Build libxport-bootstrap (existing)
include $(CLEAR_VARS)
LOCAL_MODULE := libxport-bootstrap
LOCAL_SRC_FILES := xport-bootstrap.c
LOCAL_LDLIBS := -llog -landroid
include $(BUILD_SHARED_LIBRARY)

# Build fontsize executable
include $(CLEAR_VARS)
LOCAL_MODULE := fontsize
LOCAL_SRC_FILES := fontsize.c
include $(BUILD_EXECUTABLE)

# Build textcolor executable
include $(CLEAR_VARS)
LOCAL_MODULE := textcolor
LOCAL_SRC_FILES := textcolor.c
include $(BUILD_EXECUTABLE)

# Build backgroundcolor executable
include $(CLEAR_VARS)
LOCAL_MODULE := backgroundcolor
LOCAL_SRC_FILES := backgroundcolor.c
include $(BUILD_EXECUTABLE)


