#pragma once

#include "audio_window.h"

namespace koggles {

// Process-lifetime state shared by audio producers and the GL renderer. Never destroyed.
class Engine {
public:
    static Engine& instance();
    AudioWindow& audio() { return audio_; }

private:
    Engine() = default;
    AudioWindow audio_;
};

}  // namespace koggles
