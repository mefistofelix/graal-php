@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "GRAALPHP_JDK=%~dp0tools\graalvm-25.4.4.1.1+1.1"
set "DEPS=build\deps\25.4.4.1.1"
if exist "%GRAALPHP_JDK%\bin\javac.exe" goto dependencies
if not exist tools mkdir tools
curl.exe --fail --location --retry 3 --output tools\graalvm-25i4.zip https://gds.oracle.com/download/graal/25i4/archive/graalvm-jdk-25i4-25.0.4.1.1_windows-x64_bin.zip
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile tools\graalvm-25i4.zip SHA256 | findstr /i /c:"cf71435f04d67267563c40572c29bbef017242dbb996a02bbe95a2e5ad5d761d" >nul
if not "!errorlevel!"=="0" (echo GraalVM archive changed. Update version and checksum together. & exit /b 1)
tar.exe -xf tools\graalvm-25i4.zip -C tools
if not "!errorlevel!"=="0" exit /b 1
:dependencies
if not exist "%DEPS%" mkdir "%DEPS%"
set "PROCESSORS="
set "JAR_DEPS="
for /f "tokens=1,2,3 delims=|" %%a in (dependencies.lock) do (
    if not exist "%DEPS%\%%a" (
        curl.exe --fail --location --retry 3 --output "%DEPS%\%%a" %%b
        if not "!errorlevel!"=="0" exit /b 1
    )
    certutil -hashfile "%DEPS%\%%a" SHA256 | findstr /i /c:"%%c" >nul
    if not "!errorlevel!"=="0" exit /b 1
    set "PROCESSORS=!PROCESSORS!%DEPS%\%%a;"
    set "JAR_DEPS=!JAR_DEPS! deps/25.4.4.1.1/%%a"
)
"%GRAALPHP_JDK%\bin\java.exe" BuildSupport.java clean
if not "!errorlevel!"=="0" exit /b 1
if not exist build\classes mkdir build\classes
if not exist build\generated mkdir build\generated
(for /r src %%f in (*.java) do (set "SOURCE=%%f" & echo "!SOURCE:\=/!")) > build\sources.txt
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -cp "%DEPS%/*" -processorpath "%PROCESSORS%" -proc:full -s build\generated -d build\classes @build\sources.txt
if not "!errorlevel!"=="0" exit /b 1
> build\manifest.mf echo Main-Class: graalphp.Main
>> build\manifest.mf echo Class-Path:%JAR_DEPS%
"%GRAALPHP_JDK%\bin\jar.exe" --create --file build\graalphp.jar --manifest build\manifest.mf -C build\classes .
if not "!errorlevel!"=="0" exit /b 1
if /i "%~1"=="run" ("%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar build\graalphp.jar "%~2" & exit /b !errorlevel!)
if /i "%~1"=="native" goto native
if /i "%~1"=="profile-native" goto native
if /i "%~1"=="reactor-probe" goto native
if /i "%~1"=="native-libs" goto native_libraries
if /i "%~1"=="native-stack-probe" goto native_stack_probe
if /i "%~1"=="ffi-bridge-test" goto ffi_bridge_test
if /i "%~1"=="verify" goto verify
if /i "%~1"=="curl-test" goto curl_test
if /i "%~1"=="curl-benchmark" goto curl_benchmark
if /i "%~1"=="curl-native-control" goto curl_native_control
if /i "%~1"=="network-test" goto network_test
if /i "%~1"=="network-benchmark" goto network_benchmark
if /i "%~1"=="trueasync" goto trueasync
if /i "%~1"=="oracle" goto oracle
if /i "%~1"=="autoload-test" goto autoload_test
if /i "%~1"=="benchmark" ("%GRAALPHP_JDK%\bin\java.exe" -cp "build\classes;%DEPS%/*" graalphp.lab.Main --benchmark & exit /b !errorlevel!)
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar build\graalphp.jar --version
exit /b %errorlevel%
:curl_native_control
call :native_libraries
if not "!errorlevel!"=="0" exit /b 1
tools\xmake.exe -y curl-native-benchmark
exit /b %errorlevel%
:curl_benchmark
call :native
if not "!errorlevel!"=="0" exit /b 1
call :setup_trueasync
if not "!errorlevel!"=="0" exit /b 1
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -d build\test-classes tests\graalphp\NetworkBenchmark.java tests\graalphp\CurlBenchmark.java
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp build\test-classes graalphp.CurlBenchmark %~2 %~3 %~4 %~5 %~6
exit /b %errorlevel%
:curl_test
set "CURL_EXECUTABLE="
if /i "%~2"=="trueasync" (
    call :setup_trueasync
    set "CURL_EXECUTABLE=trueasync"
) else if /i "%~2"=="native" (
    call :native
    set "CURL_EXECUTABLE=%~dp0build\graalphp.exe"
) else (call :native_libraries)
if not "!errorlevel!"=="0" exit /b 1
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -d build\test-classes tests\graalphp\CurlIntegrationTest.java
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp build\test-classes graalphp.CurlIntegrationTest "!CURL_EXECUTABLE!"
exit /b %errorlevel%
:network_benchmark
call :native
if not "!errorlevel!"=="0" exit /b 1
call :setup_trueasync
if not "!errorlevel!"=="0" exit /b 1
if "%GRAALPHP_BENCH_CALLBACKS%"=="1" (
    tools\xmake.exe -y ffi-bridge-fixture
    if not "!errorlevel!"=="0" exit /b 1
)
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -d build\test-classes tests\graalphp\NetworkBenchmark.java
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp build\test-classes graalphp.NetworkBenchmark %~2 %~3 %~4
exit /b %errorlevel%
:network_test
if /i "%~2"=="native" (call :native) else (call :native_libraries)
if not "!errorlevel!"=="0" exit /b 1
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -cp "build\classes;%DEPS%/*" -d build\test-classes tests\graalphp\NetworkIntegrationTest.java
if not "!errorlevel!"=="0" exit /b 1
set "NETWORK_EXECUTABLE="
if /i "%~2"=="native" set "NETWORK_EXECUTABLE=%~dp0build\graalphp.exe"
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.NetworkIntegrationTest "!NETWORK_EXECUTABLE!"
exit /b %errorlevel%
:verify
call :native_libraries
if not "!errorlevel!"=="0" exit /b 1
if not exist build\test-classes mkdir build\test-classes
(for /r tests %%f in (*.java) do (set "SOURCE=%%f" & echo "!SOURCE:\=/!")) > build\test-sources.txt
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -cp "build\classes;%DEPS%/*" -d build\test-classes @build\test-sources.txt
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.IntegrationTest
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.lab.ValueModelTest
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.runtime.CycleCollectorTest
if not "!errorlevel!"=="0" exit /b 1
:trueasync
call :setup_trueasync
if not "!errorlevel!"=="0" exit /b 1
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -cp "build\classes;%DEPS%/*" -d build\test-classes tests\graalphp\TrueAsyncTest.java
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.TrueAsyncTest
exit /b %errorlevel%
:setup_trueasync
if exist tools\trueasync-0.10.0\php.exe goto trueasync_sources
curl.exe --fail --location --retry 3 -o tools\trueasync-0.10.0.zip https://github.com/true-async/releases/releases/download/v0.10.0/php-trueasync-0.10.0-php8.6-windows-x64.zip
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile tools\trueasync-0.10.0.zip SHA256 | findstr /i /c:"c25923c3474c30c83d89719bf65d13b196b07e0a0774d6535ac9a33c64a1a56a" >nul
if not "!errorlevel!"=="0" exit /b 1
if not exist tools\trueasync-0.10.0 mkdir tools\trueasync-0.10.0
tar.exe -xf tools\trueasync-0.10.0.zip -C tools\trueasync-0.10.0
if not "!errorlevel!"=="0" exit /b 1
:trueasync_sources
if exist build\reference\php-async-6acdd07ff500f5799ea83bbf333d686b5dacabbf\tests exit /b 0
if not exist build\reference mkdir build\reference
curl.exe --fail --location --retry 3 -o build\trueasync-source.zip https://codeload.github.com/true-async/php-async/zip/6acdd07ff500f5799ea83bbf333d686b5dacabbf
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile build\trueasync-source.zip SHA256 | findstr /i /c:"db06d553a98be1c63cf5819ef200f318c2f8e61f47fdb3dcdf64ad463dd56cc9" >nul
if not "!errorlevel!"=="0" exit /b 1
tar.exe -xf build\trueasync-source.zip -C build\reference
exit /b %errorlevel%
:autoload_test
call :setup_trueasync
if not "!errorlevel!"=="0" exit /b 1
call :setup_oracle
if not "!errorlevel!"=="0" exit /b 1
set "AUTOLOAD_EXECUTABLE="
if /i "%~2"=="native" (
    call :native
    if not "!errorlevel!"=="0" exit /b 1
    set "AUTOLOAD_EXECUTABLE=%~dp0build\graalphp.exe"
)
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -cp "build\classes;%DEPS%/*" -d build\test-classes tests\graalphp\AutoloadTest.java
if not "!errorlevel!"=="0" exit /b 1
"%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.AutoloadTest tools/php-8.6.0RC2/php.exe tools/trueasync-0.10.0/php.exe "!AUTOLOAD_EXECUTABLE!"
exit /b %errorlevel%
:oracle
call :setup_oracle
if not "!errorlevel!"=="0" exit /b 1
goto compare
:setup_oracle
if exist tools\php-8.6.0RC2\php.exe exit /b 0
curl.exe --fail --location --retry 3 --output tools\php.zip https://downloads.php.net/~windows/qa/php-8.6.0RC2-nts-Win32-vs18-x64.zip
if not "!errorlevel!"=="0" curl.exe --fail --location --retry 3 --output tools\php.zip https://downloads.php.net/~windows/qa/archives/php-8.6.0RC2-nts-Win32-vs18-x64.zip
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile tools\php.zip SHA256 | findstr /i /c:"9fc46a9761a4799994085caa5c0035b06ae2c1d7cfcf65670154f0cb366ea354" >nul
if not "!errorlevel!"=="0" exit /b 1
if not exist tools\php-8.6.0RC2 mkdir tools\php-8.6.0RC2
tar.exe -xf tools\php.zip -C tools\php-8.6.0RC2
exit /b %errorlevel%
:compare
for %%n in (compat language) do (
    tools\php-8.6.0RC2\php.exe -n examples\%%n.php > build\php-%%n.txt
    if not "!errorlevel!"=="0" exit /b 1
    "%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar build\graalphp.jar examples\%%n.php > build\graalphp-%%n.txt
    if not "!errorlevel!"=="0" exit /b 1
    fc /b build\php-%%n.txt build\graalphp-%%n.txt
    if not "!errorlevel!"=="0" exit /b 1
)
exit /b %errorlevel%
:ffi_bridge_test
call :native_libraries
if not "!errorlevel!"=="0" exit /b 1
tools\xmake.exe -y ffi-bridge-fixture
if not "!errorlevel!"=="0" exit /b 1
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -cp "build\classes;%DEPS%/*" -d build\test-classes tests\graalphp\NativeBridgeTest.java
if not "!errorlevel!"=="0" exit /b 1
if /i "%~2"=="native" (
    call "%GRAALPHP_JDK%\bin\native-image.cmd" -O1 -march=compatibility --initialize-at-build-time=graalphp.truffle,graalphp.runtime --enable-native-access=ALL-UNNAMED -J-Xmx6g --parallelism=8 -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.NativeBridgeTest -o build\ffi-bridge-test-runner
    if not "!errorlevel!"=="0" exit /b 1
    build\ffi-bridge-test-runner.exe build\ffi-bridge-fixture.dll
) else (
    "%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.NativeBridgeTest build\ffi-bridge-fixture.dll
)
exit /b %errorlevel%
:native_stack_probe
call :native_libraries
if not "!errorlevel!"=="0" exit /b 1
tools\xmake.exe -y native-stack-probe
if not "!errorlevel!"=="0" exit /b 1
if not exist build\test-classes mkdir build\test-classes
"%GRAALPHP_JDK%\bin\javac.exe" --release 25 -proc:none -cp "build\classes;%DEPS%/*" -d build\test-classes tests\graalphp\NativeStackProbe.java
if not "!errorlevel!"=="0" exit /b 1
if /i "%~2"=="native" (
    call "%GRAALPHP_JDK%\bin\native-image.cmd" -O1 -march=compatibility --initialize-at-build-time=graalphp.truffle,graalphp.runtime --enable-native-access=ALL-UNNAMED -J-Xmx6g --parallelism=8 -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.NativeStackProbe -o build\native-stack-probe-runner
    if not "!errorlevel!"=="0" exit /b 1
    build\native-stack-probe-runner.exe build\native-stack-probe.dll
) else (
    "%GRAALPHP_JDK%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "build\classes;build\test-classes;%DEPS%/*" graalphp.NativeStackProbe build\native-stack-probe.dll
)
exit /b %errorlevel%
:native
call :native_libraries
if not "!errorlevel!"=="0" exit /b 1
set "NATIVE_NAME=graalphp"
set "NATIVE_MONITORING="
set "NATIVE_CLASSES=build\classes"
if /i "%~1"=="profile-native" (
    set "NATIVE_NAME=graalphp-profile"
    set "NATIVE_MONITORING=--enable-monitoring=jfr"
)
if /i "%~1"=="reactor-probe" (
    "%GRAALPHP_JDK%\bin\java.exe" BuildSupport.java reactor-probe "%~2"
    if not "!errorlevel!"=="0" exit /b 1
    set "NATIVE_NAME=graalphp-probe-%~2"
    set "NATIVE_CLASSES=build\probe-%~2-classes"
)
call "%GRAALPHP_JDK%\bin\native-image.cmd" -O3 -march=compatibility --initialize-at-build-time=graalphp.truffle,graalphp.runtime --enable-native-access=ALL-UNNAMED !NATIVE_MONITORING! -J-Xmx6g --parallelism=8 -cp "!NATIVE_CLASSES!;%DEPS%/*" graalphp.Main -o build\!NATIVE_NAME!
exit /b %errorlevel%
:toolchain
if exist tools\msvcup.exe goto install_toolchain
curl.exe --fail --location --retry 3 -o tools\msvcup.exe https://github.com/mefistofelix/msvcup/releases/download/2b2e5062e7da/msvcup.exe
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile tools\msvcup.exe SHA256 | findstr /i /c:"a9d275a5378f56547eb022715d9a351684b6124761f6d931db56a68e55772c8b" >nul
if not "!errorlevel!"=="0" exit /b 1
:install_toolchain
tools\msvcup.exe install "msvc sdk" build\toolchain
if not "!errorlevel!"=="0" exit /b 1
call build\toolchain\vcvars64.bat
exit /b %errorlevel%
:native_libraries
call :toolchain
if not "!errorlevel!"=="0" exit /b 1
if not exist tools\xmake.exe curl.exe --fail --location --retry 3 -o tools\xmake.exe https://raw.githubusercontent.com/mefistofelix/php-xmake/f6526d592bdcedf8b43fb2b5f2d834df058ef713/xmake.exe
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile tools\xmake.exe SHA256 | findstr /i /c:"c761833a96191dbdf55a87df4db3eae66ee102aa6a0d19a96209c38d963983d4" >nul
if not "!errorlevel!"=="0" exit /b 1
if not exist tools\hx.exe curl.exe --fail --location --retry 3 -o tools\hx.exe https://github.com/mefistofelix/hx/releases/download/v1.0.24/hx.exe
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile tools\hx.exe SHA256 | findstr /i /c:"600ad92f63e5773f362acf17d8787c7102498ec1bb870d522f6a702a6ad1f291" >nul
if not "!errorlevel!"=="0" exit /b 1
set "NEED_LLVM="
if not exist tools\llvm\bin\llvm-nm.exe set "NEED_LLVM=1"
if not exist tools\llvm\bin\llvm-objcopy.exe set "NEED_LLVM=1"
if defined NEED_LLVM (
    tools\hx.exe -delpathseg 1 -repath "bin/llvm-*.exe" https://github.com/llvm/llvm-project/releases/download/llvmorg-22.1.8/clang+llvm-22.1.8-x86_64-pc-windows-msvc.tar.xz tools/llvm > build\llvm-fetch.log
    if not "!errorlevel!"=="0" exit /b 1
)
certutil -hashfile tools\llvm\bin\llvm-nm.exe SHA256 | findstr /i /c:"be16316535c403048b3d73282f4280b23b00bf5dc9b4c69d4c0a6881fbfac4d9" >nul
if not "!errorlevel!"=="0" exit /b 1
certutil -hashfile tools\llvm\bin\llvm-objcopy.exe SHA256 | findstr /i /c:"f257e6c8e936d21309f813a5a79d649de3d5c2f3bff7a15da4857f6626d16f4d" >nul
if not "!errorlevel!"=="0" exit /b 1
if not exist build\native mkdir build\native
set "XMAKE_GLOBALDIR=%~dp0build\xmake-global"
tools\xmake.exe f -y -p windows -a x64 -m release --sdk=build/toolchain --curl_costs=n
if not "!errorlevel!"=="0" exit /b 1
tools\xmake.exe -y graalphp-native
if not "!errorlevel!"=="0" exit /b 1
tools\xmake.exe -y graalphp-builtins
if not "!errorlevel!"=="0" exit /b 1
for %%n in (msvcp140 vcruntime140 vcruntime140_1) do (
    copy /y "%VCToolsInstallDir%bin\Hostx64\x64\%%n.dll" build\ >nul
    if not "!errorlevel!"=="0" exit /b 1
)
exit /b 0
