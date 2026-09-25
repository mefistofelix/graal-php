#ifndef GP_FFI_BRIDGE_H
#define GP_FFI_BRIDGE_H
#include <stdint.h>
#if defined(GP_STATIC)
#define GP_FFI_EXPORT
#elif defined(_WIN32)
#define GP_FFI_EXPORT __declspec(dllexport)
#else
#define GP_FFI_EXPORT __attribute__((visibility("default")))
#endif
GP_FFI_EXPORT uint64_t gp_ffi_create(uint64_t function, const char *signature);
GP_FFI_EXPORT int gp_ffi_set_i(uint64_t handle, int index, int64_t value);
GP_FFI_EXPORT int gp_ffi_set_d(uint64_t handle, int index, double value);
GP_FFI_EXPORT int gp_ffi_set_s(uint64_t handle, int index, const char *value);
GP_FFI_EXPORT int gp_ffi_step(uint64_t handle);
GP_FFI_EXPORT int gp_ffi_count(uint64_t handle);
GP_FFI_EXPORT int64_t gp_ffi_get_i(uint64_t handle, int index);
GP_FFI_EXPORT double gp_ffi_get_d(uint64_t handle, int index);
GP_FFI_EXPORT const char *gp_ffi_get_s(uint64_t handle, int index);
GP_FFI_EXPORT int gp_ffi_abort(uint64_t handle);
GP_FFI_EXPORT int gp_ffi_close(uint64_t handle);
#endif
