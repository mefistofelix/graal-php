#ifndef GRAALPHP_BUILTINS_H
#define GRAALPHP_BUILTINS_H
#include <stdint.h>
#if defined(_WIN32) && !defined(GP_STATIC)
__declspec(dllexport)
#endif
uint64_t gp_builtin_lookup(const char *library, const char *symbol);
#endif
