#include "engine.h"

namespace koggles {

Engine& Engine::instance() {
    static Engine engine;
    return engine;
}

}  // namespace koggles
