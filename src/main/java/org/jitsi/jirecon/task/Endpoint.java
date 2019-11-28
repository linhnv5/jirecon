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

import java.util.*;
import org.jitsi.utils.MediaType;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.Jid;

/**
 * Data structure that encapsulates endpoint.
 * <p>
 * An endpoint represents a participant in the meeting. It contains id and
 * ssrcs.
 * 
 * @author lishunyang
 * 
 */
public class Endpoint
{

    /**
     * Endpoint id.
     */
    private Jid id;

    /**
     * Map between <tt>MediaType</tt> and ssrc. Notice that only audio or video has ssrc.
     */
    private Map<MediaType, Long> ssrcs = new HashMap<MediaType, Long>();

    public Endpoint() {
    }

    public void setId(Jid id)
    {
        this.id = id;
    }

    public void setSsrc(MediaType mediaType, Long ssrc)
    {
    	if (ssrc != null)
    		ssrcs.put(mediaType, ssrc);
    }

    public Jid getId()
    {
        return id;
    }

    public BareJid getBareId()
    {
        return id.asBareJid();
    }

    public Map<MediaType, Long> getSsrcs()
    {
        return ssrcs;
    }

    public long getSsrc(MediaType mediaType)
    {
        return ssrcs.get(mediaType);
    }

}
