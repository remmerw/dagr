package io.github.remmerw.dagr


internal enum class Level {
    INIT, APP;

    companion object {
        const val LENGTH: Int = 2
        private val cached = entries.toTypedArray()

        fun levels(): Array<Level> {
            return cached
        }
    }
}
