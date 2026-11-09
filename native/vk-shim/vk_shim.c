// libvulkan_archdock.so – a minimal single-ICD Vulkan "loader" for the
// Android-side virgl_test_server (bionic).
//
// virglrenderer's Venus backend links against libvulkan. On Android that is
// the platform loader, which only ever loads the vendor HAL. This shim
// instead loads Mesa's "wrapper" ICD (libvulkan_wrapper.so, next to this
// library), which forwards to /system/lib64/libvulkan.so (the Mali driver)
// while emulating extensions Venus needs. ARCHDOCK_VK_DRIVER=system (or a
// missing wrapper) uses the platform loader directly.
//
// SPDX-License-Identifier: GPL-3.0-or-later
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define TAG "ArchDock/vk-shim"
#define EXPORT __attribute__((visibility("default")))

typedef VkResult (VKAPI_PTR *PFN_Negotiate)(uint32_t *);

static PFN_vkGetInstanceProcAddr g_gipa;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static void self_dir(char *out, size_t len) {
    Dl_info info;
    out[0] = 0;
    if (dladdr((void *) &self_dir, &info) && info.dli_fname) {
        snprintf(out, len, "%s", info.dli_fname);
        char *slash = strrchr(out, '/');
        if (slash) *slash = 0;
    }
}

static void init(void) {
    const char *mode = getenv("ARCHDOCK_VK_DRIVER");
    if (!mode || strcmp(mode, "system") != 0) {
        char dir[512], path[600];
        self_dir(dir, sizeof dir);
        snprintf(path, sizeof path, "%s/libvulkan_wrapper.so", dir);
        void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (h) {
            PFN_Negotiate negotiate = (PFN_Negotiate) dlsym(h, "vk_icdNegotiateLoaderICDInterfaceVersion");
            if (negotiate) { uint32_t v = 5; negotiate(&v); }
            g_gipa = (PFN_vkGetInstanceProcAddr) dlsym(h, "vk_icdGetInstanceProcAddr");
            if (g_gipa) __android_log_print(ANDROID_LOG_INFO, TAG, "using Mesa wrapper ICD %s", path);
        } else {
            __android_log_print(ANDROID_LOG_WARN, TAG, "wrapper not loadable (%s), using system Vulkan", dlerror());
        }
    }
    if (!g_gipa) {
        void *h = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (h) g_gipa = (PFN_vkGetInstanceProcAddr) dlsym(h, "vkGetInstanceProcAddr");
        __android_log_print(ANDROID_LOG_INFO, TAG, "using system libvulkan.so: %s", g_gipa ? "ok" : "FAILED");
    }
}

static PFN_vkVoidFunction gipa(VkInstance instance, const char *name) {
    pthread_once(&g_once, init);
    return g_gipa ? g_gipa(instance, name) : NULL;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char *name) {
    if (instance == VK_NULL_HANDLE && name && strcmp(name, "vkGetInstanceProcAddr") == 0)
        return (PFN_vkVoidFunction) vkGetInstanceProcAddr;
    return gipa(instance, name);
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char *name) {
    // Only reached if a caller links vkGetDeviceProcAddr directly; Venus
    // fetches it through vkGetInstanceProcAddr with a real instance.
    (void) device; (void) name;
    return NULL;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *a, VkInstance *out) {
    PFN_vkCreateInstance f = (PFN_vkCreateInstance) gipa(VK_NULL_HANDLE, "vkCreateInstance");
    return f ? f(ci, a, out) : VK_ERROR_INITIALIZATION_FAILED;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceVersion(uint32_t *v) {
    PFN_vkEnumerateInstanceVersion f = (PFN_vkEnumerateInstanceVersion) gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (!f) { *v = VK_API_VERSION_1_0; return VK_SUCCESS; }
    return f(v);
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceExtensionProperties(const char *layer, uint32_t *n, VkExtensionProperties *p) {
    PFN_vkEnumerateInstanceExtensionProperties f =
        (PFN_vkEnumerateInstanceExtensionProperties) gipa(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties");
    return f ? f(layer, n, p) : VK_ERROR_INITIALIZATION_FAILED;
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceLayerProperties(uint32_t *n, VkLayerProperties *p) {
    (void) p;
    *n = 0; // no layers without a real loader
    return VK_SUCCESS;
}
