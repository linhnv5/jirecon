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
package org.jitsi.jirecon.xmpp.transport;

import java.io.*;
import java.util.*;
import java.util.Map.*;
import java.util.concurrent.*;

import org.jitsi.impl.neomedia.recording.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.jirecon.datachannel.*;
import org.jitsi.jirecon.recorder.RecorderRtpImpl;
import org.jitsi.jirecon.recorder.SynchronizerImpl;
import org.jitsi.jirecon.xmpp.ChatRoom;
import org.jitsi.jirecon.xmpp.Endpoint;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.recording.*;
import org.jitsi.utils.MediaType;
import org.json.simple.*;
import org.json.simple.parser.*;

import net.java.sip.communicator.util.Logger;

 /**
 * <tt>StreamRecorderManager</tt> is used to record media
 * streams and save them into local files.
 *
 * @todo Review thread safety.
 * @author lishunyang
 */
public class RecorderManager
{

	/**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger.getLogger(RecorderManager.class);

    /**
     * The map between <tt>MediaType</tt> and <tt>MediaStream</tt>. Those are
     * used to receiving media streams.
     */
    private Map<MediaType, MediaStream> streams = new HashMap<MediaType, MediaStream>();

    /**
     * The instance of <tt>MediaService</tt>.
     */
    private MediaService mediaService;

    /**
     * The map between <tt>MediaType</tt> and <tt>RTPTranslator</tt>. Those are
     * used to initialize recorder.
     */
    private Map<MediaType, RTPTranslator> rtpTranslators = new HashMap<MediaType, RTPTranslator>();

    /**
     * The map between <tt>MediaType</tt> and <tt>Recorder</tt>. Those are used
     * to record media streams into local files.
     */
    private Map<MediaType, Recorder> recorders = new HashMap<MediaType, Recorder>();

    /**
     * SCTP data channel. It's used for receiving some event packets, such as
     * SPEAKER_CHANGE event.
     */
    private DataChannelAdapter dataChannel;

    /**
     * Used for handling recorder's event.
     */
    private RecorderEventHandlerImpl eventHandler;

    /**
     * Map between <tt>MediaType</tt> and local recorder's ssrc.
     */
    private Map<MediaType, Long> localSsrcs = new HashMap<MediaType, Long>();

    /**
     * Whether the <tt>JireconRecorderImpl</tt> is receiving streams.
     */
    private boolean isReceiving = false;

    /**
     * Whether the <tt>JireconRecorderImpl</tt> is recording streams.
     */
    private boolean isRecording = false;

    /**
     * Indicate where <tt>JireconRecorderImpl</tt> will put the local files.
     */
    private String outputDir;

    /**
     * An implementation of <tt>RecorderEventHandler</tt>. It is mainly used for
     * recording SPEAKER_CHANGED event in to meta data file.
     * 
     * @author lishunyang
     * 
     */
    private class RecorderEventHandlerImpl
        implements RecorderEventHandler
    {
        /**
         * The true <tt>RecorderEventHandler</tt> which is used for handling
         * event actually.
         */
        private RecorderEventHandler handler;

        /**
         * The construction method for creating
         * <tt>JireconRecorderEventHandler</tt>.
         * 
         * @param filename the meta data file's name.
         * @throws Exception if failed to create handler
         */
        public RecorderEventHandlerImpl(String filename)
            throws Exception
        {
            /*
             * If there is an existed file with "filename", add suffix to
             * "filename". For instance, from "metadata.json" to
             * "metadata.json-1".
             */
            int count = 1;
            String filenameAvailable = filename;
            File file = null;
            while (true)
            {
                file = new File(filenameAvailable);

                try
                {
                    handler = new RecorderEventHandlerJSONImpl(filenameAvailable);
                    break;
                }
                catch (IOException e)
                {
                    if (file.exists())
                        filenameAvailable = filename + "-" + count++;
                    else
                        throw new Exception("Could not create event handler, no write permission to meta file.");
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {
            logger.debug("close");
        }

        /**
         * Handle event.
         */
        @Override
        public synchronized boolean handleEvent(RecorderEvent event)
        {
            logger.info(event + " ssrc:" + event.getSsrc());

            RecorderEvent.Type type = event.getType();

            if (type.equals(RecorderEvent.Type.SPEAKER_CHANGED))
            {
                /*
                 * We have to use audio ssrc instead endpoint id to find video
                 * ssrc because of the compatibility.
                 */
                logger.debug("SPEAKER_CHANGED audio ssrc: " + event.getAudioSsrc());

                final long audioSsrc = event.getAudioSsrc();
                final long videoSsrc = getAssociatedSsrc(audioSsrc, MediaType.AUDIO, MediaType.VIDEO);

                if (videoSsrc < 0)
                {
                    logger.error("Could not find video SSRC associated with audioSsrc=" + audioSsrc);

                    // don't write events without proper 'ssrc' values
                    return false;
                }

                // for the moment just use the first SSRC
                event.setSsrc(videoSsrc);
            }

            String endpointId = null;
            if (event.getEndpointId() == null || event.getEndpointId().isEmpty())
            {
                // Here we find and set the event.endpointId field of the event,
                // if hasn't already been set.
                //
                // The value of the SSRC field depends on the MediaType of the
                // Event. If it is VIDEO -- it is the video SSRC, if it is AUDIO
                // then it is the audio SSRC. The audioSsrc field is just an
                // additional field, which is only used for certain VIDEO
                // events.

                // If the event.audioSsrc field is set, try to find the endpoint
                // ID by that field.
                long audioSsrc = event.getAudioSsrc();
                if (audioSsrc != -1) // we need a constant for easier grepping
                {
                    endpointId = getEndpointId(audioSsrc, MediaType.AUDIO);
                    if (endpointId != null)
                        event.setEndpointId(endpointId.toString());
                }

                // If we failed to find the endpoint ID by the event.audioSsrc
                // field and if the event.ssrc field is set, try to find the
                // endpoint ID by the event.ssrc field.
                long ssrc = event.getSsrc();
                if (endpointId == null && ssrc != -1)
                {
                    // First assume the event.ssrc field contains an audio SSRC.
                    endpointId = getEndpointId(ssrc, event.getMediaType());
                    if (endpointId != null)
                        event.setEndpointId(endpointId.toString());
                }
            }

            handler.handleEvent(event);
            return true;
        }
    }

    /**
     * This inner class is acting as an adaptor, so we can use
     * <tt>WebRtcDataStreamManager</tt> and <tt>WebRtcDataStream</tt> easily.
     * 
     * @author lishunyang
     * 
     */
    private class DataChannelAdapter
        implements WebRtcDataStreamListener
    {

    	/**
         * We have to keep this <tt>DtlsControl</tt> for a while, because
         * <tt>WebRtcDataStreamManager</tt> need this when we start it.
         */
        private DtlsControl dtlsControl;
        
        /**
         * Encapsulate the <tt>WebRtcDataStreamManager</tt>.
         */
        private WebRtcDataStreamManager streamManager;
        
        /**
         * Encapsulate the <tt>WebRtcDataStreamManager</tt>.
         */
        private WebRtcDataStream dataChannel;
        
        private ExecutorService executorService = Executors.newSingleThreadExecutor();
        
        private final Object syncRoot = new Object();
        
        public DataChannelAdapter(DtlsControl dtlsControl)
        {
            this.dtlsControl = dtlsControl;
            this.streamManager = new WebRtcDataStreamManager("We don't need the endpointId");
            this.streamManager.setListener(this);
        }

        /**
         * Start <tt>WebRtcDataStreamManager</tt> and wait for videobridge
         * create a default <tt>WebRtcDataStream</tt> whose sid is "0".
         * 
         * @param connector
         * @param streamTarget
         */
        public void connect(StreamConnector connector, MediaStreamTarget streamTarget)
        {
            streamManager.runAsClient(connector, streamTarget, dtlsControl);

            executorService.execute(new Runnable()
            {
                @Override
                public void run()
                {
                    synchronized (syncRoot)
                    {
                        try
                        {
                            syncRoot.wait();
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }

                    logger.info("DataChannel connected (?)");
                    prepareDataChannel();
                }

            });
        }

        public void disconnect()
        {
            streamManager.shutdown();
            dtlsControl.cleanup(null);
        }

        @Override
        public void onChannelOpened(WebRtcDataStream channel)
        {
            dataChannel = channel;
            
            synchronized (syncRoot)
            {
                syncRoot.notify();
            }
        }
        
        private void prepareDataChannel()
        {
            dataChannel.setDataCallback(new WebRtcDataStream.DataCallback()
            {
                @Override
                public void onStringData(WebRtcDataStream src, String msg)
                {
                    try
                    {
                        /*
                         * Once we got an legal message(SPEAKER_CHANGE event),
                         * we create a RecorderEvent and let event handler to
                         * handle it.
                         */
                        JSONParser parser = new JSONParser();
                        JSONObject json = (JSONObject) parser.parse(msg);
                        String endpointId = json.get("dominantSpeakerEndpoint").toString();

                        logger.debug("Hey! " + endpointId);
                        System.out.println("Event: " + msg);
                        System.out.println("Hey! " + endpointId);

                        RecorderEvent event = new RecorderEvent();
                        event.setMediaType(MediaType.AUDIO);
                        event.setType(RecorderEvent.Type.SPEAKER_CHANGED);
                        event.setEndpointId(endpointId);
                        event.setAudioSsrc(getEndpointSsrc(endpointId, MediaType.AUDIO));
                        event.setInstant(System.currentTimeMillis());

                        eventHandler.handleEvent(event);
                    }
                    catch (ParseException e)
                    {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onBinaryData(WebRtcDataStream src, byte[] data)
                {
                    /*
                     * We don't care about binary data.
                     */
                }
            });
        }
    }

    /**
     * The instance of <tt>JireconSession</tt>.
     */
    private ChatRoom mucClient;

    /**
     * Constructor
     */
    public RecorderManager(ChatRoom mucClient) {
    	this.mucClient = mucClient;
	}

    /**
     * Initialize <tt>JireconRecorder</tt>.
     * <p>
     * <strong>Warning:</strong> LibJitsi must be started before calling this
     * method.
     * 
     * @param outputDir decide where to output the files. The directory must be
     *            existed and writable.
     * @param dtlsControls is the map between <tt>MediaType</tt> and
     *            <tt>DtlsControl</tt> which is used for SRTP transfer.
     */
    @SuppressWarnings("deprecation")
	public void init(String outputDir, Map<MediaType, DtlsControl> dtlsControls)
    {
        this.mediaService = LibJitsi.getMediaService();
        this.outputDir = outputDir;

        logger.setLevelAll();

        /*
         * NOTE: DtlsControl will be managed by MediaStream. So we don't need to
         * open/close DtlsControl additionally.
         */
        createMediaStreams(dtlsControls);
        createDataChannel(dtlsControls.get(MediaType.DATA));
    }

    /**
     * Start recording media streams.
     * 
     * @param formatAndDynamicPTs
     * @param connectors is the map between <tt>MediaType</tt> and
     *            <tt>StreamConnector</tt>. <tt>JireconRecorder</tt> needs those
     *            connectors to transfer stream data.
     * @param targets is the map between <tt>MediaType</tt> and
     *            <tt>MediaStreamTarget</tt>. Every target indicates a media
     *            source.
     * @throws Exception if some operation failed and the
     *             recording is aborted.
     */
    @SuppressWarnings("deprecation")
	public void startRecording(
        Map<MediaType, Map<MediaFormat, Byte>> formatAndDynamicPTs,
        Map<MediaType, StreamConnector> connectors,
        Map<MediaType, MediaStreamTarget> targets)
        throws Exception
    {
        /*
         * Here we don't guarantee whether file path is available.
         * RecorderEventHandlerImpl needs check this and do some job.
         */
        final String filename = "metadata.json";
        eventHandler = new RecorderEventHandlerImpl(outputDir + File.separator + filename);

        /*
         * 1. Open sctp data channel, if there is data connector and target.
         */
        logger.info("<OpenDataChannel>");
        openDataChannel(connectors.get(MediaType.DATA), targets.get(MediaType.DATA));

        /*
         * 2. Prepare audio and video media streams.
         */
        logger.info("<PrepareMediaStream>");
        prepareMediaStreams(formatAndDynamicPTs, connectors, targets);

        /*
         * 3. Start receiving audio and video streams
         */
        logger.info("<StartReceivingStream>");
        startReceivingStreams();

        /*
         * 4. Prepare audio and video recorders.
         */
        logger.info("<PrepareRecorder>");
        prepareRecorders();

        /*
         * 5. Start recording audio and video streams.
         */
        logger.info("<StartRecording>");
        startRecordingStreams();
    }

    /**
     * Stop the recording.
     */
    public void stopRecording()
    {
        stopRecordingStreams();
        stopReceivingStreams();
        closeDataChannel();

        // Create an empty ".recording_finished" file in the output directory in
        // order to mark the directory as containing a finished recording.
        File recordingFinished = new File(outputDir + File.separator + ".recording_finished");
        try
        {
            if (!recordingFinished.createNewFile())
                logger.warn(".recording_finished already exists");
        }
        catch (IOException ioe)
        {
            logger.warn("Failed to create .recording_finished: " + ioe);
        }

        /*
        /*
         * NOTE: We don't need to stop translators because those media streams
         * will do it.
         */
        stopTranslators();
    }

    /**
     * Make all <tt>JireconRecorderImpl</tt> ready to start receiving media
     * streams.
     * 
     * @param formatAndPTs
     * @param connectors is the map between <tt>MediaType</tt> and
     *            <tt>StreamConnector</tt>. Those connectors are used to
     *            transfer stream data.
     * @param targets is the map between <tt>MediaType</tt> and
     *            <tt>MediaStreamTarget</tt>. The target indicate media stream
     *            source.
     * @throws Exception if some operation failed and the
     *             preparation is aborted.
     */
    private void prepareMediaStreams(
        Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs,
        Map<MediaType, StreamConnector> connectors,
        Map<MediaType, MediaStreamTarget> targets)
        throws Exception
    {
        logger.debug("prepareMediaStreams");

        for (Entry<MediaType, MediaStream> e : streams.entrySet())
        {
            final MediaType mediaType = e.getKey();
            final MediaStream stream  = e.getValue();

            StreamConnector connector = connectors.get(mediaType);
            MediaStreamTarget target  = targets.get(mediaType);
            stream.setConnector(connector);
            stream.setTarget(target);

            for (Entry<MediaFormat, Byte> f : formatAndPTs.get(mediaType).entrySet())
            {
                stream.addDynamicRTPPayloadType(f.getValue(), f.getKey());
                if (stream.getFormat() == null)
                    stream.setFormat(f.getKey());
            }

            stream.setRTPTranslator(getTranslator(mediaType));
        }
    }

    /**
     * The shared synchronizer between the audio and the video recorder.
     */
    private Synchronizer synchronizer;

    private Synchronizer getSynchronizer()
    {
        if (synchronizer == null)
            synchronizer = new SynchronizerImpl();
        return synchronizer;
    }

    /**
     * Make the <tt>JireconRecorderImpl</tt> ready to start recording media
     * streams.
     * 
     * @throws Exception if some operation failed and the
     *             preparation is aborted.
     */
    private void prepareRecorders() throws Exception
    {
        logger.debug("prepareRecorders");

        for (Entry<MediaType, RTPTranslator> e : rtpTranslators.entrySet())
        {
            Recorder recorder = new RecorderRtpImpl(this, e.getValue());// mediaService.createRecorder(e.getValue());

            // The idea is for the two recorders (for audio and video) to share
            // a Synchronizer instance. Otherwise audio and video will not be
            // synced.
            recorder.setSynchronizer(getSynchronizer());
            recorders.put(e.getKey(), recorder);
        }

        updateSynchronizers();
    }

    /**
     * Open data channel, build SCTP connection with remote sctp server.
     * <p>
     * If there any parameters is null, data channel won't be openned.
     * 
     * @param connector Data stream connector
     * @param streamTarget Data stream target
     */
    private void openDataChannel(StreamConnector connector, MediaStreamTarget streamTarget)
    {
        if (connector == null || streamTarget == null)
        {
            logger.debug("Ignore data channel");
            return;
        }
        dataChannel.connect(connector, streamTarget);
    }

    /**
     * Start receiving media streams.
     * 
     * @throws Exception if some operation failed and the
     *             receiving is aborted.
     */
    private void startReceivingStreams() throws Exception
    {
        logger.debug("startReceiving");

        int startCount = 0;
        for (Entry<MediaType, MediaStream> e : streams.entrySet())
        {
            MediaStream stream = e.getValue();

            stream.getSrtpControl().start(e.getKey());
            stream.start();

            if (stream.isStarted())
            	startCount += 1;
        }

        // If any media stream failed to start, the starting procedure failed.
        if (streams.size() != startCount)
            throw new Exception("Could not start receiving streams");

        isReceiving = true;
    }

    /**
     * Start recording media streams.
     * 
     * @throws Exception if some operation failed and the
     *             recording is aborted.
     */
    private void startRecordingStreams() throws Exception
    {
        logger.info("startRecording");
        
        if (!isReceiving)
            throw new Exception("Could not start recording streams, media streams are not receiving.");
        if (isRecording)
            throw new Exception("Could not start recording streams, recorders are already recording.");

        for (Entry<MediaType, Recorder> entry : recorders.entrySet())
        {
            final Recorder recorder = entry.getValue();
            recorder.setEventHandler(eventHandler);
            try
            {
                recorder.start(entry.getKey().toString(), outputDir);
            }
            catch (Exception e)
            {
                throw new Exception("Could not start recording streams, " + e.getMessage());
            }
        }
        isRecording = true;
    }

    private void closeDataChannel()
    {
        dataChannel.disconnect();
    }

    /**
     * Stop recording media streams.
     */
    private void stopRecordingStreams()
    {
        logger.debug("Stop recording streams.");
        
        if (!isRecording)
            return;

        for (Entry<MediaType, Recorder> e : recorders.entrySet())
            e.getValue().stop();

        recorders.clear();
        isRecording = false;
    }

    /**
     * Stop receiving media streams.
     */
    private void stopReceivingStreams()
    {
        logger.debug("Stop receiving streams");
        
        if (!isReceiving)
            return;

        for (Map.Entry<MediaType, MediaStream> e : streams.entrySet())
            e.getValue().close();

        streams.clear();
        isReceiving = false;
    }

    /**
     * Stop the RTP translators.
     * <p>
     * Actually we don't stop <tt>RTPTranslator</tt>s manually, because it will
     * be closed automatically by recorders.
     */
    private void stopTranslators()
    {
        for (Entry<MediaType, RTPTranslator> e : rtpTranslators.entrySet())
            e.getValue().dispose();
        rtpTranslators.clear();
    }

    /**
     * Create data channel. We need <tt>DtlsControl</tt> to initialize it.
     * 
     * @param dtlsControl
     */
    private void createDataChannel(DtlsControl dtlsControl)
    {
        dataChannel = new DataChannelAdapter(dtlsControl);
    }

    /**
     * Create media streams. After media streams are created, we can get ssrcs
     * of them.
     * <p>
     * <strong>Warning:</strong> We can only add <tt>SrtpControl</tt> to
     * <tt>MediaStream</tt> at this moment.
     * 
     * @param dtlsControls is the map between <tt>MediaType</tt> and
     *            <tt>SrtpControl</tt>.
     */
    private void createMediaStreams(Map<MediaType, DtlsControl> dtlsControls)
    {
        logger.debug("createMediaStreams");
        
        for (MediaType mediaType : new MediaType[]{ MediaType.AUDIO, MediaType.VIDEO })
        {
            MediaStream stream = mediaService.createMediaStream(
                        null,
                        mediaType,
                        dtlsControls.get(mediaType));
            streams.put(mediaType, stream);

            stream.setName(mediaType.toString());
            stream.setDirection(MediaDirection.RECVONLY);
        }
    }

    /**
     * Get a <tt>RTPTranslator</tt> for a specified <tt>MediaType</tt>. Create a
     * new one if it doesn't exist.
     * 
     * @param mediaType is the <tt>MediaType</tt> that you specified.
     * @return <tt>RTPTranslator</tt>
     */
    private RTPTranslator getTranslator(MediaType mediaType)
    {
        RTPTranslator translator = null;
        
        if (rtpTranslators.containsKey(mediaType))
            translator = rtpTranslators.get(mediaType);
        else
        {
            translator = mediaService.createRTPTranslator();

            /*
             * We have to do the casting, because RTPTranslator interface doesn't
             * have that method.
             */
            ((RTPTranslatorImpl) translator).setLocalSSRC(localSsrcs.get(mediaType));
            rtpTranslators.put(mediaType, translator);
        }
        return translator;
    }

    /**
     * Find and get the <tt>MediaType</tt> ssrc which belongs to some endpoint.
     * <strong>Warning:</strong> An endpoint means a media stream source, each
     * media stream source generally contains two ssrc, one for audio stream and
     * one for video stream.
     * 
     * @param ssrc indicates an endpoint.
     * @param mediaType is the <tt>MediaType</tt> which indicates which ssrc you want to get.
     * @return ssrc or -1 if not found
     */
    private long getAssociatedSsrc(long ssrc, MediaType getMediaType, MediaType mediaType)
    {
    	Collection<Endpoint> endpoints = mucClient.getEndpoints();
        if (endpoints != null && !endpoints.isEmpty())
        {
            for (Endpoint endpoint : endpoints)
            {
                Map<MediaType, List<Long>> ssrcs = endpoint.getSsrcs();

                if (ssrcs.size() < 2)
                    continue;

                if (ssrcs.get(getMediaType).contains(ssrc))
                    return ssrcs.get(mediaType).get(0);
            }
        }
        else
            logger.warn("The endpoints collection is empty!");
        return -1;
    }

    /**
     * Find the specified <tt>MediaType<tt> ssrc which belongs to some endpont.
     * <strong>Warning:</strong> An endpoint means a media stream source, each
     * media stream source generally contains two ssrc, one for audio stream and
     * one for video stream.
     * 
     * @param endpointId indicates an endpoint
     * @param mediaType is the <tt>MediaType</tt> which indicates which ssrc you
     *            want to get.
     * @return ssrc or -1 if not found
     */
    private long getEndpointSsrc(String endpointId, MediaType mediaType)
    {
    	Collection<Endpoint> endpoints = mucClient.getEndpoints();
        if (endpoints != null && !endpoints.isEmpty())
        {
            for (Endpoint endpoint : endpoints)
            {
                if (endpoint.getId().toString().compareTo(endpointId) == 0)
                    return endpoint.getSsrc(mediaType).get(0);
            }
        }
        else
            logger.warn("The endpoints collection is empty!");

        return -1;
    }

    /**
     * Finds the endpoint ID by SSRC.
     *
     * @param ssrc the SSRC of the endpoint.
     * @param mediaType the SSRC media type.
     * @return the endpoint ID or an empty string if the endpoint ID is not
     * found
     */
    public String getEndpointId(long ssrc, MediaType mediaType)
    {
    	Collection<Endpoint> endpoints = mucClient.getEndpoints();
        if (endpoints != null && !endpoints.isEmpty())
        {
        	for (Endpoint endpoint : endpoints)
            {
                if (endpoint.getSsrc(mediaType).contains(ssrc))
                    return endpoint.getId().toString();
            }
        }
        else
            logger.warn("The endpoints collection is empty!");
        return null;
    }

    /**
     * Call when source change
     */
    public void updateSynchronizers()
    {
    	Collection<Endpoint> endpoints = mucClient.getEndpoints();
        for (Endpoint endpoint : endpoints)
        {
            final String endpointId = endpoint.getId().toString();
            for (Entry<MediaType, List<Long>> ssrc : endpoint.getSsrcs().entrySet())
            {
                Recorder recorder = recorders.get(ssrc.getKey());

                // During the ICE connectivity establishment and after we've
                // joined the MUC, there is a high probability that we
                // process a media type/ssrc for which we *don't* have a
                // recorder yet (because we get XMPP presence packets before
                // the recorders are prepared (see method
                // prepareRecorders())
                if (recorder != null) {
                	for (Long ssrcl : ssrc.getValue())
                		recorder.getSynchronizer().setEndpoint(ssrcl, endpointId);
                }

                logger.info("endpoint: " + endpointId + " " + ssrc.getKey() + " " + ssrc.getValue());
            }
        }
    }

    /**
     * Get local ssrcs of each <tt>MediaType</tt>.
     * 
     * @return Map between <tt>MediaType</tt> and ssrc.
     */
    public Map<MediaType, Long> getLocalSsrcs()
    {
        if (!localSsrcs.isEmpty())
            return localSsrcs;

        synchronized (streams)
        {
            for (Entry<MediaType, MediaStream> entry : streams.entrySet())
                localSsrcs.put(entry.getKey(), entry.getValue().getLocalSourceID() & 0xFFFFFFFFL);
        }
        return localSsrcs;
    }

}
