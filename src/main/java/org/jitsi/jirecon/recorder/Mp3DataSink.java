/*
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
package org.jitsi.jirecon.recorder;

import org.jitsi.service.neomedia.recording.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;

import javax.media.*;
import javax.media.datasink.*;
import javax.media.format.*;
import javax.media.protocol.*;
import java.io.*;

/**
 * A <tt>DataSink</tt> implementation which writes output in webm format.
 *
 * @author Boris Grozev
 */
public class Mp3DataSink
	implements DataSink, BufferTransferHandler
{

	/**
	 * The <tt>Logger</tt> used by the <tt>WebmDataSink</tt> class and its instances
	 * for logging output.
	 */
	private static final Logger logger = Logger.getLogger(Mp3DataSink.class);

	/**
	 * Whether to generate a RECORDING_ENDED event when closing.
	 */
	private static boolean USE_RECORDING_ENDED_EVENTS = true;

	/**
	 * The <tt>Mp3Writer</tt> which we use to write the frames to a file.
	 */
	private Mp3Writer writer = null;

	private RecorderEventHandler eventHandler;
	private long ssrc = -1;

	/**
	 * Whether this <tt>DataSink</tt> is open and should write to its
	 * <tt>WebmWriter</tt>.
	 */
	private boolean open = false;
	private final Object openCloseSyncRoot = new Object();

	/**
	 * A <tt>Buffer</tt> used to transfer frames.
	 */
	private Buffer buffer = new Buffer();

	private WebmWriter.FrameDescriptor fd = new WebmWriter.FrameDescriptor();

	/**
	 * Our <tt>DataSource</tt>.
	 */
	private DataSource dataSource = null;

	/**
	 * The name of the file into which we will write.
	 */
	private String filename;

	/**
	 * The RTP time stamp of the first frame written to the output webm file.
	 */
	private long firstFrameRtpTimestamp = -1;

	/**
	 * The time as returned by <tt>System.currentTimeMillis()</tt> of the first
	 * frame written to the output webm file.
	 */
	private long firstFrameTime = -1;

	/**
	 * The PTS (presentation timestamp) of the last frame written to the output
	 * file. In milliseconds.
	 */
	private long lastFramePts = -1;

    /**
	 * Initialize a new <tt>WebmDataSink</tt> instance.
	 * 
	 * @param filename   the name of the file into which to write.
	 * @param dataSource the <tt>DataSource</tt> to use.
	 */
	public Mp3DataSink(String filename, DataSource dataSource) {
		this.filename = filename;
		this.dataSource = dataSource;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void addDataSinkListener(DataSinkListener dataSinkListener) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void close() {
		synchronized (openCloseSyncRoot) {
			if (!open) {
				if (logger.isDebugEnabled())
					logger.debug("Not closing WebmDataSink: already closed.");
				return;
			}
			if (writer != null)
				writer.close();
			if (USE_RECORDING_ENDED_EVENTS && eventHandler != null && firstFrameTime != -1 && lastFramePts != -1) {
				RecorderEvent event = new RecorderEvent();
				event.setType(RecorderEvent.Type.RECORDING_ENDED);
				event.setSsrc(ssrc);
				event.setFilename(filename);

				// make sure that the difference in the 'instant'-s of the
				// STARTED and ENDED events matches the duration of the file
				event.setDuration(lastFramePts);

				event.setMediaType(MediaType.VIDEO);
				eventHandler.handleEvent(event);
			}

			open = false;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getContentType() {
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public MediaLocator getOutputLocator() {
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void open() throws IOException, SecurityException {
		synchronized (openCloseSyncRoot) {
			if (dataSource instanceof PushBufferDataSource) {
				PushBufferDataSource pbds = (PushBufferDataSource) dataSource;
				PushBufferStream[] streams = pbds.getStreams();

				// XXX: should we allow for multiple streams in the data source?
				for (PushBufferStream stream : streams) {
					// XXX whats the proper way to check for this? and handle?
					if (!stream.getFormat().matches(new VideoFormat("VP8")))
						throw new IOException("Unsupported stream format");

					stream.setTransferHandler(this);
				}
			}
			dataSource.connect();

			open = true;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void removeDataSinkListener(DataSinkListener dataSinkListener) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setOutputLocator(MediaLocator mediaLocator) {
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void start() throws IOException {
		writer = new Mp3Writer(filename);
		writer.writeFileHeader();
		dataSource.start();
		if (logger.isInfoEnabled())
			logger.info("Created Mp3Writer on " + filename);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void stop() throws IOException {
		// XXX: should we do something here? reset waitingForKeyframe?
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object getControl(String s) {
		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object[] getControls() {
		return new Object[0];
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setSource(DataSource dataSource) throws IOException, IncompatibleSourceException {
		// maybe we should throw an exception here, since we don't support
		// changing the data source?
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void transferData(PushBufferStream stream) {
		synchronized (openCloseSyncRoot) {
			if (!open)
				return;

			try {
				stream.read(buffer);
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}

			byte[] data = (byte[]) buffer.getData();
			int offset = buffer.getOffset();
			int len = buffer.getLength();

			/*
			 * Until an SDES packet is received by FMJ, it doesn't correctly set the
			 * packets' timestamps. To avoid waiting, we use the RTP time stamps directly.
			 * We can do this because VP8 always uses a rate of 90000.
			 */
			long rtpTimeStamp = buffer.getRtpTimeStamp();

			fd.buffer = data;
			fd.offset = offset;
			fd.length = len;

			long diff = rtpTimeStamp - firstFrameRtpTimestamp;
			if (diff < -(1L << 31))
				diff += 1L << 32;
			// pts is in milliseconds, the VP8 rtp clock rate is 90000
			fd.pts = diff / 90;
			writer.writeFrame(fd);

			lastFramePts = fd.pts;
		} // synchronized
	}

	public RecorderEventHandler getEventHandler() {
		return eventHandler;
	}

	public void setEventHandler(RecorderEventHandler eventHandler) {
		this.eventHandler = eventHandler;
	}

	public void setSsrc(long ssrc) {
		this.ssrc = ssrc;
	}

}
