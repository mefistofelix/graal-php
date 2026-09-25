#ifndef GRAALPHP_REACTOR_H
#define GRAALPHP_REACTOR_H
#include <stdint.h>
#include <trufflenfi.h>
#if defined(_WIN32) && !defined(GP_STATIC)
#define GP_REACTOR_API __declspec(dllexport)
#else
#define GP_REACTOR_API
#endif
typedef void (*gp_completion)(int64_t token, int status, uint64_t value, int64_t length);
GP_REACTOR_API uint64_t gp_reactor_create(TruffleEnv *env, gp_completion completion, int embedded);
GP_REACTOR_API int gp_reactor_step(uint64_t reactor, uint64_t timeout_ms);
GP_REACTOR_API int gp_reactor_wakeup(uint64_t reactor);
GP_REACTOR_API int gp_reactor_embed_wait(uint64_t reactor);
GP_REACTOR_API void gp_reactor_embed_arm(uint64_t reactor);
GP_REACTOR_API int gp_reactor_destroy(TruffleEnv *env, uint64_t reactor);
GP_REACTOR_API int gp_reactor_timer(uint64_t reactor, int64_t token, uint64_t milliseconds);
GP_REACTOR_API int gp_reactor_cancel(uint64_t reactor, int64_t token);
GP_REACTOR_API int gp_reactor_stop(uint64_t reactor);
GP_REACTOR_API int gp_reactor_curl(uint64_t reactor, int64_t token, uint64_t transfer);
GP_REACTOR_API int gp_reactor_curl_multi(uint64_t reactor, int64_t token, int64_t group, int operation, uint64_t value, int64_t argument);
GP_REACTOR_API int gp_tcp_listen(uint64_t reactor, int64_t token, const char *host, int port, int backlog);
GP_REACTOR_API int gp_tcp_port(uint64_t reactor, int64_t token, int64_t connection);
GP_REACTOR_API int gp_tcp_accept(uint64_t reactor, int64_t token, int64_t listener);
GP_REACTOR_API int gp_tcp_read(uint64_t reactor, int64_t token, int64_t connection);
GP_REACTOR_API int gp_tcp_write(uint64_t reactor, int64_t token, int64_t connection, const unsigned char *bytes, int length);
GP_REACTOR_API int gp_tcp_close(uint64_t reactor, int64_t token, int64_t connection);
#endif
