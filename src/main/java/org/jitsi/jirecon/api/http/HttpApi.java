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
	 * Jirecon taskmanager, use to start, stop task, stop service
	 */
	private TaskManager taskManager;

	public HttpApi(TaskManager taskManager) {
		this.taskManager = taskManager;
	}

	/**
	 * [startService] will start a new task service.
	 * Returns a response with [Response.Status.OK] on success, [Response.Status.NO_CONTENT] if error
	 */
	@GET
	@Path("start/{room}")
	public Response startService(@PathParam(value = "room") String room)
	{
		if (taskManager.startJireconTask(room))
			return Response.ok().build();
		return Response.noContent().build();
	}

	/**
	 * [stopService] will stop the current service immediately
	 */
	@GET
	@Path("stop/{room}")
	public Response stopService(@PathParam(value = "room") String room) {
		if (taskManager.stopJireconTask(room, true))
			return Response.ok().build();
		return Response.noContent().build();
	}

}
