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

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import org.jitsi.jirecon.task.TaskEvent.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.jirecon.xmpp.ChatRoom;
import org.jitsi.jirecon.xmpp.Xmpp;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.xmpp.extensions.jingle.Reason;

import net.java.sip.communicator.util.Logger;

/**
 * The individual task to record specified Jitsi-meeting. It is designed in Mediator
 * pattern, <tt>JireconTaskImpl</tt> is the mediator, others like
 * <tt>JireconSession</tt>, <tt>JireconRecorder</tt> are the colleagues.
 * 
 * @author lishunyang
 * @author Boris Grozev
 * 
 */
public class Task implements TaskEventListener, Runnable
{

	/**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger.getLogger(Task.class);
    
    /**
     * The <tt>JireconEvent</tt> listeners, they will be notified when some
     * important things happen.
     */
    private List<TaskEventListener> listeners = new ArrayList<TaskEventListener>();

    /**
     * Xmpp Connection
     */
    private Xmpp xmpp;

    /**
     * The chat room
     */
    private ChatRoom chatRoom;

    /**
     * The thread pool to make the method "start" to be asynchronous.
     */
    private ExecutorService taskExecutor;

    /**
     * Indicate whether this task has stopped.
     */
    private boolean isStopped = false;
    
    /**
     * Indicate whether this task has aborted. We need this to identify the
     * situation when method "stop" is called. In case to fire appropriate
     * FINISHED or ABORTED event.
     */
    private boolean isAborted = false;

    /**
     * The MUC jid, which is used to join a MUC. There is no mandatory to use
     * whole jid as long as it can be recognized by <tt>XMPPConnector</tt> and
     * join a MUC successfully.
     */
    private String mucJid;

    /**
     * The name that will be used in MUC.
     */
    private String nickname;

    /**
     * Where does JireconTask output files.
     */
    private String outputDir;

    /**
     * Thread exception handler, in order to catch exceptions of the task
     * thread.
     * 
     * @author lishunyang
     * 
     */
    private class ThreadExceptionHandler implements Thread.UncaughtExceptionHandler
    {
        @Override
        public void uncaughtException(Thread t, Throwable e)
        {
            /*
             * Exception can only be thrown by Task.
             */
            stop();
            fireEvent(new TaskEvent(mucJid, TaskEvent.Type.TASK_ABORTED));
        }
    }

    /**
     * Handler factory, in order to create thread with
     * <tt>ThreadExceptionHandler</tt>
     * 
     * @author lishunyang
     * 
     */
    private class HandlerThreadFactory implements ThreadFactory
    {
        @Override
        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(r);
            t.setUncaughtExceptionHandler(new ThreadExceptionHandler());
            return t;
        }
    }

    /**
     * Initialize a <tt>JireconTask</tt>. Specify which Jitsi-meet you want to
     * record and where we should output the media files.
     * 
     * @param mucJid indicates which meet you want to record.
     * @param connection is an existed <tt>XMPPConnection</tt> which will be
     *            used to send/receive Jingle packet.
     * @param savingDir indicates where we should output the media files.
     */
    public void init(String mucJid, Xmpp mucClientManager, String savingDir)
    {
        logger.info(this.getClass() + " init");

        this.outputDir = savingDir;
        File dir = new File(savingDir);
        if (!dir.exists())
            dir.mkdirs();

        this.xmpp = mucClientManager;

        ConfigurationService configuration = LibJitsi.getConfigurationService();

        this.mucJid   = mucJid;
        this.nickname = configuration.getString(ConfigurationKey.NICK_KEY);

        taskExecutor   = Executors.newSingleThreadExecutor(new HandlerThreadFactory());
    }

    /**
     * Uninitialize the <tt>JireconTask</tt> and get ready to be recycled by GC.
     * 
     * @param keepData Whether we should keep data. Keep the data if it is true,
     *            other wise remove them.
     */
    public void uninit(boolean keepData)
    {
        // Stop the task in case of something hasn't been released correctly.
        stop();

        // 
        listeners.clear();

        if (!keepData)
        {
            logger.info("Delete output files " + outputDir);
            try
            {
                Runtime.getRuntime().exec("rm -fr " + outputDir);
            }
            catch (IOException e)
            {
                logger.info("Failed to remove output files, " + e.getMessage());
            }
        }
    }

    /**
     * Register an event listener to this <tt>JireconTask</tt>.
     * 
     * @param listener
     */
    public void addEventListener(TaskEventListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Remove and event listener from this <tt>JireconTask</tt>.
     * 
     * @param listener
     */
    public void removeEventListener(TaskEventListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleEvent(TaskEvent evt)
    {
        for (TaskEventListener l : listeners)
            l.handleEvent(evt);
    }

    /**
     * Fire the event if the task has finished or aborted.
     * 
     * @param evt is the <tt>JireconEvent</tt> you want to notify the listeners.
     */
    private void fireEvent(TaskEvent evt)
    {
    	if (TaskEvent.Type.TASK_ABORTED == evt.getType())
            isAborted = true;

        for (TaskEventListener l : listeners)
            l.handleEvent(evt);
    }

    /**
     * Start the <tt>JireconTask</tt>.
     * <p>
     * <strong>Warning:</strong> This is a asynchronous method, so it will
     * return quickly, but it doesn't mean that the task has been successfully
     * started. It will notify event listeners if the task is failed.
     */
    public void start()
    {
        taskExecutor.execute(this);
    }

    /**
     * Stop the <tt>JireconTask</tt>.
     */
    public void stop()
    {
        if (!isStopped)
        {
            isStopped = true;

            logger.info(this.getClass() + " stop.");

            if (chatRoom != null)
            {
                chatRoom.disconnect(Reason.SUCCESS, "OK, gotta go.");
                xmpp.leaveMUC(mucJid);
            }

            /*
             * We should only fire TASK_FINISHED event when the task has really
             * finished, because when task is aborted, this "stop" method will
             * also be called, and in this scene we shouldn't fire TASK_FINISHED
             * event.
             */
            if (!isAborted)
                fireEvent(new TaskEvent(this.mucJid, TaskEvent.Type.TASK_FINISED));
        }
    }

    /**
     * This is actually the main part of method "start", in order to make the
     * method "start" to be asynchronous.
     */
    @Override
    public void run()
    {
        try
        {
            /* 1. Join MUC. */
            addEventListener(chatRoom = xmpp.joinMUC(mucJid, nickname));

            /* 2. Init recorder manager */
            chatRoom.startRecording(outputDir);

            /* 3. Wait for session-init packet. */
            chatRoom.waitForInitPacket();

            /* 4. Task started */
            fireEvent(new TaskEvent(this.mucJid, TaskEvent.Type.TASK_STARTED));
        }
        catch (Exception e)
        {
        	e.printStackTrace();
        	stop();
            fireEvent(new TaskEvent(this.mucJid, TaskEvent.Type.TASK_ABORTED));
        }
    }

}
