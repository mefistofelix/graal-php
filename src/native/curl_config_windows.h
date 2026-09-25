/* Windows x64/MSVC configuration for the Xmake upstream curl target.
 * curl 8.22's config-win32.h is now restricted to legacy IDE projects. */
#pragma once
#define CURL_OS "x86_64-pc-win32"
#define SIZEOF_INT 4
#define SIZEOF_LONG 4
#define SIZEOF_SIZE_T 8
#define SIZEOF_CURL_OFF_T 8
#define SIZEOF_CURL_SOCKET_T 8
#define SIZEOF_TIME_T 8
#define SIZEOF_OFF_T 4
#define ssize_t __int64
#define HAVE_FCNTL_H 1
#define HAVE_IO_H 1
#define HAVE_LOCALE_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_STDINT_H 1
#define HAVE_BOOL_T 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_UTIME_H 1
#define HAVE_CLOSESOCKET 1
#define HAVE_FREEADDRINFO 1
#define HAVE_GETADDRINFO 1
#define HAVE_GETADDRINFO_THREADSAFE 1
#define HAVE_GETHOSTNAME 1
#define HAVE_GETPEERNAME 1
#define HAVE_GETSOCKNAME 1
#define HAVE_IOCTLSOCKET 1
#define HAVE_IOCTLSOCKET_FIONBIO 1
#define HAVE_SELECT 1
#define HAVE_SETLOCALE 1
#define HAVE_SOCKET 1
#define HAVE_UTIME 1
#define HAVE_RECV 1
#define RECV_TYPE_ARG1 SOCKET
#define RECV_TYPE_ARG2 char *
#define RECV_TYPE_ARG3 int
#define RECV_TYPE_ARG4 int
#define RECV_TYPE_RETV int
#define HAVE_SEND 1
#define SEND_TYPE_ARG1 SOCKET
#define SEND_TYPE_ARG2 char *
#define SEND_TYPE_ARG3 int
#define SEND_TYPE_ARG4 int
#define SEND_TYPE_RETV int
#define HAVE_SIGNAL 1
#define HAVE_STRUCT_SOCKADDR_STORAGE 1
#define HAVE_STRUCT_TIMEVAL 1
#define HAVE_SOCKADDR_IN6_SIN6_SCOPE_ID 1
#define USE_RESOLV_THREADED 1
#define USE_WIN32_CRYPTO 1
#define USE_UNIX_SOCKETS 1
