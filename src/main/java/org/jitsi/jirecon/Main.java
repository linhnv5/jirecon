/*
/*
 * Jirecon, the JItsi REcording COntainer.
 *
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.jirecon;

import org.jitsi.jirecon.api.http.HttpApi;
import org.jitsi.jirecon.api.http.internal.InternalHttpApi;
import org.jitsi.jirecon.task.TaskManager;
import org.jitsi.jirecon.utils.ConfigurationKey;
import org.jitsi.service.configuration.ConfigurationService;
import org.jitsi.service.libjitsi.LibJitsi;

import net.java.sip.communicator.util.Logger;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;


/**
 * A launch application which is used to run <tt>TaskManager</tt>.
 * <p>
 * Usually there will be a associated Shell script to start this application.
 * 
 * @author lishunyang
 * 
 */
public class Main
{

	private static Logger logger = Logger.getLogger(Main.class);

	/**
     * Prefix of configuration parameter.
     */
    private static final String CONF_ARG_NAME = "--conf=";

    /**
     * Application entry.
     * 
     * @param args <tt>JireconLauncher</tt>:
     *            <p>
     *            --conf=CONFIGURATION_FILE_PATH. Indicate the path of
     *            configuration file.
     *            <p>
     * @throws Exception 
     */
    public static void main(String[] args)
    		throws Exception
    {
        String conf = null;

        for (String arg : args)
        {
            if (arg.startsWith(CONF_ARG_NAME))
                conf = arg.substring(CONF_ARG_NAME.length());
        }

        if (conf == null)
            conf = "jirecon.properties";

        TaskManager jirecon = new TaskManager();

        try
        {
            jirecon.init(conf);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

    	ConfigurationService cfg = LibJitsi.getConfigurationService();

    	int internalHttpPort = cfg.getInt(ConfigurationKey.HTTP_INTERNAL_PORT, 2333);
    	logger.info("Using port "+internalHttpPort+" for internal HTTP API");

    	// InternalHttpApi
    	Runnable configChangedHandler = () -> {
    		// Exit so we can be restarted and load the new config
    		System.exit(0);
    	};

    	Runnable gracefulShutdownHandler = () -> {
    		// Exit with code 255 to indicate we do not want process restart
    		System.exit(255);
    	};

    	Runnable shutdownHandler = () -> {
    		jirecon.uninit();

    		logger.info("Service stopped");
    		System.exit(255);
    	};

    	launchHttpServer(internalHttpPort, new InternalHttpApi(configChangedHandler, gracefulShutdownHandler, shutdownHandler));

    	int httpApiPort = cfg.getInt(ConfigurationKey.HTTP_PORT, 2323);
    	logger.info("Using port "+httpApiPort+" for the HTTP API");

    	try {
			launchHttpServer(httpApiPort, new HttpApi(jirecon));
		} catch (Exception e) {
		}
    }

    private static void launchHttpServer(int port, Object component)
    		throws Exception
    {
    	ResourceConfig jerseyConfig = new ResourceConfig();
    	jerseyConfig.register(JacksonFeature.class);
    	jerseyConfig.registerInstances(component);

    	ServletHolder servlet = new ServletHolder(new ServletContainer(jerseyConfig));
    	Server server = new Server(port);
    	ServletContextHandler context = new ServletContextHandler(server, "/*");
    	context.addServlet(servlet, "/*");
    	server.start();
    }

}
