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
package org.jitsi.jirecon.muc;

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.impl.neomedia.format.MediaFormatFactoryImpl;
import org.jitsi.jirecon.task.Endpoint;
import org.jitsi.jirecon.task.TaskEvent;
import org.jitsi.jirecon.task.TaskEvent.TaskEventListener;
import org.jitsi.jirecon.task.TaskManagerEvent;
import org.jitsi.jirecon.task.TaskManagerEvent.JireconEventListener;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.utils.MediaType;
import org.jitsi.xmpp.extensions.AbstractPacketExtension;
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension;
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension;
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension.CreatorEnum;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension.SendersEnum;
import org.jitsi.xmpp.extensions.jirecon.*;
import org.jitsi.xmpp.extensions.jitsimeet.MediaPresenceExtension.Source;
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension;
import org.jitsi.xmpp.extensions.jingle.JingleIQ;
import org.jitsi.xmpp.extensions.jingle.JinglePacketFactory;
import org.jitsi.xmpp.extensions.jingle.JingleUtils;
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension;
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension;
import org.jitsi.xmpp.extensions.jingle.Reason;
import org.jitsi.xmpp.extensions.jingle.RtpDescriptionPacketExtension;
import org.jitsi.xmpp.extensions.jingle.SctpMapExtension;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.XMPPError.Condition;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jivesoftware.smackx.nick.packet.Nick;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;

/**
 * Manage Jingle session, join MUC, build Jingle session etc.
 * 
 * @author lishunyang
 * @author Boris Grozev
 * 
 */
public final class MucClient implements JireconEventListener
{

	/**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger.getLogger(MucClient.class.getName());
    
    /**
     * Maximum wait time in milliseconds.
     */
    private static final int MAX_WAIT_TIME = 10000;

    /**
     * The human-readable <tt>nickname</tt> which will be set in presence sent
     * to the MUC. Not to be confused with the ID within the room.
     */
    private static final String NICKNAME = "Jirecon Recorder";

    /**
     * The <tt>XMPPConnection</tt> is used to send/receive XMPP packets.
     */
    private XMPPConnection connection;

    /**
     * The <tt>JireconTaskEventListener</tt>, if <tt>JireconRecorder</tt> has
     * something important, it will notify them.
     */
    private final List<TaskEventListener> listeners = new ArrayList<TaskEventListener>();

    /**
     * The instance of a <tt>MultiUserChat</tt>. <tt>JireconSessionImpl</tt>
     * will join it as the first step.
     */
    private MultiUserChat muc;

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
     * <tt>Endpoint</tt>s in the meeting.
     */
    private final Map<Jid, Endpoint> endpoints = new HashMap<Jid, Endpoint>();

    /**
     * Packet listening for precence packet handle
     */
    private StanzaListener receivingListener;
    
    /**
     * Initialize <tt>JireconSession</tt>.
     * 
     * @param connection is used for send/receive XMPP packet.
     * @throws Exception  if failed to join MUC.
     */
    MucClient(XMPPConnection connection, String mucJid, String nickname)
    		throws Exception
    {
        this.connection = connection;
        this.joinMUC(mucJid, nickname);
    }

    /**
     * Join a Multi-User-Chat of specified MUC jid.
     * 
     * @param mucJid The specified MUC jid.
     * @param nickname The name in MUC.
     * @throws Exception if failed to join MUC.
     */
    private void joinMUC(final String mucJid, String nickname)
        throws Exception
    {
        logger.info("Joining MUC...");

        /*
         * Register the receiving packet listener to handle presence packet.
         */
        receivingListener = new StanzaListener()
        {
            @Override
            public void processStanza(Stanza packet)
            {
                logger.info(packet.getClass() + "<---: " + packet);
                if (packet instanceof Presence)
                	handlePresencePacket((Presence) packet);
            }
        };
        connection.addSyncStanzaListener(receivingListener, new StanzaFilter()
        {
            @Override
            public boolean accept(Stanza packet)
            {
            	Localpart localpart;
            	return (localpart = packet.getFrom().getLocalpartOrNull()) != null && mucJid.equals(localpart.toString());
            }
        });

        MultiUserChatManager mucManager = MultiUserChatManager.getInstanceFor(connection);

        //
        muc = mucManager.getMultiUserChat(JidCreate.entityBareFrom(mucJid));
        int suffix = 1;
        String finalNickname = nickname;
        while (true)
        {
            try
            {
            	muc.join(Resourcepart.from(finalNickname));
            	break;
            }
            catch (XMPPErrorException e)
            {
            	e.printStackTrace();
                if (e.getXMPPError().getCondition() == Condition.conflict && suffix < 10)
                {
                    finalNickname = nickname + "_" + suffix++;
                    continue;
                }
                throw new Exception("Could not join MUC, " + e.getMessage());
            }
        }

        Stanza presence = new Presence(Presence.Type.available);
        presence.setTo(muc.getRoom());
        presence.addExtension(new Nick(NICKNAME));
        presence.addExtension(new RecorderExtension(null));
        connection.sendStanza(presence);

        this.localFullJid = JidCreate.from(mucJid + "/" + finalNickname);
        logger.info("Joined MUC as "+localFullJid);
    }

    /**
     * Leave the Multi-User-Chat
     * @throws Exception if leave unsuccessful
     */
    private void leaveMUC()
    {
        logger.info("leaveMUC");

        if (muc != null) {
			try {
				muc.leave();
			} catch (Exception e) {
			}
        }

        connection.removeSyncStanzaListener(receivingListener);
    }

    /**
     * Disconnect with XMPP server and terminate the Jingle session.
     * 
     * @param reason <tt>Reason</tt> type of disconnecting.
     * @param reasonText The human-read reasons.
     * @throws Exception 
     */
    public void disconnect(Reason reason, String reasonText)
    {
    	try {
			sendByePacket(reason, reasonText);
		} catch (Exception e) {
		}
        leaveMUC();
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
    public void sendAcceptPacket(
        Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs,
        Map<MediaType, Long> localSsrcs,
        Map<MediaType, AbstractPacketExtension> transportPEs,
        Map<MediaType, AbstractPacketExtension> fingerprintPEs) throws NotConnectedException, InterruptedException
    {
        logger.debug("sendAcceptPacket");
        
        connection.sendStanza(createAcceptPacket(formatAndPTs, localSsrcs, transportPEs, fingerprintPEs));
    }

    /**
     * Send Jingle session-terminate packet.
     * 
     * @param reason is the <tt>Reason</tt> type of the termination packet.
     * @param reasonText is the human-read text.
     * @throws InterruptedException 
     * @throws NotConnectedException 
     */
    private void sendByePacket(Reason reason, String reasonText) throws NotConnectedException, InterruptedException
    {
        logger.debug("sendByePacket");

        connection.sendStanza(JinglePacketFactory.createSessionTerminate(localFullJid, remoteFullJid, sid, reason, reasonText));
    }

    private JingleIQ initIQ;
    private Object waitForInitPacketSyncRoot = new Object();

	public IQ handleIQRequest(IQ iqRequest) {
		if (iqRequest instanceof JingleIQ) {
			JingleIQ iq = (JingleIQ)iqRequest;

			switch (iq.getAction()) {
				case SESSION_INITIATE:
					initJingleSession(initIQ = iq);
					synchronized (waitForInitPacketSyncRoot)
					{
						waitForInitPacketSyncRoot.notify();
					}
					break;
				case ADDSOURCE:
				case SOURCEADD:
					break;
				default:
					break;
			}
			return IQ.createResultIQ(iqRequest);
		}
		return null;
	}

	/**
     * Wait for Jingle session-init packet after join the MUC.
     * <p>
     * <strong>Warning:</strong> This method will block for at most
     * <tt>MAX_WAIT_TIME</tt> ms to wait for session-init packet. If time out,
     * throws exception.
     * <p>
     * Once We got session-init packet, send back ack packet.
     * 
     * @return Jingle session-init packet that we get.
     * @throws Exception if the method time out.
     */
    public JingleIQ waitForInitPacket() 
        throws Exception
    {
        logger.info("waitForInitPacket");

        if (this.initIQ == null) {
            boolean interrupted = false;

            synchronized (waitForInitPacketSyncRoot)
            {
                while (this.initIQ == null)
                {
                    try
                    {
                        waitForInitPacketSyncRoot.wait(MAX_WAIT_TIME);
                        break;
                    }
                    catch (InterruptedException ie)
                    {
                        interrupted = true;
                    }
                }
            }
            if (interrupted)
                Thread.currentThread().interrupt();

            if (this.initIQ == null)
                throw new Exception("Could not get session-init packet, maybe the MUC has locked.");
        }
        
        return this.initIQ;
    }

    private List<MediaType> supportedMediaTypes;
    private Map<MediaType, Map<MediaFormat, Byte>> formatAndPTs;
    private Map<MediaType, DtlsFingerprintPacketExtension> fingerprints;
    private Map<MediaType, IceUdpTransportPacketExtension> iceTransports;

    /**
	 * @return the supportedMediaTypes
	 */
	public List<MediaType> getSupportedMediaTypes() {
		return supportedMediaTypes;
	}

	/**
	 * @return the formatAndPTs
	 */
	public Map<MediaType, Map<MediaFormat, Byte>> getFormatAndPTs() {
		return formatAndPTs;
	}

	/**
	 * @return the fingerprints
	 */
	public Map<MediaType, DtlsFingerprintPacketExtension> getFingerprints() {
		return fingerprints;
	}

	/**
	 * @return the iceTransports
	 */
	public Map<MediaType, IceUdpTransportPacketExtension> getIceTransports() {
		return iceTransports;
	}

    private void initJingleSession(JingleIQ initIq) {
        sid = initIq.getSID();
        localFullJid  = initIq.getTo();
        remoteFullJid = initIq.getFrom();

        // 
        List<ContentPacketExtension> listContent = initIq.getContentList();

        /**
<iq
	to='recorder@recorder.jaime.kidtopi.com/c8b0cdce-fa16-493f-81ad-b57d815cf204'
	from='testroom@conference.jaime.kidtopi.com/focus'
	id='cmVjb3JkZXJAcmVjb3JkZXIuamFpbWUua2lkdG9waS5jb20vYzhiMGNkY2UtZmExNi00OTNmLTgxYWQtYjU3ZDgxNWNmMjA0AHM0QWJqLTgyODU2AI3Dq3WHDa5ELaDCtcG7VHM='
	type='set'>
	<jingle xmlns='urn:xmpp:jingle:1' action='session-initiate'
		initiator='focus@auth.jaime.kidtopi.com/focus3253432340751975'
		sid='4mjoq38iig0e5'>
		<content name='audio' senders='both' creator='initiator'>
			<description xmlns='urn:xmpp:jingle:apps:rtp:1'
				maxptime='60' media='audio'>
				<payload-type xmlns='urn:xmpp:jingle:apps:rtp:1'
					name='opus' clockrate='48000' id='111' channels='2'>
					<parameter xmlns='urn:xmpp:jingle:apps:rtp:1' value='10'
						name='minptime' />
					<parameter xmlns='urn:xmpp:jingle:apps:rtp:1' value='1'
						name='useinbandfec' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='transport-cc' />
				</payload-type>
				<payload-type xmlns='urn:xmpp:jingle:apps:rtp:1'
					id='103' name='ISAC' clockrate='16000' />
				<payload-type xmlns='urn:xmpp:jingle:apps:rtp:1'
					id='104' name='ISAC' clockrate='32000' />
				<payload-type xmlns='urn:xmpp:jingle:apps:rtp:1'
					id='126' name='telephone-event' clockrate='8000' />
				<rtp-hdrext xmlns='urn:xmpp:jingle:apps:rtp:rtp-hdrext:0'
					id='1' uri='urn:ietf:params:rtp-hdrext:ssrc-audio-level' />
				<rtp-hdrext xmlns='urn:xmpp:jingle:apps:rtp:rtp-hdrext:0'
					id='5'
					uri='http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1770662345'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet' owner='jvb' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2504927419'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/5f12cc4e' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='945621892'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/0ff29808' />
				</source>
			</description>
			<transport xmlns='urn:xmpp:jingle:transports:ice-udp:1'
				pwd='2e1meo1ni5lhu3g7skpunms6kf' ufrag='427q91dquj8uei'>
				<fingerprint xmlns='urn:xmpp:jingle:apps:dtls:0'
					hash='sha-1' setup='actpass' required='false'>C4:D2:89:F8:1E:E7:DD:13:46:DE:96:17:0A:CE:22:7A:52:74:9C:4A</fingerprint>
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					type='host' protocol='udp'
					id='178407b1edb350034d02a39a117b8b580ffffffffe9d7e475'
					ip='172.16.11.44' component='1' port='18341' foundation='1'
					generation='0' priority='2130706431' network='0' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					rel-port='18341' type='srflx' protocol='udp'
					id='178407b1edb350034d02a39a117b8b5807882e2e2' ip='58.187.9.153'
					component='1' port='18341' foundation='2' generation='0'
					network='0' priority='1694498815' rel-addr='172.16.11.44' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					type='host' protocol='udp'
					id='178407b1edb350034d02a39a117b8b580ffffffffd2bf457d'
					ip='172.16.11.44' component='2' port='18342' foundation='1'
					generation='0' priority='2130706430' network='0' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					rel-port='18342' type='srflx' protocol='udp'
					id='178407b1edb350034d02a39a117b8b580616a43ea' ip='58.187.9.153'
					component='2' port='18342' foundation='2' generation='0'
					network='0' priority='1694498814' rel-addr='172.16.11.44' />
			</transport>
		</content>
		<content name='video' senders='both' creator='initiator'>
			<description xmlns='urn:xmpp:jingle:apps:rtp:1'
				media='video'>
				<payload-type xmlns='urn:xmpp:jingle:apps:rtp:1'
					id='100' name='VP8' clockrate='90000'>
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='ccm' subtype='fir' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='nack' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='nack' subtype='pli' />
					<parameter xmlns='urn:xmpp:jingle:apps:rtp:1' value='800'
						name='x-google-start-bitrate' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='transport-cc' />
				</payload-type>
				<payload-type xmlns='urn:xmpp:jingle:apps:rtp:1'
					id='107' name='H264' clockrate='90000'>
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='ccm' subtype='fir' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='nack' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='nack' subtype='pli' />
					<parameter xmlns='urn:xmpp:jingle:apps:rtp:1' value='800'
						name='x-google-start-bitrate' />
					<parameter xmlns='urn:xmpp:jingle:apps:rtp:1'
						value='42e01f;level-asymmetry-allowed=1;packetization-mode=1;'
						name='profile-level-id' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='transport-cc' />
				</payload-type>
				<payload-type xmlns='urn:xmpp:jingle:apps:rtp:1'
					id='101' name='VP9' clockrate='90000'>
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='ccm' subtype='fir' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='nack' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='nack' subtype='pli' />
					<rtcp-fb xmlns='urn:xmpp:jingle:apps:rtp:rtcp-fb:0'
						type='transport-cc' />
					<parameter xmlns='urn:xmpp:jingle:apps:rtp:1' value='800'
						name='x-google-start-bitrate' />
				</payload-type>
				<rtp-hdrext xmlns='urn:xmpp:jingle:apps:rtp:rtp-hdrext:0'
					id='3'
					uri='http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time' />
				<rtp-hdrext xmlns='urn:xmpp:jingle:apps:rtp:rtp-hdrext:0'
					id='5'
					uri='http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='4120097836'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet' owner='jvb' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1795212635'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/5f12cc4e' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2078631139'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/5f12cc4e' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1350511161'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/5f12cc4e' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3893156471'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/5f12cc4e' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2472965206'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/5f12cc4e' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='353021661'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/5f12cc4e' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3869850570'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/0ff29808' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1360448886'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/0ff29808' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3148520756'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/0ff29808' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2746650326'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/0ff29808' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1095012019'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/0ff29808' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1426664304'>
					<ssrc-info xmlns='http://jitsi.org/jitmeet'
						owner='testroom@conference.jaime.kidtopi.com/0ff29808' />
				</source>
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1795212635' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2078631139' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1350511161' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2472965206' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3893156471' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='353021661' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1795212635' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1350511161' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3893156471' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3869850570' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1360448886' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3148520756' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1095012019' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2746650326' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='1426664304' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3869850570' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='3148520756' />
				<source xmlns='urn:xmpp:jingle:apps:rtp:ssma:0'
					direction='sendrecv' ssrc='2746650326' />
			</description>
			<transport xmlns='urn:xmpp:jingle:transports:ice-udp:1'
				pwd='4ddfeuh8po1fc0aqbhodkioot1' ufrag='2pfgg1dquj8uev'>
				<fingerprint xmlns='urn:xmpp:jingle:apps:dtls:0'
					hash='sha-1' setup='actpass' required='false'>C4:D2:89:F8:1E:E7:DD:13:46:DE:96:17:0A:CE:22:7A:52:74:9C:4A</fingerprint>
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					type='host' protocol='udp'
					id='178407b1edb350033e4d9b066c6c07960ffffffffc611689f'
					ip='172.16.11.44' component='1' port='18343' foundation='1'
					generation='0' priority='2130706431' network='0' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					rel-port='18343' type='srflx' protocol='udp'
					id='178407b1edb350033e4d9b066c6c0796054bc670c' ip='58.187.9.153'
					component='1' port='18343' foundation='2' generation='0'
					network='0' priority='1694498815' rel-addr='172.16.11.44' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					type='host' protocol='udp'
					id='178407b1edb350033e4d9b066c6c079602b25f1ab' ip='172.16.11.44'
					component='2' port='18344' foundation='1' generation='0'
					priority='2130706430' network='0' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					rel-port='18344' type='srflx' protocol='udp'
					id='178407b1edb350033e4d9b066c6c07960ffffffffb9d0f018'
					ip='58.187.9.153' component='2' port='18344' foundation='2'
					generation='0' network='0' priority='1694498814'
					rel-addr='172.16.11.44' />
			</transport>
		</content>
		<content name='data' senders='both' creator='initiator'>
			<description xmlns='urn:xmpp:jingle:apps:rtp:1'
				media='application' />
			<transport xmlns='urn:xmpp:jingle:transports:ice-udp:1'
				pwd='2u6nmo0dkvpf4sj2gn9d4meksk' ufrag='2h6b51dquj8uf2'>
				<rtcp-mux xmlns='urn:xmpp:jingle:transports:ice-udp:1' />
				<fingerprint xmlns='urn:xmpp:jingle:apps:dtls:0'
					hash='sha-1' setup='actpass' required='false'>C4:D2:89:F8:1E:E7:DD:13:46:DE:96:17:0A:CE:22:7A:52:74:9C:4A</fingerprint>
				<sctpmap xmlns='urn:xmpp:jingle:transports:dtls-sctp:1'
					number='5000' protocol='webrtc-datachannel' streams='1024' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					type='host' network='0'
					id='178407b1edb3500320ad27bb167137c202a6e96a7' ip='172.16.11.44'
					component='1' port='4443' foundation='1' generation='0'
					protocol='ssltcp' priority='2130706431' tcptype='passive' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					type='host' protocol='udp'
					id='178407b1edb3500320ad27bb167137c202a6eac5c' ip='172.16.11.44'
					component='1' port='10000' foundation='3' generation='0'
					priority='2130706431' network='0' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					rel-port='4443' type='srflx' network='0'
					id='178407b1edb3500320ad27bb167137c20ffffffffb9199514'
					protocol='ssltcp' ip='58.187.9.153' component='1' port='4443'
					foundation='2' generation='0' rel-addr='172.16.11.44'
					priority='1694498815' tcptype='passive' />
				<candidate xmlns='urn:xmpp:jingle:transports:ice-udp:1'
					rel-port='10000' type='srflx' protocol='udp'
					id='178407b1edb3500320ad27bb167137c20ffffffffb919aac9'
					ip='58.187.9.153' component='1' port='10000' foundation='4'
					generation='0' network='0' priority='1677724415'
					rel-addr='172.16.11.44' />
			</transport>
		</content>
		<bridge-session xmlns='http://jitsi.org/protocol/focus'
			xmlns:stream='http://etherx.jabber.org/streams' id='ffbec0_c2f0d9'></bridge-session>
	</jingle>
</iq>
         */
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

                //
                List<Source> sources = rtpDescriptionPacketExtension.getChildExtensionsOfType(Source.class);
                for (Source source : sources)
                {
                	SSRCInfoPacketExtension ssrcInfo = source.getFirstChildOfType(SSRCInfoPacketExtension.class);
                	if (ssrcInfo != null)
                		getOrCreateEndpoint(ssrcInfo.getOwner()).addSsrc(mediaType, Long.valueOf(source.getSSRC()));
                }
        	}
            formatAndPTs.put(mediaType, mapMediaFormat);

            //
            IceUdpTransportPacketExtension iceUdpTransportPacketExtension = contentPacketExtension.getFirstChildOfType(IceUdpTransportPacketExtension.class);
            
            iceTransports.put(mediaType, iceUdpTransportPacketExtension);
            fingerprints.put(mediaType, iceUdpTransportPacketExtension.getFirstChildOfType(DtlsFingerprintPacketExtension.class));

        	this.supportedMediaTypes.add(mediaType);
        }
    }

    /**
     * Handle the Jingle presence packet, record the partcipant's information
     * like jid, ssrc.
     * 
     * @param p is the presence packet.
     */
    private void handlePresencePacket(Presence p)
    {
        MUCUser userExt = (MUCUser) p.getExtension(MUCUser.ELEMENT, MUCUser.NAMESPACE);

        /*
         * In case of presence packet isn't sent by participant, so we can't get
         * participant id from p.getFrom().
         */ 
        Jid participantJid = userExt.getItem().getJid();

        /*
         * Jitsi-meeting presence packet should contain participant jid and
         * media packet extension
         */
        if (participantJid == null)
            return;

        System.out.println("Join: "+participantJid);

        // Oh, it seems that some participant has left the MUC.
        if (p.getType() == Presence.Type.unavailable)
        {
            removeEndpoint(participantJid);
            fireEvent(new TaskEvent(TaskEvent.Type.PARTICIPANT_LEFT));
        }
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
    
    public void addTaskEventListener(TaskEventListener listener)
    {
        synchronized (listeners)
        {
            listeners.add(listener);
        }
    }

    public void removeTaskEventListener(TaskEventListener listener)
    {
        synchronized (listeners)
        {
            listeners.remove(listener);
        }
    }
    
    /**
     * Fire a <tt>TaskEvent</tt>, notify listeners we've made new
     * progress which they may interest in.
     * 
     * @param event
     */
    private void fireEvent(TaskEvent event)
    {
        synchronized (listeners)
        {
            for (TaskEventListener l : listeners)
                l.handleTaskEvent(event);
        }
    }
    
    /**
     * Handles events coming from the {@link org.jitsi.jirecon.task.Task} which owns
     * us.
     *
     * @param evt is the specified event.
     */
    @Override
    public void handleEvent(TaskManagerEvent evt)
    {
        TaskManagerEvent.Type type = evt.getType();
        if (TaskManagerEvent.Type.TASK_STARTED.equals(type))
            sendRecordingOnPresence();
        else if (TaskManagerEvent.Type.TASK_ABORTED.equals(type) || TaskManagerEvent.Type.TASK_FINISED.equals(type))
            sendRecordingOffPresence();
    }

    /**
     * Send local presence to the MUC, indicating that recording is turned on.
     * @throws InterruptedException 
     * @throws NotConnectedException 
     */
    private void sendRecordingOnPresence()
    {
    	Stanza presence = new Presence(Presence.Type.available);
        presence.setTo(muc.getRoom());
        presence.addExtension(new Nick(NICKNAME));
        presence.addExtension(new RecorderExtension("true"));
        try {
			connection.sendStanza(presence);
		} catch (NotConnectedException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
    }

    /**
     * Send local presence to the MUC, indicating that recording is turned off.
     * @throws InterruptedException 
     * @throws NotConnectedException 
     */
    private void sendRecordingOffPresence()
    {
    	Stanza presence = new Presence(Presence.Type.available);
        presence.setTo(muc.getRoom());
        presence.addExtension(new Nick(NICKNAME));
        presence.addExtension(new RecorderExtension("false"));
        try {
			connection.sendStanza(presence);
		} catch (NotConnectedException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
    }

    /**
     * Get all endpoint in this conference
     */
    public List<Endpoint> getEndpoints()
    {
        synchronized (endpoints)
        {
            return new LinkedList<Endpoint>(endpoints.values());
        }
    }

    private Endpoint getOrCreateEndpoint(Jid jid)
    {
        synchronized (endpoints)
        {
        	Endpoint endpoint = endpoints.get(jid);
            if (endpoint == null)
            {
                logger.info("Add Endpoint " + jid);
            	endpoints.put(jid, endpoint = new Endpoint(jid));
            	fireEvent(new TaskEvent(TaskEvent.Type.PARTICIPANT_CAME));
            }
            return endpoint;
        }
    }

    /**
     * Remove an endpoint with the given JID specified endpoint.
     * 
     * @param jid Indicate which endpoint to remove.
     */
    private void removeEndpoint(Jid jid)
    {
        logger.info("Remove Endpoint " + jid);
        
        synchronized (endpoints)
        {
            endpoints.remove(jid);
        }
    }

}
