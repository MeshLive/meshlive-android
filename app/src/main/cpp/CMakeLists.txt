cmake_minimum_required(VERSION 3.22.1)
project(bluetooth_video_native LANGUAGES C CXX)

# Host-only mode for the JVM round-trip test of the experimental libopus call codec: builds just the
# Opus JNI bridge as a desktop library (no libaom, no Android log), e.g.
#   cmake -S app/src/main/cpp -B <temp>/opus-host -DBTV_OPUS_HOST_TEST=ON -DJNI_INCLUDE_DIRS=...
# then run the unit tests with BTV_NATIVE_OPUS_HOST_LIB=<built library>. See NativeOpusHostRoundTripTest.
option(BTV_OPUS_HOST_TEST "Build only the libopus JNI bridge for host JVM tests" OFF)

if(NOT BTV_OPUS_HOST_TEST)
    set(CMAKE_ASM_COMPILER "${CMAKE_C_COMPILER}" CACHE FILEPATH "" FORCE)
    # Debug APKs carry benchmark instrumentation, but codec code must still be
    # optimized or their FPS/thermal results are meaningless.
    add_compile_options(-O3)
    # Reproducible builds: clang writes its own version text into .comment ("llvm.ident"). The Windows and the
    # Linux NDK clang of the same NDK print different text (the Linux one adds "+pgo, +bolt, +lto, +mlgo"), so
    # the library differed by host (measured 2026-10-29: F-Droid's Linux build of 0.4.2-beta vs our Windows
    # one). -fno-ident drops it for every source compiled here (C, C++ and assembler, all subdirectories); the
    # NDK's prebuilt runtime objects keep their own identical text, and lld adds the same "Linker: LLD" line.
    add_compile_options(-fno-ident)
endif()

# Reproducible builds (tools/check_reproducible_build.py): assert/__FILE__ strings in libopus embedded the
# absolute checkout path, so two builds in different directories differed. -ffile-prefix-map rewrites every
# source, generated-file and build-directory prefix to a fixed /btv/<name> before it reaches an object file.
# Both the logical (junction/symlink kept) and the resolved path are mapped. Applies to the directory scope,
# so it must be called before the add_subdirectory() of the tree it covers.
function(btv_map_source_prefix dir name)
    get_filename_component(abs_dir "${dir}" ABSOLUTE)
    get_filename_component(real_dir "${dir}" REALPATH)
    foreach(prefix IN ITEMS "${abs_dir}" "${real_dir}")
        add_compile_options("-ffile-prefix-map=${prefix}=/btv/${name}")
    endforeach()
endfunction()
if(NOT BTV_OPUS_HOST_TEST)
    btv_map_source_prefix("${CMAKE_CURRENT_LIST_DIR}" app)
    btv_map_source_prefix("${CMAKE_BINARY_DIR}" build)
    # The NDK toolchain folder (bin/.. of the compiler: sysroot and clang resource-dir headers). Its path, with
    # the SDK location and host tag (windows-x86_64 vs linux-x86_64), was in the debug line tables. Those are
    # stripped from the APK, but lld's build ID hashes the unstripped output, so the APK's build ID differed by
    # host even when every other byte matched (measured 2026-10-29 against F-Droid's Linux build).
    get_filename_component(btv_ndk_toolchain "${CMAKE_C_COMPILER}" DIRECTORY)
    get_filename_component(btv_ndk_toolchain "${btv_ndk_toolchain}" DIRECTORY)
    btv_map_source_prefix("${btv_ndk_toolchain}" ndk-toolchain)
endif()

# Reproducible builds: libaom (cmake/version.cmake) and libopus (cmake/OpusPackageVersion.cmake) run
# `git describe` when Git is found and their source folder has git metadata, and compile the result into the
# library. F-Droid's full srclib clone gave libaom "3.14.1-152-g0006e96cdb"; our shallow fetch has no tags and
# gave "v3.14.1" (measured 2026-10-29). Hiding Git from both makes them take their documented no-Git path
# (libaom: "Git missing, version will be read from CHANGELOG", the first "vX.Y.Z" line; libopus: the
# package_version file), the same text a source export without .git produces on any host. The value must stay
# non-empty (libaom's version.cmake rejects an empty GIT_EXECUTABLE) and must not exist (it checks EXISTS).
# Normal variables, so they shadow any GIT_EXECUTABLE left in an older CMake cache.
set(CMAKE_DISABLE_FIND_PACKAGE_Git TRUE)
set(GIT_EXECUTABLE "${CMAKE_BINARY_DIR}/GIT_EXECUTABLE-NOTFOUND")

# Experimental native call audio (DEBUG test_call_native_opus, default off): libopus 1.5.2, the
# official release tarball https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
# (SHA-256 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1, xiph SHA256SUMS.txt)
# extracted to reference-repos/libopus. Fixed configuration: float build, no DRED/OSCE/deep PLC
# (no DNN weights), so the library adds only the classic SILK/CELT codec.
set(OPUS_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../reference-repos/libopus")
if(NOT EXISTS "${OPUS_SOURCE_DIR}/include/opus.h" OR NOT EXISTS "${OPUS_SOURCE_DIR}/package_version")
    message(FATAL_ERROR "Pinned libopus 1.5.2 source is missing at ${OPUS_SOURCE_DIR} (extract the release tarball named in CMakeLists.txt)")
endif()
file(READ "${OPUS_SOURCE_DIR}/package_version" BTV_OPUS_PACKAGE_VERSION)
if(NOT BTV_OPUS_PACKAGE_VERSION MATCHES "PACKAGE_VERSION=.1[.]5[.]2.")
    message(FATAL_ERROR "reference-repos/libopus is not the pinned 1.5.2 release: ${BTV_OPUS_PACKAGE_VERSION}")
endif()
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT BTV_OPUS_HOST_TEST)
    btv_map_source_prefix("${OPUS_SOURCE_DIR}" libopus)
endif()
add_subdirectory("${OPUS_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/libopus" EXCLUDE_FROM_ALL)

if(BTV_OPUS_HOST_TEST)
    if(NOT JNI_INCLUDE_DIRS)
        find_package(JNI REQUIRED)
    endif()
    add_library(bluetooth_video_opus_host SHARED native_opus_codec.cpp)
    target_compile_features(bluetooth_video_opus_host PRIVATE cxx_std_17)
    target_include_directories(bluetooth_video_opus_host PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(bluetooth_video_opus_host PRIVATE opus)
    return()
endif()

# Pinned libaom checkout (THIRD_PARTY_NOTICES). Default: reference-repos/libaom; Gradle -PaomSourceDir=<dir> overrides.
if(NOT AOM_SOURCE_DIR)
    set(AOM_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../reference-repos/libaom")
endif()
if(NOT EXISTS "${AOM_SOURCE_DIR}/aom/aom_encoder.h")
    message(FATAL_ERROR "Pinned libaom source is missing at ${AOM_SOURCE_DIR}")
endif()

set(CONFIG_AV1_ENCODER 1 CACHE BOOL "" FORCE)
set(CONFIG_AV1_DECODER 0 CACHE BOOL "" FORCE)
set(CONFIG_REALTIME_ONLY 0 CACHE BOOL "" FORCE)
set(ENABLE_APPS 0 CACHE BOOL "" FORCE)
set(ENABLE_DOCS 0 CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES 0 CACHE BOOL "" FORCE)
set(ENABLE_TESTDATA 0 CACHE BOOL "" FORCE)
set(ENABLE_TESTS 0 CACHE BOOL "" FORCE)
set(ENABLE_TOOLS 0 CACHE BOOL "" FORCE)
set(CONFIG_WEBM_IO 0 CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS 0 CACHE BOOL "" FORCE)

# Expected libaom version text: the first "vX.Y.Z" line of the pinned commit's CHANGELOG (no-Git path above).
# In that path libaom keeps an existing config/aom_version.h, so one written earlier from `git describe` in a
# reused build folder is removed first; the check after add_subdirectory() fails on any other text.
set(BTV_AOM_VERSION_STRING "v3.14.1")
set(BTV_AOM_VERSION_HEADER "${CMAKE_BINARY_DIR}/libaom/config/aom_version.h")
function(btv_read_aom_version out_var)
    set(text "")
    if(EXISTS "${BTV_AOM_VERSION_HEADER}")
        file(STRINGS "${BTV_AOM_VERSION_HEADER}" text REGEX "^#define VERSION_STRING_NOSP ")
        string(REGEX REPLACE "^#define VERSION_STRING_NOSP \"(.*)\"$" "\\1" text "${text}")
    endif()
    set(${out_var} "${text}" PARENT_SCOPE)
endfunction()
btv_read_aom_version(BTV_AOM_VERSION_FOUND)
if(EXISTS "${BTV_AOM_VERSION_HEADER}" AND NOT BTV_AOM_VERSION_FOUND STREQUAL BTV_AOM_VERSION_STRING)
    file(REMOVE "${BTV_AOM_VERSION_HEADER}")
endif()

btv_map_source_prefix("${AOM_SOURCE_DIR}" libaom)
add_subdirectory("${AOM_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/libaom" EXCLUDE_FROM_ALL)
btv_read_aom_version(BTV_AOM_VERSION_FOUND)
if(NOT BTV_AOM_VERSION_FOUND STREQUAL BTV_AOM_VERSION_STRING)
    message(FATAL_ERROR "libaom version text is '${BTV_AOM_VERSION_FOUND}', expected '${BTV_AOM_VERSION_STRING}' (the pinned CHANGELOG version): update BTV_AOM_VERSION_STRING with the libaom pin")
endif()

# Bundled libvpx VP9 encoder (DECISIONS "Design VX"): libvpx v1.17.0, the commit of tag v1.17.0
# (6df3ec34557879fff673706f4a1d9fbd0f3a6f0e, tree manifest SHA-256 in tools/fetch_native_sources.py) at
# reference-repos/libvpx; Gradle -PvpxSourceDir=<dir> overrides. libvpx builds with configure + make, so (as
# Android's external/libvpx and Chromium do) its configure output for each ABI is generated once by
# tools/generate_libvpx_config.py and checked in under libvpx_config/<abi>/: VP9 encoder only, real-time only,
# no VP8/decoder/high bit depth, NEON (arm64: dotprod/i8mm kernels picked at run time).
if(NOT VPX_SOURCE_DIR)
    set(VPX_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../reference-repos/libvpx")
endif()
if(NOT EXISTS "${VPX_SOURCE_DIR}/vpx/vp8cx.h" OR NOT EXISTS "${VPX_SOURCE_DIR}/CHANGELOG")
    message(FATAL_ERROR "Pinned libvpx v1.17.0 source is missing at ${VPX_SOURCE_DIR} (python tools/fetch_native_sources.py)")
endif()
file(STRINGS "${VPX_SOURCE_DIR}/CHANGELOG" BTV_VPX_CHANGELOG_HEAD LIMIT_COUNT 1)
if(NOT BTV_VPX_CHANGELOG_HEAD MATCHES " v1[.]17[.]0 ")
    message(FATAL_ERROR "${VPX_SOURCE_DIR} is not the pinned libvpx v1.17.0: ${BTV_VPX_CHANGELOG_HEAD}")
endif()
set(VPX_CONFIG_DIR "${CMAKE_CURRENT_LIST_DIR}/libvpx_config/${ANDROID_ABI}")
if(NOT EXISTS "${VPX_CONFIG_DIR}/sources.cmake")
    message(FATAL_ERROR "No libvpx configuration for ABI ${ANDROID_ABI} (tools/generate_libvpx_config.py)")
endif()
include("${VPX_CONFIG_DIR}/sources.cmake")
list(TRANSFORM VPX_SOURCES PREPEND "${VPX_SOURCE_DIR}/")
btv_map_source_prefix("${VPX_SOURCE_DIR}" libvpx)
add_library(btv_vpx_vp9_encoder STATIC ${VPX_SOURCES} "${VPX_CONFIG_DIR}/vpx_config.c")
target_include_directories(btv_vpx_vp9_encoder PUBLIC "${VPX_CONFIG_DIR}" "${VPX_SOURCE_DIR}")
# libvpx's configure adds -DNDEBUG outside --enable-debug; debug APKs carry benchmarks, so keep it release-like.
target_compile_definitions(btv_vpx_vp9_encoder PRIVATE NDEBUG)
target_compile_options(btv_vpx_vp9_encoder PRIVATE -w)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # libvpx build/make/Makefile: these kernels are built for the extension and called only when
    # vpx_ports/aarch64_cpudetect.c finds it (AT_HWCAP on Android).
    foreach(source IN LISTS VPX_SOURCES)
        if(source MATCHES "_neon_dotprod[.]c$")
            set_source_files_properties("${source}" PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
        elseif(source MATCHES "_neon_i8mm[.]c$")
            set_source_files_properties("${source}" PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod+i8mm")
        endif()
    endforeach()
endif()

add_library(bluetooth_video_native SHARED native_aom_encoder.cpp native_vpx_encoder.cpp native_opus_codec.cpp)
target_compile_features(bluetooth_video_native PRIVATE cxx_std_17)
target_compile_options(bluetooth_video_native PRIVATE -Wall -Wextra -Werror)
target_include_directories(
    bluetooth_video_native
    PRIVATE
    "${AOM_SOURCE_DIR}"
    "${CMAKE_BINARY_DIR}/libaom"
)
target_link_libraries(bluetooth_video_native PRIVATE aom btv_vpx_vp9_encoder opus log)

# 16 KB page-size compatibility (developer.android.com/guide/practices/page-sizes). NDK r28 defaults
# max-page-size to 16 KB but leaves common-page-size at 4 KB, so the GNU_RELRO segment can end on a
# 4 KB boundary: the Debug build's libbluetooth_video_native.so failed the documented RELRO check
# ((VirtAddr + MemSiz) % 0x4000 == 0, remainder 8192) while Release happened to pass. Both flags make
# the layout deterministic; tools/check_apk_16kb.py repeats the check on a built APK.
target_link_options(bluetooth_video_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

# Reproducible builds: the release library is linked without any non-loaded section. lld computes the build ID
# (.note.gnu.build-id, which stays in the APK) over its whole output, and AGP strips .debug_*, .symtab and .strtab
# only afterwards, when it packages the APK. Those sections still differed between the Windows and the Linux NDK
# (measured 2026-10-29: F-Droid's Linux build of 0.4.3-beta matched ours in every byte of the library except the
# 20-byte build ID), and part of the debug information comes from the NDK's prebuilt libc++, which no compile
# flag reaches. --strip-all drops them at link time, so the build ID hashes only what the APK keeps. Compilation
# is unchanged (compiling with -g0 instead was measured to change 96 bytes of code). Debug builds keep debug
# information and symbols for native debugging.
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_link_options(bluetooth_video_native PRIVATE "-Wl,--strip-all")
endif()
