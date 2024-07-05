package org.example

import io.vertx.ext.web.RoutingContext
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jboss.logging.Logger
import java.util.concurrent.CancellationException

const val delayDuration: Long = 10000

@Path("/long")
class ExampleResource(private val log: Logger) {
    @GET
    @Path("a")
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun longRunning(rc: RoutingContext): String = coroutineScope {
        val requestJob = coroutineContext[Job]!!
        // Listen for the connection close event
        rc.request().connection().closeHandler {
            requestJob.cancel(CancellationException("Client connection closed"))
        }
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