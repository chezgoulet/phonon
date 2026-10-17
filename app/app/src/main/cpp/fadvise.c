/*
 * Debug-only test instrument. android.system.Os does not expose
 * posix_fadvise(2) on any released SDK (it exposes posix_fallocate only), so
 * the app reaches libc directly for the one syscall wrapper it needs.
 *
 * Returns 0 on success, a positive errno on failure, or -1 if the path could
 * not be copied out of the JVM.
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_org_phononai_app_DebugEvict_nativeFadviseDontNeed(
        JNIEnv *env, jclass clazz, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return -1;

    int rc = 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        rc = errno;
    } else {
        int fr = posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
        if (fr != 0) rc = fr;
        close(fd);
    }

    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return rc;
}
