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

import java.util.*;

import org.jitsi.jirecon.task.TaskManager;
import org.jitsi.jirecon.task.TaskManagerEvent;
import org.jitsi.jirecon.task.TaskManagerEvent.*;


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

	/**
     * Prefix of configuration parameter.
     */
    private static final String CONF_ARG_NAME = "--conf=";

    /**
     * Configuration file path.
     */
    private static String conf;

    /**
     * The number of recording task.
     */
    private static int taskCount;

    /**
     * Sync object
     */
    private static Object syncRoot = new Object();

    private static class MainJireconEventListener implements JireconEventListener {
        @Override
        public void handleEvent(TaskManagerEvent evt)
        {
            if (evt.getType() == TaskManagerEvent.Type.TASK_ABORTED || evt.getType() == TaskManagerEvent.Type.TASK_FINISED)
            {
                System.out.println("Task: " + evt.getMucJid() + " " + evt.getType());
                
                if (--taskCount == 0)
                {
                    synchronized (syncRoot)
                    {
                        syncRoot.notifyAll();
                    }
                }
            }
        }
    }
   
    /**
     * Application entry.
     * 
     * @param args <tt>JireconLauncher</tt> only cares about two arguments:
     *            <p>
     *            1. --conf=CONFIGURATION FILE PATH. Indicate the path of
     *            configuration file.
     *            <p>
     *            2. --time=RECORDING SECONDS. Indicate how many seconds will
     *            the recording task last. If you didn't specify this parameter,
     *            Jirecon will continue recording forever unless all
     *            participants has left the meeting.
     */
    public static void main(String[] args)
    {
        conf = null;
        List<String> mucJids = new ArrayList<String>();

        for (String arg : args)
        {
            if (arg.startsWith(CONF_ARG_NAME))
                conf = arg.substring(CONF_ARG_NAME.length());
            else
                mucJids.add(arg);
        }

        // Debug
        if (mucJids.size() == 0)
        	mucJids.add("testroom");

        // no task ?
        if ((taskCount = mucJids.size()) == 0)
        {
            System.out.println("You have to specify at least one conference jid to record, exit.");
            return;
        }
        if (conf == null)
            conf = "jirecon.properties";

        final TaskManager jirecon = new TaskManager();
        
        jirecon.addEventListener(new MainJireconEventListener());

        try
        {
            jirecon.init(conf);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        for (String jid : mucJids)
            jirecon.startJireconTask(jid);

        try
        {
            synchronized (syncRoot)
            {
                syncRoot.wait();
            }
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }

        for (String jid : mucJids)
            jirecon.stopJireconTask(jid, true);

        jirecon.uninit();
        System.out.println("JireconLauncher exit.");
    }

}
