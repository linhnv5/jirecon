package org.jitsi.jirecon.recorder;

import org.jitsi.impl.neomedia.recording.RecorderRtpImpl;
import org.jitsi.service.neomedia.RTPTranslator;

public class RecorderImpl extends RecorderRtpImpl {

	public RecorderImpl(RTPTranslator translator) {
		super(translator);
	}

}
