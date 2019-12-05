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
import static org.bytedeco.javacpp.presets.avutil.AVERROR_EAGAIN;
import static org.bytedeco.javacpp.swresample.*;

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
import org.bytedeco.javacpp.avutil.AVFrame;
import org.bytedeco.javacpp.avutil.AVRational;
import org.bytedeco.javacpp.swresample.SwrContext;
import org.jitsi.jirecon.recorder.WebmWriter.FrameDescriptor;

public class Mp3Writer
{

    private AVOutputFormat fmt;
    private AVFormatContext oc;

	public Mp3Writer(String filename)
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
	        	throw new IOException("Could not open file: "+WebmWriter.av_err2str(ret));
	        oc.pb(pb);
	    }
    }

	private AVStream st;
	private AVCodecContext encCtx;
	private AVCodecContext decCtx;

	// Resample context
    private SwrContext swrCtx;

    private AVFrame frame;
    private AVFrame frame2;

	public void writeFileHeader()
    {
    	int ret;
    	
    	try {
    		/* find the encoder */
    		AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_WAVPACK);
    		if (codec.isNull())
    			throw new Exception("Could not find encoder for " + avcodec_get_name(fmt.video_codec()));

    		/* Open Codec Context */
    	    if ((decCtx = avcodec_alloc_context3(codec)).isNull())
    			throw new Exception("Failed to allocate codec context");

			/* put sample parameters */
    	    decCtx.bit_rate(768_000);

		    /* check that the encoder supports s16 pcm input */
    	    decCtx.sample_fmt(AV_SAMPLE_FMT_S16);

		    /* select other audio parameters supported by the encoder */
    	    decCtx.sample_rate(48000);
    	    decCtx.channel_layout(AV_CH_LAYOUT_MONO);
    	    decCtx.channels(av_get_channel_layout_nb_channels(decCtx.channel_layout()));

    	    /* open it */
    	    ret = avcodec_open2(decCtx, codec, new AVDictionary(null));
    	    if (ret < 0)
    			throw new Exception("Could not open stream codec: "+WebmWriter.av_err2str(ret));

    		//
            int nb_samples = (decCtx.codec().capabilities() & AV_CODEC_CAP_VARIABLE_FRAME_SIZE) > 0 ?  10000 : decCtx.frame_size();

    	    /* tmp Frame */
    		frame = allocAudioFrame(decCtx.sample_fmt(), decCtx.channel_layout(), decCtx.sample_rate(), nb_samples);

    	    /* find the encoder */
    		codec = avcodec_find_encoder(fmt.audio_codec());
    		if (codec.isNull())
    			throw new Exception("Could not find encoder for " + avcodec_get_name(fmt.video_codec()));

    		/* New stream */
    		st = avformat_new_stream(oc, codec);
    		if (st == null)
    			throw new Exception("Could not allocate stream");

    		/* Open Codec Context */
    	    if ((encCtx = avcodec_alloc_context3(codec)).isNull())
    			throw new Exception("Failed to allocate codec context");

    		st.id(oc.nb_streams() - 1);

			/* put sample parameters */
		    encCtx.bit_rate(48000);

		    /* check that the encoder supports s16 pcm input */
		    encCtx.sample_fmt(AV_SAMPLE_FMT_S16);

		    /* select other audio parameters supported by the encoder */
		    encCtx.sample_rate(48000);
		    encCtx.channel_layout(AV_CH_LAYOUT_MONO);
		    encCtx.channels(av_get_channel_layout_nb_channels(encCtx.channel_layout()));

    		/* Some formats want stream headers to be separate. */
    		if ((oc.oformat().flags() & AVFMT_GLOBALHEADER) > 0)
    			encCtx.flags(encCtx.flags() | AV_CODEC_FLAG_GLOBAL_HEADER);

    	    /* open it */
    	    ret = avcodec_open2(encCtx, codec, new AVDictionary(null));
    	    if (ret < 0)
    			throw new Exception("Could not open stream codec: "+WebmWriter.av_err2str(ret));

    		//
            nb_samples = (encCtx.codec().capabilities() & AV_CODEC_CAP_VARIABLE_FRAME_SIZE) > 0 ?  10000 : encCtx.frame_size();

    	    /* tmp Frame */
    		frame2 = allocAudioFrame(encCtx.sample_fmt(), encCtx.channel_layout(), encCtx.sample_rate(), nb_samples);

    		/* copy the stream parameters to the muxer */
    	    ret = avcodec_parameters_from_context(st.codecpar(), encCtx);
    	    if (ret < 0)
    			throw new Exception("Could not copy the stream parameters\n");

    	    /* Write the stream header, if any. */
    	    ret = avformat_write_header(oc, new AVDictionary(null));
    	    if (ret < 0)
    	    	throw new Exception("Error occurred when opening output file: "+WebmWriter.av_err2str(ret));
    	    
            /* Create a resampler context for the conversion. */
    		swrCtx = swr_alloc();

            // Error
            if (swrCtx.isNull())
            	throw new Exception("Could not allocate resample context");

            /* set options */
            av_opt_set_int       (swrCtx, "in_channel_count",   decCtx.channels(),     0);
            av_opt_set_int       (swrCtx, "in_sample_rate",     decCtx.sample_rate(),  0);
            av_opt_set_sample_fmt(swrCtx, "in_sample_fmt",      decCtx.sample_fmt(),   0);
            av_opt_set_int       (swrCtx, "out_channel_count",  encCtx.channels(),     0);
            av_opt_set_int       (swrCtx, "out_sample_rate",    encCtx.sample_rate(),  0);
            av_opt_set_sample_fmt(swrCtx, "out_sample_fmt",     encCtx.sample_fmt(),   0);

            /* Open the resampler with the specified parameters. */
            if ((ret = swr_init(swrCtx)) < 0)
                throw new Exception("Could not open resample context: "+WebmWriter.av_err2str(ret));
    	} catch(Exception e) {
    		e.printStackTrace();
    	}
    }

    public void close()
    {
    	avcodec_free_context(decCtx);
    	avcodec_free_context(encCtx);

    	swr_free(swrCtx);

    	if ((fmt.flags() & AVFMT_NOFILE) > 0)
	        /* Close the output file. */
	        avio_close(oc.pb());

	    /* free the stream */
	    avformat_free_context(oc);
    }

	/* audio output */
	private static AVFrame allocAudioFrame(int sample_fmt, long channel_layout, int sample_rate, int nb_samples) {
		AVFrame frame = av_frame_alloc();

		int ret;

		if (frame.isNull()) {
			System.out.println("Error allocating an audio frame\n");
			System.exit(1);
		}

		frame.format(sample_fmt);
		frame.channel_layout(channel_layout);
		frame.sample_rate(sample_rate);
		frame.nb_samples(nb_samples);

		if (nb_samples != 0) {
			ret = av_frame_get_buffer(frame, 0);
			if (ret < 0) {
				System.out.println("Error allocating an audio buffer\n");
				System.exit(1);
			}
		}

		return frame;
	}

	private long samplesCount = 0;
	
	/**
	 * Write audio frame
	 * @throws Exception
	 */
    private void encodeAudioFrame()
    		throws Exception
    {
	    AVCodecContext c   = encCtx;
	    AVFrame frame      = frame2;

	    int ret;

	    /* Convert pts */
        AVRational r = new AVRational(); r.num(1); r.den(c.sample_rate());
        frame.pts(av_rescale_q(samplesCount, r, c.time_base()));
        samplesCount += frame.nb_samples();

		/* encode the image */
        if ((ret = avcodec_send_frame(c, frame)) < 0)
        	throw new Exception("Failed to send frame: "+WebmWriter.av_err2str(ret));

        // Packet receiver
        AVPacket pkt = new AVPacket();
        av_init_packet(pkt);
        pkt.data(null);
        pkt.size(0);

        // loop encoder
        while ((ret = avcodec_receive_packet(c, pkt)) == 0) {
    	    /* Write the compressed frame to the media file. */
    		/* Set stream index */
    	    pkt.stream_index(st.index());

    	    /* rescale output packet timestamp values from codec to stream timebase */
    	    av_packet_rescale_ts(pkt, c.time_base(), st.time_base());

    	    /* Write the compressed frame to the media file. */
    	    if ((ret = av_interleaved_write_frame(oc, pkt)) < 0)
            	throw new Exception("Error during write frame: "+WebmWriter.av_err2str(ret));

    	    /* Un ref packet */
            av_packet_unref(pkt);
        }

        // Error handle
        if (ret != AVERROR_EAGAIN() && ret != AVERROR_EOF)
        	throw new Exception("Error during encoding: "+WebmWriter.av_err2str(ret));
    }

	/**
	 * Resample frame to output
	 * @throws Exception
	 */
	private void resampleAudioFrame(AVFrame tmpFrame)
			throws Exception
	{
    	int ret;

    	if (swr_is_initialized(swrCtx) == 0)
    		return;

    	/* write to swr buffer */
    	if (tmpFrame != null) {
        	if ((ret = swr_convert(swrCtx, null, 0, tmpFrame.data(), tmpFrame.nb_samples())) < 0)
        		throw new Exception("Error while converting: "+WebmWriter.av_err2str(ret));
    	}

    	// write
        while (swr_get_delay(swrCtx, encCtx.sample_rate()) >= frame2.nb_samples()) {
        	if ((ret = swr_convert(swrCtx, frame2.data(), frame2.nb_samples(), null, 0)) < 0)
        		throw new Exception("Error while converting: "+WebmWriter.av_err2str(ret));
        	// Call endcode
        	encodeAudioFrame();
        }

        // 
    	int offset;
    	if (tmpFrame == null && (offset = (int) swr_get_delay(swrCtx, encCtx.sample_rate())) > 0) {
        	if ((ret = swr_convert(swrCtx, frame2.data(), frame2.nb_samples(), null, 0)) < 0)
        		throw new Exception("Error while converting: "+WebmWriter.av_err2str(ret));
        	//
        	av_samples_set_silence(frame2.data(), offset, frame2.nb_samples()-offset, frame2.channels(), frame2.format());
        	// Call endcode
        	encodeAudioFrame();
    	}
	}

	public void writeFrame(FrameDescriptor fd)
    {
    	int ret;

    	byte[] data = new byte[(int) fd.length];
    	System.arraycopy(fd.buffer, fd.offset, data, 0, (int) fd.length);

    	AVPacket pkt = new AVPacket();
        av_init_packet(pkt);
        pkt.pts(fd.pts);
        pkt.size((int) fd.length);
        pkt.data(new BytePointer(data));

		// Decoder
        try
        {
    	    /* send the packet with the compressed data to the decoder */
    	    ret = avcodec_send_packet(decCtx, pkt);
    	    if (ret < 0)
    	        throw new Exception("Error submitting the packet to the decoder");
    	    av_packet_unref(pkt);

    	    /* read all the output frames (in general there may be any number of them */
    	    while ((ret = avcodec_receive_frame(decCtx, frame)) == 0)
    	    	resampleAudioFrame(frame);

    	    // Error handle
            if (ret != AVERROR_EAGAIN() && ret != AVERROR_EOF)
            	throw new Exception("Error during decoding");
        }
        catch (Exception e)
        {
        	e.printStackTrace();
        }
        finally
        {
			av_packet_unref(pkt);
		}
    }

}
