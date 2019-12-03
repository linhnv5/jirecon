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

import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;

import java.io.*;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.avcodec.AVCodec;
import org.bytedeco.javacpp.avcodec.AVCodecContext;
import org.bytedeco.javacpp.avcodec.AVPacket;
import org.bytedeco.javacpp.avformat.AVFormatContext;
import org.bytedeco.javacpp.avformat.AVIOContext;
import org.bytedeco.javacpp.avformat.AVOutputFormat;
import org.bytedeco.javacpp.avformat.AVStream;
import org.bytedeco.javacpp.avutil.AVDictionary;

public class WebmWriter
{

    /**
     * Constant corresponding to <tt>VPX_FRAME_IS_KEY</tt> from libvpx's
     * <tt>vpx/vpx_encoder.h</tt>
     */
    public static int FLAG_FRAME_IS_KEY = 0x01;

    /**
     * Constant corresponding to <tt>VPX_FRAME_IS_INVISIBLE</tt> from libvpx's
     * <tt>vpx/vpx_encoder.h</tt>
     */
    public static int FLAG_FRAME_IS_INVISIBLE = 0x04;

    public static String av_err2str(int errnum) {
		BytePointer data = new BytePointer(new byte[AV_ERROR_MAX_STRING_SIZE]);
		av_make_error_string(data, AV_ERROR_MAX_STRING_SIZE, errnum);
		return data.getString();
	}

    private AVOutputFormat fmt;
    private AVFormatContext oc;

    public WebmWriter(String filename)
            throws IOException
    {
    	int ret;

    	// 
	    oc = new AVFormatContext();

	    /* allocate the output media context */
	    avformat_alloc_output_context2(oc, null, null, filename);
	    if (oc.isNull())
	    	throw new IOException("Could not deduce output format from file extension");
	    
	    fmt = oc.oformat();

	    /* open the output file, if needed */
	    if ((fmt.flags() & AVFMT_NOFILE) == 0) {
	    	AVIOContext pb = new AVIOContext(null);
	        ret = avio_open(pb, filename, AVIO_FLAG_WRITE);
	        if (ret < 0)
	        	throw new IOException("Could not open file: "+av_err2str(ret));
	        oc.pb(pb);
	    }
    }

	private AVCodecContext c;

	public void writeWebmFileHeader(int width, int height)
    {
    	int ret;
    	
    	try {
    		/* find the encoder */
    		AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_VP8);
    		if (codec.isNull())
    			throw new Exception("Could not find encoder for " + avcodec_get_name(fmt.video_codec()));

    		/* New stream */
    		AVStream st = avformat_new_stream(oc, codec);
    		if (st == null)
    			throw new Exception("Could not allocate stream");

    		/* Open Codec Context */
    	    if ((c = avcodec_alloc_context3(codec)).isNull())
    			throw new Exception("Failed to allocate codec context");

    		st.id(oc.nb_streams() - 1);

    		c.bit_rate(400000);

    		/* Resolution must be a multiple of two. */
    		c.width(width);
    		c.height(height);

    		/*
    		 * timebase: This is the fundamental unit of time (in seconds) in terms of which
    		 * frame timestamps are represented. For fixed-fps content, timebase should be
    		 * 1/framerate and timestamp increments should be identical to 1.
    		 */
    		c.time_base().den(15);
    		c.time_base().num(1);
    		c.gop_size(12); /* emit one intra frame every twelve frames at most */
    		c.pix_fmt(AV_PIX_FMT_YUV420P);

    		/* Some formats want stream headers to be separate. */
    		if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) > 0)
    			c.flags(c.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);

    	    /* open it */
    	    ret = avcodec_open2(c, codec, new AVDictionary(null));
    	    if (ret < 0)
    			throw new Exception("Could not open stream codec: "+av_err2str(ret));

    	    /* copy the stream parameters to the muxer */
    	    ret = avcodec_parameters_from_context(st.codecpar(), c);
    	    if (ret < 0)
    			throw new Exception("Could not copy the stream parameters\n");
    	    
    	    /* Write the stream header, if any. */
    	    ret = avformat_write_header(oc, new AVDictionary(null));
    	    if (ret < 0)
    	    	throw new Exception("Error occurred when opening output file: "+av_err2str(ret));
    	} catch(Exception e) {
    		e.printStackTrace();
    	}
    }

    public void close()
    {
	    avcodec_free_context(c);

	    if ((fmt.flags() & AVFMT_NOFILE) > 0)
	        /* Close the output file. */
	        avio_close(oc.pb());

	    /* free the stream */
	    avformat_free_context(oc);
    }

    public void writeFrame(FrameDescriptor fd, boolean keyFrame)
    {
    	int ret;

    	byte[] data = new byte[(int) fd.length];
    	System.arraycopy(fd.buffer, fd.offset, data, 0, (int) fd.length);

    	AVPacket pkt = new AVPacket();
        av_init_packet(pkt);
        pkt.pts(fd.pts);
        pkt.size((int) fd.length);
        pkt.data(new BytePointer(data));
 
        try {
        	if ((ret= av_interleaved_write_frame(oc, pkt)) < 0)
        		throw new Exception("Exception write packet: "+av_err2str(ret));
        } catch(Exception e) {
        	e.printStackTrace();
        }

        av_packet_unref(pkt);
    }

    public static class FrameDescriptor
    {
        public byte[] buffer;
        public int offset;
        public long length;
        public long pts;
        public int flags;
    }

}
