package org.example

import io.vertx.core.http.HttpClientOptions
import io.vertx.ext.web.RoutingContext
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.*
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestHeader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


const val delayDuration: Long = 500

@Path("call")
class CallCancelResource(
    @RestClient private val helloClient: HelloClient,
    @ConfigProperty(name = "quarkus.http.port") private val serverPort: Int,
    private val log: Logger
) {
    @GET
    @Path("cancel1")
    suspend fun cancel1(@RestHeader("x-timeout") delay: Int?, ctx: RoutingContext) = coroutineScope {
        // Uses a custom Vert.x client and resets the connection on cancellation
        val delayLong = delay?.toLong() ?: 10L
        log.info("Timeout $delayLong ms")
        withTimeoutOrNull(delayLong) {
            suspendCancellableCoroutine { cont->
                val options = HttpClientOptions().setDefaultHost("localhost").setDefaultPort(serverPort)
                val client = ctx.vertx().createHttpClient(options)
                client.request(io.vertx.core.http.HttpMethod.GET, "/long/a").onComplete { conn->
                    if (conn.succeeded()) {
                        val request = conn.result()
                        cont.invokeOnCancellation {
                            log.info("coroutine cancelled. resetting connection")
                            request.reset()
                        }
                        request.send().onComplete { resp->
                            if (resp.succeeded()) {
                                val body = resp.result().body()
                                body.onComplete { bodyState->
                                    if (bodyState.succeeded()) {
                                        cont.resume(bodyState.result().toString(Charsets.UTF_8))
                                    } else {
                                        cont.resumeWithException(bodyState.cause())
                                    }
                                }
                            } else {
                                cont.resumeWithException(resp.cause())
                            }
                        }
                    } else {
                        cont.resumeWithException(conn.cause())
                    }
                }
            }
        }
    }

    @GET
    @Path("cancel2")
    suspend fun cancel2(@RestHeader("x-timeout") delay: Int?, ctx: RoutingContext) = coroutineScope {
        val delayLong = delay?.toLong() ?: 10L
        log.info("Timeout $delayLong ms")
        withTimeoutOrNull(delayLong) {
            helloClient.longRunning()
        }
    }
}

@Path("/long")
class ExampleResource(private val log: Logger) {
    @GET
    @Path("a")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun longRunning(rc: RoutingContext): String = coroutineScope {
        log.info("/long/a starting")
        async {
            delay(delayDuration)
            log.info("/long/a responding")
            "Completed request"
        }.await()
    }

    @GET
    @Path("b")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun hello(): String = coroutineScope {
        delay(delayDuration)
        log.info("/long/b responding")
        "Completed request"
    }
}

@Path("/long")
@RegisterRestClient
interface HelloClient {
    @GET
    @Path("a")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun longRunning(): String?
}
