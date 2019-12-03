package org.jitsi.jirecon.api.http.internal;

import java.util.concurrent.TimeUnit;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import net.java.sip.communicator.util.Logger;

@Path("/jirecon/internal")
public class InternalHttpApi {

    private Logger logger = Logger.getLogger(InternalHttpApi.class);

    private Runnable configChangedHandler;
    private Runnable gracefulShutdownHandler;
    private Runnable shutdownHandler;

    public InternalHttpApi(
    		Runnable configChangedHandler,
    		Runnable gracefulShutdownHandler,
    		Runnable shutdownHandler
    	) {
    	this.configChangedHandler = configChangedHandler;
    	this.gracefulShutdownHandler = gracefulShutdownHandler;
    	this.shutdownHandler = shutdownHandler;
    }

    /**
     * Signal this Jirecon to shutdown gracefully, meaning shut down when
     * it is idle (i.e. finish any currently running service). Returns a 200
     * and schedules a shutdown for when it becomes idle.
     */
    @POST
    @Path("gracefulShutdown")
    public Response gracefulShutdown() {
        logger.info("Jirecon gracefully shutting down");

        // Schedule firing the handler so we have a chance to send the successful
        // response.
        TaskPools.recurringTasksPool.schedule(gracefulShutdownHandler, 1, TimeUnit.SECONDS);
        return Response.ok().build();
    }

    /**
     * Signal this Jibri to reload its config file at the soonest opportunity
     * (when it does not have a currently running service). Returns a 200
     */
    @POST
    @Path("notifyConfigChanged")
    public Response reloadConfig() {
        logger.info("Config file changed");

        // Schedule firing the handler so we have a chance to send the successful
        // response.
        TaskPools.recurringTasksPool.schedule(configChangedHandler, 1, TimeUnit.SECONDS);
        return Response.ok().build();
    }

    /**
     * Signal this Jibri to (cleanly) stop any services that are
     * running and shutdown.  Returns a 200 and schedules a shutdown with a 1
     * second delay.
     */
    @POST
    @Path("shutdown")
    public Response shutdown() {
        logger.info("Jirecon is forcefully shutting down");
        
        // Schedule firing the handler so we have a chance to send the successful
        // response.
        TaskPools.recurringTasksPool.schedule(shutdownHandler, 1, TimeUnit.SECONDS);
        return Response.ok().build();
    }

}
