// Linux host regression of the production mirror freopen implementation.
// tools/test_krkr_mirror_stdio.py extracts the actual C++ function bodies.
#include <atomic>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <iterator>
#include <stdio_ext.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

using FopenFn = FILE* (*)(const char*, const char*);
using FreopenFn = FILE* (*)(const char*, const char*, FILE*);
std::atomic<bool> gMirrorActive{true};
constexpr int kMirrorOpenFailed = -2;
constexpr const char* kTag = "host-test";
constexpr int ANDROID_LOG_WARN = 1;
int __android_log_print(int, const char*, const char*, ...) { return 0; }

std::string resolveEnginePath(const char* path) { return path == nullptr ? "" : path; }
bool pathMatchesPrefix(const char*) { return true; }
std::string callJavaScopedSaveRedirect(const char*) { return {}; }
std::string callJavaOpenRedirect(const char*, int) { return {}; }
std::string callJavaRedirect(const char*) { return {}; }

// Match RandomAccessFile plus NativeBridge's explicit truncation/append seek.
// In particular the Java fd does not carry O_APPEND: FILE must be rebuilt.
int callJavaOpen(const char* path, int flags) {
    const int access = (flags & O_ACCMODE) == O_RDONLY ? O_RDONLY : O_RDWR;
    const int fd = ::open(path, access | (flags & (O_TRUNC | O_CREAT)), 0600);
    if (fd >= 0 && (flags & O_APPEND) != 0) assert(lseek(fd, 0, SEEK_END) >= 0);
    return fd;
}

// Emulate Bionic's rejection of fflush on a pure read-only FILE.
int bionicFlush(FILE* stream) {
    if (!__fwritable(stream)) {
        errno = EBADF;
        return EOF;
    }
    return ::fflush(stream);
}
#define fflush bionicFlush
#include "krkr_mirror_stdio_under_test.inc"
#undef fflush

static void put(const std::string& path, const std::string& value) {
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    file << value;
    assert(file.good());
}
static std::string get(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}
static std::string readAll(FILE* stream) {
    std::string value;
    int next;
    while ((next = fgetc(stream)) != EOF) value.push_back(static_cast<char>(next));
    assert(!ferror(stream));
    return value;
}
static FILE* reopen(FILE* stream, const std::string& path, const char* mode) {
    FILE* result = hookedFreopenCommon(path.c_str(), mode, stream, ::freopen);
    assert(result != nullptr);
    return result;
}

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::string path = std::string(argv[1]) + "/first";
    const std::string second = std::string(argv[1]) + "/second";

    put(path, "ABC");
    FILE* stream = fopen(path.c_str(), "r+");
    assert(stream);
    stream = reopen(stream, path, "a+");
    assert(fseek(stream, 0, SEEK_SET) == 0);
    assert(fwrite("X", 1, 1, stream) == 1);
    assert(fclose(stream) == 0);
    assert(get(path) == "ABCX");

    put(path, "ABC");
    stream = fopen(path.c_str(), "a+");
    assert(stream);
    stream = reopen(stream, path, "r+");
    assert(fseek(stream, 0, SEEK_SET) == 0);
    assert(fwrite("X", 1, 1, stream) == 1);
    assert(fclose(stream) == 0);
    assert(get(path) == "XBC");

    put(path, "ABC");
    stream = fopen(path.c_str(), "r+");
    assert(stream);
    char outputBuffer[4096];
    assert(setvbuf(stream, outputBuffer, _IOFBF, sizeof(outputBuffer)) == 0);
    assert(fwrite("OLD", 1, 3, stream) == 3);
    assert(__fpending(stream) == 3);
    stream = reopen(stream, path, "w+");
    struct stat info {};
    assert(fstat(fileno(stream), &info) == 0);
    assert(info.st_size == 0);
    assert(fwrite("N", 1, 1, stream) == 1);
    assert(fclose(stream) == 0);
    assert(get(path) == "N");

    put(path, "ABCDEFG");
    put(second, "123");
    stream = fopen(path.c_str(), "rb");
    assert(stream);
    char inputBuffer[4096];
    assert(setvbuf(stream, inputBuffer, _IOFBF, sizeof(inputBuffer)) == 0);
    assert(fgetc(stream) == 'A');
    stream = reopen(stream, second, "rb");
    assert(readAll(stream) == "123");
    assert(fclose(stream) == 0);

    put(path, "ABC");
    stream = fopen(path.c_str(), "rb");
    assert(stream);
    stream = reopen(stream, second, "w+");
    assert(fwrite("Z", 1, 1, stream) == 1);
    rewind(stream);
    assert(readAll(stream) == "Z");
    assert(fclose(stream) == 0);

    put(path, "ABC");
    stream = fopen(path.c_str(), "r+");
    assert(stream);
    char pendingBuffer[4096];
    assert(setvbuf(stream, pendingBuffer, _IOFBF, sizeof(pendingBuffer)) == 0);
    assert(fwrite("OLD", 1, 3, stream) == 3);
    assert(__fpending(stream) == 3);
    stream = reopen(stream, path, "rb");
    assert(readAll(stream) == "OLD");
    assert(fwrite("X", 1, 1, stream) == 0);
    assert(ferror(stream));
    fclose(stream);
    assert(get(path) == "OLD");

    // Rebuilding a same-mode stream must preserve ordinary update semantics.
    put(path, "ABC");
    put(second, "123");
    stream = fopen(path.c_str(), "r+");
    assert(stream);
    stream = reopen(stream, second, "r+");
    assert(fseek(stream, 0, SEEK_SET) == 0);
    assert(fwrite("X", 1, 1, stream) == 1);
    assert(fclose(stream) == 0);
    assert(get(second) == "X23");

    // A read-only stream reopened for writing acquires FILE write permissions.
    const std::string third = std::string(argv[1]) + "/third";
    put(path, "ABC");
    stream = fopen(path.c_str(), "rb");
    assert(stream);
    stream = reopen(stream, third, "w");
    assert(fwrite("N", 1, 1, stream) == 1);
    assert(fclose(stream) == 0);
    assert(get(third) == "N");

    // Audit R1: use the actual hookFopenCommon entry, whose Java-first O_RDWR fd
    // is deliberately wider than the pure-write FILE requested by fdopen("w").
    // Matching descriptor access cannot prove that the old FILE can read.
    put(second, "123");
    stream = hookFopenCommon(::fopen, path.c_str(), "w");
    assert(stream);
    assert((fcntl(fileno(stream), F_GETFL) & O_ACCMODE) == O_RDWR);
    assert(__fwritable(stream));
    assert(!__freadable(stream));
    stream = reopen(stream, second, "r+");
    assert(readAll(stream) == "123");
    assert(fseek(stream, 0, SEEK_SET) == 0);
    assert(fwrite("X", 1, 1, stream) == 1);
    assert(fclose(stream) == 0);
    assert(get(second) == "X23");

    puts("9 production mirror freopen host regressions passed");
}
