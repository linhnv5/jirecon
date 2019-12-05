package org.jitsi.jirecon.xmpp;

import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.Jid;

/**
 * An participant in the meeting
 * 
 * @author ljnk975
 *
 */
public class Participant {

	/**
	 * Jid of participant
	 */
	private Jid jid;
	
	/**
     * Full MUC address:
     * room_name@muc.server.net/nickname
     */
    private EntityFullJid occupantJid;

    /**
     * Participant nickname
     */
    private String nickName;

    /**
     * Create participant by it's jid
     * 
     * @param jid
     */
    Participant(Jid jid) {
    	this.jid = jid;
	}
    
    /**
	 * @return the jid
	 */
	public Jid getJid() {
		return jid;
	}

	/**
	 * @param jid the jid to set
	 */
	public void setJid(Jid jid) {
		this.jid = jid;
	}

	/**
	 * @return the occupantJid
	 */
	public EntityFullJid getOccupantJid() {
		return occupantJid;
	}

	/**
	 * @param occupantJid the occupantJid to set
	 */
	public void setOccupantJid(EntityFullJid occupantJid) {
		this.occupantJid = occupantJid;
	}

	/**
	 * @return the nickName
	 */
	public String getNickName() {
		return nickName;
	}

	/**
	 * @param nickName the nickName to set
	 */
	public void setNickName(String nickName) {
		this.nickName = nickName;
	}

}
