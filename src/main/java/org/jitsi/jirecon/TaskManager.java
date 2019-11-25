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

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;

import org.jitsi.jirecon.TaskManagerEvent.*;
import org.jitsi.jirecon.protocol.extension.*;
import org.jitsi.jirecon.utils.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.xmpp.extensions.jingle.JingleIQ;
import org.jitsi.xmpp.extensions.jingle.JingleIQProvider;
import org.jitsi.xmpp.extensions.jingle.SctpMapExtension;
import org.jitsi.xmpp.extensions.jingle.SctpMapExtensionProvider;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;

/**
 * The manager of <tt>Task</tt>s. Each <tt>Task</tt> represents a
 * recording task for a specific Jitsi Meet conference.
 *
 * @author lishunyang
 */
public class TaskManager
    implements JireconEventListener
{
    /**
     * The <tt>Logger</tt> used by the <tt>TaskManager</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger = Logger.getLogger(TaskManager.class.getName());
    
    /**
     * List of <tt>EventListener</tt>.
     */
    private List<JireconEventListener> listeners = new ArrayList<JireconEventListener>();

    /**
     * The <tt>XMPPConnection</tt> used by this <tt>TaskManager</tt> (shared
     * between all <tt>JireconTask</tt>s).
     */
    private XMPPTCPConnection connection;

    /**
     * Maps an ID of a Jitsi Meet conference (the JID of the MUC) to the
     * <tt>JireconTask</tt> for the conference.
     */
    private final Map<String, Task> tasks = new HashMap<String, Task>();

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
    synchronized public void init(String configurationPath)
        throws Exception
    {
        logger.info("Initialize.");

        if (isInitialized)
        {
            logger.log(Level.WARNING, "Already initialized: ", new Throwable());
            return;
        }

        LibJitsi.start();
        initializePacketProviders();

        System.setProperty(ConfigurationService.PNAME_CONFIGURATION_FILE_NAME, configurationPath);
        System.setProperty(ConfigurationService.PNAME_CONFIGURATION_FILE_IS_READ_ONLY, "true");
        final ConfigurationService cfg = LibJitsi.getConfigurationService();
        
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

        try
        {
            connect(xmppHost, xmppPort, xmppDomain, xmppUser, xmppPass);
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
    synchronized public void uninit()
    {
        logger.info("Un-initialize");
        if (!isInitialized)
        {
            logger.log(Level.WARNING, "Not initialized: ", new Throwable());
            return;
        }

        synchronized (tasks)
        {
            for (Task task : tasks.values())
            {
                task.uninit(true);
            }
        }
        closeConnection();
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
        task.init(mucJid, connection, outputDir);

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
        else
        {
            task.stop();
            task.uninit(keepData);
        }
        return true;
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
    private void connect(String xmppHost, int xmppPort, String xmppDomain, String xmppUser, String xmppPass) throws XMPPException, SmackException, IOException, InterruptedException {
        logger.info("Connecting to xmpp environment on "+xmppHost+":"+xmppPort+" domain="+xmppDomain);

        XMPPTCPConnectionConfiguration.Builder builder = XMPPTCPConnectionConfiguration.builder()
        		.setHost(xmppHost)
        		.setPort(xmppPort)
        		.setXmppDomain(xmppDomain)
                .setUsernameAndPassword(xmppUser, xmppPass);

        if (true)
        {
            logger.log(Level.WARNING, "Disabling certificate verification!");
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
            logger.log(Level.WARNING, "Failed to register disco#info features.");

        // Reconnect automatic
        ReconnectionManager.getInstanceFor(connection).enableAutomaticReconnection();

        //
        logger.info("Logging in as XMPP client using: host=" + xmppHost + "; port=" + xmppPort + "; user=" + xmppUser);
        connection.connect().login();
    }

    /**
     * Closes the XMPP connection.
     */
    private void closeConnection()
    {
        logger.info("Closing the XMPP connection.");
        if (connection != null && connection.isConnected())
            connection.disconnect();
    }

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
                MediaExtension.ELEMENT_NAME,
                MediaExtension.NAMESPACE,
                new MediaExtensionProvider());
        ProviderManager.addExtensionProvider(
                SctpMapExtension.ELEMENT_NAME,
                SctpMapExtension.NAMESPACE,
                new SctpMapExtensionProvider());
    }

    /**
     * Adds a <tt>JireconEventListener</tt>.
     *
     * @param listener the <tt>JireconEventListener</tt> to add.
     */
    public void addEventListener(JireconEventListener listener)
    {
        logger.info("Adding JireconEventListener: " + listener);
        listeners.add(listener);
    }

    /**
     * Removes a <tt>JireconEventListener</tt>.
     *
     * @param listener the <tt>JireconEventListener</tt> to remove.
     */
    public void removeEventListener(JireconEventListener listener)
    {
        logger.info("Removing JireconEventListener: " + listener);
        listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     *
     * Implements {@link JireconEventListener#handleEvent(TaskManagerEvent)}.
     */
    @Override
    public void handleEvent(TaskManagerEvent evt)
    {
        String mucJid = evt.getMucJid();

        switch (evt.getType())
        {
        case TASK_ABORTED:
            stopJireconTask(mucJid, false);
            logger.info("Recording task of MUC " + mucJid + " failed.");
            fireEvent(evt);
            break;
        case TASK_FINISED:
            stopJireconTask(mucJid, true);
            logger.info("Recording task of MUC: " + mucJid
                + " finished successfully.");
            fireEvent(evt);
            break;
        case TASK_STARTED:
            logger.info("Recording task of MUC " + mucJid + " started.");
            fireEvent(evt);
            break;
        default:
            break;
        }
    }

    /**
     * Notifies the registered <tt>JireconEventListener</tt> of a
     * <tt>TaskManagerEvent</tt>.
     * 
     * @param evt the event to send.
     */
    private void fireEvent(TaskManagerEvent evt)
    {
        for (JireconEventListener l : listeners)
        {
            l.handleEvent(evt);
        }
    }
}
