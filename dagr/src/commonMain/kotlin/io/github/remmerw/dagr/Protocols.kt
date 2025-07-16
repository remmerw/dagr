package io.github.remmerw.dagr

class Protocols {
    private val protocols: MutableMap<String, Handler> = mutableMapOf()
    fun names(): Set<String> {
        return protocols.keys.toSet()
    }

    fun put(key: String, handler: Handler) {
        protocols.put(key, handler)
    }

    fun get(key: String): Handler? {
        return protocols[key]
    }
}