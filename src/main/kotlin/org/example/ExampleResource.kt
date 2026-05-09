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
import kotlin.time.Duration.Companion.milliseconds


const val delayDurationMs: Long = 500

@Path("call")
class CallCancelResource(
    @param:RestClient private val helloClient: HelloClient,
    @param:ConfigProperty(name = "quarkus.http.port") private val serverPort: Int,
    private val log: Logger
) {
    @GET
    @Path("cancel1")
    suspend fun cancel1(@RestHeader("x-timeout") delay: Int?, ctx: RoutingContext) = coroutineScope {
        // Uses a custom Vert.x client and resets the connection on cancellation
        val delayVal = delay ?: 10
        log.info("calling /long/a with timeout $delayVal ms")
        withTimeoutOrNull(delayVal.milliseconds) {
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
    suspend fun cancel2(@RestHeader("x-timeout") delay: Int?, ctx: RoutingContext): String {
        val delayVal = delay ?: 10
        log.info("calling /long/a with timeout $delayVal ms")
        return try {
            withTimeout(delayVal.milliseconds) {
                helloClient.longRunning()
            }
        } catch (e: CancellationException) {
            log.info("cancel2 request cancelled")
            "Cancelled"
        }
    }

    @GET
    @Path("cancel3")
    suspend fun cancel3(@RestHeader("x-timeout") delay: Int?, ctx: RoutingContext): String {
        val delayVal = delay ?: 10
        log.info("calling /long/a with timeout $delayVal ms")
        return try {
            coroutineScope {
                val t1 = async {
                    withContext(NonCancellable) {
                        delay(300.milliseconds)
                        log.info("completed non-cancellable.")
                    }
                }
                val t2 = async {
                    withTimeout(delayVal.milliseconds) {
                        val res = helloClient.longRunning()
                        log.info("completed request")
                        res
                    }
                }
                val res2 = t2.await()
                t1.await()
                res2
            }
        } catch (e: CancellationException) {
            log.info("cancel3 request cancelled")
            "Cancelled"
        }
    }
}

@Path("/long")
class ExampleResource(private val log: Logger) {
    @GET
    @Path("a")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun longRunning(rc: RoutingContext): String {
        log.info("/long/a: starting")
        try {
            delay(delayDurationMs.milliseconds)
            log.info("/long/a: responding")
            return "Completed request"
        } catch (e: CancellationException) {
            log.info("/long/a: canceling")
            return "Cancelled"
        }
    }

    @GET
    @Path("b")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun hello(): String {
        log.info("/long/b starting")
        delay(delayDurationMs.milliseconds)
        log.info("/long/b responding")
        return "Completed request"
    }
}

@Path("/long")
@RegisterRestClient
interface HelloClient {
    @GET
    @Path("a")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun longRunning(): String
}
