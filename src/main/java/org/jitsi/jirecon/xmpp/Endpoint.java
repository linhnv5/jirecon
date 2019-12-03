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
import org.jitsi.utils.MediaType;
import org.jxmpp.jid.EntityFullJid;

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
     * Full MUC address:
     * room_name@muc.server.net/nickname
     */
    private EntityFullJid occupantJid;

	/**
     * Map between <tt>MediaType</tt> and ssrc. Notice that only audio or video has ssrc.
     */
    private Map<MediaType, List<Long>> ssrcs = new HashMap<MediaType, List<Long>>();

    public Endpoint(EntityFullJid id) {
    	this.occupantJid = id;
    }

    public void setId(EntityFullJid id)
    {
        this.occupantJid = id;
    }

    public EntityFullJid getId()
    {
        return occupantJid;
    }

	public void addSsrc(MediaType mediaType, Long ssrc)
    {
    	if (ssrc != null) {
    		List<Long> listSsrc = ssrcs.get(mediaType);
    		if (listSsrc == null)
    			ssrcs.put(mediaType, listSsrc = new ArrayList<Long>());
    		listSsrc.add(ssrc);
    	}
    }

    public void removeSsrc(MediaType mediaType, Long ssrc)
    {
    	if (ssrc != null) {
    		List<Long> listSsrc = ssrcs.get(mediaType);
    		if (listSsrc != null)
    			listSsrc.remove(ssrc);
    	}
    }

    public Map<MediaType, List<Long>> getSsrcs()
    {
        return ssrcs;
    }

    public List<Long> getSsrc(MediaType mediaType)
    {
        return ssrcs.get(mediaType);
    }

    public boolean isEmpty()
    {
    	for (Map.Entry<MediaType, List<Long>> ssrc : ssrcs.entrySet())
    		if (!ssrc.getValue().isEmpty())
    			return false;
    	return true;
    }

    @Override
    public String toString() {
    	return this.occupantJid.toString();
    }

}
