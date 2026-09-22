package com.example.teachablevoiceassistant.workflow

/**
 * In-memory storage for recorded workflows.
 *
 * Deliberately just a list behind a lock - swap the body for a Room DAO later
 * without changing the call sites (save/all/findById/latest).
 */
object WorkflowRepository {

    private val workflows = mutableListOf<Workflow>()

    @Synchronized
    fun save(workflow: Workflow) {
        workflows.add(0, workflow) // newest first
    }

    @Synchronized
    fun all(): List<Workflow> = workflows.toList()

    @Synchronized
    fun findById(id: String): Workflow? = workflows.firstOrNull { it.id == id }

    @Synchronized
    fun latest(): Workflow? = workflows.firstOrNull()

    @Synchronized
    fun clear() = workflows.clear()
}