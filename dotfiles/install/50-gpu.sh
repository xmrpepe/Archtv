# Step: guest side of the GPU path.
# The Android side (virgl_test_server + Mesa wrapper ICD → Mali libvulkan.so)
# ships inside the APK. Here we install the guest ICD manifest that points
# Vulkan at Mesa's Venus driver talking to that server over /tmp/.virgl_test.
progress 10 "Installing the ArchDock Vulkan ICD manifest"
venus=$(ls /usr/lib/libvulkan_virtio.so 2>/dev/null || true)
[ -n "$venus" ] || pac -S vulkan-virtio
mkdir -p /usr/share/vulkan/icd.d
cat >/usr/share/vulkan/icd.d/archdock_wrapper_icd.aarch64.json <<JSON
{
    "file_format_version": "1.0.1",
    "ICD": {
        "library_path": "/usr/lib/libvulkan_virtio.so",
        "api_version": "1.3.0"
    }
}
JSON

progress 40 "Writing the session GPU environment"
# Login shells pick up the same variables the desktop gets (useful for SSH-style use via the phone terminal).
cat >/etc/profile.d/archdock-gpu.sh <<'SH'
# ArchDock: when the desktop is running, reuse its GPU environment.
if [ -f /tmp/archdock/session.env ]; then
  . /tmp/archdock/session.env
  unset DISPLAY
  [ -S /tmp/.X11-unix/X0 ] && export DISPLAY=:0
fi
SH

progress 70 "Mesa shader cache"
mkdir -p /home/user/.cache/mesa_shader_cache && chown -R user:user /home/user/.cache
progress 100 "GPU configured (llvmpipe fallback is automatic)"
