/* Ordinary C ABI fixture shared with PHP/TrueAsync; no managed/NFI APIs. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#define EXPORT __declspec(dllexport)
#define NOINLINE __declspec(noinline)
static volatile LONG active, finished;
#define INC(p) InterlockedIncrement(p)
#define DEC(p) InterlockedDecrement(p)
#define OWNER GetCurrentThreadId()
#else
#include <pthread.h>
#include <stdatomic.h>
#define EXPORT __attribute__((visibility("default")))
#define NOINLINE __attribute__((noinline))
static _Atomic int active, finished;
#define INC(p) atomic_fetch_add(p, 1)
#define DEC(p) atomic_fetch_sub(p, 1)
#define OWNER ((uint64_t) (uintptr_t) pthread_self())
#endif
typedef int64_t (*Transform)(int64_t, const char *);
static NOINLINE int64_t walk(Transform cb, int64_t seed, int depth, const char *text, uint64_t owner) {
    volatile int64_t locals[64];
    char local_text[128];
    if (strlen(text) >= sizeof(local_text)) abort();
    strcpy(local_text, text);
    for (int i = 0; i < 64; i++) locals[i] = seed + depth + i;
    int64_t result = 0;
    if (depth) result = walk(cb, seed, depth - 1, text, owner) + 1;
    else for (int i = 0; i < 3; i++) result += cb(seed + i, local_text);
    for (int i = 0; i < 64; i++) if (locals[i] != seed + depth + i) abort();
    if (strcmp(local_text, text) || owner != OWNER) abort();
    return result;
}
EXPORT int64_t gp_test_walk(Transform callback, int64_t seed, int depth, const char *text) {
    if (depth < 0 || depth > 128) return -1;
    INC(&active);
    int64_t result = walk(callback, seed, depth, text, OWNER);
    DEC(&active); INC(&finished);
    return result;
}
EXPORT int gp_test_active(void) { return active; }
EXPORT int gp_test_finished(void) { return finished; }
EXPORT int64_t gp_test_thread(void) { return (int64_t) OWNER; }
EXPORT double gp_test_mixed(double (*cb)(int8_t, uint8_t, int16_t, uint16_t, int32_t, uint32_t,
        int64_t, uint64_t, float, double, const char *, double), double value) {
    return cb(-120, 250, -32000, 65000, -2000000000, 4000000000U,
            INT64_C(-7000000000000), UINT64_C(14000000000000), 1.25f, 2.5, "C-local", value);
}
EXPORT float gp_test_float(float (*cb)(float), float value) { return cb(value) + 0.5f; }
EXPORT int8_t gp_test_narrow(int8_t (*cb)(uint8_t), uint8_t value) { return cb(value); }
EXPORT void gp_test_void(void (*cb)(void)) { cb(); }
EXPORT double gp_test_pair(int (*first)(int), double (*second)(double)) {
    return first(21) + second(1.25);
}
typedef struct { int64_t (*callback)(int64_t); int64_t result; } Foreign;
#ifdef _WIN32
static DWORD WINAPI foreign(void *p) { Foreign *f = p; f->result = f->callback(42); return 0; }
#else
static void *foreign(void *p) { Foreign *f = p; f->result = f->callback(42); return NULL; }
#endif
EXPORT int64_t gp_test_foreign(int64_t (*callback)(int64_t)) {
    Foreign f = {callback, 0};
#ifdef _WIN32
    HANDLE thread = CreateThread(NULL, 0, foreign, &f, 0, NULL);
    if (!thread) abort();
    WaitForSingleObject(thread, INFINITE); CloseHandle(thread);
#else
    pthread_t thread;
    if (pthread_create(&thread, NULL, foreign, &f) || pthread_join(thread, NULL)) abort();
#endif
    return f.result;
}
