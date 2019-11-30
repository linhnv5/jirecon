/*
 * Jicofo, the Jitsi Conference Focus.
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
package org.jitsi.jirecon.utils;

import org.jitsi.xmpp.extensions.health.*;

import java.util.*;

/**
 * Utility class for feature discovery.
 *
 * @author Pawel Domas
 */
public class DiscoveryUtil
{

    /**
     * List contains default feature set.
     */
    private static ArrayList<String> defaultFeatures;

    /**
     * Audio RTP feature name.
     */
    public final static String FEATURE_AUDIO = "urn:xmpp:jingle:apps:rtp:audio";

    /**
     * Video RTP feature name.
     */
    public final static String FEATURE_VIDEO = "urn:xmpp:jingle:apps:rtp:video";

    /**
     * ICE feature name.
     */
    public final static String FEATURE_ICE = "urn:xmpp:jingle:transports:ice-udp:1";

    /**
     * DTLS/SCTP feature name.
     */
    public final static String FEATURE_SCTP = "urn:xmpp:jingle:transports:dtls-sctp:1";

    /**
     * RTX (RFC4588) support.
     */
    public final static String FEATURE_RTX = "urn:ietf:rfc:4588";

    /**
     * The Jingle DTLS feature name (XEP-0320).
     */
    public final static String FEATURE_DTLS = "urn:xmpp:jingle:apps:dtls:0";

    /**
     * RTCP mux feature name.
     */
    public final static String FEATURE_RTCP_MUX = "urn:ietf:rfc:5761";

    /**
     * RTP bundle feature name.
     */
    public final static String FEATURE_RTP_BUNDLE = "urn:ietf:rfc:5888";

    /**
     * Heath checks feature namespace.
     */
    public final static String FEATURE_HEALTH_CHECK = HealthCheckIQ.NAMESPACE;

    /**
     * A namespace for our custom "lip-sync" feature. Advertised by the clients
     * that support all of the functionality required for doing the lip-sync
     * properly.
     */
    public final static String FEATURE_LIPSYNC = "http://jitsi.org/meet/lipsync";

    /**
     * Returns default participant feature set(all features).
     */
    static public List<String> getDefaultParticipantFeatureSet()
    {
        if (defaultFeatures == null)
        {
            defaultFeatures = new ArrayList<String>(7);
            defaultFeatures.add(FEATURE_AUDIO);
            defaultFeatures.add(FEATURE_VIDEO);
            defaultFeatures.add(FEATURE_ICE);
            defaultFeatures.add(FEATURE_SCTP);
            defaultFeatures.add(FEATURE_DTLS);
            defaultFeatures.add(FEATURE_RTCP_MUX);
            defaultFeatures.add(FEATURE_RTP_BUNDLE);
        }
        return defaultFeatures;
    }

}
