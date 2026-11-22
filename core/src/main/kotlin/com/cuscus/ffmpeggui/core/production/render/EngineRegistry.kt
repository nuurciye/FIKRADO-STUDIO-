package com.cuscus.ffmpeggui.core.production.render

/**
 * Explicit registry for available execution backends.
 *
 * Registration is deterministic: duplicate IDs are rejected rather than
 * silently replacing an adapter. The registry never invents fallback engines.
 */
class EngineRegistry(adapters: Iterable<EngineAdapter> = emptyList()) {
    private val adaptersById = linkedMapOf<EngineId, EngineAdapter>()

    init {
        adapters.forEach(::register)
    }

    @Synchronized
    fun register(adapter: EngineAdapter) {
        require(!adaptersById.containsKey(adapter.id)) {
            "An engine with ID '${adapter.id.value}' is already registered."
        }
        adaptersById[adapter.id] = adapter
    }

    @Synchronized
    fun find(id: EngineId): EngineAdapter? = adaptersById[id]

    @Synchronized
    fun require(id: EngineId): EngineAdapter =
        find(id) ?: throw NoSuchElementException("No engine registered with ID '${id.value}'.")

    @Synchronized
    fun all(): List<EngineAdapter> = adaptersById.values.toList()

    @Synchronized
    fun ids(): Set<EngineId> = adaptersById.keys.toSet()

    @Synchronized
    fun contains(id: EngineId): Boolean = adaptersById.containsKey(id)
}
