#include "curl_reactor.h"
#include "services.h"
#include "curl_provider.h"
#include <stdlib.h>
#include <string.h>
#ifdef GP_CURL_COSTS
#include "../../tests/native/curl_costs.h"
#else
#define COST_START(name)
#define COST_END(name)
#endif

typedef struct gp_curl_socket {
    uv_poll_t handle;
    curl_socket_t socket;
    gp_curl_loop *owner;
    struct gp_curl_socket *next;
} gp_curl_socket;
typedef struct gp_curl_request {
    CURL *easy;
    uint64_t transfer;
    int64_t token;
    int done, queued;
    CURLcode result;
    struct gp_curl_request *next, *done_next;
} gp_curl_request;
typedef struct gp_curl_waiter {
    uv_timer_t timer;
    gp_curl_loop *owner;
    int64_t token;
    struct gp_curl_waiter *next;
} gp_curl_waiter;
struct gp_curl_loop {
    uv_loop_t *loop;
    uv_timer_t timer;
    CURLM *multi;
    const gp_curl_provider *provider;
    gp_curl_socket *sockets;
    gp_curl_request *requests, *done_head, *done_tail;
    gp_curl_waiter *waiters;
    gp_completion completion;
    int closing, public_multi, started, running;
    CURLMcode status;
};

static void free_handle(uv_handle_t *handle) { free(handle->data); }
static void remove_socket(gp_curl_socket *socket) {
    gp_curl_socket **link = &socket->owner->sockets;
    while (*link != socket) link = &(*link)->next;
    *link = socket->next;
    /* Stop watching before libcurl closes or reuses this OS descriptor. */
    uv_poll_stop(&socket->handle);
    uv_close((uv_handle_t *) &socket->handle, free_handle);
}
static int socket_count(gp_curl_loop *loop) {
    int count = 0;
    for (gp_curl_socket *socket = loop->sockets; socket != NULL; socket = socket->next)
        if (uv_is_active((uv_handle_t *) &socket->handle)) count++;
    return count;
}
static void complete_select(gp_curl_loop *loop, int64_t token) {
    /* Low 32 bits: watcher count; high 32 bits: CURLM status, not a UV error. */
    uint64_t result = ((uint64_t) (uint32_t) loop->status << 32) | (uint32_t) socket_count(loop);
    loop->completion(token, 0, result, -1);
}
static void finish_waiter(gp_curl_waiter *waiter) {
    gp_curl_loop *loop = waiter->owner;
    gp_curl_waiter **link = &loop->waiters;
    while (*link != waiter) link = &(*link)->next;
    *link = waiter->next;
    uv_timer_stop(&waiter->timer);
    uv_close((uv_handle_t *) &waiter->timer, free_handle);
    complete_select(loop, waiter->token);
}
static void wake_waiters(gp_curl_loop *loop) {
    while (loop->waiters != NULL) finish_waiter(loop->waiters);
}
static void dequeue_result(gp_curl_loop *loop, gp_curl_request *request) {
    if (!request->queued) return;
    gp_curl_request **link = &loop->done_head;
    gp_curl_request *previous = NULL;
    while (*link != request) { previous = *link; link = &(*link)->done_next; }
    *link = request->done_next;
    if (loop->done_tail == request) loop->done_tail = previous;
    request->queued = 0;
    request->done_next = NULL;
}
static CURLMcode detach_request(gp_curl_loop *loop, gp_curl_request *request) {
    CURLMcode status = loop->provider->multi_remove_handle(loop->multi, request->easy);
    if (status != CURLM_OK) return status;
    gp_curl_request **link = &loop->requests;
    while (*link != request) link = &(*link)->next;
    *link = request->next;
    dequeue_result(loop, request);
    gp_curl_close(request->transfer); /* Release the native membership reference. */
    free(request);
    return CURLM_OK;
}
static void finish_request(gp_curl_loop *loop, gp_curl_request *request, CURLcode status) {
    gp_curl_finish(request->transfer, status);
    if (loop->public_multi) {
        if (request->done) return;
        request->done = request->queued = 1;
        request->result = status;
        if (loop->done_tail == NULL) loop->done_head = request;
        else loop->done_tail->done_next = request;
        loop->done_tail = request;
    } else {
        int64_t token = request->token;
        detach_request(loop, request);
        loop->completion(token, 0, (uint64_t) status, -1);
    }
}
static void drain_results(gp_curl_loop *loop) {
    int remaining;
    CURLMsg *message;
    while ((message = loop->provider->multi_info_read(loop->multi, &remaining)) != NULL) {
        if (message->msg != CURLMSG_DONE) continue;
        gp_curl_request *request = loop->requests;
        while (request != NULL && request->easy != message->easy_handle) request = request->next;
        CURLcode status = message->data.result;
        if (request != NULL) finish_request(loop, request, status);
    }
}
static void socket_action(gp_curl_loop *loop, curl_socket_t socket, int events) {
    COST_START(COST_ACTION);
    loop->status = loop->provider->multi_socket_action(loop->multi, socket, events, &loop->running);
    COST_END(COST_ACTION);
    if (loop->status != CURLM_OK) {
        for (gp_curl_request *request = loop->requests, *next; request != NULL; request = next) {
            next = request->next;
            if (!request->done) finish_request(loop, request, CURLE_FAILED_INIT);
        }
        loop->running = 0;
    } else drain_results(loop);
}
static void socket_ready(uv_poll_t *handle, int status, int events) {
    gp_curl_socket *socket = handle->data;
    gp_curl_loop *loop = socket->owner;
    int action = status < 0 ? CURL_CSELECT_ERR : 0;
    if (events & UV_READABLE) action |= CURL_CSELECT_IN;
    if (events & UV_WRITABLE) action |= CURL_CSELECT_OUT;
    socket_action(loop, socket->socket, action);
    wake_waiters(loop);
}
static int watch_socket(CURL *easy, curl_socket_t descriptor, int action, void *user, void *assigned) {
    gp_curl_loop *loop = user;
    gp_curl_socket *socket = assigned;
    if (action == CURL_POLL_REMOVE) {
        if (socket != NULL) {
            loop->provider->multi_assign(loop->multi, descriptor, NULL);
            remove_socket(socket);
        }
        return 0;
    }
    if (loop->closing) return 0;
    if (socket == NULL) {
        socket = calloc(1, sizeof(*socket));
        if (socket == NULL) return -1;
        COST_START(COST_POLL_INIT);
        int initialized = uv_poll_init_socket(loop->loop, &socket->handle, (uv_os_sock_t) descriptor);
        COST_END(COST_POLL_INIT);
        if (initialized != 0) {
            free(socket);
            return -1;
        }
        socket->owner = loop;
        socket->socket = descriptor;
        socket->handle.data = socket;
        socket->next = loop->sockets;
        loop->sockets = socket;
        if (loop->provider->multi_assign(loop->multi, descriptor, socket) != CURLM_OK) {
            remove_socket(socket);
            return -1;
        }
    }
    if (action == CURL_POLL_NONE) return uv_poll_stop(&socket->handle) == 0 ? 0 : -1;
    int events = 0;
    if (action == CURL_POLL_IN || action == CURL_POLL_INOUT) events |= UV_READABLE;
    if (action == CURL_POLL_OUT || action == CURL_POLL_INOUT) events |= UV_WRITABLE;
    COST_START(COST_POLL_START);
    int started = uv_poll_start(&socket->handle, events, socket_ready);
    COST_END(COST_POLL_START);
    return started == 0 ? 0 : -1;
}
static void timer_fired(uv_timer_t *timer) {
    gp_curl_loop *loop = timer->data;
    socket_action(loop, CURL_SOCKET_TIMEOUT, 0);
    wake_waiters(loop);
}
static int update_timer(CURLM *multi, long milliseconds, void *user) {
    COST_START(COST_TIMER);
    gp_curl_loop *loop = user;
    uv_timer_stop(&loop->timer);
    if (loop->closing || !loop->started || milliseconds < 0) { COST_END(COST_TIMER); return 0; }
    uv_update_time(loop->loop);
    int started = uv_timer_start(&loop->timer, timer_fired, (uint64_t) milliseconds, 0);
    COST_END(COST_TIMER);
    return started == 0 ? 0 : -1;
}
gp_curl_loop *gp_curl_loop_create(uv_loop_t *owner, gp_completion completion, int public_multi, int provider) {
    if (!gp_curl_initialize_provider(provider)) return NULL;
    gp_curl_loop *loop = calloc(1, sizeof(*loop));
    if (loop == NULL) return NULL;
    loop->provider = gp_curl_provider_get(provider);
    loop->loop = owner;
    loop->completion = completion;
    loop->public_multi = public_multi;
    loop->started = !public_multi;
    loop->multi = loop->provider->multi_init();
    if (loop->multi == NULL) { free(loop); return NULL; }
    if (uv_timer_init(owner, &loop->timer) != 0) { loop->provider->multi_cleanup(loop->multi); free(loop); return NULL; }
    loop->timer.data = loop;
    if (loop->provider->multi_setopt(loop->multi, CURLMOPT_SOCKETFUNCTION, watch_socket) != CURLM_OK ||
        loop->provider->multi_setopt(loop->multi, CURLMOPT_SOCKETDATA, loop) != CURLM_OK ||
        loop->provider->multi_setopt(loop->multi, CURLMOPT_TIMERFUNCTION, update_timer) != CURLM_OK ||
        loop->provider->multi_setopt(loop->multi, CURLMOPT_TIMERDATA, loop) != CURLM_OK) {
        gp_curl_loop_close(loop);
        return NULL;
    }
    return loop;
}
static CURLMcode add_request(gp_curl_loop *loop, int64_t token, uint64_t transfer) {
    if (loop->provider != gp_curl_provider_get(gp_curl_provider_id(transfer))) return CURLM_BAD_EASY_HANDLE;
    gp_curl_request *request = calloc(1, sizeof(*request));
    if (request == NULL) return CURLM_OUT_OF_MEMORY;
    COST_START(COST_BEGIN);
    request->easy = gp_curl_begin(transfer);
    COST_END(COST_BEGIN);
    request->transfer = transfer;
    request->token = token;
    COST_START(COST_ADD);
    CURLMcode status = loop->provider->multi_add_handle(loop->multi, request->easy);
    COST_END(COST_ADD);
    if (status != CURLM_OK) { free(request); return status; }
    gp_curl_retain(transfer);
    request->next = loop->requests;
    loop->requests = request;
    return CURLM_OK;
}
void gp_curl_loop_add(gp_curl_loop *loop, int64_t token, uint64_t transfer) {
    CURLMcode status = add_request(loop, token, transfer);
    if (status != CURLM_OK) {
        CURLcode error = status == CURLM_OUT_OF_MEMORY ? CURLE_OUT_OF_MEMORY : CURLE_FAILED_INIT;
        gp_curl_finish(transfer, error);
        loop->completion(token, 0, error, -1);
        return;
    }
    socket_action(loop, CURL_SOCKET_TIMEOUT, 0);
}
static void select_deadline(uv_timer_t *timer) { finish_waiter(timer->data); }
static void select_multi(gp_curl_loop *loop, int64_t token, uint64_t milliseconds) {
    loop->started = 1;
    socket_action(loop, CURL_SOCKET_TIMEOUT, 0);
    if (loop->running == 0 || milliseconds == 0) {
        complete_select(loop, token);
        return;
    }
    gp_curl_waiter *waiter = calloc(1, sizeof(*waiter));
    if (waiter == NULL) { loop->completion(token, UV_ENOMEM, 0, -1); return; }
    int status = uv_timer_init(loop->loop, &waiter->timer);
    if (status != 0) { free(waiter); loop->completion(token, status, 0, -1); return; }
    waiter->owner = loop;
    waiter->token = token;
    waiter->timer.data = waiter;
    waiter->next = loop->waiters;
    loop->waiters = waiter;
    uv_update_time(loop->loop);
    status = uv_timer_start(&waiter->timer, select_deadline, milliseconds, 0);
    if (status != 0) {
        loop->status = CURLM_INTERNAL_ERROR;
        finish_waiter(waiter);
    }
}
static void clear_multi(gp_curl_loop *loop) {
    while (loop->requests != NULL) detach_request(loop, loop->requests);
    loop->running = 0;
    loop->started = !loop->public_multi;
    uv_timer_stop(&loop->timer);
    wake_waiters(loop);
}
void gp_curl_loop_command(gp_curl_loop *loop, int64_t token, int operation, uint64_t value, int64_t argument) {
    gp_curl_request *request = loop->requests;
    while (request != NULL && request->transfer != value) request = request->next;
    CURLMcode status = CURLM_OK;
    switch (operation) {
        case 1: status = add_request(loop, 0, value); break;
        case 2:
            if (request != NULL) status = detach_request(loop, request);
            if (loop->requests == NULL) { loop->running = 0; wake_waiters(loop); }
            break;
        case 3: {
            loop->started = 1;
            socket_action(loop, CURL_SOCKET_TIMEOUT, 0);
            size_t count = 0;
            for (request = loop->requests; request != NULL; request = request->next) if (request->done) count++;
            uint64_t *result = malloc((2 + count * 2) * sizeof(uint64_t));
            if (result == NULL) { loop->completion(token, UV_ENOMEM, 0, -1); return; }
            result[0] = (uint64_t) loop->status;
            result[1] = (uint64_t) loop->running;
            size_t index = 2;
            for (request = loop->requests; request != NULL; request = request->next) {
                if (request->done) { result[index++] = request->transfer; result[index++] = request->result; }
            }
            loop->completion(token, 0, (uint64_t) (uintptr_t) result, (int64_t) (index * sizeof(uint64_t)));
            free(result);
            if (loop->running == 0) wake_waiters(loop);
            return;
        }
        case 4: {
            request = loop->done_head;
            if (request == NULL) { loop->completion(token, 0, 0, -1); return; }
            dequeue_result(loop, request);
            uint64_t count = 0;
            for (gp_curl_request *item = loop->done_head; item != NULL; item = item->done_next) count++;
            uint64_t result[] = {request->transfer, (uint64_t) request->result, count};
            loop->completion(token, 0, (uint64_t) (uintptr_t) result, sizeof(result));
            return;
        }
        case 5: select_multi(loop, token, value); return;
        case 6:
            status = value >= 30000
                ? loop->provider->multi_setopt(loop->multi, (CURLMoption) value, (curl_off_t) argument)
                : loop->provider->multi_setopt(loop->multi, (CURLMoption) value, (long) argument);
            break;
        case 7: clear_multi(loop); break;
        case 9: {
            int length = gp_curl_size(value);
            unsigned char *body = malloc(length == 0 ? 1 : (size_t) length);
            if (body == NULL) { loop->completion(token, UV_ENOMEM, 0, -1); return; }
            gp_curl_copy(value, body, length);
            loop->completion(token, 0, (uint64_t) (uintptr_t) body, length);
            free(body);
            return;
        }
        case 10: case 12: {
            const char *text = operation == 10 ? gp_curl_error(value) : gp_curl_info_string(value, (int) argument);
            loop->completion(token, 0, (uint64_t) (uintptr_t) text, (int64_t) strlen(text));
            return;
        }
        case 11: loop->completion(token, 0, (uint64_t) gp_curl_info_long(value, (int) argument), -1); return;
        default: status = CURLM_UNKNOWN_OPTION; break;
    }
    loop->completion(token, 0, (uint64_t) status, -1);
}
void gp_curl_loop_cancel(gp_curl_loop *loop, int64_t token) {
    if (loop->public_multi) {
        gp_curl_waiter *waiter = loop->waiters;
        while (waiter != NULL && waiter->token != token) waiter = waiter->next;
        if (waiter != NULL) finish_waiter(waiter);
    } else {
        gp_curl_request *request = loop->requests;
        while (request != NULL && request->token != token) request = request->next;
        if (request != NULL) finish_request(loop, request, CURLE_ABORTED_BY_CALLBACK);
    }
}
void gp_curl_loop_close(gp_curl_loop *loop) {
#ifdef GP_CURL_COSTS
    print_curl_costs();
#endif
    loop->closing = 1;
    if (loop->public_multi) clear_multi(loop);
    else while (loop->requests != NULL) finish_request(loop, loop->requests, CURLE_ABORTED_BY_CALLBACK);
    loop->provider->multi_cleanup(loop->multi);
    while (loop->sockets != NULL) remove_socket(loop->sockets);
    uv_timer_stop(&loop->timer);
    uv_close((uv_handle_t *) &loop->timer, free_handle);
}
