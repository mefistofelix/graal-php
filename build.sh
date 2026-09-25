#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# SDKMAN remains project-local; the installer does not edit shell startup files.
export SDKMAN_DIR="$PWD/tools/sdkman"
if ! command -v zip >/dev/null && command -v apt-get >/dev/null && command -v dpkg-deb >/dev/null; then
    mkdir -p tools/zip
    if [[ ! -x tools/zip/unpack/usr/bin/zip ]]; then
        (cd tools/zip && apt-get download zip && dpkg-deb --extract ./zip_*.deb unpack)
    fi
    export PATH="$PWD/tools/zip/unpack/usr/bin:$PATH"
fi
if [[ ! -s "$SDKMAN_DIR/bin/sdkman-init.sh" ]]; then
    mkdir -p tools
    curl --fail --location --retry 3 'https://get.sdkman.io?ci=true&rcupdate=false' -o tools/sdkman-install.sh
    bash tools/sdkman-install.sh
fi
set +u
source "$SDKMAN_DIR/bin/sdkman-init.sh"
if [[ ! -x "$SDKMAN_DIR/candidates/java/25.4.4+1-graal/bin/javac" ]]; then
    sdk install java 25.4.4+1-graal
fi
set -u
jdk="$SDKMAN_DIR/candidates/java/25.4.4+1-graal"
build_native_libraries() {
    local native_workspace="$PWD"
    [[ "$(uname -s)-$(uname -m)" == Linux-x86_64 ]] || { echo 'Native bundle setup currently supports Linux x86_64.' >&2; exit 2; }
    [[ -f tools/hx ]] || curl --fail --location --retry 3 https://github.com/mefistofelix/hx/releases/download/v1.0.24/hx -o tools/hx
    echo '95b9735de2e34fb8d8ab8ff526e44eec57964dc93fb52a00887fc8855d95be95  tools/hx' | sha256sum --check --status
    chmod +x tools/hx
    if [[ ! -x tools/xmake-953d954-linux-x64 ]]; then
        [[ -f tools/xmake-bootstrap ]] || curl --fail --location --retry 3 https://github.com/xmake-io/xmake/releases/download/v3.1.0/xmake-bundle-v3.1.0.linux.x86_64 -o tools/xmake-bootstrap
        echo '1baab457f3bf11032e82c6210bc5bec04f5b902962da17dab53cae10783170de  tools/xmake-bootstrap' | sha256sum --check --status
        chmod +x tools/xmake-bootstrap
        # The bootstrap bundle needs ncurses; extract missing runtime libraries locally.
        if ldd tools/xmake-bootstrap | grep -q 'libncurses.so.6 => not found'; then
            if [[ ! -f "tools/xmake-runtime/usr/lib/$(gcc -dumpmachine)/libncurses.so.6" ]]; then
                mkdir -p tools/xmake-runtime/packages
                (cd tools/xmake-runtime/packages && apt-get download libncurses6 libtinfo6)
                for package in tools/xmake-runtime/packages/*.deb; do dpkg-deb --extract "$package" tools/xmake-runtime; done
            fi
            export LD_LIBRARY_PATH="$PWD/tools/xmake-runtime/usr/lib/$(gcc -dumpmachine):${LD_LIBRARY_PATH:-}"
        fi
        tools/hx -recursive=1 'github://mefistofelix/xmake?ref=953d954e0042448abfa3f84f7d184b47d6aa2912' tools/xmake-source > build/xmake-fetch.log
        (
            cd tools/xmake-source/core
            export XMAKE_GLOBALDIR="$native_workspace/build/xmake-bootstrap-global"
            "$native_workspace/tools/xmake-bootstrap" f -y -P "$PWD" -F "$PWD/xmake.lua" -o "$native_workspace/build/xmake-bootstrap" -m release --embed=y --curses=n
            "$native_workspace/tools/xmake-bootstrap" -P "$PWD" -F "$PWD/xmake.lua" -y -j 8 cli
        )
        cp build/xmake-bootstrap/xmake tools/xmake-953d954-linux-x64
        chmod +x tools/xmake-953d954-linux-x64
    fi
    export GRAALPHP_JDK="$jdk"
    export XMAKE_GLOBALDIR="$PWD/build/xmake-global"
    mkdir -p build/xmake-tmp-953d954
    TMPDIR="$PWD/build/xmake-tmp-953d954" tools/xmake-953d954-linux-x64 f -y -P "$PWD" -F "$PWD/xmake.lua" -m release --curl_costs=n
    TMPDIR="$PWD/build/xmake-tmp-953d954" tools/xmake-953d954-linux-x64 -P "$PWD" -F "$PWD/xmake.lua" -y -j 8 graalphp-native
    TMPDIR="$PWD/build/xmake-tmp-953d954" tools/xmake-953d954-linux-x64 -P "$PWD" -F "$PWD/xmake.lua" -y -j 8 graalphp-builtins
}
deps=build/deps/25.4.4.1.1
mkdir -p "$deps" build/classes build/generated
processors=
jar_deps=
while IFS='|' read -r filename url sha; do
    [[ -f "$deps/$filename" ]] || curl --fail --location --retry 3 "$url" -o "$deps/$filename"
    if command -v sha256sum >/dev/null; then
        echo "$sha  $deps/$filename" | sha256sum --check --status
    else
        echo "$sha  $deps/$filename" | shasum -a 256 --check --status
    fi
    processors+="$deps/$filename:"
    jar_deps+=" deps/25.4.4.1.1/$filename"
done < dependencies.lock
"$jdk/bin/java" BuildSupport.java clean
mkdir -p build/classes build/generated
find src -name '*.java' -print > build/sources.txt
"$jdk/bin/javac" --release 25 -cp "$deps/*" -processorpath "$processors" -proc:full -s build/generated -d build/classes @build/sources.txt
printf 'Main-Class: graalphp.Main\nClass-Path:%s\n' "$jar_deps" > build/manifest.mf
"$jdk/bin/jar" --create --file build/graalphp.jar --manifest build/manifest.mf -C build/classes .
case "${1:-build}" in
    native-libs) build_native_libraries ;;
    curl-native-control)
        build_native_libraries
        TMPDIR="$PWD/build/xmake-tmp-953d954" tools/xmake-953d954-linux-x64 -P "$PWD" -F "$PWD/xmake.lua" -y -j 8 curl-native-benchmark
        ;;
    build) "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -jar build/graalphp.jar --version ;;
    run) "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -jar build/graalphp.jar "${2:?Specify a PHP file}" ;;
    curl-test)
        build_native_libraries
        curl_executable=
        if [[ "${2:-}" == native ]]; then
            "$jdk/bin/native-image" -O3 -march=compatibility \
                --initialize-at-build-time=graalphp.truffle,graalphp.runtime \
                --enable-native-access=ALL-UNNAMED -J-Xmx6g --parallelism=8 \
                -cp "build/classes:$deps/*" graalphp.Main -o build/graalphp
            curl_executable="$PWD/build/graalphp"
        fi
        mkdir -p build/test-classes
        "$jdk/bin/javac" --release 25 -proc:none -d build/test-classes tests/graalphp/CurlIntegrationTest.java
        "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp build/test-classes graalphp.CurlIntegrationTest "$curl_executable"
        ;;
    network-test)
        build_native_libraries
        network_executable=
        if [[ "${2:-}" == native ]]; then
            "$jdk/bin/native-image" -O3 -march=compatibility \
                --initialize-at-build-time=graalphp.truffle,graalphp.runtime \
                --enable-native-access=ALL-UNNAMED -J-Xmx6g --parallelism=8 \
                -cp "build/classes:$deps/*" graalphp.Main -o build/graalphp
            network_executable="$PWD/build/graalphp"
        fi
        mkdir -p build/test-classes
        "$jdk/bin/javac" --release 25 -proc:none -cp "build/classes:$deps/*" -d build/test-classes tests/graalphp/NetworkIntegrationTest.java
        "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp "build/classes:build/test-classes:$deps/*" graalphp.NetworkIntegrationTest "$network_executable"
        ;;
    verify)
        build_native_libraries
        mkdir -p build/test-classes
        find tests -name '*.java' -print > build/test-sources.txt
        "$jdk/bin/javac" --release 25 -proc:none -cp "build/classes:$deps/*" -d build/test-classes @build/test-sources.txt
        "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp "build/classes:build/test-classes:$deps/*" graalphp.IntegrationTest
        "$jdk/bin/java" -cp "build/classes:build/test-classes:$deps/*" graalphp.lab.ValueModelTest
        "$jdk/bin/java" -cp "build/classes:build/test-classes:$deps/*" graalphp.runtime.CycleCollectorTest
        ;;
    oracle)
        "${PHP_ORACLE:-php}" -n -r 'if (PHP_MAJOR_VERSION !== 8 || PHP_MINOR_VERSION !== 6) exit(1);'
        "${PHP_ORACLE:-php}" -n examples/compat.php > build/php-compat.txt
        "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -jar build/graalphp.jar examples/compat.php > build/graalphp-compat.txt
        cmp build/php-compat.txt build/graalphp-compat.txt
        ;;
    trueasync)
        case "$(uname -s)-$(uname -m)" in
            Linux-x86_64) platform=linux-x86_64; archive_sha=1657eda2a086d6d9ace779979d53d161f1ad2df4df9db2cb3ec3dd90b280a8aa ;;
            Linux-aarch64) platform=linux-aarch64; archive_sha=dce6c84a551c3d60666d915fa05ed6b422d87c3f200e0fd465163c0c18472b2f ;;
            Darwin-x86_64) platform=macos-x86_64; archive_sha=af4c4872e34b7d014a1f3c6b58393e1a868c76efa2176c08934ad80d0cca1cf4 ;;
            Darwin-arm64) platform=macos-aarch64; archive_sha=364df5e457e9843dc6ffdfbff86b1454f4ff0aee8e2d4897ea8c002aa0002c9f ;;
            *) echo 'No pinned TrueAsync binary for this platform' >&2; exit 2 ;;
        esac
        archive=php-trueasync-0.10.0-php8.6-$platform
        if [[ ! -x "tools/$archive/php" ]]; then
            curl --fail --location --retry 3 "https://github.com/true-async/releases/releases/download/v0.10.0/$archive.tar.gz" -o "tools/$archive.tar.gz"
            if command -v sha256sum >/dev/null; then
                echo "$archive_sha  tools/$archive.tar.gz" | sha256sum --check --status
            else
                echo "$archive_sha  tools/$archive.tar.gz" | shasum -a 256 --check --status
            fi
            tar -xf "tools/$archive.tar.gz" -C tools
        fi
        if [[ ! -d build/reference/php-async-6acdd07ff500f5799ea83bbf333d686b5dacabbf/tests ]]; then
            mkdir -p build/reference
            curl --fail --location --retry 3 https://codeload.github.com/true-async/php-async/zip/6acdd07ff500f5799ea83bbf333d686b5dacabbf -o build/trueasync-source.zip
            source_sha=db06d553a98be1c63cf5819ef200f318c2f8e61f47fdb3dcdf64ad463dd56cc9
            if command -v sha256sum >/dev/null; then
                echo "$source_sha  build/trueasync-source.zip" | sha256sum --check --status
            else
                echo "$source_sha  build/trueasync-source.zip" | shasum -a 256 --check --status
            fi
            unzip -q build/trueasync-source.zip -d build/reference
        fi
        mkdir -p build/test-classes
        "$jdk/bin/javac" --release 25 -proc:none -cp "build/classes:$deps/*" -d build/test-classes tests/graalphp/TrueAsyncTest.java
        native_arguments=()
        if [[ $# -gt 1 && "$2" == native ]]; then native_arguments=("$PWD/build/graalphp"); fi
        "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp "build/classes:build/test-classes:$deps/*" graalphp.TrueAsyncTest "$PWD/tools/$archive/php" "${native_arguments[@]}"
        ;;
    ffi-bridge-test)
        build_native_libraries
        TMPDIR="$PWD/build/xmake-tmp-953d954" tools/xmake-953d954-linux-x64 -P "$PWD" -F "$PWD/xmake.lua" -y ffi-bridge-fixture
        mkdir -p build/test-classes
        "$jdk/bin/javac" --release 25 -proc:none -cp "build/classes:$deps/*" -d build/test-classes tests/graalphp/NativeBridgeTest.java
        if [[ "${2:-}" == native ]]; then
            "$jdk/bin/native-image" -O1 -march=compatibility \
                --initialize-at-build-time=graalphp.truffle,graalphp.runtime \
                --enable-native-access=ALL-UNNAMED -J-Xmx6g --parallelism=8 \
                -cp "build/classes:build/test-classes:$deps/*" graalphp.NativeBridgeTest -o build/ffi-bridge-test-runner
            build/ffi-bridge-test-runner "$PWD/build/libffi-bridge-fixture.so"
        else
            "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp "build/classes:build/test-classes:$deps/*" graalphp.NativeBridgeTest "$PWD/build/libffi-bridge-fixture.so"
        fi
        ;;
    native-stack-probe)
        build_native_libraries
        TMPDIR="$PWD/build/xmake-tmp-953d954" tools/xmake-953d954-linux-x64 -P "$PWD" -F "$PWD/xmake.lua" -y native-stack-probe
        mkdir -p build/test-classes
        "$jdk/bin/javac" --release 25 -proc:none -cp "build/classes:$deps/*" -d build/test-classes tests/graalphp/NativeStackProbe.java
        if [[ "${2:-}" == native ]]; then
            "$jdk/bin/native-image" -O1 -march=compatibility \
                --initialize-at-build-time=graalphp.truffle,graalphp.runtime \
                --enable-native-access=ALL-UNNAMED -J-Xmx6g --parallelism=8 \
                -cp "build/classes:build/test-classes:$deps/*" graalphp.NativeStackProbe -o build/native-stack-probe-runner
            build/native-stack-probe-runner "$PWD/build/libnative-stack-probe.so"
        else
            "$jdk/bin/java" --enable-native-access=ALL-UNNAMED -cp "build/classes:build/test-classes:$deps/*" graalphp.NativeStackProbe "$PWD/build/libnative-stack-probe.so"
        fi
        ;;
    benchmark) "$jdk/bin/java" -Xms256m -Xmx256m -cp "build/classes:$deps/*" graalphp.lab.Main --benchmark ;;
    native)
        build_native_libraries
        "$jdk/bin/native-image" -O3 -march=compatibility \
            --initialize-at-build-time=graalphp.truffle,graalphp.runtime \
            --enable-native-access=ALL-UNNAMED -J-Xmx6g --parallelism=8 \
            -cp "build/classes:$deps/*" graalphp.Main -o build/graalphp
        ;;
    *) echo 'Usage: bash build.sh [build|run FILE|verify|oracle|trueasync [native]|network-test [native]|ffi-bridge-test [native]|native-stack-probe [native]|benchmark|native|native-libs]' >&2; exit 2 ;;
esac
