package org.jitsi.jirecon.xmpp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jitsi.impl.neomedia.format.MediaFormatFactoryImpl;
import org.jitsi.jirecon.xmpp.transport.DtlsControlManager;
import org.jitsi.jirecon.xmpp.transport.IceUdpTransportManager;
import org.jitsi.jirecon.xmpp.transport.RecorderManager;
import org.jitsi.service.libjitsi.LibJitsi;
import org.jitsi.service.neomedia.MediaService;
import org.jitsi.service.neomedia.MediaStreamTarget;
import org.jitsi.service.neomedia.StreamConnector;
import org.jitsi.service.neomedia.format.AudioMediaFormat;
import org.jitsi.service.neomedia.format.MediaFormat;
import org.jitsi.utils.MediaType;
import org.jitsi.xmpp.extensions.AbstractPacketExtension;
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension;
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.JingleIQ;
import org.jitsi.xmpp.extensions.jingle.JinglePacketFactory;
import org.jitsi.xmpp.extensions.jingle.JingleUtils;
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension;
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension;
import org.jitsi.xmpp.extensions.jingle.Reason;
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension;
import org.jitsi.xmpp.extensions.jingle.SctpMapExtension;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension.CreatorEnum;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension.SendersEnum;
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension;
import org.jitsi.xmpp.extensions.jitsimeet.MediaPresenceExtension.Source;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jxmpp.jid.Jid;

import net.java.sip.communicator.util.Logger;

/**
 * Manage Jingle session, join MUC, build Jingle session etc.
 * 
 * @author lishunyang
 * @author Boris Grozev
 * 
 */
public class JingleSession
{

	/**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger.getLogger(JingleSession.class);

    /**
     * Local node's full-jid which is used for creating <tt>JingleIQ</tt>.
     */
    private Jid localFullJid;

    /**
     * Remote node's full-jid which is used for creating <tt>JingleIQ</tt>.
     */
    private Jid remoteFullJid;

    /**
     * Jingle session id which is used for making <tt>JingleIq</tt>.
     */
    private String sid;

    /**
     * Chat room of this session
     */
    private ChatRoom chatRoom;

    /**
     * The instance of <tt>JireconTransportManager</tt>.
     */
    IceUdpTransportManager transportMgr;

    /**
     * The instance of <tt>DtlsControlManager</tt>.
     */
    DtlsControlManager dtlsControlMgr;

    /**
     * The instance of <tt>RecorderManager</tt>.
     */
    RecorderManager recorderMgr;

    /** 
     * Mark if session is inited
     */
    boolean isSessionInit;

    /**
     * Create jingle session from chat room
     * @param chatRoom the chat room
     */
    JingleSession(ChatRoom chatRoom) {
    	this.chatRoom = chatRoom;

        this.transportMgr   = new IceUdpTransportManager();
        this.dtlsControlMgr = new DtlsControlManager();

        /* Init recorder manager */
        recorderMgr = new RecorderManager(chatRoom);
    }

    private List<MediaType> supportedMediaTypes;
    private Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs;
    private Map<MediaType, DtlsFingerprintPacketExtension> fingerprints;
    private Map<MediaType, IceUdpTransportPacketExtension> iceTransports;

	/**
	 * Add source to endpoint
	 * @param content
	 */
	void addSource(ContentPacketExtension content) {
    	MediaType mediaType = JingleUtils.getMediaType(content);
        RtpDescriptionPacketExtension rtpDescriptionPacketExtension = JingleUtils.getRtpDescription(content);
    	if (rtpDescriptionPacketExtension != null) {
            //
            List<Source> sources = rtpDescriptionPacketExtension.getChildExtensionsOfType(Source.class);
            for (Source source : sources)
            {
            	SSRCInfoPacketExtension ssrcInfo = source.getFirstChildOfType(SSRCInfoPacketExtension.class);
            	if (ssrcInfo != null && !ssrcInfo.getOwner().toString().equals("jvb")) {
            		System.out.println("Add Source: "+source.getSSRC()+" id="+ssrcInfo.getOwner());
            		Endpoint endpoint = chatRoom.getEndpoint(ssrcInfo.getOwner().asEntityFullJidIfPossible());
            		if (endpoint != null)
            			endpoint.addSsrc(mediaType, Long.valueOf(source.getSSRC()));
            	}
            }
    	}
	}

	/**
	 * Remove source to endpoint
	 * @param content
	 */
	void removeSource(ContentPacketExtension content) {
    	MediaType mediaType = JingleUtils.getMediaType(content);
        RtpDescriptionPacketExtension rtpDescriptionPacketExtension = JingleUtils.getRtpDescription(content);
    	if (rtpDescriptionPacketExtension != null) {
            //
            List<Source> sources = rtpDescriptionPacketExtension.getChildExtensionsOfType(Source.class);
            for (Source source : sources)
            {
            	SSRCInfoPacketExtension ssrcInfo = source.getFirstChildOfType(SSRCInfoPacketExtension.class);
            	if (ssrcInfo != null) {
            		System.out.println("Remove Source: "+source.getSSRC()+" id="+ssrcInfo.getOwner());
            		Endpoint endpoint = chatRoom.getEndpoint(ssrcInfo.getOwner().asEntityFullJidIfPossible());
            		if (endpoint != null)
            		{
                		endpoint.removeSsrc(mediaType, Long.valueOf(source.getSSRC()));
                		if (endpoint.isEmpty())
                			chatRoom.removeEndpoint(endpoint.getId());
            		}
            	}
            }
    	}
	}

	/**
	 * Init jvb session
	 * @param initIq
	 * @throws Exception 
	 */
    void initJingleSession(JingleIQ initIq)
    		throws Exception
    {
        sid = initIq.getSID();
        localFullJid  = initIq.getTo();
        remoteFullJid = initIq.getFrom();

        // 
        List<ContentPacketExtension> listContent = initIq.getContentList();

        // Init session
        this.supportedMediaTypes = new ArrayList<MediaType>();

        this.formatAndPTs  = new HashMap<MediaType, Map<MediaFormat, Byte>>();
        this.fingerprints  = new HashMap<MediaType, DtlsFingerprintPacketExtension>();
        this.iceTransports = new HashMap<MediaType, IceUdpTransportPacketExtension>();

        MediaFormatFactoryImpl fmtFactory = new MediaFormatFactoryImpl();
        for (ContentPacketExtension contentPacketExtension : listContent) {
        	MediaType mediaType = JingleUtils.getMediaType(contentPacketExtension);

        	RtpDescriptionPacketExtension rtpDescriptionPacketExtension = JingleUtils.getRtpDescription(contentPacketExtension);
        	Map<MediaFormat, Byte> mapMediaFormat = null;
        	if (rtpDescriptionPacketExtension != null) {
        		mapMediaFormat = new HashMap<MediaFormat, Byte>();
                for (PayloadTypePacketExtension payloadTypePacketExt : rtpDescriptionPacketExtension.getPayloadTypes())
                {
                    MediaFormat format = fmtFactory.createMediaFormat(
                    		payloadTypePacketExt.getName(),
                            payloadTypePacketExt.getClockrate(),
                            payloadTypePacketExt.getChannels()
                    );
                    if (format != null)
                    	mapMediaFormat.put(format, (byte) (payloadTypePacketExt.getID()));
                }
        	}
            formatAndPTs.put(mediaType, mapMediaFormat);

            // Add source
            addSource(contentPacketExtension);

            //
            IceUdpTransportPacketExtension iceUdpTransportPacketExtension = contentPacketExtension.getFirstChildOfType(IceUdpTransportPacketExtension.class);
            
            iceTransports.put(mediaType, iceUdpTransportPacketExtension);
            fingerprints.put(mediaType, iceUdpTransportPacketExtension.getFirstChildOfType(DtlsFingerprintPacketExtension.class));

        	this.supportedMediaTypes.add(mediaType);
        }
        
        /*
         * Prepare for sending session-accept packet.
         */
        // Media format and payload type id.
        // Transport packet extension.
        for (MediaType mediaType : supportedMediaTypes)
            transportMgr.harvestLocalCandidates(mediaType);

        Map<MediaType, AbstractPacketExtension> transportPEs = new HashMap<MediaType, AbstractPacketExtension>();
        for (MediaType mediaType : supportedMediaTypes)
            transportPEs.put(mediaType, transportMgr.createTransportPacketExt(mediaType));

        // Fingerprint packet extension.
        for (MediaType mediaType : supportedMediaTypes)
            dtlsControlMgr.setRemoteFingerprint(mediaType, fingerprints.get(mediaType));

        Map<MediaType, AbstractPacketExtension> fingerprintPEs = new HashMap<MediaType, AbstractPacketExtension>();
        for (MediaType mediaType : supportedMediaTypes)
            fingerprintPEs.put(mediaType, dtlsControlMgr.createFingerprintPacketExt(mediaType));

        /* Send session-accept packet. */
        Map<MediaType, Long> localSsrcs = recorderMgr.getLocalSsrcs();
        sendAcceptPacket(formatAndPTs, localSsrcs, transportPEs, fingerprintPEs);

        /* Wait for session-ack packet. */
        // Go on with ICE, no need to waste an RTT here.
//        jingleSessionMgr.waitForResultPacket();

        //
        
        /*
         * Prepare for ICE connectivity establishment. Harvest remote candidates.
         */
        Map<MediaType, IceUdpTransportPacketExtension> remoteTransportPEs = new HashMap<MediaType, IceUdpTransportPacketExtension>();
        for (MediaType mediaType : supportedMediaTypes)
            remoteTransportPEs.put(mediaType, iceTransports.get(mediaType));
        transportMgr.addRemoteCandidates(remoteTransportPEs);

        /*
         * Start establishing ICE connectivity. Warning: that this method is asynchronous method.
         */
        transportMgr.startConnectivityEstablishment();

        /*
         * Wait for ICE to complete (or fail).
         */
        if(!transportMgr.wrapupConnectivityEstablishment())
            throw new Exception("Failed to establish an ICE session.");

        logger.info("ICE connection established (" + chatRoom.localpart + ")");

        /*
         * Prepare for recording. Once transport manager has selected
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

        /* Start recording. */
        logger.info("<=================RECORDING=================>");
        recorderMgr.startRecording(formatAndPTs, streamConnectors, mediaStreamTargets);
        
        /* Set flag */
        isSessionInit = true;
    }

    /**
     * Create content packet extension in Jingle session-accept packet.
     * 
     * @return content packet extension.
     */
    private ContentPacketExtension createContentPacketExtension(
        String name,
        RtpDescriptionPacketExtension descriptionPE,
        AbstractPacketExtension transportPE)
    {
        logger.debug(this.getClass() + " createContentPacketExtension");
        
        ContentPacketExtension content = new ContentPacketExtension();
        content.setCreator(CreatorEnum.responder);
        content.setName(name);
        content.setSenders(SendersEnum.initiator);
        if (null != descriptionPE)
            content.addChildExtension(descriptionPE);
        content.addChildExtension(transportPE);

        return content;
    }

    /**
     * Create <tt>RtpDescriptionPacketExtension</tt> with specified mediatype,
     * media formats, payload type ids and ssrcs.
     * 
     * @param mediaType
     * @param formatAndPayloadTypes
     * @param localSsrc
     * @return
     */
    private RtpDescriptionPacketExtension createDescriptionPacketExt(
        MediaType mediaType, Map<MediaFormat, Byte> formatAndPayloadTypes,
        Long localSsrc)
    {
        RtpDescriptionPacketExtension description = new RtpDescriptionPacketExtension();
        
        /*
         *  1. Set media type.
         */
        description.setMedia(mediaType.toString());

        /*
         *  2. Set local ssrc.
         */
        description.setSsrc(localSsrc.toString());

        /*
         *  3. Set payload type id.
         */
        for (Map.Entry<MediaFormat, Byte> e : formatAndPayloadTypes.entrySet())
        {
            PayloadTypePacketExtension payloadType = new PayloadTypePacketExtension();
            payloadType.setId(e.getValue());
            payloadType.setName(e.getKey().getEncoding());
            if (e.getKey() instanceof AudioMediaFormat)
                payloadType.setChannels(((AudioMediaFormat) e.getKey()).getChannels());
            payloadType.setClockrate((int) e.getKey().getClockRate());
            for (Map.Entry<String, String> en : e.getKey().getFormatParameters().entrySet())
            {
                ParameterPacketExtension parameter = new ParameterPacketExtension();
                parameter.setName(en.getKey());
                parameter.setValue(en.getValue());
                payloadType.addParameter(parameter);
            }
            description.addPayloadType(payloadType);
        }

        final MediaService mediaService = LibJitsi.getMediaService();
        
        /*
         *  4. Set source information.
         */
        SourcePacketExtension sourcePacketExtension = new SourcePacketExtension();
        final String label = UUID.randomUUID().toString().replace("-", "");
        final String msLabel = UUID.randomUUID().toString();
        
        sourcePacketExtension.setSSRC(localSsrc);
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension("cname", mediaService.getRtpCname()));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension("msid", msLabel + " " + label));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension("mslabel", msLabel));
        sourcePacketExtension.addChildExtension(new ParameterPacketExtension("label", label));
        description.addChildExtension(sourcePacketExtension);
        
        return description;
    }

    /**
     * Create Jingle session-accept packet.
     * 
     * @return Jingle session-accept packet.
     */
    @SuppressWarnings("deprecation")
	private JingleIQ createAcceptPacket(
        Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs,
        Map<MediaType, Long> localSsrcs,
        Map<MediaType, AbstractPacketExtension> transportPEs,
        Map<MediaType, AbstractPacketExtension> fingerprintPEs)
    {
        logger.debug("createSessionAcceptPacket");
        
        List<ContentPacketExtension> contentPEs = new ArrayList<ContentPacketExtension>();

        for (MediaType mediaType : MediaType.values())
        {
            if (!transportPEs.containsKey(mediaType) || !fingerprintPEs.containsKey(mediaType))
                continue;
            
            /* The packet extension that we will create :) */
            RtpDescriptionPacketExtension descriptionPE = null;
            AbstractPacketExtension transportPE = null;
            AbstractPacketExtension fingerprintPE = null;
            ContentPacketExtension contentPE = null;
            SctpMapExtension sctpMapPE = null;

            /*
             * 1. Create DescriptionPE. Only audio and video need this one.
             */
            if (mediaType == MediaType.VIDEO || mediaType == MediaType.AUDIO) 
                descriptionPE = createDescriptionPacketExt(mediaType, formatAndPTs.get(mediaType), localSsrcs.get(mediaType));
            
            /* 
             * 2. Create TransportPE with FingerprintPE. 
             */
            transportPE   = transportPEs.get(mediaType);
            fingerprintPE = fingerprintPEs.get(mediaType);
            transportPE.addChildExtension(fingerprintPE);
            
            /* 
             * 3. Create sctpMapPE. Only data need this one. 
             */
            if (mediaType == MediaType.DATA)
            {
                /*
                 * Actually the port could be any number, but let's keep it 5000
                 * everywhere.
                 */
                final int port = 5000;

                /*
                 * Jirecon didn't care about this at this moment. So just set it 1024. 
                 */
                final int numStreams = 1024;
                
                sctpMapPE = new SctpMapExtension();
                sctpMapPE.setPort(port);
                sctpMapPE.setProtocol(SctpMapExtension.Protocol.WEBRTC_CHANNEL);
                sctpMapPE.setStreams(numStreams);
                transportPE.addChildExtension(sctpMapPE);
            }

            /*
             * 4. Create Content packet extension with DescriptionPE(it could be
             * null) and TransportPE above.
             */
            contentPE = createContentPacketExtension(mediaType.toString(), descriptionPE, transportPE);

            contentPEs.add(contentPE);
        }

        JingleIQ acceptJiq = JinglePacketFactory.createSessionAccept(localFullJid, remoteFullJid, sid, contentPEs);
        acceptJiq.setInitiator(remoteFullJid);
        return acceptJiq;
    }
    
    /**
     * Send Jingle session-accept packet to the remote peer.
     * 
     * @param formatAndPTs Map between <tt>MediaFormat</tt> and payload type id.
     * @param localSsrcs Local sscrs of audio and video.
     * @param transportPEs DtlsTransport packet extensions.
     * @param fingerprintPEs Fingerprint packet extensions.
     * @throws InterruptedException 
     * @throws NotConnectedException 
     */
    private void sendAcceptPacket(
        Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs,
        Map<MediaType, Long> localSsrcs,
        Map<MediaType, AbstractPacketExtension> transportPEs,
        Map<MediaType, AbstractPacketExtension> fingerprintPEs) throws NotConnectedException, InterruptedException
    {
        logger.debug("sendAcceptPacket");
        
        chatRoom.connection.sendStanza(createAcceptPacket(formatAndPTs, localSsrcs, transportPEs, fingerprintPEs));
    }

    /**
     * Send Jingle session-terminate packet.
     * 
     * @param reason is the <tt>Reason</tt> type of the termination packet.
     * @param reasonText is the human-read text.
     * @throws InterruptedException 
     * @throws NotConnectedException 
     */
    void sendByePacket(Reason reason, String reasonText) throws NotConnectedException, InterruptedException
    {
        logger.debug("sendByePacket");

        chatRoom.connection.sendStanza(JinglePacketFactory.createSessionTerminate(localFullJid, remoteFullJid, sid, reason, reasonText));
    }

    /**
     * Start recording
     */
    void startRecording(String outputDir)
    {
        recorderMgr.init(outputDir, dtlsControlMgr.getAllDtlsControl());
    }

    /**
     * Free resource
     */
    void free()
    {
        transportMgr.free();
        recorderMgr.stopRecording();
    }

}
