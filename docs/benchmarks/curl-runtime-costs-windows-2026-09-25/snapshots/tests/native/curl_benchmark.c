/* C-only control: the production reactor, providers and archives, without PHP/NFI dispatch.
 * Completions enqueue work; no recursive curl invocation from inside a native callback. */
#include "services.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct { uint64_t transfer; int remaining, ready; } client;
static client *clients;
static int client_count;
static uint64_t reactor;
static const char *expected;

static void fail(const char *message) { fprintf(stderr, "%s\n", message); exit(1); }
static void closure_ref(TruffleEnv *env, void *closure) { (void) env; (void) closure; }
/* The callback is a static C function. These are the only NFI services used by
 * reactor create/destroy; reference retention is unnecessary in this control. */
static const struct __TruffleNativeAPI native_api = {.newClosureRef = closure_ref, .releaseClosureRef = closure_ref};
static void completed(int64_t token, int status, uint64_t value, int64_t length) {
    if (token < 1 || token > client_count || status || value || length != -1) fail("Native completion failed");
    client *item = &clients[token - 1];
    int size = gp_curl_size(item->transfer);
    unsigned char *body = malloc((size_t) size);
    if (body == NULL) fail("Body allocation failed");
    gp_curl_copy(item->transfer, body, size);
    if ((size_t) size != strlen(expected) || memcmp(body, expected, (size_t) size)) fail("Response mismatch");
    free(body);
    item->remaining--;
    item->ready = 1;
}
static int number(const char *name, int fallback) {
    const char *value = getenv(name);
    int parsed = value == NULL ? fallback : atoi(value);
    if (parsed < 1 || parsed > 1000000) fail("Invalid benchmark parameter");
    return parsed;
}
static void batch(int count, int iterations, const char *path, const char *body) {
    char url[1024];
    if (snprintf(url, sizeof(url), "%s%s", getenv("BENCH_UPSTREAM"), path) >= sizeof(url)) fail("URL too long");
    client_count = count;
    clients = calloc((size_t) count, sizeof(*clients));
    if (clients == NULL) fail("Client allocation failed");
    expected = body;
    for (int i = 0; i < count; i++) {
        clients[i].transfer = gp_curl_create_provider(0);
        if (!clients[i].transfer) fail("curl_init failed");
        if (gp_curl_option_string(clients[i].transfer, 10002, url) ||
            gp_curl_option_string(clients[i].transfer, 10177, "*") ||
            gp_curl_option_long(clients[i].transfer, 155, 10000) ||
            gp_curl_option_long(clients[i].transfer, 84, 2)) fail("curl_setopt failed");
        clients[i].remaining = iterations;
        clients[i].ready = 1;
    }
    for (;;) {
        int active = 0;
        for (int i = 0; i < count; i++) if (clients[i].remaining) {
            active++;
            if (clients[i].ready) {
                clients[i].ready = 0;
                if (gp_reactor_curl(reactor, i + 1, clients[i].transfer)) fail("Submission failed");
            }
        }
        if (!active) break;
        int queued = 0, pending = 0;
        for (int i = 0; i < count; i++) if (clients[i].remaining) {
            pending++;
            if (clients[i].ready) queued++;
        }
        if (pending) gp_reactor_step(reactor, queued ? 0 : 10000);
    }
    for (int i = 0; i < count; i++) gp_curl_close(clients[i].transfer);
    free(clients);
    clients = NULL;
    client_count = 0;
}
int main(void) {
    TruffleEnv environment = &native_api;
    if (getenv("BENCH_UPSTREAM") == NULL || getenv("BENCH_BODY") == NULL) fail("Use the CurlBenchmark driver");
    if (getenv("BENCH_PARKED") != NULL && atoi(getenv("BENCH_PARKED")) != 0) fail("C control has no parked coroutines");
    reactor = gp_reactor_create(&environment, completed, 0);
    if (!reactor) fail("Reactor creation failed");
    printf("VERSION %s\n", gp_curl_version_provider(0));
    batch(64, number("BENCH_WARMUP", 256), "/payload", getenv("BENCH_BODY"));
    batch(1, 1, "/baseline", "ok");
    batch(1, 1, "/suspended", "ok");
    batch(1, 1, "/begin", "ok");
    batch(number("BENCH_CLIENTS", 64), number("BENCH_ITERATIONS", 1024), "/payload", getenv("BENCH_BODY"));
    batch(1, 1, "/end", "ok");
    gp_reactor_stop(reactor);
    if (gp_reactor_destroy(&environment, reactor)) fail("Reactor shutdown failed");
    puts("PASS curl benchmark");
    return 0;
}
