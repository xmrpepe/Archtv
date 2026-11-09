// Thread pinning and small perf helpers for Tensor G4.
#include <android/log.h>
#include <dirent.h>
#include <jni.h>
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <cstdlib>

#define TAG "ArchDock/Perf"

extern "C" {

/**
 * Pins every thread of [pid] to the CPUs in [mask]. Works for our own child
 * processes (same uid). Android's cpuset for the app's cgroup still applies:
 * if the app is backgrounded the kernel may clamp to the little cores.
 * Returns the number of threads pinned.
 */
JNIEXPORT jint JNICALL
Java_dev_archdock_perf_NativePerf_pinProcess(JNIEnv *, jclass, jint pid, jlong mask) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int cpu = 0; cpu < 64; cpu++)
        if (mask & (1LL << cpu)) CPU_SET(cpu, &set);

    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *dir = opendir(path);
    if (!dir) return -1;
    int pinned = 0;
    while (struct dirent *e = readdir(dir)) {
        if (e->d_name[0] == '.') continue;
        pid_t tid = static_cast<pid_t>(atoi(e->d_name));
        if (tid > 0 && sched_setaffinity(tid, sizeof(set), &set) == 0) pinned++;
    }
    closedir(dir);
    __android_log_print(ANDROID_LOG_INFO, TAG, "pinned %d threads of pid %d to mask 0x%llx", pinned, pid,
                        static_cast<unsigned long long>(mask));
    return pinned;
}

JNIEXPORT jint JNICALL
Java_dev_archdock_perf_NativePerf_currentCpu(JNIEnv *, jclass) {
    return sched_getcpu();
}

}  // extern "C"
