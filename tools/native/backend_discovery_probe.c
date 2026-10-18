// Loads ggml's dynamic backends the way llama.cpp's llama_backend_init does,
// then prints every backend it found. Run from the build's bin/ directory so
// /proc/self/exe resolves to the same directory the variant .so files live in -
// exactly the situation on Android, where the executable and the backends are
// both extracted into nativeLibraryDir.
#include <stdio.h>
#include "ggml-backend.h"

int main(void) {
    ggml_backend_load_all();
    size_t n = ggml_backend_reg_count();
    printf("ggml_backend_reg_count = %zu\n", n);
    for (size_t i = 0; i < n; i++) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        printf("  backend[%zu] = %s\n", i, ggml_backend_reg_name(reg));
    }
    return n > 0 ? 0 : 1;
}
