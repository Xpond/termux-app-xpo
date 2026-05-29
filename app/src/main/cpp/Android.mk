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

# Build font executable
include $(CLEAR_VARS)
LOCAL_MODULE := font
LOCAL_SRC_FILES := font.c
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

# Build sysmon executable
include $(CLEAR_VARS)
LOCAL_MODULE := sysmon
LOCAL_SRC_FILES := sysmon.c
include $(BUILD_EXECUTABLE)

# Build debug_proc executable
include $(CLEAR_VARS)
LOCAL_MODULE := debug_proc
LOCAL_SRC_FILES := debug_proc.c
include $(BUILD_EXECUTABLE)


