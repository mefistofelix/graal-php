#include <stdint.h>
#include <string.h>
#include <sqlite3.h>
#include <pcre2.h>
#include <zlib.h>
#include <uv.h>
#include <trufflenfi.h>

#if defined(GP_STATIC)
#define GP_EXPORT
#elif defined(_WIN32)
#define GP_EXPORT __declspec(dllexport)
#else
#define GP_EXPORT __attribute__((visibility("default")))
#endif

/* Small ownership boundaries: native allocations never masquerade as PHP values. */
GP_EXPORT const char *gp_sqlite_version(void) { return sqlite3_libversion(); }
GP_EXPORT const char *gp_uv_version(void) { return uv_version_string(); }
GP_EXPORT int64_t gp_crc32(const char *text) {
    return (int64_t) crc32_z(0, (const unsigned char *) text, strlen(text));
}
GP_EXPORT int gp_regex_match(const char *pattern, const char *text) {
    int error;
    PCRE2_SIZE offset;
    pcre2_code *code = pcre2_compile((PCRE2_SPTR) pattern, PCRE2_ZERO_TERMINATED, 0, &error, &offset, NULL);
    if (code == NULL) return -1;
    pcre2_match_data *match = pcre2_match_data_create_from_pattern(code, NULL);
    if (match == NULL) { pcre2_code_free(code); return -2; }
    int result = pcre2_match(code, (PCRE2_SPTR) text, strlen(text), 0, 0, match, NULL);
    pcre2_match_data_free(match);
    pcre2_code_free(code);
    return result >= 0 ? 1 : result == PCRE2_ERROR_NOMATCH ? 0 : -2;
}
GP_EXPORT int gp_sqlite_scalar(const char *sql, int64_t (*result)(int64_t)) {
    sqlite3 *database = NULL;
    sqlite3_stmt *statement = NULL;
    int status = sqlite3_open(":memory:", &database);
    if (status == SQLITE_OK) status = sqlite3_prepare_v2(database, sql, -1, &statement, NULL);
    if (status == SQLITE_OK) {
        status = sqlite3_step(statement);
        if (status == SQLITE_ROW) { result(sqlite3_column_int64(statement, 0)); status = SQLITE_OK; }
    }
    sqlite3_finalize(statement);
    sqlite3_close(database);
    return status;
}
GP_EXPORT int64_t gp_callback(int64_t (*function)(int64_t), int64_t value) { return function(value); }
GP_EXPORT int64_t gp_sleep_echo(unsigned int milliseconds, int64_t value) {
    uv_sleep(milliseconds);
    return value;
}
GP_EXPORT int64_t gp_thread_id(void) {
#ifdef _WIN32
    return (int64_t) GetCurrentThreadId();
#else
    return (int64_t) (uintptr_t) uv_thread_self();
#endif
}

typedef struct {
    TruffleContext *context;
    int64_t (*function)(TruffleEnv *, int64_t);
    int64_t value;
    int64_t result;
} gp_callback_work;

static void gp_callback_worker(void *pointer) {
    gp_callback_work *work = (gp_callback_work *) pointer;
    TruffleEnv *env = (*work->context)->attachCurrentThread(work->context);
    if (env == NULL) { work->result = -3; return; }
    work->result = work->function(env, work->value);
    (*work->context)->detachCurrentThread(work->context);
}

GP_EXPORT int64_t gp_callback_thread(TruffleEnv *env, int64_t (*function)(TruffleEnv *, int64_t), int64_t value) {
    gp_callback_work work = { (*env)->getTruffleContext(env), function, value, 0 };
    uv_thread_t thread;
    if (uv_thread_create(&thread, gp_callback_worker, &work) != 0) return -1;
    if (uv_thread_join(&thread) != 0) return -2;
    return work.result;
}

/* A scoped catalog makes linked archives available without dlopen or executable exports. */
#include "builtins.h"
#include "reactor.h"
#include "services.h"
#include "ffi_bridge.h"
#define GP_SYMBOL(name) if (strcmp(symbol, #name) == 0) return (uint64_t) (uintptr_t) &name
GP_EXPORT uint64_t gp_builtin_lookup(const char *library, const char *symbol) {
    if (strcmp(library, "sqlite3") == 0) {
        GP_SYMBOL(sqlite3_libversion_number); GP_SYMBOL(sqlite3_threadsafe);
        GP_SYMBOL(sqlite3_compileoption_used); GP_SYMBOL(gp_sqlite_scalar);
        GP_SYMBOL(gp_sqlite_open); GP_SYMBOL(gp_sqlite_close); GP_SYMBOL(gp_sqlite_exec);
        GP_SYMBOL(gp_sqlite_error); GP_SYMBOL(gp_sqlite_busy_timeout); GP_SYMBOL(gp_sqlite_last_id); GP_SYMBOL(gp_sqlite_changes);
        GP_SYMBOL(gp_sqlite_prepare); GP_SYMBOL(gp_sqlite_finalize); GP_SYMBOL(gp_sqlite_reset);
        GP_SYMBOL(gp_sqlite_bind_index); GP_SYMBOL(gp_sqlite_bind_int); GP_SYMBOL(gp_sqlite_bind_double);
        GP_SYMBOL(gp_sqlite_bind_bytes); GP_SYMBOL(gp_sqlite_bind_null);
        GP_SYMBOL(gp_sqlite_step); GP_SYMBOL(gp_sqlite_columns); GP_SYMBOL(gp_sqlite_column_name);
        GP_SYMBOL(gp_sqlite_column_type); GP_SYMBOL(gp_sqlite_column_int); GP_SYMBOL(gp_sqlite_column_double);
        GP_SYMBOL(gp_sqlite_column_size); GP_SYMBOL(gp_sqlite_column_copy);
    } else if (strcmp(library, "curl") == 0) {
        GP_SYMBOL(gp_curl_create_provider); GP_SYMBOL(gp_curl_version_provider);
        GP_SYMBOL(gp_curl_create); GP_SYMBOL(gp_curl_close); GP_SYMBOL(gp_curl_option_long);
        GP_SYMBOL(gp_curl_option_string); GP_SYMBOL(gp_curl_header); GP_SYMBOL(gp_curl_impersonate);
        GP_SYMBOL(gp_curl_perform); GP_SYMBOL(gp_curl_cancel); GP_SYMBOL(gp_curl_size);
        GP_SYMBOL(gp_curl_reset_cancel);
        GP_SYMBOL(gp_curl_copy); GP_SYMBOL(gp_curl_error); GP_SYMBOL(gp_curl_info_long);
        GP_SYMBOL(gp_curl_info_string); GP_SYMBOL(gp_curl_version);
        GP_SYMBOL(gp_curl_multi_strerror);
    } else if (strcmp(library, "zlib") == 0) {
        GP_SYMBOL(zlibCompileFlags); GP_SYMBOL(gp_crc32);
    } else if (strcmp(library, "pcre2") == 0) {
        GP_SYMBOL(gp_regex_match);
    } else if (strcmp(library, "libuv") == 0) {
        GP_SYMBOL(uv_version); GP_SYMBOL(uv_sleep); GP_SYMBOL(uv_available_parallelism);
    } else if (strcmp(library, "runtime") == 0) {
        GP_SYMBOL(gp_ffi_create); GP_SYMBOL(gp_ffi_set_i); GP_SYMBOL(gp_ffi_set_d); GP_SYMBOL(gp_ffi_set_s);
        GP_SYMBOL(gp_ffi_step); GP_SYMBOL(gp_ffi_count); GP_SYMBOL(gp_ffi_get_i); GP_SYMBOL(gp_ffi_get_d);
        GP_SYMBOL(gp_ffi_get_s); GP_SYMBOL(gp_ffi_abort); GP_SYMBOL(gp_ffi_close);
        GP_SYMBOL(gp_sleep_echo); GP_SYMBOL(gp_thread_id); GP_SYMBOL(gp_callback);
        GP_SYMBOL(gp_callback_thread);
        GP_SYMBOL(gp_reactor_create); GP_SYMBOL(gp_reactor_step); GP_SYMBOL(gp_reactor_destroy);
        GP_SYMBOL(gp_reactor_wakeup); GP_SYMBOL(gp_reactor_embed_wait); GP_SYMBOL(gp_reactor_embed_arm);
        GP_SYMBOL(gp_reactor_timer);
        GP_SYMBOL(gp_reactor_cancel); GP_SYMBOL(gp_reactor_stop);
        GP_SYMBOL(gp_reactor_curl);
        GP_SYMBOL(gp_reactor_curl_multi);
        GP_SYMBOL(gp_tcp_listen); GP_SYMBOL(gp_tcp_port); GP_SYMBOL(gp_tcp_accept);
        GP_SYMBOL(gp_tcp_read); GP_SYMBOL(gp_tcp_write); GP_SYMBOL(gp_tcp_close); GP_SYMBOL(gp_bytes_copy);
    }
    return 0;
}
