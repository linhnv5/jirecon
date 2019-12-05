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
package org.jitsi.jirecon.xmpp;

import java.util.*;

import net.java.sip.communicator.util.*;

import org.jitsi.jirecon.task.TaskEvent;
import org.jitsi.jirecon.task.TaskEvent.TaskEventListener;
import org.jitsi.xmpp.extensions.jingle.ContentPacketExtension;
import org.jitsi.xmpp.extensions.jitsimeet.UserInfoPacketExt;
import org.jitsi.xmpp.extensions.jingle.JingleIQ;
import org.jitsi.xmpp.extensions.jingle.Reason;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.XMPPError.Condition;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jivesoftware.smackx.nick.packet.Nick;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;

/**
 * Define chatroom struct
 * 
 * @author ljnk975
 * 
 */
public final class ChatRoom implements TaskEventListener
{

	/**
     * The <tt>Logger</tt>, used to log messages to standard output.
     */
    private static final Logger logger = Logger.getLogger(ChatRoom.class);

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
    XMPPConnection connection;

    /**
     * The instance of a <tt>MultiUserChat</tt>. <tt>JireconSessionImpl</tt>
     * will join it as the first step.
     */
    private MultiUserChat muc;

    /**
     * This room localpart
     */
    Localpart localpart;

    /**
     * Participant in the meeting
     */
    final Map<Jid, Participant> participants = new HashMap<Jid, Participant>();

    /**
     * <tt>Endpoint</tt>s in the meeting.
     */
    final Map<EntityFullJid, Endpoint> endpoints = new HashMap<EntityFullJid, Endpoint>();

    /**
     * Packet listening for precence packet handle
     */
    private StanzaListener receivingListener;

    /**
     * Jingle session
     */
    private JingleSession jingleSession;

    /**
     * Initialize <tt>JireconSession</tt>.
     * 
     * @param connection is used for send/receive XMPP packet.
     * @throws Exception  if failed to join MUC.
     */
    ChatRoom(XMPPConnection connection)
    		throws Exception
    {
        this.connection = connection;
    }

    /**
     * Join a Multi-User-Chat of specified MUC jid.
     * 
     * @param mucJid The specified MUC jid.
     * @param nickname The name in MUC.
     * @throws Exception if failed to join MUC.
     */
    void joinMUC(String mucJid, String nickname)
        throws Exception
    {
        logger.info("Joining MUC...");

        // muc manager
        MultiUserChatManager mucManager = MultiUserChatManager.getInstanceFor(connection);

        // Room id
        EntityBareJid room = JidCreate.entityBareFrom(mucJid);

        // Localpart
        localpart = room.getLocalpart();

        //
        muc = mucManager.getMultiUserChat(room);
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

        Presence presence = new Presence(Presence.Type.available);
        presence.setTo(muc.getRoom());
        presence.addExtension(new Nick(NICKNAME));
        presence.addExtension(new UserInfoPacketExt());
        connection.sendStanza(presence);

        /*
         * Set jingle session
         */
        this.jingleSession = new JingleSession(this);

        /*
         * Register the receiving packet listener to handle presence packet.
         */
        receivingListener = new StanzaListener()
        {
            @Override
            public void processStanza(Stanza packet)
            {
                if (packet instanceof Presence)
                	handlePresencePacket((Presence) packet);
            }
        };
        connection.addSyncStanzaListener(receivingListener, new StanzaFilter()
        {
            @Override
            public boolean accept(Stanza packet)
            {
            	return localpart.equals(packet.getFrom().getLocalpartOrNull());
            }
        });

        logger.info("Joined MUC as "+mucJid+"/"+finalNickname);
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
			jingleSession.sendByePacket(reason, reasonText);
		} catch (Exception e) {
		}
        jingleSession.free();
        leaveMUC();
    }

    private JingleIQ initIQ = null;
    private Object waitForInitPacketSyncRoot = new Object();
    
    /**
     * Handle iq packet
     * @param iqRequest the iq packet request
     * @return result iq packet
     */
	public IQ handleIQRequest(IQ iqRequest) {
		List<ContentPacketExtension> listContent;

		if (iqRequest instanceof JingleIQ) {
			JingleIQ iq = (JingleIQ)iqRequest;

			System.out.println("Action="+iq.getAction());
			switch (iq.getAction()) {
				case SESSION_INITIATE:
					this.initIQ = iq;
					break;
				case ADDSOURCE:
				case SOURCEADD:
			        listContent = iq.getContentList();
			        for (ContentPacketExtension content : listContent)
			        	jingleSession.addSource(content);
			        jingleSession.recorderMgr.updateSynchronizers();
					break;
				case REMOVESOURCE:
				case SOURCEREMOVE:
			        listContent = iq.getContentList();
			        for (ContentPacketExtension content : listContent)
			        	jingleSession.removeSource(content);
			        jingleSession.recorderMgr.updateSynchronizers();
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

        if (!jingleSession.isSessionInit) {
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

    		jingleSession.initJingleSession(initIQ);
        }

        return this.initIQ;
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

        // Oh, it seems that some participant has left the MUC.
        if (p.getType() == Presence.Type.unavailable)
        {
        	System.out.println(participantJid+" leave!");
        	removeParticipant(participantJid);
        }
        else
        {
        	System.out.println(participantJid+" came!");
        	Participant pa = addParticipant(participantJid);
        	pa.setOccupantJid(p.getFrom().asEntityFullJidIfPossible());
        	
        	Nick nick = (Nick) p.getExtension(Nick.ELEMENT_NAME, Nick.NAMESPACE);
        	if (nick != null)
        		pa.setNickName(nick.getName());
        }
    }

    /**
     * Handles events coming from the {@link org.jitsi.jirecon.task.Task} which owns
     * us.
     *
     * @param evt is the specified event.
     */
    @Override
    public void handleEvent(TaskEvent evt)
    {
        TaskEvent.Type type = evt.getType();
        if (TaskEvent.Type.TASK_STARTED.equals(type))
            sendRecordingOnPresence();
        else if (TaskEvent.Type.TASK_ABORTED.equals(type) || TaskEvent.Type.TASK_FINISED.equals(type))
            sendRecordingOffPresence();
    }

    /**
     * Send local presence to the MUC, indicating that recording is turned on.
     * @throws InterruptedException 
     * @throws NotConnectedException 
     */
    private void sendRecordingOnPresence()
    {
//    	Stanza presence = new Presence(Presence.Type.available);
//        presence.setTo(muc.getRoom());
//        presence.addExtension(new Nick(NICKNAME));
//        presence.addExtension(new RecorderExtension("true"));
//        try {
//			connection.sendStanza(presence);
//		} catch (NotConnectedException e) {
//			e.printStackTrace();
//		} catch (InterruptedException e) {
//			e.printStackTrace();
//		}
    }

    /**
     * Send local presence to the MUC, indicating that recording is turned off.
     * @throws InterruptedException 
     * @throws NotConnectedException 
     */
    private void sendRecordingOffPresence()
    {
//    	Stanza presence = new Presence(Presence.Type.available);
//        presence.setTo(muc.getRoom());
//        presence.addExtension(new Nick(NICKNAME));
//        presence.addExtension(new RecorderExtension("false"));
//        try {
//			connection.sendStanza(presence);
//		} catch (NotConnectedException e) {
//			e.printStackTrace();
//		} catch (InterruptedException e) {
//			e.printStackTrace();
//		}
    }

    /**
     * Get all endpoint in this conference
     */
    public Collection<Endpoint> getEndpoints()
    {
        synchronized (endpoints)
        {
            return endpoints.values();
        }
    }

    private Participant addParticipant(Jid jid)
    {
        logger.info("Add Participant " + jid);

        Participant p;
        synchronized (participants)
        {
            participants.put(jid, p = new Participant(jid));
		}
        return p;
    }

    private void removeParticipant(Jid jid)
    {
        logger.info("Remove Participant " + jid);

        synchronized (participants)
        {
            participants.remove(jid);
		}
    }

    Participant getParticipantForEndPoint(Endpoint endpoint)
    {
        synchronized (participants)
        {
        	for (Map.Entry<Jid, Participant> entry : participants.entrySet())
        	{
        		if (endpoint.getId().equals(entry.getValue().getOccupantJid()))
        			return entry.getValue();
        	}
		}
		return null;
    }

    /**
     * Get an endpoint with the given JID specified endpoint. <br/>
     * If endpoint not exists then create endpoint and return
     * 
     * @param jid Indicate which endpoint to remove.
     * @return endpoint
     */
    Endpoint getEndpoint(EntityFullJid jid)
    {
        synchronized (endpoints)
        {
        	Endpoint endpoint = endpoints.get(jid);
            if (endpoint == null)
            {
                logger.info("Add Endpoint " + jid);
            	endpoints.put(jid, endpoint = new Endpoint(jid));
            }
            return endpoint;
        }
    }

    /**
     * Remove an endpoint with the given JID specified endpoint.
     * 
     * @param jid Indicate which endpoint to remove.
     */
    void removeEndpoint(EntityFullJid jid)
    {
        logger.info("Remove Endpoint " + jid);
        
        synchronized (endpoints)
        {
            endpoints.remove(jid);
        }
    }

    /**
     * Start recording
     */
    public void startRecording(String outputDir)
    {
    	jingleSession.startRecording(outputDir);
    }

}
