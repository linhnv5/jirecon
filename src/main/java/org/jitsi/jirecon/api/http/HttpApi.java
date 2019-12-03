package org.jitsi.jirecon.api.http;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import org.jitsi.jirecon.task.TaskManager;

/**
 * The [HttpApi] is for starting and stopping the various Jirecon services
 */
@Path("/jirecon")
public class HttpApi {

	/**
	 * [startService] will start a new service using the given [StartServiceParams].
	 * Returns a response with [Response.Status.OK] on success, [Response.Status.PRECONDITION_FAILED]
	 * if this Jibri is already busy and [Response.Status.INTERNAL_SERVER_ERROR] on error
	 * NOTE: start service is largely async, so a return of [Response.Status.OK] here just means Jibri
	 * was able to *try* to start the request.  We don't have a way to get ongoing updates about services
	 * via the HTTP API at this point.
	 */
	@GET
	@Path("startService/{room}")
	public Response startService(@PathParam(value = "room") String room)
	{
		if (TaskManager.gI().startJireconTask(room))
			return Response.ok().build();
		return Response.noContent().build();
	}

	/**
	 * [stopService] will stop the current service immediately
	 */
	@GET
	@Path("stopService/{room}")
	public Response stopService(@PathParam(value = "room") String room) {
		if (TaskManager.gI().stopJireconTask(room, true))
			return Response.ok().build();
		return Response.noContent().build();
	}

}
