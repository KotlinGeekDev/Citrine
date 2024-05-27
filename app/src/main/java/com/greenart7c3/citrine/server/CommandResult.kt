package com.greenart7c3.citrine.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vitorpamplona.quartz.events.Event

data class CommandResult(val eventId: String, val result: Boolean, val description: String = "") {
    fun toJson(): String {
        return jacksonObjectMapper().writeValueAsString(
            listOf("OK", eventId, result, description),
        )
    }

    companion object {
        fun ok(event: Event) = CommandResult(event.id, true)
        fun duplicated(event: Event) = CommandResult(event.id, true, "duplicate:")
        fun invalid(event: Event, message: String) = CommandResult(event.id, false, "invalid: $message")
    }
}
