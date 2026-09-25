/* Feasibility probe: only C frames live on these stacks. Never enter NFI from them. */
#include <stdint.h>
#include <stdlib.h>
#ifdef _WIN32
#include <windows.h>
#define EXPORT __declspec(dllexport)
#define NOINLINE __declspec(noinline)
#else
#include <pthread.h>
#include <ucontext.h>
#define EXPORT __attribute__((visibility("default")))
#define NOINLINE __attribute__((noinline))
#endif

typedef struct Probe {
    int event;
    int depth;
    int64_t seed;
    int64_t value;
    int64_t reply;
#ifdef _WIN32
    DWORD owner;
    void *fiber;
    void *parent;
#else
    pthread_t owner;
    ucontext_t fiber;
    ucontext_t parent;
    void *stack;
#endif
} Probe;

static int is_owner(Probe *probe) {
#ifdef _WIN32
    return probe->owner == GetCurrentThreadId();
#else
    return pthread_equal(probe->owner, pthread_self());
#endif
}

static void yield_to_bridge(Probe *probe) {
#ifdef _WIN32
    SwitchToFiber(probe->parent);
#else
    if (swapcontext(&probe->fiber, &probe->parent) != 0) abort();
#endif
}

/* This is a native callback trampoline, not a managed/NFI closure. */
static int64_t callback(Probe *probe, int64_t value) {
    probe->value = value;
    probe->event = 1;
    yield_to_bridge(probe);
    return probe->reply;
}

static NOINLINE int64_t native_frames(Probe *probe, int depth,
                                     int64_t (*invoke)(Probe *, int64_t)) {
    volatile int64_t local[16];
    for (int i = 0; i < 16; i++) local[i] = probe->seed + depth + i;
    int64_t result = 0;
    if (depth) result = native_frames(probe, depth - 1, invoke) + 1;
    else for (int i = 0; i < 3; i++) result += invoke(probe, local[0] + i);
    for (int i = 0; i < 16; i++) if (local[i] != probe->seed + depth + i) abort();
    if (!is_owner(probe)) abort();
    return result;
}

static void run(Probe *probe) {
    probe->value = native_frames(probe, probe->depth, callback);
    probe->event = 2;
    yield_to_bridge(probe);
    abort();
}

#ifdef _WIN32
static VOID WINAPI entry(void *argument) { run(argument); }
#else
static void entry(unsigned int low, unsigned int high) {
    run((Probe *) (uintptr_t) ((uint64_t) low | ((uint64_t) high << 32)));
}
#endif

EXPORT int64_t gp_stack_probe_create(int64_t seed, int depth) {
    if (depth < 0 || depth > 128) return 0;
    Probe *probe = calloc(1, sizeof(*probe));
    if (!probe) return 0;
    probe->seed = seed;
    probe->depth = depth;
#ifdef _WIN32
    probe->owner = GetCurrentThreadId();
    probe->fiber = CreateFiberEx(0, 1024 * 1024, FIBER_FLAG_FLOAT_SWITCH, entry, probe);
    if (!probe->fiber) { free(probe); return 0; }
#else
    probe->owner = pthread_self();
    probe->stack = malloc(1024 * 1024);
    if (!probe->stack || getcontext(&probe->fiber) != 0) {
        free(probe->stack); free(probe); return 0;
    }
    probe->fiber.uc_stack.ss_sp = probe->stack;
    probe->fiber.uc_stack.ss_size = 1024 * 1024;
    probe->fiber.uc_link = NULL;
    uintptr_t pointer = (uintptr_t) probe;
    makecontext(&probe->fiber, (void (*)(void)) entry, 2,
                (unsigned int) pointer, (unsigned int) ((uint64_t) pointer >> 32));
#endif
    return (int64_t) (uintptr_t) probe;
}

EXPORT int gp_stack_probe_step(int64_t handle, int64_t reply) {
    Probe *probe = (Probe *) (uintptr_t) handle;
    if (!probe || !is_owner(probe) || probe->event == 2) return -1;
    probe->reply = reply;
#ifdef _WIN32
    int converted = !IsThreadAFiber();
    probe->parent = converted ? ConvertThreadToFiberEx(NULL, FIBER_FLAG_FLOAT_SWITCH) : GetCurrentFiber();
    if (!probe->parent) return -2;
    SwitchToFiber(probe->fiber);
    if (converted && !ConvertFiberToThread()) return -3;
#else
    if (swapcontext(&probe->parent, &probe->fiber) != 0) return -2;
#endif
    return probe->event;
}

EXPORT int64_t gp_stack_probe_value(int64_t handle) {
    Probe *probe = (Probe *) (uintptr_t) handle;
    if (!probe || !is_owner(probe)) abort();
    return probe->value;
}

/* Deliberately require normal completion: abandoning C/C++ frames needs a policy. */
EXPORT int gp_stack_probe_close(int64_t handle) {
    Probe *probe = (Probe *) (uintptr_t) handle;
    if (!probe || !is_owner(probe) || probe->event != 2) return -1;
#ifdef _WIN32
    DeleteFiber(probe->fiber);
#else
    free(probe->stack);
#endif
    free(probe);
    return 0;
}
