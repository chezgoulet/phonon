/*
 * Phonon exec spike probe.
 *
 * Packaged as libphonon_probe.so in jniLibs so Android extracts it into
 * applicationInfo.nativeLibraryDir. The app execs it directly. Its only job is
 * to prove the kernel lets an untrusted app execute that file and to report
 * which artifact actually ran.
 */
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv) {
#if defined(__x86_64__)
    const char *abi = "x86_64";
#elif defined(__aarch64__)
    const char *abi = "arm64-v8a";
#elif defined(__arm__)
    const char *abi = "armeabi-v7a";
#else
    const char *abi = "unknown";
#endif

    printf("PHONON_PROBE_OK\n");
    printf("PHONON_PROBE_ABI=%s\n", abi);
    printf("PHONON_PROBE_PID=%d\n", (int)getpid());
    fflush(stdout);

    fprintf(stderr, "PHONON_PROBE_ARGV0=%s\n",
            (argc > 0 && argv[0] != NULL) ? argv[0] : "(none)");
    fflush(stderr);
    return 0;
}
