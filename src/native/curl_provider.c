#include <stdint.h>
#include "curl_provider.h"
const gp_curl_provider gp_curl_impersonation = { GP_CURL_FUNCTIONS, curl_easy_impersonate };
const gp_curl_provider *gp_curl_provider_get(int provider) {
    return provider == 0 ? &gp_curl_standard : provider == 1 ? &gp_curl_impersonation : NULL;
}
