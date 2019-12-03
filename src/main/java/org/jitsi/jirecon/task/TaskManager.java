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
package org.jitsi.jirecon.task;

import java.text.*;
import java.util.*;

import org.jitsi.jirecon.task.TaskEvent.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.jirecon.xmpp.Xmpp;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jivesoftware.smack.*;

import net.java.sip.communicator.util.Logger;

/**
 * The manager of <tt>Task</tt>s. Each <tt>Task</tt> represents a
 * recording task for a specific Jitsi Meet conference.
 *
 * @author lishunyang
 */
public final class TaskManager implements TaskEventListener
{

    /**
     * The <tt>Logger</tt> used by the <tt>TaskManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(TaskManager.class);
    
    /**
     * List of <tt>EventListener</tt>.
     */
    private List<TaskEventListener> listeners = new ArrayList<TaskEventListener>();

    /**
     * Maps an ID of a Jitsi Meet conference (the JID of the MUC) to the
     * <tt>JireconTask</tt> for the conference.
     */
    private final Map<String, Task> tasks = new HashMap<String, Task>();

    /**
     * Xmpp connection
     */
    private Xmpp xmpp = new Xmpp();

    /**
     * The base directory to save recording files. <tt>JireconImpl</tt> will
     * save each recording in its own subdirectory of the base directory.
     */
    private String baseOutputDir;
    
    /**
     * Indicates whether <tt>JireconImpl</tt> has been initialized.
     */
    private boolean isInitialized = false;

    /**
     * Initialize <tt>Jirecon</tt>.
     * <p>
     * Once this method has been executed successfully, <tt>Jirecon</tt> should
     * be ready to start working.
     * 
     * Start Libjitsi, load configuration file and create connection with XMPP
     * server.
     * 
     * @param configurationPath is the configuration file path.
     * @throws Exception if failed to initialize Jirecon.
     * 
     */
    public synchronized void init(String configurationPath)
        throws Exception
    {
        logger.info("Initialize.");

        if (isInitialized)
        {
            logger.warn("Already initialized: ", new Throwable());
            return;
        }

        LibJitsi.start();

        System.setProperty(ConfigurationService.PNAME_CONFIGURATION_FILE_NAME, configurationPath);
        System.setProperty(ConfigurationService.PNAME_CONFIGURATION_FILE_IS_READ_ONLY, "true");

        ConfigurationService cfg = LibJitsi.getConfigurationService();

        baseOutputDir = cfg.getString(ConfigurationKey.SAVING_DIR_KEY);
        if (baseOutputDir == null || baseOutputDir.equals(""))
            throw new Exception("Failed to initialize Jirecon: output directory not set.");

        // Remove the suffix '/'
        if (baseOutputDir.endsWith("/"))
            baseOutputDir = baseOutputDir.substring(0, baseOutputDir.length() - 1);

        final String xmppDomain = cfg.getString(ConfigurationKey.XMPP_DOMAIN_KEY);
        final String xmppHost   = cfg.getString(ConfigurationKey.XMPP_HOST_KEY);
        final int    xmppPort   = cfg.getInt(ConfigurationKey.XMPP_PORT_KEY, -1);
        final String xmppUser   = cfg.getString(ConfigurationKey.XMPP_USER_KEY);
        final String xmppPass   = cfg.getString(ConfigurationKey.XMPP_PASS_KEY);
        final String mucDomain  = cfg.getString(ConfigurationKey.DOMAIN_KEY);

        try
        {
            xmpp.connect(xmppHost, xmppPort, xmppDomain, xmppUser, xmppPass, mucDomain);
        }
        catch (XMPPException e)
        {
            logger.info("Failed to initialize Jirecon: " + e);
            uninit();
            throw e;
        }
        
        isInitialized = true;
    }

    /**
     * Uninitialize <tt>Jirecon</tt>, prepare for GC.
     * <p>
     * <strong>Warning:</tt> If there are any residue <tt>JireconTask</tt>s,
     * </tt>Jirecon</tt> will stop them and notify <tt>JireconEventListener</tt>
     * s.
     * 
     * Stop Libjitsi and close connection with XMPP server.
     * 
     */
    public synchronized void uninit()
    {
        logger.info("Un-initialize");
        if (!isInitialized)
        {
            logger.warn("Not initialized: ", new Throwable());
            return;
        }

        synchronized (tasks)
        {
            for (Task task : tasks.values())
                task.uninit(true);
        }

        xmpp.closeConnection();
        LibJitsi.stop();
    }

    /**
     * Create a new recording task for a specified Jitsi-meeting.
     * <p>
     * <strong>Warning:</strong> This method is asynchronous, it will return
     * immediately regardless of whether the task has been started successfully.
     * Note that a return value of <tt>false</tt> means that the task has
     * failed to start, while a return value of <tt>true</tt> does not guarantee
     * success.
     * 
     * @param mucJid indicates the Jitsi Meet conference to record.
     * @return <tt>true</tt> if the task was initiated asynchronously (and its
     * success is unknown), or <tt>false</tt> if the task failed to be initiated.
     */
    public boolean startJireconTask(String mucJid)
    {
        logger.info("Starting jirecon task: " + mucJid);

        Task task;
        synchronized (tasks)
        {
            if (tasks.containsKey(mucJid))
            {
                logger.info("Not starting duplicate task: " + mucJid);
                return false;
            }
            task = new Task();
            tasks.put(mucJid, task);
        }

        String outputDir = baseOutputDir + "/" + mucJid + new SimpleDateFormat("-yyMMdd-HHmmss").format(new Date());

        task.addEventListener(this);
        task.init(mucJid, xmpp, outputDir);

        task.start();
        return true;
    }

    /**
     * Stops a recording task for a specified Jitsi Meet conference.
     * 
     * @param mucJid the MUC JID of the Jitsi Meet conference to stop.
     * @param keepData Whether to keep the output files or delete them.
     * @return <tt>true</tt> if the task was stopped successfully and
     * <tt>false</tt> otherwise.
     */
    public boolean stopJireconTask(String mucJid, boolean keepData)
    {
        logger.info("Stopping task: " + mucJid);
        
        Task task;
        synchronized (tasks)
        {
            task = tasks.remove(mucJid);
        }

        if (task == null)
        {
            logger.info("Failed to stop non-existent task: " + mucJid);
            return false;
        }
        
        task.stop();
        task.uninit(keepData);

        return true;
    }

    /**
     * Adds a <tt>JireconEventListener</tt>.
     *
     * @param listener the <tt>JireconEventListener</tt> to add.
     */
    public void addEventListener(TaskEventListener listener)
    {
        logger.info("Adding JireconEventListener: " + listener);
        listeners.add(listener);
    }

    /**
     * Removes a <tt>JireconEventListener</tt>.
     *
     * @param listener the <tt>JireconEventListener</tt> to remove.
     */
    public void removeEventListener(TaskEventListener listener)
    {
        logger.info("Removing JireconEventListener: " + listener);
        listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     *
     * Implements {@link TaskEventListener#handleEvent(TaskEvent)}.
     */
    @Override
    public void handleEvent(TaskEvent evt)
    {
        String mucJid = evt.getMucJid();

        switch (evt.getType())
        {
        	case TASK_ABORTED:
        		logger.info("Recording task of MUC " + mucJid + " failed.");
        		break;
        	case TASK_FINISED:
        		logger.info("Recording task of MUC: " + mucJid + " finished successfully.");
        		break;
        	case TASK_STARTED:
        		logger.info("Recording task of MUC " + mucJid + " started.");
        		break;
        }

		fireEvent(evt);
    }

    /**
     * Notifies the registered <tt>JireconEventListener</tt> of a
     * <tt>TaskManagerEvent</tt>.
     * 
     * @param evt the event to send.
     */
    private void fireEvent(TaskEvent evt)
    {
        for (TaskEventListener l : listeners)
            l.handleEvent(evt);
    }

}
