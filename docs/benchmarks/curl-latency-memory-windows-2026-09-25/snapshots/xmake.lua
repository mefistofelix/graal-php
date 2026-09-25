-- Adapted from php-xmake f6526d592bdcedf8b43fb2b5f2d834df058ef713.
-- Keep the target-owned fetch/materialization model; see docs/native-build.md.
set_project("graalphp-native")
if is_plat("windows") then
    set_toolchains("msvc")
    set_runtimes("MD")
    set_config("sdk", path.absolute("build/toolchain"))
else
    set_toolchains("gcc")
    add_cflags("-fPIC")
end
set_kind("static")
set_targetdir("build/native/lib")
set_config("builddir", "build/native/objects")
option("curl_costs")
    set_default(false)
    set_showmenu(true)
    set_description("Instrument native cURL timings for diagnostics")
option_end()

target("native-stack-probe")
    set_default(false)
    set_kind("shared")
    set_targetdir("build")
    set_languages("c11")
    add_files("tests/native/native_stack_probe.c")
    if not is_plat("windows") then add_syslinks("pthread") end

target("ffi-bridge-fixture")
    set_default(false)
    set_kind("shared")
    set_targetdir("build")
    set_languages("c11")
    add_files("tests/native/ffi_bridge_fixture.c")
    if not is_plat("windows") then add_syslinks("pthread") end

target("curl-native-benchmark")
    set_default(false)
    set_kind("binary")
    set_targetdir("build")
    set_languages("c11")
    set_optimize("fastest")
    add_defines("GP_STATIC")
    add_files("tests/native/curl_benchmark.c")
    add_includedirs("src/native", "$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/include")
    add_deps("graalphp-builtins")

target("zlib")
    before_sources(function () os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://madler/zlib?ref=v1.3.2", "build/native/deps/zlib"}) end)
    add_includedirs("build/native/deps/zlib", {public = true})
    add_defines("ZLIB_BUILD")
    if is_plat("windows") then
        add_defines("NO_FSEEKO", "_CRT_SECURE_NO_DEPRECATE", "_CRT_NONSTDC_NO_DEPRECATE")
    else
        add_defines("HAVE_UNISTD_H", "_LARGEFILE64_SOURCE=1")
    end
    add_files("build/native/deps/zlib/*.c")

target("pcre2")
    before_sources(function () os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://PCRE2Project/pcre2?ref=pcre2-10.44", "build/native/deps/pcre2"}) end)
    set_optimize("fastest")
    add_includedirs("build/native/deps/pcre2/src", {public = true})
    add_defines("PCRE2_CODE_UNIT_WIDTH=8", "PCRE2_STATIC", {public = true})
    add_defines("HAVE_CONFIG_H")
    add_files("build/native/deps/pcre2/src/pcre2.h.generic", {source = function (sourcefile, sources, depend)
        local output = "build/native/deps/pcre2/src/pcre2.h"
        io.writefile(output, io.readfile(sourcefile))
        table.insert(depend.files, output)
        table.remove(sources, 1)
    end})
    add_files("build/native/deps/pcre2/src/config.h.generic", {source = function (_, sources, depend)
        local output = "build/native/deps/pcre2/src/config.h"
        io.writefile(output, [[#define HAVE_MEMMOVE 1
#ifdef _WIN32
#define HAVE_WINDOWS_H 1
#else
#define HAVE_UNISTD_H 1
#endif
#define SUPPORT_PCRE2_8 1
#define SUPPORT_UNICODE 1
#define SUPPORT_JIT 1
#define PCRE2_EXPORT
#define HEAP_LIMIT 20000000
#define LINK_SIZE 2
#define MATCH_LIMIT 10000000
#define MATCH_LIMIT_DEPTH MATCH_LIMIT
#define MAX_NAME_COUNT 10000
#define MAX_NAME_SIZE 128
#define MAX_VARLOOKBEHIND 255
#define NEWLINE_DEFAULT 2
#define PARENS_NEST_LIMIT 250
]])
        table.insert(depend.files, output)
        table.remove(sources, 1)
    end})
    add_files("build/native/deps/pcre2/src/pcre2_chartables.c.dist", {source = function (sourcefile, sources, depend)
        local output = "build/native/deps/pcre2/src/pcre2_chartables.c"
        io.writefile(output, io.readfile(sourcefile))
        sources[1] = output
        table.insert(depend.files, output)
    end})
    add_files(
        "build/native/deps/pcre2/src/pcre2_auto_possess.c",
        "build/native/deps/pcre2/src/pcre2_chkdint.c",
        "build/native/deps/pcre2/src/pcre2_compile.c",
        "build/native/deps/pcre2/src/pcre2_config.c",
        "build/native/deps/pcre2/src/pcre2_context.c",
        "build/native/deps/pcre2/src/pcre2_convert.c",
        "build/native/deps/pcre2/src/pcre2_dfa_match.c",
        "build/native/deps/pcre2/src/pcre2_error.c",
        "build/native/deps/pcre2/src/pcre2_extuni.c",
        "build/native/deps/pcre2/src/pcre2_find_bracket.c",
        "build/native/deps/pcre2/src/pcre2_jit_compile.c",
        "build/native/deps/pcre2/src/pcre2_maketables.c",
        "build/native/deps/pcre2/src/pcre2_match.c",
        "build/native/deps/pcre2/src/pcre2_match_data.c",
        "build/native/deps/pcre2/src/pcre2_newline.c",
        "build/native/deps/pcre2/src/pcre2_ord2utf.c",
        "build/native/deps/pcre2/src/pcre2_pattern_info.c",
        "build/native/deps/pcre2/src/pcre2_script_run.c",
        "build/native/deps/pcre2/src/pcre2_serialize.c",
        "build/native/deps/pcre2/src/pcre2_string_utils.c",
        "build/native/deps/pcre2/src/pcre2_study.c",
        "build/native/deps/pcre2/src/pcre2_substitute.c",
        "build/native/deps/pcre2/src/pcre2_substring.c",
        "build/native/deps/pcre2/src/pcre2_tables.c",
        "build/native/deps/pcre2/src/pcre2_ucd.c",
        "build/native/deps/pcre2/src/pcre2_valid_utf.c",
        "build/native/deps/pcre2/src/pcre2_xclass.c"
    )


target("sqlite3")
    before_sources(function () os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"-delpathseg", "1", "https://www.sqlite.org/2026/sqlite-amalgamation-3530200.zip", "build/native/deps/sqlite3"}) end)
    set_optimize("fastest")
    add_includedirs("build/native/deps/sqlite3", {public = true})
    add_defines(
        "SQLITE_ENABLE_COLUMN_METADATA",
        "SQLITE_ENABLE_FTS3",
        "SQLITE_ENABLE_FTS4",
        "SQLITE_ENABLE_FTS5"
    )
    add_files("build/native/deps/sqlite3/sqlite3.c")
    if is_plat("linux") then
        add_syslinks("pthread", "dl", "m", {public = true})
    end


target("libuv")
    before_sources(function () os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://libuv/libuv?ref=v1.52.1", "build/native/deps/libuv"}) end)
    set_optimize("fastest")
    add_files("src/native/patches/libuv-electron.patch", {source = function (sourcefile, sources, depend)
        local root = "build/native/deps/libuv"
        if not try {function () os.runv("git", {"apply", "-p3", "--directory=" .. root, "--reverse", "--check", sourcefile}); return true end} then
            os.runv("git", {"apply", "-p3", "--directory=" .. root, sourcefile})
        end
        for output in io.readfile(sourcefile):gmatch("\n%+%+%+ b/deps/uv/([^\r\n]+)") do table.insert(depend.files, path.join(root, output)) end
        local output = "build/native/deps/libuv/libuv/uv.h"
        os.mkdir(path.directory(output))
        os.cp(root .. "/include/uv.h", output)
        table.insert(depend.files, output)
        table.remove(sources, 1)
    end})
    add_includedirs("build/native/deps/libuv/include", "build/native/deps/libuv", {public = true})
    add_includedirs("build/native/deps/libuv/src")
    add_files("build/native/deps/libuv/src/*.c")
    if is_plat("windows") then
        add_defines(
            "WIN32_LEAN_AND_MEAN",
            "_CRT_DECLARE_NONSTDC_NAMES=0",
            "_WIN32_WINNT=0x0A00"
        )
        add_cflags("/we4013", {force = true})
        add_syslinks(
            "psapi",
            "user32",
            "advapi32",
            "iphlpapi",
            "userenv",
            "ws2_32",
            "dbghelp",
            "ole32",
            "shell32",
            {public = true}
        )
            add_files("build/native/deps/libuv/src/win/*.c")
    elseif is_plat("linux") then
        set_basename("uv")
        add_defines("_GNU_SOURCE", "_FILE_OFFSET_BITS=64", "_LARGEFILE_SOURCE")
        add_includedirs("build/native/deps/libuv/src/unix")
        add_syslinks("pthread", "dl", "rt", {public = true})
        add_files(
            "build/native/deps/libuv/src/unix/async.c",
            "build/native/deps/libuv/src/unix/core.c",
            "build/native/deps/libuv/src/unix/dl.c",
            "build/native/deps/libuv/src/unix/fs.c",
            "build/native/deps/libuv/src/unix/getaddrinfo.c",
            "build/native/deps/libuv/src/unix/getnameinfo.c",
            "build/native/deps/libuv/src/unix/loop-watcher.c",
            "build/native/deps/libuv/src/unix/loop.c",
            "build/native/deps/libuv/src/unix/pipe.c",
            "build/native/deps/libuv/src/unix/poll.c",
            "build/native/deps/libuv/src/unix/process.c",
            "build/native/deps/libuv/src/unix/random-devurandom.c",
            "build/native/deps/libuv/src/unix/signal.c",
            "build/native/deps/libuv/src/unix/stream.c",
            "build/native/deps/libuv/src/unix/tcp.c",
            "build/native/deps/libuv/src/unix/thread.c",
            "build/native/deps/libuv/src/unix/tty.c",
            "build/native/deps/libuv/src/unix/udp.c",
            "build/native/deps/libuv/src/unix/linux.c",
            "build/native/deps/libuv/src/unix/procfs-exepath.c",
            "build/native/deps/libuv/src/unix/proctitle.c",
            "build/native/deps/libuv/src/unix/random-getrandom.c",
            "build/native/deps/libuv/src/unix/random-sysctl-linux.c"
        )
    end


-- Each curl provider owns its curl and TLS symbols. The unpatched provider can
-- advance independently; curl-impersonate retains its complete pinned bundle.
target("brotli")
    before_sources(function () os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://google/brotli?ref=v1.2.0", "build/native/deps/brotli"}) end)
    add_includedirs("build/native/deps/brotli/c/include", {public = true})
    add_files("build/native/deps/brotli/c/common/*.c", "build/native/deps/brotli/c/dec/*.c")

target("zstd")
    before_sources(function () os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://facebook/zstd?ref=v1.5.7", "build/native/deps/zstd"}) end)
    add_includedirs("build/native/deps/zstd/lib", {public = true})
    add_defines("ZSTD_DISABLE_ASM", "ZSTD_LEGACY_SUPPORT=0")
    add_files("build/native/deps/zstd/lib/common/*.c", "build/native/deps/zstd/lib/decompress/*.c")

target("boringssl")
    add_rules("utils.inherit.links")
    before_sources(function ()
        os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://lexiforest/curl-impersonate?ref=v2.2.2", "build/native/deps/curl-impersonate"})
        os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://google/boringssl?ref=156c7b75ae9b8c3b3f847acf264f17594c3859fb", "build/native/deps/boringssl"})
    end)
    set_languages("c11", "cxx17")
    set_optimize("fastest")
    add_includedirs("build/native/deps/boringssl/include", {public = true})
    add_defines("BORINGSSL_IMPLEMENTATION", "OPENSSL_NO_ASM", "NDEBUG")
    if is_plat("windows") then
        add_defines("_HAS_EXCEPTIONS=0", "WIN32_LEAN_AND_MEAN", "NOMINMAX", "_CRT_SECURE_NO_WARNINGS")
        add_cxflags("/utf-8", "/Zc:__cplusplus", "/permissive-", {force = true})
        add_syslinks("ws2_32", "advapi32", "bcrypt", {public = true})
    else
        add_cxxflags("-fPIC", "-fno-exceptions", "-fno-rtti")
        add_syslinks("pthread", {public = true})
    end
    add_files("build/native/deps/curl-impersonate/patches/boringssl.patch", {source = function (sourcefile, sources, depend)
        local root = "build/native/deps/boringssl"
        if not try {function () os.runv("git", {"apply", "--directory=" .. root, "--reverse", "--check", sourcefile}); return true end} then
            os.runv("git", {"apply", "--directory=" .. root, sourcefile})
        end
        for output in io.readfile(sourcefile):gmatch("\n%+%+%+ b/([^\r\n]+)") do table.insert(depend.files, path.join(root, output)) end
        import("core.base.json")
        local manifest = json.loadfile(root .. "/gen/sources.json")
        table.insert(depend.files, root .. "/gen/sources.json")
        table.remove(sources, 1)
        for _, component in ipairs({"bcm", "crypto", "ssl"}) do
            for _, file in ipairs(manifest[component].srcs) do table.insert(sources, path.join(root, file)) end
        end
    end})

target("impersonate-nghttp2")
    before_sources(function () os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://nghttp2/nghttp2?ref=v1.63.0", "build/native/deps/impersonate-nghttp2"}) end)
    add_includedirs("build/native/deps/impersonate-nghttp2/lib/includes", {public = true})
    add_defines("NGHTTP2_STATICLIB", "BUILDING_NGHTTP2")
    if is_plat("windows") then add_defines("HAVE_GETTICKCOUNT64", "HAVE_WINDOWS_H", "ssize_t=int") end
    add_files("build/native/deps/impersonate-nghttp2/lib/includes/nghttp2/nghttp2ver.h.in", {source = function (sourcefile, sources, depend)
        local output = sourcefile:gsub("%.in$", "")
        io.writefile(output, (io.readfile(sourcefile):gsub("@PACKAGE_VERSION@", "1.63.0"):gsub("@PACKAGE_VERSION_NUM@", "0x013f00")))
        table.insert(depend.files, output)
        table.remove(sources, 1)
    end})
    add_files("build/native/deps/impersonate-nghttp2/lib/*.c")

target("curl-impersonate")
    add_rules("utils.inherit.links")
    before_sources(function ()
        os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://lexiforest/curl-impersonate?ref=v2.2.2", "build/native/deps/curl-impersonate"})
        os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://curl/curl?ref=curl-8_21_0", "build/native/deps/impersonate-curl"})
    end)
    set_languages("c11")
    add_deps("boringssl", "impersonate-nghttp2", "brotli", "zlib", "zstd")
    add_includedirs("build/native/deps/impersonate-curl/include", {public = true})
    add_includedirs("build/native/deps/impersonate-curl/lib")
    add_defines("BUILDING_LIBCURL", "CURL_STATICLIB", "NGHTTP2_STATICLIB", "CURL_DISABLE_LDAP", "CURL_DISABLE_LDAPS", "HAVE_BROTLI", "HAVE_LIBZ", "HAVE_ZSTD", "HAVE_DES_ECB_ENCRYPT", "HAVE_SSL_SET0_WBIO", "HAVE_SSL_SET1_ECH_CONFIG_LIST", "USE_IPV6", "USE_NGHTTP2", "USE_OPENSSL", "USE_ECH", "USE_HTTPSRR", "USE_SSLS_EXPORT")
    if is_plat("windows") then
        add_defines("USE_WIN32_IDN", "CURL_CA_NATIVE", "strtok_r=strtok_s")
        add_syslinks("advapi32", "bcrypt", "crypt32", "iphlpapi", "normaliz", "winmm", "ws2_32", {public = true})
    else
        add_defines("HAVE_CONFIG_H", "_GNU_SOURCE")
        add_syslinks("pthread", "dl", {public = true})
        add_files("src/native/curl_config_linux.h", {source = function (sourcefile, sources, depend)
            local output = "build/native/deps/impersonate-curl/lib/curl_config.h"
            io.writefile(output, io.readfile(sourcefile))
            table.insert(depend.files, output)
            table.remove(sources, 1)
        end})
    end
    add_files("build/native/deps/curl-impersonate/patches/curl.patch", {source = function (sourcefile, sources, depend)
        local root = "build/native/deps/impersonate-curl"
        if not try {function () os.runv("git", {"apply", "--directory=" .. root, "--reverse", "--check", sourcefile}); return true end} then
            os.runv("git", {"apply", "--directory=" .. root, sourcefile})
        end
        for output in io.readfile(sourcefile):gmatch("\n%+%+%+ b/([^\r\n]+)") do table.insert(depend.files, path.join(root, output)) end
        io.writefile(root .. "/lib/vtls/openssl_msvc.c", (io.readfile(root .. "/lib/vtls/openssl.c")
            :gsub("static const size_t kMaxSignatureAlgorithmNameLen = 23;", "enum { kMaxSignatureAlgorithmNameLen = 23 };")))
        table.insert(depend.files, root .. "/lib/vtls/openssl_msvc.c")
        table.remove(sources, 1)
        table.join2(sources, os.files(root .. "/lib/*.c"), os.files(root .. "/lib/**/*.c"))
    end})
    remove_files("build/native/deps/impersonate-curl/lib/dllmain.c", "build/native/deps/impersonate-curl/lib/vtls/openssl.c")

target("standard-boringssl")
    add_rules("utils.inherit.links")
    before_sources(function ()
        os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"github://google/boringssl?ref=156c7b75ae9b8c3b3f847acf264f17594c3859fb", "build/native/deps/standard-boringssl"})
    end)
    set_languages("c11", "cxx17")
    set_optimize("fastest")
    add_includedirs("build/native/deps/standard-boringssl/include", {public = true})
    -- Upstream ships complete generated C/C++ symbol prefixes; no source patch.
    add_defines("BORINGSSL_PREFIX=gp_std", "ssl_session_st=gp_std_ssl_session_st", {public = true})
    add_defines("BORINGSSL_IMPLEMENTATION", "OPENSSL_NO_ASM", "NDEBUG")
    if is_plat("windows") then
        add_defines("_HAS_EXCEPTIONS=0", "WIN32_LEAN_AND_MEAN", "NOMINMAX", "_CRT_SECURE_NO_WARNINGS")
        add_cxflags("/utf-8", "/Zc:__cplusplus", "/permissive-", {force = true})
        add_syslinks("ws2_32", "advapi32", "bcrypt", {public = true})
    else
        add_cxxflags("-fPIC", "-fno-exceptions", "-fno-rtti")
        add_syslinks("pthread", {public = true})
    end
    add_files("build/native/deps/standard-boringssl/gen/sources.json", {source = function (sourcefile, sources, depend)
        import("core.base.json")
        local manifest = json.loadfile(sourcefile)
        table.remove(sources, 1)
        for _, component in ipairs({"bcm", "crypto", "ssl"}) do
            for _, file in ipairs(manifest[component].srcs) do table.insert(sources, path.join("build/native/deps/standard-boringssl", file)) end
        end
    end})

target("standard-curl")
    add_rules("utils.inherit.links")
    before_sources(function ()
        os.runv(path.join(os.projectdir(), os.host() == "windows" and "tools/hx.exe" or "tools/hx"), {"-delpathseg", "1", "https://github.com/curl/curl/releases/download/curl-8_22_0/curl-8.22.0.tar.xz", "build/native/deps/standard-curl-8.22.0"})
        if get_config("curl_costs") then os.runv(path.join(os.getenv("GRAALPHP_JDK"), "bin/java"), {"BuildSupport.java", "curl-states"}) end
    end)
    set_languages("c11")
    set_optimize("fastest")
    add_deps("standard-boringssl", "impersonate-nghttp2", "brotli", "zlib", "zstd")
    -- These headers are private: the common shim compiles against the existing
    -- impersonate ABI. This target also owns the standard provider table.
    add_includedirs("build/native/deps/standard-curl-8.22.0/include", "build/native/deps/standard-curl-8.22.0/lib", "src/native")
    add_defines("BUILDING_LIBCURL", "CURL_STATICLIB", "NGHTTP2_STATICLIB", "CURL_DISABLE_LDAP", "CURL_DISABLE_LDAPS", "HAVE_BROTLI", "HAVE_LIBZ", "HAVE_ZSTD", "HAVE_DES_ECB_ENCRYPT", "HAVE_SSL_SET0_WBIO", "USE_IPV6", "USE_NGHTTP2", "USE_OPENSSL")
    if is_plat("windows") then
        add_defines("USE_WIN32_IDN", "CURL_CA_NATIVE", "strtok_r=strtok_s", "HAVE_CONFIG_H")
        add_files("src/native/curl_config_windows.h", {source = function (sourcefile, sources, depend)
            local output = "build/native/deps/standard-curl-8.22.0/lib/curl_config.h"
            io.writefile(output, io.readfile(sourcefile))
            table.insert(depend.files, output)
            table.remove(sources, 1)
        end})
        add_syslinks("advapi32", "bcrypt", "crypt32", "iphlpapi", "normaliz", "winmm", "ws2_32", {public = true})
    else
        add_defines("HAVE_CONFIG_H", "_GNU_SOURCE")
        add_syslinks("pthread", "dl", {public = true})
        add_files("src/native/curl_config_linux.h", {source = function (sourcefile, sources, depend)
            local output = "build/native/deps/standard-curl-8.22.0/lib/curl_config.h"
            io.writefile(output, io.readfile(sourcefile))
            table.insert(depend.files, output)
            table.remove(sources, 1)
        end})
    end
    add_files("build/native/deps/standard-curl-8.22.0/lib/libcurl.def", {source = function (sourcefile, sources, depend)
        local names = {}
        for name in io.readfile(sourcefile):gmatch("curl_[%w_]+") do if name ~= "curl_multi_socket" then names[name] = true end end
        local definitions = {"#pragma once"}
        for name in table.orderpairs(names) do table.insert(definitions, "#define " .. name .. " gp_std_" .. name) end
        local output = "build/native/deps/standard-curl-8.22.0/lib/gp_curl_prefix.h"
        io.writefile(output, table.concat(definitions, "\n") .. "\n")
        table.insert(depend.files, output)
        table.remove(sources, 1)
    end})
    add_files("build/native/deps/standard-curl-8.22.0/lib/*.c", "build/native/deps/standard-curl-8.22.0/lib/**/*.c", "src/native/curl_provider_standard.c")
    if get_config("curl_costs") then
        remove_files("build/native/deps/standard-curl-8.22.0/lib/multi.c")
        remove_files("build/native/deps/standard-curl-8.22.0/lib/http.c")
        remove_files("build/native/deps/standard-curl-8.22.0/lib/cf-socket.c")
        add_files("build/probe-curl-states/multi.c", "build/probe-curl-states/http.c", "build/probe-curl-states/cf-socket.c")
    end
    add_files("src/native/curl_provider_standard.c", {forceincludes = "gp_curl_prefix.h"})
    if not is_plat("windows") then
        -- GCC's public type-checking macros undefine the renamed function names.
        -- The table stores addresses, so use libcurl's plain declarations here.
        add_files("src/native/curl_provider_standard.c", {defines = "CURL_DISABLE_TYPECHECK"})
    end
    remove_files("build/native/deps/standard-curl-8.22.0/lib/dllmain.c")
    after_link(function (target)
        local nm = os.host() == "windows" and "tools/llvm/bin/llvm-nm.exe" or "nm"
        local objcopy = os.host() == "windows" and "tools/llvm/bin/llvm-objcopy.exe" or "objcopy"
        local symbols = "\n" .. os.iorunv(nm, {"--extern-only", "--format=posix", target:targetfile()})
        local renames = {}
        for name, kind in symbols:gmatch("\n([%w_]+) ([A-Za-z?]) ") do
            if not name:startswith("gp_") and (name:startswith("curl_") or name:startswith("Curl_") or name:startswith("curlx_") or (kind ~= "U" and not name:startswith("__"))) then
                table.insert(renames, name .. " gp_std_" .. name)
            end
        end
        if #renames == 0 then return end
        local mapping = "build/native/deps/standard-curl-8.22.0/gp-symbols.txt"
        io.writefile(mapping, table.concat(table.unique(renames), "\n") .. "\n")
        os.runv(objcopy, {"--redefine-syms=" .. mapping, target:targetfile()})
    end)

target("graalphp-native")
    set_optimize("fastest")
    if get_config("curl_costs") then add_defines("GP_CURL_COSTS") end
    set_kind("shared")
    add_deps("zlib", "pcre2", "sqlite3", "libuv", "curl-impersonate", "standard-curl")
    add_files("src/native/shim.c", "src/native/reactor.c", "src/native/services.c", "src/native/curl_reactor.c", "src/native/curl_provider.c", "src/native/ffi_bridge.c")
    add_defines("CURL_STATICLIB")
    add_includedirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/include")
    -- Use the pinned GraalVM NFI ABI archive, already linked into Native Image.
    -- A second independently built libffi would collide with NFI's symbols.
    if is_plat("windows") then
        add_includedirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/windows-amd64/default/include")
        add_linkdirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/windows-amd64/default")
    else
        add_includedirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/linux-amd64/glibc/include")
        add_linkdirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/linux-amd64/glibc")
    end
    add_links("ffi")
    set_targetdir("build")

target("graalphp-builtins")
    set_optimize("fastest")
    if get_config("curl_costs") then add_defines("GP_CURL_COSTS") end
    set_kind("static")
    add_deps("zlib", "pcre2", "sqlite3", "libuv", "curl-impersonate", "standard-curl")
    add_files("src/native/shim.c", "src/native/reactor.c", "src/native/services.c", "src/native/curl_reactor.c", "src/native/curl_provider.c", "src/native/ffi_bridge.c")
    add_defines("CURL_STATICLIB")
    add_defines("GP_STATIC")
    add_includedirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/include")
    if is_plat("windows") then
        add_includedirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/windows-amd64/default/include")
    else
        add_includedirs("$(env GRAALPHP_JDK)/lib/svm/macros/truffle-svm/builder/linux-amd64/glibc/include")
    end
