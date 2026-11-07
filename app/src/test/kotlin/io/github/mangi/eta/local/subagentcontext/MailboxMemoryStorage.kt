package io.github.mangi.eta.local.subagentcontext

import io.github.mangi.eta.agent.subagent.YieldableSubagents
import java.util.concurrent.LinkedBlockingQueue
import org.json.JSONArray
import org.json.JSONObject

/** Transactional fake shared by the domain and real child-executor regressions. */
internal class MailboxMemoryStorage(val runId: String = "parent") : SubagentMailbox.Storage, YieldableSubagents.Persistence {
    val members = linkedMapOf<String, SubagentMailbox.Member>()
    private val active = mutableMapOf<String, () -> Boolean>()
    var messages = JSONArray()
    var stopped = false
    var failBeforeCommit = false
    var failAfterCommit = false
    val stored = LinkedBlockingQueue<String>()
    val finished = LinkedBlockingQueue<String>()

    @Synchronized override fun reserveGroupMember(taskId: String, toolCallId: String, round: Int, prompt: String, groupKey: String) {
        check(!stopped && taskId !in members)
        members[taskId] = SubagentMailbox.Member(taskId, "", toolCallId, groupKey, "reserved", false)
    }
    @Synchronized override fun bind(identity: SubagentMailbox.Identity, allowed: Boolean, isActive: () -> Boolean) {
        check(identity.invocation.scope.parentRunId == runId)
        val member = members.getValue(identity.invocation.taskId)
        check(member.state == "reserved" && member.subagentId.isEmpty() && member.groupKey == identity.invocation.scope.groupKey)
        members[member.taskId] = member.copy(subagentId = identity.subagentId, allowed = allowed)
        active[member.taskId] = isActive
    }
    @Synchronized override fun close(identity: SubagentMailbox.Identity) {
        val me = members.getValue(identity.invocation.taskId)
        members[me.taskId] = me.copy(state = "terminal")
        active.remove(me.taskId)
    }
    @Synchronized override fun started(taskId: String, toolCallId: String, round: Int, subagentId: String, prompt: String, accepted: String) {
        val me = members.getValue(taskId)
        check(me.subagentId == subagentId)
        members[taskId] = me.copy(state = "running")
    }
    @Synchronized override fun finished(taskId: String, report: String): Boolean {
        members[taskId] = members.getValue(taskId).copy(state = "finished")
        finished.offer(taskId)
        return !stopped
    }
    @Synchronized override fun interrupted() { stopped = true }
    @Synchronized override fun access(operation: (SubagentMailbox.State) -> JSONObject): JSONObject {
        if (failBeforeCommit) error("fixture unavailable")
        val copy = JSONArray(messages.toString())
        val snapshot = members.values.map { if (active[it.taskId]?.invoke() == false) it.copy(state = "terminal") else it }
        val result = operation(SubagentMailbox.State(runId, snapshot, copy, stopped))
        val oldSize = messages.length()
        messages = copy
        for (index in oldSize until messages.length()) stored.offer(messages.getJSONObject(index).getString("message_id"))
        if (failAfterCommit) error("fixture lost acknowledgement")
        return result
    }
    fun add(id: String, group: String = "batch", allowed: Boolean = true, isActive: () -> Boolean = { true }): SubagentMailbox.Identity {
        reserveGroupMember(id, "call-$id", 1, "fixture", group)
        val identity = SubagentMailbox.Identity(SubagentMailbox.Invocation(SubagentMailbox.Scope(runId, group), id), "sub-$id")
        bind(identity, allowed, isActive)
        started(id, "call-$id", 1, identity.subagentId, "fixture", "accepted")
        return identity
    }
}
