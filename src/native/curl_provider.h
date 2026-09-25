#ifndef GRAALPHP_CURL_PROVIDER_H
#define GRAALPHP_CURL_PROVIDER_H
#include <stdint.h>
#include <curl/curl.h>

/* Both libcurl versions retain this public ABI. An easy/multi/slist is always
 * operated on by the table that created it; their private structs never cross. */
typedef struct {
    CURLcode (*global_init)(long);
    CURL *(*easy_init)(void);
    void (*easy_cleanup)(CURL *);
    CURLcode (*easy_setopt)(CURL *, CURLoption, ...);
    CURLcode (*easy_getinfo)(CURL *, CURLINFO, ...);
    CURLcode (*easy_perform)(CURL *);
    const char *(*easy_strerror)(CURLcode);
    struct curl_slist *(*slist_append)(struct curl_slist *, const char *);
    void (*slist_free_all)(struct curl_slist *);
    char *(*version)(void);
    CURLM *(*multi_init)(void);
    CURLMcode (*multi_cleanup)(CURLM *);
    CURLMcode (*multi_setopt)(CURLM *, CURLMoption, ...);
    CURLMcode (*multi_add_handle)(CURLM *, CURL *);
    CURLMcode (*multi_remove_handle)(CURLM *, CURL *);
    CURLMcode (*multi_socket_action)(CURLM *, curl_socket_t, int, int *);
    CURLMcode (*multi_assign)(CURLM *, curl_socket_t, void *);
    CURLMsg *(*multi_info_read)(CURLM *, int *);
    CURLcode (*impersonate)(CURL *, const char *, int);
} gp_curl_provider;

#define GP_CURL_FUNCTIONS \
    curl_global_init, curl_easy_init, curl_easy_cleanup, curl_easy_setopt, \
    curl_easy_getinfo, curl_easy_perform, curl_easy_strerror, \
    curl_slist_append, curl_slist_free_all, curl_version, curl_multi_init, \
    curl_multi_cleanup, curl_multi_setopt, curl_multi_add_handle, \
    curl_multi_remove_handle, curl_multi_socket_action, curl_multi_assign, curl_multi_info_read

/* Stable IDs: 0 = ordinary upstream curl, 1 = curl-impersonate. */
extern const gp_curl_provider gp_curl_standard;
extern const gp_curl_provider gp_curl_impersonation;
const gp_curl_provider *gp_curl_provider_get(int provider);
int gp_curl_provider_id(uint64_t transfer);
int gp_curl_initialize_provider(int provider);
#endif
