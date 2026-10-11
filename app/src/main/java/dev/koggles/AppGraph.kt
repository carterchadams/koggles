package dev.koggles

/** Process-wide singletons. Later tasks add services here as `val`s. */
object AppGraph {
    lateinit var app: KogglesApp
        private set

    fun init(app: KogglesApp) {
        this.app = app
    }
}
