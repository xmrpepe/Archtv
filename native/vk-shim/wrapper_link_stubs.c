// Link-time stubs for the Mesa wrapper as ArchDock uses it (Android side of
// the Venus server). Window-system presentation happens in the Linux guest,
// and Adreno driver injection (adrenotools) is irrelevant on Mali, so these
// paths are never taken – the stubs only satisfy the linker.
// SPDX-License-Identifier: GPL-3.0-or-later
#include <stddef.h>

struct wsi_device;
struct VkAllocationCallbacks;
typedef int VkResult;

VkResult wsi_x11_init_wsi(struct wsi_device *wsi, const struct VkAllocationCallbacks *alloc, const void *dri_options) {
    (void) wsi; (void) alloc; (void) dri_options;
    return 0; /* VK_SUCCESS: X11 surfaces simply aren't offered */
}

void wsi_x11_finish_wsi(struct wsi_device *wsi, const struct VkAllocationCallbacks *alloc) {
    (void) wsi; (void) alloc;
}

/* Returning NULL makes the wrapper fall back to /system/lib64/libvulkan.so. */
void *adrenotools_open_libvulkan(int dlopenMode, int featureFlags, const char *tmpLibDir, const char *hookLibDir,
                                 const char *customDriverDir, const char *customDriverName,
                                 const char *fileRedirectDir, void **userMappingHandle) {
    (void) dlopenMode; (void) featureFlags; (void) tmpLibDir; (void) hookLibDir; (void) customDriverDir;
    (void) customDriverName; (void) fileRedirectDir; (void) userMappingHandle;
    return NULL;
}
