/* Included only by the opt-in Xmake curl_costs diagnostic build. */
#include <stdio.h>
enum { COST_BEGIN, COST_ADD, COST_ACTION, COST_POLL_INIT, COST_POLL_START, COST_TIMER, COST_COUNT };
static uint64_t cost_calls[COST_COUNT], cost_nanos[COST_COUNT];
#define COST_START(name) uint64_t cost_start_##name = uv_hrtime()
#define COST_END(name) do { cost_calls[name]++; cost_nanos[name] += uv_hrtime() - cost_start_##name; } while (0)
static void print_curl_costs(void) {
    const char *names[] = {"easy_begin", "multi_add", "socket_action", "poll_init", "poll_start", "timer"};
    for (int i = 0; i < COST_COUNT; i++) if (cost_calls[i])
        fprintf(stderr, "COST C/%s count=%llu milliseconds=%.3f us_per_call=%.3f\n", names[i],
                (unsigned long long) cost_calls[i], cost_nanos[i] / 1e6, cost_nanos[i] / 1e3 / cost_calls[i]);
}
