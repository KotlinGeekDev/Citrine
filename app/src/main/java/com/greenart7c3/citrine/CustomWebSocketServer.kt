package com.greenart7c3.citrine

import EOSE
import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.database.toEventWithTags
import com.vitorpamplona.quartz.events.Event
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketDeflateExtension
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.zip.Deflater


class CustomWebSocketServer(private val port: Int, private val context: Context) {
    private lateinit var server: ApplicationEngine
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun port(): Int {
        return server.environment.connectors.first().port
    }

    fun start() {
        server = startKtorHttpServer(port)
    }

    fun stop() {
        server.stop(1000)
    }

    private suspend fun subscribe(
        subscriptionId: String,
        filterNodes: List<JsonNode>,
        session: DefaultWebSocketServerSession
    ) {
        val filters = filterNodes.map { jsonNode ->
            val tags = jsonNode.fields().asSequence()
                .filter { it.key.startsWith("#") }
                .map { it.key.substringAfter("#") to it.value.map { item -> item.asText() }.toSet() }
                .toMap()

            val filter = objectMapper.treeToValue(jsonNode, EventFilter::class.java)

            filter.copy(tags = tags)
        }.toSet()

        EventSubscription.subscribe(subscriptionId, filters, session, context, objectMapper)
    }


    private suspend fun processNewRelayMessage(newMessage: String, session: DefaultWebSocketServerSession) {
        Log.d("message", newMessage)
        val msgArray = Event.mapper.readTree(newMessage)
        when (val type = msgArray.get(0).asText()) {
            "REQ" -> {
                val subscriptionId = msgArray.get(1).asText()
                subscribe(subscriptionId, msgArray.drop(2), session)
            }
            "EVENT" -> {
                Log.d("EVENT", newMessage)
                processEvent(msgArray.get(1), session)
            }
            "CLOSE" -> {
                EventSubscription.close(msgArray.get(1).asText())
            }
            "PING" -> {
                session.send(NoticeResult("PONG").toJson())
            }
            else -> {
                val errorMessage = NoticeResult.invalid("unknown message type $type").toJson()
                Log.d("message", errorMessage)
                session.send(errorMessage)
            }
        }
    }

    private suspend fun processEvent(eventNode: JsonNode, session: DefaultWebSocketServerSession) {
        val event = objectMapper.treeToValue(eventNode, Event::class.java)

        if (!event.hasVerifiedSignature()) {
            session.send(CommandResult.invalid(event, "event signature verification failed").toJson())
            return
        }

        val eventEntity = AppDatabase.getDatabase(context).eventDao().getByEventId(event.id)
        if (eventEntity != null) {
            session.send(CommandResult.duplicated(event).toJson())
            return
        }

        AppDatabase.getDatabase(context).eventDao().insertEventWithTags(event.toEventWithTags())

        session.send(CommandResult.ok(event).toJson())
    }

    private fun startKtorHttpServer(port: Int): ApplicationEngine {
        return embeddedServer(CIO, port = port) {
            install(WebSockets) {
                pingPeriodMillis = 1000L
                timeoutMillis = 300000L
                extensions {
                    install(WebSocketDeflateExtension) {
                        /**
                         * Compression level to use for [java.util.zip.Deflater].
                         */
                        compressionLevel = Deflater.DEFAULT_COMPRESSION

                        /**
                         * Prevent compressing small outgoing frames.
                         */
                        compressIfBiggerThan(bytes = 4 * 1024)
                    }
                }
            }

            routing {
                // Handle HTTP GET requests
                get("/") {
                    if (call.request.headers["Accept"] == "application/nostr+json") {
                        val json = """
                        {
                            "id": "ws://localhost:7777",
                            "name": "Citrine",
                            "description": "A Nostr relay in you phone",
                            "pubkey": "",
                            "supported_nips": [],
                            "software": "https://github.com/greenart7c3/Citrine",
                            "version": "0.0.1"
                        }
                        """
                        call.respondText(json, ContentType.Application.Json)
                    } else {
                        call.respondText("Use a Nostr client or Websocket client to connect", ContentType.Text.Html)
                    }
                }

                // WebSocket endpoint
                webSocket("/") {
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val message = frame.readText()
                                    processNewRelayMessage(message, this)
                                }
                                else -> {
                                    Log.d("error", frame.toString())
                                    send(NoticeResult.invalid("Error processing message").toJson())
                                }
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        Log.d("error", e.toString())
                        send(NoticeResult.invalid("Error processing message").toJson())
                    }
                }
            }
        }.start(wait = false)
    }
}