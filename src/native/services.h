#ifndef GRAALPHP_SERVICES_H
#define GRAALPHP_SERVICES_H
#include "reactor.h"
GP_REACTOR_API void gp_bytes_copy(uint64_t source, unsigned char *destination, int length);
GP_REACTOR_API uint64_t gp_sqlite_open(const char *path);
GP_REACTOR_API int gp_sqlite_close(uint64_t db);
GP_REACTOR_API int gp_sqlite_exec(uint64_t db, const char *sql);
GP_REACTOR_API const char *gp_sqlite_error(uint64_t db);
GP_REACTOR_API int gp_sqlite_busy_timeout(uint64_t db, int milliseconds);
GP_REACTOR_API int64_t gp_sqlite_last_id(uint64_t db);
GP_REACTOR_API int gp_sqlite_changes(uint64_t db);
GP_REACTOR_API uint64_t gp_sqlite_prepare(uint64_t db, const char *sql);
GP_REACTOR_API int gp_sqlite_finalize(uint64_t statement);
GP_REACTOR_API int gp_sqlite_reset(uint64_t statement);
GP_REACTOR_API int gp_sqlite_bind_index(uint64_t statement, const char *name);
GP_REACTOR_API int gp_sqlite_bind_int(uint64_t statement, int index, int64_t value);
GP_REACTOR_API int gp_sqlite_bind_double(uint64_t statement, int index, double value);
GP_REACTOR_API int gp_sqlite_bind_bytes(uint64_t statement, int index, const unsigned char *value, int length, int type);
GP_REACTOR_API int gp_sqlite_bind_null(uint64_t statement, int index);
GP_REACTOR_API int gp_sqlite_step(uint64_t statement);
GP_REACTOR_API int gp_sqlite_columns(uint64_t statement);
GP_REACTOR_API const char *gp_sqlite_column_name(uint64_t statement, int column);
GP_REACTOR_API int gp_sqlite_column_type(uint64_t statement, int column);
GP_REACTOR_API int64_t gp_sqlite_column_int(uint64_t statement, int column);
GP_REACTOR_API double gp_sqlite_column_double(uint64_t statement, int column);
GP_REACTOR_API int gp_sqlite_column_size(uint64_t statement, int column);
GP_REACTOR_API void gp_sqlite_column_copy(uint64_t statement, int column, unsigned char *destination, int length);
GP_REACTOR_API uint64_t gp_curl_create(void);
GP_REACTOR_API uint64_t gp_curl_create_provider(int provider);
GP_REACTOR_API void gp_curl_close(uint64_t transfer);
GP_REACTOR_API int gp_curl_option_long(uint64_t transfer, int option, int64_t value);
GP_REACTOR_API int gp_curl_option_string(uint64_t transfer, int option, const char *value);
GP_REACTOR_API int gp_curl_header(uint64_t transfer, const char *header);
GP_REACTOR_API int gp_curl_impersonate(uint64_t transfer, const char *profile, int headers);
GP_REACTOR_API int gp_curl_perform(uint64_t transfer);
GP_REACTOR_API void gp_curl_cancel(uint64_t transfer);
GP_REACTOR_API void gp_curl_reset_cancel(uint64_t transfer);
GP_REACTOR_API int gp_curl_size(uint64_t transfer);
GP_REACTOR_API void gp_curl_copy(uint64_t transfer, unsigned char *destination, int length);
GP_REACTOR_API const char *gp_curl_error(uint64_t transfer);
GP_REACTOR_API int64_t gp_curl_info_long(uint64_t transfer, int info);
GP_REACTOR_API const char *gp_curl_info_string(uint64_t transfer, int info);
GP_REACTOR_API const char *gp_curl_version(void);
GP_REACTOR_API const char *gp_curl_version_provider(int provider);
GP_REACTOR_API const char *gp_curl_multi_strerror(int code);
#endif
