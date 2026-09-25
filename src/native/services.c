#include "services.h"
#include "curl_reactor.h"
#include "curl_provider.h"
#include <sqlite3.h>
#include <curl/curl.h>
#include <uv.h>
#include <stdlib.h>
#include <string.h>

void gp_bytes_copy(uint64_t source, unsigned char *destination, int length) {
    if (length > 0) memcpy(destination, (const void *) (uintptr_t) source, (size_t) length);
}
#define DB(p) ((sqlite3 *) (uintptr_t) (p))
#define STMT(p) ((sqlite3_stmt *) (uintptr_t) (p))
uint64_t gp_sqlite_open(const char *path) {
    sqlite3 *database = NULL;
    /* Preserve the error-bearing handle on open failure, as SQLite specifies. */
    sqlite3_open_v2(path, &database, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, NULL);
    if (database != NULL) sqlite3_busy_timeout(database, 3000);
    return (uint64_t) (uintptr_t) database;
}
int gp_sqlite_close(uint64_t db) { return sqlite3_close_v2(DB(db)); }
int gp_sqlite_exec(uint64_t db, const char *sql) { return sqlite3_exec(DB(db), sql, NULL, NULL, NULL); }
const char *gp_sqlite_error(uint64_t db) { return sqlite3_errmsg(DB(db)); }
int gp_sqlite_busy_timeout(uint64_t db, int milliseconds) { return sqlite3_busy_timeout(DB(db), milliseconds); }
int64_t gp_sqlite_last_id(uint64_t db) { return sqlite3_last_insert_rowid(DB(db)); }
int gp_sqlite_changes(uint64_t db) { return sqlite3_changes(DB(db)); }
uint64_t gp_sqlite_prepare(uint64_t db, const char *sql) {
    sqlite3_stmt *statement = NULL;
    if (sqlite3_prepare_v2(DB(db), sql, -1, &statement, NULL) != SQLITE_OK) return 0;
    return (uint64_t) (uintptr_t) statement;
}
int gp_sqlite_finalize(uint64_t statement) { return sqlite3_finalize(STMT(statement)); }
int gp_sqlite_reset(uint64_t statement) {
    int status = sqlite3_reset(STMT(statement));
    sqlite3_clear_bindings(STMT(statement));
    return status;
}
int gp_sqlite_bind_index(uint64_t statement, const char *name) { return sqlite3_bind_parameter_index(STMT(statement), name); }
int gp_sqlite_bind_int(uint64_t statement, int index, int64_t value) { return sqlite3_bind_int64(STMT(statement), index, value); }
int gp_sqlite_bind_double(uint64_t statement, int index, double value) { return sqlite3_bind_double(STMT(statement), index, value); }
int gp_sqlite_bind_bytes(uint64_t statement, int index, const unsigned char *value, int length, int type) {
    static const unsigned char empty = 0;
    if (length == 0) value = &empty;
    return type == SQLITE_BLOB ? sqlite3_bind_blob(STMT(statement), index, value, length, SQLITE_TRANSIENT)
                              : sqlite3_bind_text(STMT(statement), index, (const char *) value, length, SQLITE_TRANSIENT);
}
int gp_sqlite_bind_null(uint64_t statement, int index) { return sqlite3_bind_null(STMT(statement), index); }
int gp_sqlite_step(uint64_t statement) { return sqlite3_step(STMT(statement)); }
int gp_sqlite_columns(uint64_t statement) { return sqlite3_column_count(STMT(statement)); }
const char *gp_sqlite_column_name(uint64_t statement, int column) { return sqlite3_column_name(STMT(statement), column); }
int gp_sqlite_column_type(uint64_t statement, int column) { return sqlite3_column_type(STMT(statement), column); }
int64_t gp_sqlite_column_int(uint64_t statement, int column) { return sqlite3_column_int64(STMT(statement), column); }
double gp_sqlite_column_double(uint64_t statement, int column) { return sqlite3_column_double(STMT(statement), column); }
int gp_sqlite_column_size(uint64_t statement, int column) { return sqlite3_column_bytes(STMT(statement), column); }
void gp_sqlite_column_copy(uint64_t statement, int column, unsigned char *destination, int length) {
    if (length > 0) memcpy(destination, sqlite3_column_blob(STMT(statement), column), (size_t) length);
}

typedef struct {
    CURL *easy;
    const gp_curl_provider *provider;
    int provider_id;
    struct curl_slist *headers;
    unsigned char *body;
    size_t length, capacity;
    char error[CURL_ERROR_SIZE];
    uv_mutex_t mutex;
    int cancelled;
    int references;
} gp_transfer;
#define TRANSFER(p) ((gp_transfer *) (uintptr_t) (p))
static uv_once_t curl_once[2] = { UV_ONCE_INIT, UV_ONCE_INIT };
static int curl_initialized[2];
static void initialize_standard(void) { curl_initialized[0] = gp_curl_standard.global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK; }
static void initialize_impersonate(void) { curl_initialized[1] = gp_curl_impersonation.global_init(CURL_GLOBAL_DEFAULT) == CURLE_OK; }
static size_t receive_body(char *data, size_t size, size_t count, void *pointer) {
    gp_transfer *transfer = pointer;
    if (size != 0 && count > SIZE_MAX / size) return 0;
    size_t length = size * count;
    /* A bounded response prevents one remote peer from exhausting the embedded runtime. */
    if (length > 16 * 1024 * 1024 - transfer->length) return 0;
    if (transfer->length + length > transfer->capacity) {
        size_t capacity = (transfer->length + length) * 2 + 4096;
        void *buffer = realloc(transfer->body, capacity);
        if (buffer == NULL) return 0;
        transfer->body = buffer;
        transfer->capacity = capacity;
    }
    memcpy(transfer->body + transfer->length, data, length);
    transfer->length += length;
    return length;
}
static int transfer_progress(void *pointer, curl_off_t a, curl_off_t b, curl_off_t c, curl_off_t d) {
    gp_transfer *transfer = pointer;
    uv_mutex_lock(&transfer->mutex);
    int cancelled = transfer->cancelled;
    uv_mutex_unlock(&transfer->mutex);
    return cancelled;
}
uint64_t gp_curl_create(void) { return gp_curl_create_provider(1); }
uint64_t gp_curl_create_provider(int provider) {
    if (!gp_curl_initialize_provider(provider)) return 0;
    gp_transfer *transfer = calloc(1, sizeof(*transfer));
    if (transfer == NULL) return 0;
    transfer->provider = gp_curl_provider_get(provider);
    transfer->provider_id = provider;
    transfer->easy = transfer->provider->easy_init();
    if (transfer->easy == NULL || uv_mutex_init(&transfer->mutex) != 0) {
        if (transfer->easy != NULL) transfer->provider->easy_cleanup(transfer->easy);
        free(transfer); return 0;
    }
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_WRITEFUNCTION, receive_body);
    transfer->references = 1;
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_WRITEDATA, transfer);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_ERRORBUFFER, transfer->error);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_XFERINFOFUNCTION, transfer_progress);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_XFERINFODATA, transfer);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_NOPROGRESS, 0L);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_NOSIGNAL, 1L);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_TIMEOUT_MS, 10000L);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_CONNECTTIMEOUT_MS, 5000L);
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_PROTOCOLS_STR, "http,https");
    transfer->provider->easy_setopt(transfer->easy, CURLOPT_REDIR_PROTOCOLS_STR, "http,https");
    return (uint64_t) (uintptr_t) transfer;
}
void gp_curl_close(uint64_t pointer) {
    gp_transfer *transfer = TRANSFER(pointer);
    uv_mutex_lock(&transfer->mutex);
    int references = --transfer->references;
    uv_mutex_unlock(&transfer->mutex);
    if (references != 0) return;
    transfer->provider->easy_cleanup(transfer->easy);
    transfer->provider->slist_free_all(transfer->headers);
    free(transfer->body);
    uv_mutex_destroy(&transfer->mutex);
    free(transfer);
}
int gp_curl_initialize_provider(int provider) {
    if (gp_curl_provider_get(provider) == NULL) return 0;
    uv_once(&curl_once[provider], provider == 0 ? initialize_standard : initialize_impersonate);
    return curl_initialized[provider];
}
int gp_curl_provider_id(uint64_t transfer) { return TRANSFER(transfer)->provider_id; }
void gp_curl_retain(uint64_t pointer) {
    gp_transfer *transfer = TRANSFER(pointer);
    uv_mutex_lock(&transfer->mutex);
    transfer->references++;
    uv_mutex_unlock(&transfer->mutex);
}
const char *gp_curl_multi_strerror(int code) { return curl_multi_strerror((CURLMcode) code); }
int gp_curl_option_long(uint64_t transfer, int option, int64_t value) { return TRANSFER(transfer)->provider->easy_setopt(TRANSFER(transfer)->easy, (CURLoption) option, (long) value); }
int gp_curl_option_string(uint64_t transfer, int option, const char *value) { return TRANSFER(transfer)->provider->easy_setopt(TRANSFER(transfer)->easy, (CURLoption) option, value); }
int gp_curl_header(uint64_t pointer, const char *header) {
    gp_transfer *transfer = TRANSFER(pointer);
    if (header == NULL || *header == 0) {
        transfer->provider->slist_free_all(transfer->headers);
        transfer->headers = NULL;
    } else {
        struct curl_slist *headers = transfer->provider->slist_append(transfer->headers, header);
        if (headers == NULL) return CURLE_OUT_OF_MEMORY;
        transfer->headers = headers;
    }
    return transfer->provider->easy_setopt(transfer->easy, CURLOPT_HTTPHEADER, transfer->headers);
}
int gp_curl_impersonate(uint64_t pointer, const char *profile, int headers) {
    gp_transfer *transfer = TRANSFER(pointer);
    if (transfer->provider->impersonate == NULL) {
        strcpy(transfer->error, "curl_impersonate requires an impersonate provider handle");
        return CURLE_NOT_BUILT_IN;
    }
    return transfer->provider->impersonate(transfer->easy, profile, headers);
}
CURL *gp_curl_begin(uint64_t pointer) {
    gp_transfer *transfer = TRANSFER(pointer);
    transfer->length = 0;
    transfer->error[0] = 0;
    gp_curl_reset_cancel(pointer);
    return transfer->easy;
}
void gp_curl_finish(uint64_t pointer, CURLcode status) {
    gp_transfer *transfer = TRANSFER(pointer);
    if (status != CURLE_OK && transfer->error[0] == 0) {
        strncpy(transfer->error, transfer->provider->easy_strerror(status), sizeof(transfer->error) - 1);
    }
}
int gp_curl_perform(uint64_t pointer) {
    gp_transfer *transfer = TRANSFER(pointer);
    transfer->length = 0;
    transfer->error[0] = 0;
    CURLcode status = transfer->provider->easy_perform(transfer->easy);
    gp_curl_finish(pointer, status);
    return status;
}
void gp_curl_cancel(uint64_t pointer) {
    gp_transfer *transfer = TRANSFER(pointer);
    uv_mutex_lock(&transfer->mutex);
    transfer->cancelled = 1;
    uv_mutex_unlock(&transfer->mutex);
}
void gp_curl_reset_cancel(uint64_t pointer) {
    gp_transfer *transfer = TRANSFER(pointer);
    uv_mutex_lock(&transfer->mutex);
    transfer->cancelled = 0;
    uv_mutex_unlock(&transfer->mutex);
}
int gp_curl_size(uint64_t transfer) { return (int) TRANSFER(transfer)->length; }
void gp_curl_copy(uint64_t transfer, unsigned char *destination, int length) { if (length > 0) memcpy(destination, TRANSFER(transfer)->body, (size_t) length); }
const char *gp_curl_error(uint64_t transfer) { return TRANSFER(transfer)->error; }
int64_t gp_curl_info_long(uint64_t transfer, int info) {
    long value = 0;
    TRANSFER(transfer)->provider->easy_getinfo(TRANSFER(transfer)->easy, (CURLINFO) info, &value);
    return (int64_t) value;
}
const char *gp_curl_info_string(uint64_t transfer, int info) {
    char *value = NULL;
    TRANSFER(transfer)->provider->easy_getinfo(TRANSFER(transfer)->easy, (CURLINFO) info, &value);
    return value == NULL ? "" : value;
}
const char *gp_curl_version(void) { return gp_curl_version_provider(1); }
const char *gp_curl_version_provider(int provider) {
    const gp_curl_provider *api = gp_curl_provider_get(provider);
    return api == NULL ? "" : api->version();
}
