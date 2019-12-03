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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jitsi.jirecon.muc.Endpoint;
import org.jitsi.jirecon.muc.MucClient;
import org.jitsi.jirecon.muc.MucClientManager;
import org.jitsi.jirecon.muc.MucEvent;
import org.jitsi.jirecon.muc.MucEvent.*;
import org.jitsi.jirecon.task.TaskEvent.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.utils.MediaType;
import org.jitsi.xmpp.extensions.AbstractPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.Reason;

/**
 * The individual task to record specified Jitsi-meeting. It is designed in Mediator
 * pattern, <tt>JireconTaskImpl</tt> is the mediator, others like
 * <tt>JireconSession</tt>, <tt>JireconRecorder</tt> are the colleagues.
 * 
 * @author lishunyang
 * @author Boris Grozev
 * 
 */
public class Task implements TaskEventListener, MucEventListener, Runnable
{

	/**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger.getLogger(Task.class.getName());
    
    /**
     * The <tt>JireconEvent</tt> listeners, they will be notified when some
     * important things happen.
     */
    private List<TaskEventListener> listeners = new ArrayList<TaskEventListener>();

    /**
     * MUC Manager
     */
    private MucClientManager mucClientManager;

    /**
     * The instance of <tt>JireconSession</tt>.
     */
    private MucClient mucClient;

    /**
     * The instance of <tt>JireconTransportManager</tt>.
     */
    private IceUdpTransportManager transportMgr;

    /**
     * The instance of <tt>DtlsControlManager</tt>.
     */
    private DtlsControlManager dtlsControlMgr;
    
    /**
     * The instance of <tt>RecorderManager</tt>.
     */
    private StreamRecorderManager recorderMgr;

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
    public void init(String mucJid, MucClientManager mucClientManager, String savingDir)
    {
        logger.info(this.getClass() + " init");

        this.outputDir = savingDir;
        File dir = new File(savingDir);
        if (!dir.exists())
            dir.mkdirs();

        this.mucClientManager = mucClientManager;

        ConfigurationService configuration = LibJitsi.getConfigurationService();

        this.mucJid   = mucJid;
        this.nickname = configuration.getString(ConfigurationKey.NICK_KEY);

        taskExecutor   = Executors.newSingleThreadExecutor(new HandlerThreadFactory());
        transportMgr   = new IceUdpTransportManager();
        dtlsControlMgr = new DtlsControlManager();
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
        transportMgr.free();

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
     * {@inheritDoc}
     */
    @Override
    public void handleMucEvent(MucEvent event)
    {
        logger.info("JireconTask event: " + event.getType());

        System.out.println("Evt: "+event.getType());
        switch (event.getType()) {
        	case SOURCE_ADD:
        	case SOURCE_REMOVE:
                recorderMgr.updateSynchronizers();
                break;

        	case PARTICIPANT_LEFT:
                Collection<Endpoint> endpoints = mucClient.getEndpoints();

                // Oh, it seems that all participants have left the MUC(except Jirecon
                // or other participants which only receive data). It's time to
                // finish the recording.
                if (endpoints.isEmpty())
                {
                    stop();
                    fireEvent(new TaskEvent(this.mucJid, TaskEvent.Type.TASK_FINISED));
                }
        		break;
        		
        	default:
        		break;
		}
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

            mucClient.disconnect(Reason.SUCCESS, "OK, gotta go.");
            mucClientManager.leaveMUC(mucJid);
            transportMgr.free();
            recorderMgr.stopRecording();

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
            mucClient = mucClientManager.joinMUC(mucJid, nickname);
            mucClient.addMucEventListener(this);
            addEventListener(mucClient);

            /* 2. Init recorder manager */
            recorderMgr    = new StreamRecorderManager(mucClient);
            recorderMgr.addTaskEventListener(this);
            recorderMgr.init(outputDir, dtlsControlMgr.getAllDtlsControl());

            /* 3. Wait for session-init packet. */
            mucClient.waitForInitPacket();
            
            List <MediaType> supportedMediaTypes = mucClient.getSupportedMediaTypes();

            /*
             * 4.1 Prepare for sending session-accept packet.
             */
            // Media format and payload type id.
            Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs = mucClient.getFormatAndPTs();

            // Transport packet extension.
            for (MediaType mediaType : supportedMediaTypes)
                transportMgr.harvestLocalCandidates(mediaType);

            Map<MediaType, AbstractPacketExtension> transportPEs = new HashMap<MediaType, AbstractPacketExtension>();
            for (MediaType mediaType : supportedMediaTypes)
                transportPEs.put(mediaType, transportMgr.createTransportPacketExt(mediaType));

            // Fingerprint packet extension.
            for (MediaType mediaType : supportedMediaTypes)
                dtlsControlMgr.setRemoteFingerprint(mediaType, mucClient.getFingerprints().get(mediaType));

            Map<MediaType, AbstractPacketExtension> fingerprintPEs = new HashMap<MediaType, AbstractPacketExtension>();
            for (MediaType mediaType : supportedMediaTypes)
                fingerprintPEs.put(mediaType, dtlsControlMgr.createFingerprintPacketExt(mediaType));

            /* 4.2 Send session-accept packet. */
            Map<MediaType, Long> localSsrcs = recorderMgr.getLocalSsrcs();
            mucClient.sendAcceptPacket(formatAndPTs, localSsrcs, transportPEs, fingerprintPEs);

            /* 4.3 Wait for session-ack packet. */
            // Go on with ICE, no need to waste an RTT here.
//            jingleSessionMgr.waitForResultPacket();

            //
            
            /*
             * 5.1 Prepare for ICE connectivity establishment. Harvest remote
             * candidates.
             */
            Map<MediaType, IceUdpTransportPacketExtension> remoteTransportPEs = new HashMap<MediaType, IceUdpTransportPacketExtension>();
            for (MediaType mediaType : supportedMediaTypes)
                remoteTransportPEs.put(mediaType, mucClient.getIceTransports().get(mediaType));
            transportMgr.addRemoteCandidates(remoteTransportPEs);

            /*
             * 5.2 Start establishing ICE connectivity. Warning: that this method is asynchronous method.
             */
            transportMgr.startConnectivityEstablishment();

            /*
             * 5.3 Wait for ICE to complete (or fail).
             */
            if(!transportMgr.wrapupConnectivityEstablishment())
            {
                logger.log(Level.SEVERE, "Failed to establish an ICE session.");
                fireEvent(new TaskEvent(this.mucJid, TaskEvent.Type.TASK_ABORTED));
                return;
            }
            logger.info("ICE connection established (" + this.mucJid + ")");

            /*
             * 6.1 Prepare for recording. Once transport manager has selected
             * candidates pairs, we can get stream connectors from it, otherwise
             * we have to wait. Notice that if ICE connectivity establishment
             * doesn't get selected pairs for a specified time(MAX_WAIT_TIME),
             * we must break the task.
             */
            Map<MediaType, StreamConnector> streamConnectors = new HashMap<MediaType, StreamConnector>();
            Map<MediaType, MediaStreamTarget> mediaStreamTargets = new HashMap<MediaType, MediaStreamTarget>();
            for (MediaType mediaType : supportedMediaTypes)
            {
                StreamConnector streamConnector = transportMgr.getStreamConnector(mediaType);
                streamConnectors.put(mediaType, streamConnector);

                MediaStreamTarget mediaStreamTarget = transportMgr.getStreamTarget(mediaType);
                mediaStreamTargets.put(mediaType, mediaStreamTarget);
            }

            /* 6.2 Start recording. */
            logger.info("<=================RECORDING=================>");
            recorderMgr.startRecording(formatAndPTs, streamConnectors, mediaStreamTargets);

            /* Task started */
            fireEvent(new TaskEvent(this.mucJid, TaskEvent.Type.TASK_STARTED));
        }
        catch (Exception e)
        {
        	e.printStackTrace();
            fireEvent(new TaskEvent(this.mucJid, TaskEvent.Type.TASK_ABORTED));
        }
    }

}
