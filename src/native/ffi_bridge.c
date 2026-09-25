/* Only C/libffi frames may execute on the alternate stack. Managed callbacks
 * execute after gp_ffi_step has returned to the original NFI entry stack. */
#include "ffi_bridge.h"
#define FFI_STATIC_BUILD
#include <ffi.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#define ATOMIC_SET(p) InterlockedExchange(p, 1)
typedef volatile LONG ErrorFlag;
#else
#include <pthread.h>
#include <ucontext.h>
#include <sys/mman.h>
#include <unistd.h>
#include <stdatomic.h>
#define ATOMIC_SET(p) atomic_store(p, 1)
typedef _Atomic int ErrorFlag;
#endif
#if defined(GP_STATIC)
#define EXPORT
#elif defined(_WIN32)
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

#define MAX_ARGS 64
#define STACK_SIZE (1024 * 1024)
typedef union { uint64_t u; int64_t i; double d; float f; void *p; } Value;
typedef struct Call Call;
typedef struct {
    ffi_cif cif;
    ffi_type *types[MAX_ARGS];
    char kinds[MAX_ARGS], result;
    int count;
} Signature;
typedef struct {
    Signature signature;
    Call *call;
    int index;
    ffi_closure *closure;
    void *code;
} Callback;
struct Call {
    Signature signature;
    void (*function)(void);
    Value values[MAX_ARGS], result, reply;
    void *arguments[MAX_ARGS];
    Callback *callbacks[MAX_ARGS], *pending;
    void **callback_args;
    int event, started, draining;
    ErrorFlag foreign_error;
#ifdef _WIN32
    DWORD owner;
    void *fiber, *parent;
#else
    pthread_t owner;
    ucontext_t fiber, parent;
    void *mapping;
    size_t mapping_size;
#endif
};
static int owner(Call *c) {
#ifdef _WIN32
    return c->owner == GetCurrentThreadId();
#else
    return pthread_equal(c->owner, pthread_self());
#endif
}
static ffi_type *type(char k) {
    switch (k) {
    case 'v': return &ffi_type_void;
    case 'b': return &ffi_type_sint8; case 'B': return &ffi_type_uint8;
    case 'h': return &ffi_type_sint16; case 'H': return &ffi_type_uint16;
    case 'i': return &ffi_type_sint32; case 'I': return &ffi_type_uint32;
    case 'l': return &ffi_type_sint64; case 'L': return &ffi_type_uint64;
    case 'f': return &ffi_type_float; case 'd': return &ffi_type_double;
    case 's': case '[': return &ffi_type_pointer;
    default: return NULL;
    }
}
static void yield(Call *c) {
#ifdef _WIN32
    SwitchToFiber(c->parent);
#else
    if (swapcontext(&c->fiber, &c->parent)) abort();
#endif
}
static void callback(ffi_cif *cif, void *result, void **args, void *data) {
    Callback *cb = data;
    Call *c = cb->call;
    Value reply = {0};
    if (!owner(c)) ATOMIC_SET(&c->foreign_error);
    else if (!c->draining) {
        c->pending = cb; c->callback_args = args; c->event = cb->index + 1;
        memset(&c->reply, 0, sizeof(c->reply));
        yield(c);
        reply = c->reply;
    }
    /* libffi requires narrow integral closure returns widened to ffi_arg. */
    if (cb->signature.result == 'f') *(float *) result = reply.f;
    else if (cb->signature.result == 'd') *(double *) result = reply.d;
    else if (cb->signature.result != 'v') *(ffi_arg *) result = (ffi_arg) reply.u;
    (void) cif;
}
static int parse(Call *c, Signature *s, const char **text, int nested) {
    s->result = *(*text)++;
    ffi_type *result = type(s->result);
    if (!result || s->result == '[' || s->result == 's') return 0;
    while (**text && **text != ']') {
        if (s->count == MAX_ARGS) return 0;
        int i = s->count++;
        char kind = *(*text)++;
        s->kinds[i] = kind; s->types[i] = type(kind);
        if (!s->types[i] || kind == 'v') return 0;
        if (kind == '[') {
            if (nested) return 0;
            Callback *cb = calloc(1, sizeof(*cb));
            if (!cb) return 0;
            c->callbacks[i] = cb; cb->call = c; cb->index = i;
            if (!parse(c, &cb->signature, text, 1) || **text != ']') return 0;
            (*text)++;
            cb->closure = ffi_closure_alloc(sizeof(ffi_closure), &cb->code);
            if (!cb->closure || ffi_prep_closure_loc(cb->closure, &cb->signature.cif,
                    callback, cb, cb->code) != FFI_OK) return 0;
            c->values[i].p = cb->code;
        }
    }
    return ffi_prep_cif(&s->cif, FFI_DEFAULT_ABI, s->count, result, s->types) == FFI_OK;
}
static void run(Call *c) {
    ffi_call(&c->signature.cif, c->function, &c->result, c->arguments);
    c->event = 0; c->pending = NULL; c->callback_args = NULL;
    yield(c);
    abort();
}
#ifdef _WIN32
static VOID WINAPI entry(void *arg) { run(arg); }
#else
static void entry(unsigned int low, unsigned int high) {
    run((Call *) (uintptr_t) ((uint64_t) low | ((uint64_t) high << 32)));
}
#endif
EXPORT int gp_ffi_close(uint64_t handle) {
    Call *c = (Call *) (uintptr_t) handle;
    if (!c || !owner(c) || (c->started && c->event != 0)) return -1;
#ifdef _WIN32
    if (c->fiber) DeleteFiber(c->fiber);
#else
    if (c->mapping) munmap(c->mapping, c->mapping_size);
#endif
    for (int i = 0; i < MAX_ARGS; i++) {
        if (c->signature.kinds[i] == 's') free(c->values[i].p);
        Callback *cb = c->callbacks[i];
        if (cb) { if (cb->closure) ffi_closure_free(cb->closure); free(cb); }
    }
    free(c); return 0;
}
EXPORT uint64_t gp_ffi_create(uint64_t function, const char *signature) {
    if (!function || !signature || !*signature) return 0;
    Call *c = calloc(1, sizeof(*c));
    if (!c) return 0;
#ifdef _WIN32
    c->owner = GetCurrentThreadId();
#else
    c->owner = pthread_self();
#endif
    uint64_t handle = (uint64_t) (uintptr_t) c;
    if (!parse(c, &c->signature, &signature, 0) || *signature) goto failed;
    c->function = (void (*)(void)) (uintptr_t) function;
    for (int i = 0; i < c->signature.count; i++) c->arguments[i] = &c->values[i];
#ifdef _WIN32
    c->fiber = CreateFiberEx(0, STACK_SIZE, FIBER_FLAG_FLOAT_SWITCH, entry, c);
    if (!c->fiber) goto failed;
#else
    size_t page = (size_t) sysconf(_SC_PAGESIZE);
    c->mapping_size = STACK_SIZE + 2 * page;
    c->mapping = mmap(NULL, c->mapping_size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (c->mapping == MAP_FAILED) { c->mapping = NULL; goto failed; }
    void *stack = (char *) c->mapping + page;
    if (mprotect(stack, STACK_SIZE, PROT_READ | PROT_WRITE) || getcontext(&c->fiber)) goto failed;
    c->fiber.uc_stack.ss_sp = stack; c->fiber.uc_stack.ss_size = STACK_SIZE; c->fiber.uc_link = NULL;
    makecontext(&c->fiber, (void (*)(void)) entry, 2, (unsigned int) handle, (unsigned int) (handle >> 32));
#endif
    return handle;
failed:
    gp_ffi_close(handle); return 0;
}
static Value *slot(Call *c, int index, char *kind) {
    if (!c || !owner(c)) return NULL;
    if (index == -1 && c->pending && !c->draining) { *kind = c->pending->signature.result; return &c->reply; }
    if (index < 0 || index >= c->signature.count || c->started) return NULL;
    *kind = c->signature.kinds[index]; return &c->values[index];
}
EXPORT int gp_ffi_set_i(uint64_t handle, int index, int64_t value) {
    char kind; Value *v = slot((Call *) (uintptr_t) handle, index, &kind);
    if (!v || !strchr("bBhHiIlLv", kind)) return -1;
    switch (kind) {
    case 'b': value = (int8_t) value; break; case 'B': value = (uint8_t) value; break;
    case 'h': value = (int16_t) value; break; case 'H': value = (uint16_t) value; break;
    case 'i': value = (int32_t) value; break; case 'I': value = (uint32_t) value; break;
    }
    v->i = value; return 0;
}
EXPORT int gp_ffi_set_d(uint64_t handle, int index, double value) {
    char kind; Value *v = slot((Call *) (uintptr_t) handle, index, &kind);
    if (!v || (kind != 'f' && kind != 'd')) return -1;
    if (kind == 'f') v->f = (float) value; else v->d = value; return 0;
}
EXPORT int gp_ffi_set_s(uint64_t handle, int index, const char *value) {
    char kind; Value *v = slot((Call *) (uintptr_t) handle, index, &kind);
    if (!v || kind != 's') return -1;
    size_t n = strlen(value) + 1;
    char *copy = malloc(n); if (!copy) return -2;
    memcpy(copy, value, n); free(v->p); v->p = copy; return 0;
}
EXPORT int gp_ffi_step(uint64_t handle) {
    Call *c = (Call *) (uintptr_t) handle;
    if (!c || !owner(c) || (c->started && c->event == 0)) return -1;
#ifdef _WIN32
    int converted = !IsThreadAFiber();
    c->parent = converted ? ConvertThreadToFiberEx(NULL, FIBER_FLAG_FLOAT_SWITCH) : GetCurrentFiber();
    if (!c->parent) return -2;
    c->started = 1; SwitchToFiber(c->fiber);
    if (converted && !ConvertFiberToThread()) abort();
#else
    c->started = 1;
    if (swapcontext(&c->parent, &c->fiber)) abort();
#endif
    return c->foreign_error ? -3 : c->event;
}
EXPORT int gp_ffi_count(uint64_t handle) {
    Call *c = (Call *) (uintptr_t) handle;
    return c && owner(c) && c->pending ? c->pending->signature.count : 0;
}
static void *read_slot(Call *c, int index, char *kind) {
    if (!c || !owner(c)) abort();
    if (index == -1 && c->started && c->event == 0) { *kind = c->signature.result; return &c->result; }
    if (!c->pending || index < 0 || index >= c->pending->signature.count) abort();
    *kind = c->pending->signature.kinds[index]; return c->callback_args[index];
}
EXPORT int64_t gp_ffi_get_i(uint64_t handle, int index) {
    char k; void *p = read_slot((Call *) (uintptr_t) handle, index, &k);
    switch (k) {
    case 'v': return 0;
    case 'b': return *(int8_t *)p; case 'B': return *(uint8_t *)p;
    case 'h': return *(int16_t *)p; case 'H': return *(uint16_t *)p;
    case 'i': return *(int32_t *)p; case 'I': return *(uint32_t *)p;
    case 'l': case 'L': return *(int64_t *)p;
    default: abort();
    }
}
EXPORT double gp_ffi_get_d(uint64_t handle, int index) {
    char k; void *p = read_slot((Call *) (uintptr_t) handle, index, &k);
    if (k == 'f') return *(float *)p;
    if (k == 'd') return *(double *)p;
    abort();
}
EXPORT const char *gp_ffi_get_s(uint64_t handle, int index) {
    char k; void *p = read_slot((Call *) (uintptr_t) handle, index, &k);
    if (k != 's') abort();
    return *(const char **)p;
}
EXPORT int gp_ffi_abort(uint64_t handle) {
    Call *c = (Call *) (uintptr_t) handle;
    if (!c || !owner(c)) return -1;
    c->draining = 1; memset(&c->reply, 0, sizeof(c->reply));
    /* Do not start an invocation cancelled before its first step. */
    if (c->started && c->event != 0) gp_ffi_step(handle);
    return c->event == 0 ? 0 : -1;
}
