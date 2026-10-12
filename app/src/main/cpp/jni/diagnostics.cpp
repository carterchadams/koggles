#include "diagnostics.h"

#ifdef KOGGLES_SHADER_DIAGNOSTICS

#include <android/log.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <mutex>
#include <thread>

namespace koggles::diag {
namespace {

constexpr size_t kMaxBytes = 1u << 20;              // beyond this, lines are dropped and reported
constexpr const char kSeqPrefix[] = "\x01KOGGLES-SEQ ";  // drain marker, written through the same pipe

// stderr is redirected into a pipe when the library loads; a reader thread splits it into lines,
// mirrors them to logcat and keeps them for take(). take() drains deterministically: it writes a
// sequence marker after everything already written and waits until the reader has consumed it.
class Capture {
public:
    void start() {
        int fds[2];
        if (pipe(fds) != 0) return;
        std::cerr.flush();
        fflush(stderr);
        if (dup2(fds[1], STDERR_FILENO) < 0) return;
        close(fds[1]);
        std::thread([this, fd = fds[0]] { readLoop(fd); }).detach();
        started_ = true;
    }

    std::string take() {
        if (!started_) return "KOGGLES-DIAG-NOT-STARTED\n";
        unsigned long seq;
        {
            std::lock_guard<std::mutex> lock(mu_);
            seq = ++nextSeq_;
        }
        std::cerr.flush();
        fflush(stderr);
        const std::string marker = std::string(kSeqPrefix) + std::to_string(seq) + "\n";
        if (write(STDERR_FILENO, marker.data(), marker.size()) < 0) return "KOGGLES-DIAG-DRAIN-TIMEOUT\n";
        std::unique_lock<std::mutex> lock(mu_);
        const bool drained = cv_.wait_for(lock, std::chrono::seconds(5), [&] { return consumedSeq_ >= seq; });
        std::string out;
        out.swap(buffer_);
        if (overflowed_) {
            out = "KOGGLES-DIAG-OVERFLOW\n" + out;
            overflowed_ = false;
        }
        if (!drained) out = "KOGGLES-DIAG-DRAIN-TIMEOUT\n" + out;
        return out;
    }

private:
    void readLoop(int fd) {
        std::string pending;
        char chunk[4096];
        for (;;) {
            const ssize_t n = read(fd, chunk, sizeof chunk);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) return;
            pending.append(chunk, static_cast<size_t>(n));
            size_t pos;
            while ((pos = pending.find('\n')) != std::string::npos) {
                onLine(pending.substr(0, pos));
                pending.erase(0, pos + 1);
            }
        }
    }

    void onLine(const std::string& line) {
        std::lock_guard<std::mutex> lock(mu_);
        if (line.rfind(kSeqPrefix, 0) == 0) {
            consumedSeq_ = std::stoul(line.substr(sizeof(kSeqPrefix) - 1));
            cv_.notify_all();
            return;
        }
        __android_log_print(ANDROID_LOG_WARN, "projectM", "%s", line.c_str());
        if (buffer_.size() + line.size() + 1 > kMaxBytes) {
            overflowed_ = true;
            return;
        }
        buffer_ += line;
        buffer_ += '\n';
    }

    std::mutex mu_;
    std::condition_variable cv_;
    std::string buffer_;
    bool overflowed_ = false;
    unsigned long consumedSeq_ = 0;
    unsigned long nextSeq_ = 0;
    bool started_ = false;
};

Capture& capture() {
    static Capture c;
    return c;
}

// Capture from the moment the library loads, before any preset is loaded.
const bool kStarted = [] {
    capture().start();
    return true;
}();

}  // namespace

bool enabled() { return true; }

std::string take() { return capture().take(); }

}  // namespace koggles::diag

#else

namespace koggles::diag {

bool enabled() { return false; }

std::string take() { return {}; }

}  // namespace koggles::diag

#endif
