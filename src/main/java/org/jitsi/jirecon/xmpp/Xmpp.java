package org.jitsi.jirecon.xmpp;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;

import org.jitsi.jirecon.task.TaskManager;
import org.jitsi.jirecon.utils.DiscoveryUtil;
import org.jitsi.xmpp.extensions.jingle.JingleIQ;
import org.jitsi.xmpp.extensions.jingle.JingleIQProvider;
import org.jitsi.xmpp.extensions.jingle.SctpMapExtension;
import org.jitsi.xmpp.extensions.jingle.SctpMapExtensionProvider;
import org.jitsi.xmpp.extensions.jitsimeet.MediaPresenceExtension;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.iqrequest.IQRequestHandler;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;

import net.java.sip.communicator.util.Logger;

/**
 * Manager MultiUserChat Service <br/>
 * Join MUC, Leave MUC, ...
 * @author ljnk975
 *
 */
public final class Xmpp
{

    /**
     * The <tt>Logger</tt> used by the <tt>TaskManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(TaskManager.class);

    /**
     * The <tt>XMPPConnection</tt> used by this <tt>MucClientManager</tt> (shared between all <tt>MucClient</tt>s).
     */
    private XMPPTCPConnection connection;

    /**
     * The XMPP domain provide MUC service
     */
    private String mucDomain;

    /**
     * Map of MUC joined
     */
    private Map<String, ChatRoom> mucClients = new HashMap<String, ChatRoom>();

    /**
     * Register our <tt>PacketExtensionProvider</tt>s with Smack's
     * <tt>ProviderManager</tt>.
     */
    private void initializePacketProviders()
    {
        ProviderManager.addIQProvider(
                JingleIQ.ELEMENT_NAME,
                JingleIQ.NAMESPACE,
                new JingleIQProvider());
        ProviderManager.addExtensionProvider(
        		SctpMapExtension.NAMESPACE,
        		SctpMapExtension.NAMESPACE,
        		new SctpMapExtensionProvider());
        MediaPresenceExtension.registerExtensions();
    }

    /**
     * Creates {@link #connection} and connects to the XMPP server.
     * 
     * @param xmppDomain is the domain name of XMPP server
     * @param xmppHost is the host name of XMPP server.
     * @param xmppPort is the port of XMPP server.
     * @param xmppUser the XMPP username to use (should NOT include the domain).
     * Use <tt>null</tt> to login anonymously.
     * @param xmppPass the XMPP password.
     * @throws XMPPException in case of failure to connect and login.
     * @throws InterruptedException 
     * @throws IOException 
     * @throws SmackException 
     */
    public void connect(String xmppHost, int xmppPort, String xmppDomain, String xmppUser, String xmppPass, String mucDomain) throws XMPPException, SmackException, IOException, InterruptedException
    {
        initializePacketProviders();

        this.mucDomain = mucDomain;

        logger.info("Connecting to xmpp environment on "+xmppHost+":"+xmppPort+" domain="+xmppDomain);

        XMPPTCPConnectionConfiguration.Builder builder = XMPPTCPConnectionConfiguration.builder()
        		.setHost(xmppHost)
        		.setPort(xmppPort)
        		.setXmppDomain(xmppDomain)
                .setUsernameAndPassword(xmppUser, xmppPass);

        if (true)
        {
            logger.warn("Disabling certificate verification!");
            builder.setCustomX509TrustManager(new X509TrustManager() {
				@Override
				public X509Certificate[] getAcceptedIssuers() {
			        return new X509Certificate[0];
				}
				@Override
				public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
					
				}
				@Override
				public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
					
				}
			});
            builder.setHostnameVerifier(new HostnameVerifier() {
				@Override
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			});
        }

        connection = new XMPPTCPConnection(builder.build());

        // Register Jingle Features.
        ServiceDiscoveryManager discoveryManager;
        if ((discoveryManager = ServiceDiscoveryManager.getInstanceFor(connection)) != null)
        {
            discoveryManager.addFeature(DiscoveryUtil.FEATURE_VIDEO);
            discoveryManager.addFeature(DiscoveryUtil.FEATURE_AUDIO);
            discoveryManager.addFeature(DiscoveryUtil.FEATURE_ICE);
            discoveryManager.addFeature(DiscoveryUtil.FEATURE_SCTP);
        }
        else
            logger.warn("Failed to register disco#info features.");

        // Reconnect automatic
        ReconnectionManager.getInstanceFor(connection).enableAutomaticReconnection();

        //
        logger.info("Logging in as XMPP client using: host=" + xmppHost + "; port=" + xmppPort + "; user=" + xmppUser);
        connection.connect().login();
        
        /*
         * Register the sending packet listener
         */
        StanzaListener sendingListener = new StanzaListener()
        {
            @Override
            public void processStanza(Stanza packet)
            {
                logger.info("--->: " + packet);
                System.out.println(packet.toXML());
            }
        };
        connection.addStanzaSendingListener(sendingListener, new StanzaFilter() {
            @Override
            public boolean accept(Stanza packet)
            {
                return true;
            }
        });

        /*
         * Register the receiving packet listener to handle presence packet.
         */
        StanzaListener receivingListener = new StanzaListener()
        {
            @Override
            public void processStanza(Stanza packet)
            {
                logger.info("["+packet.getClass().getName() + "]<---: " + packet);
                System.out.println(packet.toXML());
            }
        };
        connection.addSyncStanzaListener(receivingListener, new StanzaFilter()
        {
            @Override
            public boolean accept(Stanza packet)
            {
                return true;
            }
        });
        
        /*
         * Register a packet listener for handling Jingle session-init packet.
         */
        IQRequestHandler packetListener = new IQRequestHandler()
        {
			@Override
			public Mode getMode() {
				return Mode.sync;
			}

			@Override
			public Type getType() {
				return Type.set;
			}

			@Override
			public String getElement() {
				return JingleIQ.ELEMENT_NAME;
			}

			@Override
			public String getNamespace() {
				return JingleIQ.NAMESPACE;
			}

			@Override
			public IQ handleIQRequest(IQ packet) {
                logger.info("["+packet.getClass().getName() + "]<---: " + packet);
                System.out.println(packet.toXML());
				ChatRoom mucClient = mucClients.get(packet.getFrom().getLocalpartOrNull().toString());
				System.out.println(":: lp="+packet.getFrom().getLocalpartOrNull().toString()+" muc="+mucClient+" "+mucClients);
				if (mucClient != null)
					return mucClient.handleIQRequest(packet);
				return null;
			}
        };
        connection.registerIQRequestHandler(packetListener);
    }

	/**
     * Join a Multi-User-Chat of specified MUC jid.
     * 
     * @param mucJid The specified MUC jid.
     * @param nickname The name in MUC.
     * @return The MUC Client
     * @throws Exception if failed to join MUC.
     */
    public ChatRoom joinMUC(String mucJid, String nickname) 
        throws Exception
    {
    	System.out.println("Add chat: "+mucJid);

    	ChatRoom mucClient = new ChatRoom(connection);
    	this.mucClients.put(mucJid, mucClient);
    	mucClient.joinMUC(mucJid+"@"+mucDomain, nickname);

    	return mucClient;
    }

    /**
     * Call for remove MUCClient from map
     * 
     * @param mucJid
     */
    public void leaveMUC(String mucJid) {
    	this.mucClients.remove(mucJid);
    }

    /**
     * Closes the XMPP connection.
     */
    public void closeConnection()
    {
        logger.info("Closing the XMPP connection.");
        if (connection != null && connection.isConnected())
            connection.disconnect();
    }

}
