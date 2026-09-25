# libuv embedding patch

`libuv-electron.patch` is the unmodified Electron patch
`patches/node/feat_add_uv_loop_interrupt_on_io_change_option_to_uv_loop_configure.patch`
from commit `471e642f00c3637d5bac070e18e4bb918ca1a156`.

Source: https://github.com/electron/electron/blob/471e642f00c3637d5bac070e18e4bb918ca1a156/patches/node/feat_add_uv_loop_interrupt_on_io_change_option_to_uv_loop_configure.patch

SHA-256: `7cd0d5e14df005f43da3ec66f81c923a19a1968adf75c648240412c0bb67442d`.
The Electron MIT license is included in `ELECTRON-LICENSE.txt`.

Xmake's source materialization applies it to libuv **1.52.1** with `git apply
-p3`, removing the Node `deps/uv` prefix. The reverse check makes a repeated
build idempotent. Every patched output is tracked by the source dependency
record; the generated `libuv/uv.h` compatibility copy uses the patched header.
No CMake build is used. The patch includes its original upstream embedding tests.

It adds I/O-change notifications and interrupt suspension while the embedder's
OS waiter is parked. CLI/server mode drives libuv directly on the PHP owner and
does not enable the extra notifications. Hosted mode enables them and uses an
OS-readiness helper, with all libuv callbacks still dispatched on the owner.
The Windows shim is compiled against the same patched `uv_loop_t` definition;
it does not guess an IOCP offset from Java or load an unrelated libuv DLL.
