#ifndef GRAALPHP_CURL_REACTOR_H
#define GRAALPHP_CURL_REACTOR_H
#include "reactor.h"
#include <curl/curl.h>
#include <uv.h>

/* Internal C boundary. Only the libuv owner thread operates a multi handle. */
typedef struct gp_curl_loop gp_curl_loop;
gp_curl_loop *gp_curl_loop_create(uv_loop_t *loop, gp_completion completion, int public_multi, int provider);
void gp_curl_loop_add(gp_curl_loop *loop, int64_t token, uint64_t transfer);
void gp_curl_loop_cancel(gp_curl_loop *loop, int64_t token);
void gp_curl_loop_close(gp_curl_loop *loop);
void gp_curl_loop_command(gp_curl_loop *loop, int64_t token, int operation, uint64_t value, int64_t argument);

void gp_curl_retain(uint64_t transfer);
CURL *gp_curl_begin(uint64_t transfer);
void gp_curl_finish(uint64_t transfer, CURLcode status);
#endif
