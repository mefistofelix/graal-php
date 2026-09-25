#include "reactor.h"
#include "curl_reactor.h"
#include "curl_provider.h"
#include <stdlib.h>
#include <string.h>
#include <uv.h>
#if defined(__linux__)
#include <errno.h>
#include <sys/epoll.h>
#endif

typedef struct gp_reactor gp_reactor;
typedef struct gp_timer {
    uv_timer_t handle;
    gp_reactor *reactor;
    int64_t token;
    struct gp_timer *next;
} gp_timer;
typedef struct gp_stream {
    uv_tcp_t handle;
    gp_reactor *reactor;
    int64_t id, read_token, accept_token;
    int listening, closing, queued_count;
    struct gp_stream *next, *queued_next, *accepted_head, *accepted_tail;
} gp_stream;
typedef struct {
    uv_write_t request;
    gp_stream *stream;
    int64_t token;
    uv_buf_t buffer;
} gp_write;
typedef struct gp_curl_group {
    int64_t id;
    gp_curl_loop *loop;
    struct gp_curl_group *next;
} gp_curl_group;
typedef struct gp_command {
    enum { GP_TIMER, GP_CANCEL, GP_STOP, GP_CURL, GP_CURL_MULTI, GP_LISTEN, GP_PORT, GP_ACCEPT, GP_READ, GP_WRITE, GP_CLOSE } kind;
    int64_t token;
    uint64_t milliseconds;
    int64_t connection;
    int operation;
    int64_t argument;
    unsigned char *data;
    int length, port, backlog;
} gp_command;
struct gp_reactor {
    uv_loop_t loop;
    uv_async_t wakeup;
    uv_timer_t deadline;
    uv_sem_t poll_start, poll_finished;
    int embedded, poll_active, poll_closing, poll_timeout;
    gp_timer *timers;
    gp_stream *streams;
    gp_curl_loop *curl[2];
    gp_curl_group *curl_groups;
    int64_t next_stream;
    gp_completion completion;
    int stopping;
};

static void complete(gp_reactor *reactor, int64_t token, int status, uint64_t value) {
    if (token != 0) reactor->completion(token, status, value, -1);
}
static gp_stream *find_stream(gp_reactor *reactor, int64_t id) {
    gp_stream *stream = reactor->streams;
    while (stream != NULL && stream->id != id) stream = stream->next;
    return stream;
}
static void stream_freed(uv_handle_t *handle) { free(handle->data); }
static void close_stream(gp_stream *stream) {
    if (stream->closing) return;
    stream->closing = 1;
    gp_reactor *reactor = stream->reactor;
    gp_stream **link = &reactor->streams;
    while (*link != stream) link = &(*link)->next;
    *link = stream->next;
    if (stream->read_token != 0) {
        complete(reactor, stream->read_token, UV_ECANCELED, 0);
        stream->read_token = 0;
    }
    if (stream->accept_token != 0) {
        complete(reactor, stream->accept_token, 0, 0);
        stream->accept_token = 0;
    }
    while (stream->accepted_head != NULL) {
        gp_stream *client = stream->accepted_head;
        stream->accepted_head = client->queued_next;
        close_stream(client);
    }
    uv_close((uv_handle_t *) &stream->handle, stream_freed);
}
static gp_stream *new_stream(gp_reactor *reactor) {
    gp_stream *stream = calloc(1, sizeof(*stream));
    if (stream == NULL) return NULL;
    if (uv_tcp_init(&reactor->loop, &stream->handle) != 0) { free(stream); return NULL; }
    stream->reactor = reactor;
    stream->handle.data = stream;
    stream->id = ++reactor->next_stream;
    stream->next = reactor->streams;
    reactor->streams = stream;
    return stream;
}
static void accepted(uv_stream_t *handle, int status) {
    gp_stream *listener = handle->data;
    gp_reactor *reactor = listener->reactor;
    if (status != 0) {
        complete(reactor, listener->accept_token, status, 0);
        listener->accept_token = 0;
        return;
    }
    gp_stream *client = new_stream(reactor);
    if (client == NULL) {
        complete(reactor, listener->accept_token, UV_ENOMEM, 0);
        listener->accept_token = 0;
        return;
    }
    status = uv_accept(handle, (uv_stream_t *) &client->handle);
    if (status != 0) { close_stream(client); return; }
    uv_tcp_nodelay(&client->handle, 1);
    if (listener->accept_token != 0) {
        int64_t token = listener->accept_token;
        listener->accept_token = 0;
        complete(reactor, token, 0, client->id);
    } else if (listener->queued_count >= 128) close_stream(client);
    else {
        if (listener->accepted_tail == NULL) listener->accepted_head = client;
        else listener->accepted_tail->queued_next = client;
        listener->accepted_tail = client;
        listener->queued_count++;
    }
}
static void allocate_read(uv_handle_t *handle, size_t suggested, uv_buf_t *buffer) {
    buffer->base = malloc(65536);
    buffer->len = buffer->base == NULL ? 0 : 65536;
}
static void received(uv_stream_t *handle, ssize_t count, const uv_buf_t *buffer) {
    gp_stream *stream = handle->data;
    if (count != 0) {
        uv_read_stop(handle);
        int64_t token = stream->read_token;
        stream->read_token = 0;
        if (count > 0) stream->reactor->completion(token, 0, (uint64_t) (uintptr_t) buffer->base, count);
        else if (count == UV_EOF) stream->reactor->completion(token, 0, 0, 0);
        else complete(stream->reactor, token, (int) count, 0);
    }
    free(buffer->base);
}
static void written(uv_write_t *request, int status) {
    gp_write *write = request->data;
    complete(write->stream->reactor, write->token, status, 0);
    free(write->buffer.base);
    free(write);
}
static void process_network(gp_reactor *reactor, gp_command *command) {
    gp_stream *stream = find_stream(reactor, command->connection);
    int status = 0;
    if (command->kind == GP_LISTEN) {
        struct sockaddr_storage address = {0};
        status = strchr((char *) command->data, ':') == NULL
            ? uv_ip4_addr((char *) command->data, command->port, (struct sockaddr_in *) &address)
            : uv_ip6_addr((char *) command->data, command->port, (struct sockaddr_in6 *) &address);
        if (status == 0) {
            stream = new_stream(reactor);
            if (stream == NULL) status = UV_ENOMEM;
        }
        if (status == 0) status = uv_tcp_bind(&stream->handle, (struct sockaddr *) &address, 0);
        if (status == 0) status = uv_listen((uv_stream_t *) &stream->handle, command->backlog, accepted);
        if (status != 0 && stream != NULL) close_stream(stream);
        if (status == 0) stream->listening = 1;
        complete(reactor, command->token, status, status == 0 ? stream->id : 0);
        return;
    }
    if (stream == NULL) {
        complete(reactor, command->token, command->kind == GP_CLOSE ? 0 : UV_EBADF, 0);
        return;
    }
    if (command->kind == GP_CLOSE) {
        close_stream(stream);
        complete(reactor, command->token, 0, 0);
    } else if (command->kind == GP_PORT) {
        struct sockaddr_storage address = {0};
        int length = sizeof(address);
        status = uv_tcp_getsockname(&stream->handle, (struct sockaddr *) &address, &length);
        int port = address.ss_family == AF_INET ? ntohs(((struct sockaddr_in *) &address)->sin_port)
                                               : ntohs(((struct sockaddr_in6 *) &address)->sin6_port);
        complete(reactor, command->token, status, status == 0 ? port : 0);
    } else if (command->kind == GP_ACCEPT) {
        if (!stream->listening) complete(reactor, command->token, UV_EINVAL, 0);
        else if (stream->accept_token != 0) complete(reactor, command->token, UV_EBUSY, 0);
        else if (stream->accepted_head != NULL) {
            gp_stream *client = stream->accepted_head;
            stream->accepted_head = client->queued_next;
            if (stream->accepted_head == NULL) stream->accepted_tail = NULL;
            stream->queued_count--;
            complete(reactor, command->token, 0, client->id);
        } else stream->accept_token = command->token;
    } else if (command->kind == GP_READ) {
        if (stream->listening || stream->read_token != 0) complete(reactor, command->token, UV_EBUSY, 0);
        else {
            stream->read_token = command->token;
            status = uv_read_start((uv_stream_t *) &stream->handle, allocate_read, received);
            if (status != 0) { stream->read_token = 0; complete(reactor, command->token, status, 0); }
        }
    } else if (command->kind == GP_WRITE) {
        gp_write *write = calloc(1, sizeof(*write));
        if (write == NULL) { complete(reactor, command->token, UV_ENOMEM, 0); return; }
        write->stream = stream;
        write->token = command->token;
        write->buffer = uv_buf_init((char *) command->data, command->length);
        command->data = NULL;
        write->request.data = write;
        status = uv_write(&write->request, (uv_stream_t *) &stream->handle, &write->buffer, 1, written);
        if (status != 0) written(&write->request, status);
    }
}

static void timer_closed(uv_handle_t *handle) { free(handle->data); }
static void finish_timer(gp_timer *timer, int status) {
    gp_reactor *reactor = timer->reactor;
    gp_timer **link = &reactor->timers;
    while (*link != timer) link = &(*link)->next;
    *link = timer->next;
    uv_timer_stop(&timer->handle);
    uv_close((uv_handle_t *) &timer->handle, timer_closed);
    complete(reactor, timer->token, status, 0);
}
static void timer_fired(uv_timer_t *handle) { finish_timer(handle->data, 0); }

/* Commands and completions execute on the PHP owner. No command queue or wakeup
 * is needed for owner-thread submissions. Only external work uses uv_async_send. */
static void process_command(gp_reactor *reactor, gp_command *command) {
    uv_update_time(&reactor->loop);
    if (command->kind == GP_STOP) {
        reactor->stopping = 1;
        for (int provider = 0; provider < 2; provider++)
            if (reactor->curl[provider] != NULL) { gp_curl_loop_close(reactor->curl[provider]); reactor->curl[provider] = NULL; }
        while (reactor->curl_groups != NULL) {
            gp_curl_group *group = reactor->curl_groups;
            reactor->curl_groups = group->next;
            gp_curl_loop_close(group->loop);
            free(group);
        }
        while (reactor->timers != NULL) finish_timer(reactor->timers, UV_ECANCELED);
        /* Close listeners first so their accepted queues cannot retain dead streams. */
        gp_stream *stream = reactor->streams;
        while (stream != NULL) {
            gp_stream *next_stream = stream->next;
            if (stream->listening) close_stream(stream);
            stream = next_stream;
        }
        while (reactor->streams != NULL) close_stream(reactor->streams);
        uv_close((uv_handle_t *) &reactor->deadline, NULL);
        uv_close((uv_handle_t *) &reactor->wakeup, NULL);
    } else if (command->kind == GP_CANCEL) {
        for (int provider = 0; provider < 2; provider++)
            if (reactor->curl[provider] != NULL) gp_curl_loop_cancel(reactor->curl[provider], command->token);
        for (gp_curl_group *group = reactor->curl_groups; group != NULL; group = group->next)
            gp_curl_loop_cancel(group->loop, command->token);
        gp_timer *timer = reactor->timers;
        while (timer != NULL && timer->token != command->token) timer = timer->next;
        if (timer != NULL) finish_timer(timer, UV_ECANCELED);
        gp_stream *stream = reactor->streams;
        while (stream != NULL) {
            if (stream->read_token == command->token || stream->accept_token == command->token) { close_stream(stream); break; }
            stream = stream->next;
        }
    } else if (reactor->stopping) {
        complete(reactor, command->token, UV_ECANCELED, 0);
    } else if (command->kind == GP_CURL) {
        int provider = gp_curl_provider_id(command->milliseconds);
        if (reactor->curl[provider] == NULL) reactor->curl[provider] = gp_curl_loop_create(&reactor->loop, reactor->completion, 0, provider);
        if (reactor->curl[provider] == NULL) complete(reactor, command->token, UV_ENOMEM, 0);
        else gp_curl_loop_add(reactor->curl[provider], command->token, command->milliseconds);
    } else if (command->kind == GP_CURL_MULTI) {
        gp_curl_group **link = &reactor->curl_groups;
        while (*link != NULL && (*link)->id != command->connection) link = &(*link)->next;
        if (command->operation == 8) {
            if (*link != NULL) {
                gp_curl_group *group = *link;
                *link = group->next;
                gp_curl_loop_close(group->loop);
                free(group);
            }
            complete(reactor, command->token, 0, 0);
        } else {
            if (*link == NULL) {
                gp_curl_group *group = calloc(1, sizeof(*group));
                if (group != NULL) {
                    group->id = command->connection;
                    group->loop = gp_curl_loop_create(&reactor->loop, reactor->completion, 1, (int) (command->connection & 1));
                    if (group->loop == NULL) { free(group); group = NULL; }
                }
                *link = group;
            }
            if (*link == NULL) complete(reactor, command->token, UV_ENOMEM, 0);
            else gp_curl_loop_command((*link)->loop, command->token, command->operation, command->milliseconds, command->argument);
        }
    } else if (command->kind >= GP_LISTEN) {
        process_network(reactor, command);
    } else {
        gp_timer *timer = calloc(1, sizeof(*timer));
        if (timer == NULL) complete(reactor, command->token, UV_ENOMEM, 0);
        else {
            timer->reactor = reactor;
            timer->token = command->token;
            int status = uv_timer_init(&reactor->loop, &timer->handle);
            if (status != 0) { free(timer); complete(reactor, command->token, status, 0); }
            else {
                timer->handle.data = timer;
                timer->next = reactor->timers;
                reactor->timers = timer;
                status = uv_timer_start(&timer->handle, timer_fired, command->milliseconds, 0);
                if (status != 0) finish_timer(timer, status);
            }
        }
    }
    free(command->data);
}

static void awakened(uv_async_t *handle) {}
static void deadline_fired(uv_timer_t *handle) {}

/* Park the optional OS waiter before the owner consumes events in uv_run(). */
static void pause_poller(gp_reactor *reactor) {
    if (!reactor->poll_active) return;
    if (uv_sem_trywait(&reactor->poll_finished) != 0) {
        uv_async_send(&reactor->wakeup);
        uv_sem_wait(&reactor->poll_finished);
    }
    reactor->poll_active = 0;
    uv_loop_interrupt_suspend(&reactor->loop);
}

uint64_t gp_reactor_create(TruffleEnv *env, gp_completion completion, int embedded) {
    gp_reactor *reactor = calloc(1, sizeof(*reactor));
    if (reactor == NULL) return 0;
    if (uv_loop_init(&reactor->loop) != 0) { free(reactor); return 0; }
    if (uv_async_init(&reactor->loop, &reactor->wakeup, awakened) != 0) {
        uv_loop_close(&reactor->loop); free(reactor); return 0;
    }
    uv_timer_init(&reactor->loop, &reactor->deadline);
    if (embedded) {
        if (uv_sem_init(&reactor->poll_start, 0) != 0) goto failed;
        if (uv_sem_init(&reactor->poll_finished, 0) != 0) {
            uv_sem_destroy(&reactor->poll_start);
            goto failed;
        }
        if (uv_loop_configure(&reactor->loop, UV_LOOP_INTERRUPT_ON_IO_CHANGE) != 0) {
            uv_sem_destroy(&reactor->poll_start);
            uv_sem_destroy(&reactor->poll_finished);
            goto failed;
        }
        reactor->embedded = 1;
        uv_loop_interrupt_suspend(&reactor->loop);
    }
    reactor->wakeup.data = reactor;
    /* NFI's argument reference ends at return; the loop retains its own until drained. */
    (*env)->newClosureRef(env, (void *) completion);
    reactor->completion = completion;
    return (uint64_t) (uintptr_t) reactor;
failed:
    uv_close((uv_handle_t *) &reactor->deadline, NULL);
    uv_close((uv_handle_t *) &reactor->wakeup, NULL);
    uv_run(&reactor->loop, UV_RUN_DEFAULT);
    uv_loop_close(&reactor->loop);
    free(reactor);
    return 0;
}

int gp_reactor_step(uint64_t pointer, uint64_t timeout_ms) {
    gp_reactor *reactor = (gp_reactor *) (uintptr_t) pointer;
    pause_poller(reactor);
    uv_update_time(&reactor->loop);
    if (timeout_ms != 0) uv_timer_start(&reactor->deadline, deadline_fired, timeout_ms, 0);
    int alive = uv_run(&reactor->loop, timeout_ms == 0 ? UV_RUN_NOWAIT : UV_RUN_ONCE);
    if (timeout_ms != 0) uv_timer_stop(&reactor->deadline);
    return alive;
}

/* The poller observes readiness only. It never runs libuv callbacks or guest PHP.
 * Semaphore handoff publishes timeout/close state and prevents two consumers.
 * Windows reads the C libuv structure built with this shim, never guessed offsets. */
int gp_reactor_embed_wait(uint64_t pointer) {
    gp_reactor *reactor = (gp_reactor *) (uintptr_t) pointer;
    uv_sem_wait(&reactor->poll_start);
    if (reactor->poll_closing) return 0;
    int status = 1;
#if defined(_WIN32)
    DWORD bytes;
    ULONG_PTR key;
    OVERLAPPED *overlapped;
    BOOL result = GetQueuedCompletionStatus(reactor->loop.iocp, &bytes, &key, &overlapped,
            reactor->poll_timeout < 0 ? INFINITE : (DWORD) reactor->poll_timeout);
    if (overlapped != NULL) {
        if (!PostQueuedCompletionStatus(reactor->loop.iocp, bytes, key, overlapped)) status = UV_EIO;
    } else if (!result && GetLastError() != WAIT_TIMEOUT) status = UV_EIO;
#elif defined(__linux__)
    struct epoll_event event;
    if (epoll_wait(uv_backend_fd(&reactor->loop), &event, 1, reactor->poll_timeout) < 0 && errno != EINTR)
        status = -errno;
#else
    status = UV_ENOSYS;
#endif
    uv_sem_post(&reactor->poll_finished);
    return status;
}

void gp_reactor_embed_arm(uint64_t pointer) {
    gp_reactor *reactor = (gp_reactor *) (uintptr_t) pointer;
    if (reactor->poll_active || reactor->poll_closing) return;
    reactor->poll_timeout = uv_backend_timeout(&reactor->loop);
    uv_loop_interrupt_resume(&reactor->loop);
    reactor->poll_active = 1;
    uv_sem_post(&reactor->poll_start);
}

int gp_reactor_wakeup(uint64_t pointer) {
    gp_reactor *reactor = (gp_reactor *) (uintptr_t) pointer;
    return uv_async_send(&reactor->wakeup);
}

int gp_reactor_destroy(TruffleEnv *env, uint64_t pointer) {
    gp_reactor *reactor = (gp_reactor *) (uintptr_t) pointer;
    uv_run(&reactor->loop, UV_RUN_DEFAULT);
    int status = uv_loop_close(&reactor->loop);
    if (status == 0) {
        (*env)->releaseClosureRef(env, (void *) reactor->completion);
        if (reactor->embedded) {
            uv_sem_destroy(&reactor->poll_start);
            uv_sem_destroy(&reactor->poll_finished);
        }
        free(reactor);
    }
    return status;
}

static int execute(uint64_t pointer, gp_command *command) {
    gp_reactor *reactor = (gp_reactor *) (uintptr_t) pointer;
    process_command(reactor, command);
    return 0;
}
static int submit(uint64_t pointer, int kind, int64_t token, uint64_t milliseconds) {
    gp_command command = {0};
    command.kind = kind; command.token = token; command.milliseconds = milliseconds;
    return execute(pointer, &command);
}
int gp_reactor_timer(uint64_t reactor, int64_t token, uint64_t milliseconds) { return submit(reactor, GP_TIMER, token, milliseconds); }
int gp_reactor_cancel(uint64_t reactor, int64_t token) { return submit(reactor, GP_CANCEL, token, 0); }
int gp_reactor_stop(uint64_t pointer) {
    gp_reactor *reactor = (gp_reactor *) (uintptr_t) pointer;
    pause_poller(reactor);
    if (reactor->embedded) {
        reactor->poll_closing = 1;
        uv_sem_post(&reactor->poll_start);
    }
    return submit(pointer, GP_STOP, 0, 0);
}
int gp_reactor_curl(uint64_t reactor, int64_t token, uint64_t transfer) { return submit(reactor, GP_CURL, token, transfer); }
int gp_reactor_curl_multi(uint64_t reactor, int64_t token, int64_t group, int operation, uint64_t value, int64_t argument) {
    gp_command storage = {0};
    gp_command *command = &storage;
    command->kind = GP_CURL_MULTI;
    command->token = token;
    command->connection = group;
    command->operation = operation;
    command->milliseconds = value;
    command->argument = argument;
    return execute(reactor, command);
}
static int network_command(uint64_t reactor, int kind, int64_t token, int64_t connection, const void *data, int length, int port, int backlog) {
    if (length < 0 || length > 16 * 1024 * 1024) return UV_EINVAL;
    gp_command value = {0};
    gp_command *command = &value;
    command->kind = kind; command->token = token; command->connection = connection;
    command->port = port; command->backlog = backlog; command->length = length;
    if (data != NULL) {
        command->data = malloc((size_t) length + 1);
        if (command->data == NULL) return UV_ENOMEM;
        memcpy(command->data, data, (size_t) length);
        command->data[length] = 0;
    }
    return execute(reactor, command);
}
int gp_tcp_listen(uint64_t reactor, int64_t token, const char *host, int port, int backlog) { return network_command(reactor, GP_LISTEN, token, 0, host, (int) strlen(host), port, backlog); }
int gp_tcp_port(uint64_t reactor, int64_t token, int64_t connection) { return network_command(reactor, GP_PORT, token, connection, NULL, 0, 0, 0); }
int gp_tcp_accept(uint64_t reactor, int64_t token, int64_t connection) { return network_command(reactor, GP_ACCEPT, token, connection, NULL, 0, 0, 0); }
int gp_tcp_read(uint64_t reactor, int64_t token, int64_t connection) { return network_command(reactor, GP_READ, token, connection, NULL, 0, 0, 0); }
int gp_tcp_write(uint64_t reactor, int64_t token, int64_t connection, const unsigned char *bytes, int length) { return network_command(reactor, GP_WRITE, token, connection, bytes, length, 0, 0); }
int gp_tcp_close(uint64_t reactor, int64_t token, int64_t connection) { return network_command(reactor, GP_CLOSE, token, connection, NULL, 0, 0, 0); }
