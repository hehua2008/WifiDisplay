package com.hym.rtplib;

import android.media.MediaFormat;
import android.util.Log;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.util.AvcUtils;
import com.hym.rtplib.util.CheckUtils;
import com.hym.rtplib.util.TimeUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TSPacketizer implements MediaConstants, Errno {
    private static final String TAG = TSPacketizer.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int EMIT_HDCP20_DESCRIPTOR = 1;
    public static final int EMIT_HDCP21_DESCRIPTOR = 2;

    public static final int EMIT_PAT_AND_PMT = 1;
    public static final int EMIT_PCR = 2;
    public static final int IS_ENCRYPTED = 4;
    public static final int PREPEND_SPS_PPS_TO_IDR_FRAMES = 8;

    private static final int PID_PMT = 0x100;
    private static final int PID_PCR = 0x1000;

    private final int mFlags;

    private final List<Track> mTracks = new ArrayList<>();
    private final List<ABuffer> mProgramInfoDescriptors = new ArrayList<>();

    private int mPATContinuityCounter;
    private int mPMTContinuityCounter;

    private static final int[] CRC_TABLE = new int[256];

    static {
        initCrcTable();
    }

    public TSPacketizer(int flags) {
        mFlags = flags;
        mPATContinuityCounter = 0;
        mPMTContinuityCounter = 0;

        if ((flags & (EMIT_HDCP20_DESCRIPTOR | EMIT_HDCP21_DESCRIPTOR)) != 0) {
            int hdcpVersion;
            if ((flags & EMIT_HDCP20_DESCRIPTOR) != 0) {
                CheckUtils.check((flags & EMIT_HDCP21_DESCRIPTOR) == 0);
                hdcpVersion = 0x20;
            } else {
                CheckUtils.check((flags & EMIT_HDCP20_DESCRIPTOR) == 0);

                // HDCP2.0 _and_ HDCP 2.1 specs say to set the version
                // inside the HDCP descriptor to 0x20!!!
                hdcpVersion = 0x20;
            }

            // HDCP descriptor
            ABuffer descriptor = new ABuffer(7);
            ByteBuffer desData = descriptor.data();
            byte[] hdcpData = {
                    0x05,  // descriptor_tag
                    5,  // descriptor_length
                    'H',
                    'D',
                    'C',
                    'P',
                    (byte) hdcpVersion
            };
            desData.put(hdcpData);

            mProgramInfoDescriptors.add(descriptor);
        }
    }

    // Returns trackIndex or error.
    public int addTrack(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);

        int PIDStart;
        boolean isVideo = isVideo(mime);
        boolean isAudio = isAudio(mime);

        if (isVideo) {
            PIDStart = 0x1011;
        } else if (isAudio) {
            PIDStart = 0x1100;
        } else {
            return ERROR_UNSUPPORTED;
        }

        int streamType;
        int streamIDStart;
        int streamIDStop;

        if (isH264(mime)) {
            streamType = 0x1b;
            streamIDStart = 0xe0;
            streamIDStop = 0xef;
        } else if (isAAC(mime)) {
            streamType = 0x0f;
            streamIDStart = 0xc0;
            streamIDStop = 0xdf;
        } else if (isPCMAudio(mime)) {
            streamType = 0x83;
            streamIDStart = 0xbd;
            streamIDStop = 0xbd;
        } else {
            return ERROR_UNSUPPORTED;
        }

        int numTracksOfThisType = 0;
        int PID = PIDStart;

        for (int i = 0; i < mTracks.size(); ++i) {
            Track track = mTracks.get(i);

            if (track.getStreamType() == streamType) {
                ++numTracksOfThisType;
            }

            if ((isAudio && track.isAudio()) || (isVideo && track.isVideo())) {
                ++PID;
            }
        }

        int streamID = streamIDStart + numTracksOfThisType;
        if (streamID > streamIDStop) {
            return -ERANGE;
        }

        Track track = new Track(format, PID, streamType, streamID);
        if (mTracks.add(track)) {
            return 0;
        }
        return NO_MEMORY;
    }

    public int packetize(
            int trackIndex,
            ABuffer accessUnit,
            final ABuffer[] packets,
            int flags,
            ByteBuffer PES_private_data, int PES_private_data_len,
            int numStuffingBytes) {
        long timeUs = accessUnit.meta().getLong(TIME_US);

        packets[0] = null;

        if (trackIndex >= mTracks.size()) {
            return -ERANGE;
        }

        Track track = mTracks.get(trackIndex);

        if (track.isH264() && (flags & PREPEND_SPS_PPS_TO_IDR_FRAMES) != 0
                && AvcUtils.isIDR(accessUnit)) {
            // prepend codec specific data, i.e. SPS and PPS.
            accessUnit = track.prependCSD(accessUnit);
        } else if (track.isAAC() && track.lacksADTSHeader()) {
            CheckUtils.check((flags & IS_ENCRYPTED) == 0);
            accessUnit = track.prependADTSHeader(accessUnit);
        }

        // 0x47
        // transport_error_indicator = b0
        // payload_unit_start_indicator = b1
        // transport_priority = b0
        // PID
        // transport_scrambling_control = b00
        // adaptation_field_control = b??
        // continuity_counter = b????
        // -- payload follows
        // packet_startcode_prefix = 0x000001
        // stream_id
        // PES_packet_length = 0x????
        // reserved = b10
        // PES_scrambling_control = b00
        // PES_priority = b0
        // data_alignment_indicator = b1
        // copyright = b0
        // original_or_copy = b0
        // PTS_DTS_flags = b10  (PTS only)
        // ESCR_flag = b0
        // ES_rate_flag = b0
        // DSM_trick_mode_flag = b0
        // additional_copy_info_flag = b0
        // PES_CRC_flag = b0
        // PES_extension_flag = b0
        // PES_header_data_length = 0x05
        // reserved = b0010 (PTS)
        // PTS[32..30] = b???
        // reserved = b1
        // PTS[29..15] = b??? ???? ???? ???? (15 bits)
        // reserved = b1
        // PTS[14..0] = b??? ???? ???? ???? (15 bits)
        // reserved = b1
        // the first fragment of "buffer" follows

        // Each transport packet (except for the last one contributing to the PES
        // payload) must contain a multiple of 16 bytes of payload per HDCP spec.
        boolean alignPayload =
                (mFlags & (EMIT_HDCP20_DESCRIPTOR | EMIT_HDCP21_DESCRIPTOR)) != 0;

    /*
       a) The very first PES transport stream packet contains

       4 bytes of TS header
       ... padding
       14 bytes of static PES header
       PES_private_data_len + 1 bytes (only if PES_private_data_len > 0)
       numStuffingBytes bytes

       followed by the payload

       b) Subsequent PES transport stream packets contain

       4 bytes of TS header
       ... padding

       followed by the payload
    */

        int PES_packet_length = accessUnit.size() + 8 + numStuffingBytes;
        if (PES_private_data_len > 0) {
            PES_packet_length += PES_private_data_len + 1;
        }

        int numTSPackets = 1;

        {
            // Make sure the PES header fits into a single TS packet:
            int PES_header_size = 14 + numStuffingBytes;
            if (PES_private_data_len > 0) {
                PES_header_size += PES_private_data_len + 1;
            }

            CheckUtils.checkLessOrEqual(PES_header_size, 188 - 4);

            int sizeAvailableForPayload = 188 - 4 - PES_header_size;
            int numBytesOfPayload = accessUnit.size();

            if (numBytesOfPayload > sizeAvailableForPayload) {
                numBytesOfPayload = sizeAvailableForPayload;

                if (alignPayload && numBytesOfPayload > 16) {
                    numBytesOfPayload -= (numBytesOfPayload % 16);
                }
            }

            int numPaddingBytes = sizeAvailableForPayload - numBytesOfPayload;
//#if 0
            if (DEBUG) {
                Log.d(TAG,
                        String.format("packet 1 contains %d padding bytes and %d bytes of payload",
                                numPaddingBytes, numBytesOfPayload));
            }
//#endif
            int numBytesOfPayloadRemaining = accessUnit.size() - numBytesOfPayload;

//#if 0
            if (DEBUG) {
                // The following hopefully illustrates the logic that led to the
                // more efficient computation in the #else block...

                while (numBytesOfPayloadRemaining > 0) {
                    sizeAvailableForPayload = 188 - 4;

                    numBytesOfPayload = numBytesOfPayloadRemaining;

                    if (numBytesOfPayload > sizeAvailableForPayload) {
                        numBytesOfPayload = sizeAvailableForPayload;

                        if (alignPayload && numBytesOfPayload > 16) {
                            numBytesOfPayload -= (numBytesOfPayload % 16);
                        }
                    }

                    numPaddingBytes = sizeAvailableForPayload - numBytesOfPayload;
//#if 0
                    if (DEBUG) {
                        Log.d(TAG, String.format(
                                "packet %d contains %d padding bytes and %d bytes of payload",
                                numTSPackets + 1, numPaddingBytes, numBytesOfPayload));
                    }
//#endif
                    numBytesOfPayloadRemaining -= numBytesOfPayload;
                    ++numTSPackets;
                }
            }
//#else
            else {
                // This is how many bytes of payload each subsequent TS packet
                // can contain at most.
                sizeAvailableForPayload = 188 - 4;
                int sizeAvailableForAlignedPayload = sizeAvailableForPayload;
                if (alignPayload) {
                    // We're only going to use a subset of the available space
                    // since we need to make each fragment a multiple of 16 in size.
                    sizeAvailableForAlignedPayload -=
                            (sizeAvailableForAlignedPayload % 16);
                }

                int numFullTSPackets =
                        numBytesOfPayloadRemaining / sizeAvailableForAlignedPayload;

                numTSPackets += numFullTSPackets;

                numBytesOfPayloadRemaining -=
                        numFullTSPackets * sizeAvailableForAlignedPayload;

                // numBytesOfPayloadRemaining < sizeAvailableForAlignedPayload
                if (numFullTSPackets == 0 && numBytesOfPayloadRemaining > 0) {
                    // There wasn't enough payload left to form a full aligned payload,
                    // the last packet doesn't have to be aligned.
                    ++numTSPackets;
                } else if (numFullTSPackets > 0
                        && numBytesOfPayloadRemaining
                        + sizeAvailableForAlignedPayload > sizeAvailableForPayload) {
                    // The last packet emitted had a full aligned payload and together
                    // with the bytes remaining does exceed the unaligned payload
                    // size, so we need another packet.
                    ++numTSPackets;
                }
            }
//#endif
        }

        if ((flags & EMIT_PAT_AND_PMT) != 0) {
            numTSPackets += 2;
        }

        if ((flags & EMIT_PCR) != 0) {
            ++numTSPackets;
        }

        ABuffer buffer = new ABuffer(numTSPackets * 188);
        ByteBuffer packetDataStart = buffer.data();

        if ((flags & EMIT_PAT_AND_PMT) != 0) {
            // Program Association Table (PAT):
            // 0x47
            // transport_error_indicator = b0
            // payload_unit_start_indicator = b1
            // transport_priority = b0
            // PID = b0000000000000 (13 bits)
            // transport_scrambling_control = b00
            // adaptation_field_control = b01 (no adaptation field, payload only)
            // continuity_counter = b????
            // skip = 0x00
            // --- payload follows
            // table_id = 0x00
            // section_syntax_indicator = b1
            // must_be_zero = b0
            // reserved = b11
            // section_length = 0x00d
            // transport_stream_id = 0x0000
            // reserved = b11
            // version_number = b00001
            // current_next_indicator = b1
            // section_number = 0x00
            // last_section_number = 0x00
            //   one program follows:
            //   program_number = 0x0001
            //   reserved = b111
            //   program_map_PID = PID_PMT (13 bits!)
            // CRC = 0x????????

            if (++mPATContinuityCounter == 16) {
                mPATContinuityCounter = 0;
            }

            ByteBuffer ptr = packetDataStart.duplicate();
            ptr.put((byte) 0x47);
            ptr.put((byte) 0x40);
            ptr.put((byte) 0x00);
            ptr.put((byte) (0x10 | mPATContinuityCounter));
            ptr.put((byte) 0x00);

            ByteBuffer crcDataStart = ptr.duplicate();
            ptr.put((byte) 0x00);
            ptr.put((byte) 0xb0);
            ptr.put((byte) 0x0d);
            ptr.put((byte) 0x00);
            ptr.put((byte) 0x00);
            ptr.put((byte) 0xc3);
            ptr.put((byte) 0x00);
            ptr.put((byte) 0x00);
            ptr.put((byte) 0x00);
            ptr.put((byte) 0x01);
            ptr.put((byte) (0xe0 | (PID_PMT >> 8)));
            ptr.put((byte) (PID_PMT & 0xff));

            CheckUtils.checkEqual(ptr.position() - crcDataStart.position(), 12);
            int crc = htonl(crc32(crcDataStart.slice(), ptr.position() - crcDataStart.position()));
            ptr.putInt(crc);

            int sizeLeft = packetDataStart.position() + 188 - ptr.position();
            for (int i = 0; i < sizeLeft; i++) {
                ptr.put((byte) 0xff);
            }

            packetDataStart.position(packetDataStart.position() + 188);

            // Program Map (PMT):
            // 0x47
            // transport_error_indicator = b0
            // payload_unit_start_indicator = b1
            // transport_priority = b0
            // PID = PID_PMT (13 bits)
            // transport_scrambling_control = b00
            // adaptation_field_control = b01 (no adaptation field, payload only)
            // continuity_counter = b????
            // skip = 0x00
            // -- payload follows
            // table_id = 0x02
            // section_syntax_indicator = b1
            // must_be_zero = b0
            // reserved = b11
            // section_length = 0x???
            // program_number = 0x0001
            // reserved = b11
            // version_number = b00001
            // current_next_indicator = b1
            // section_number = 0x00
            // last_section_number = 0x00
            // reserved = b111
            // PCR_PID = kPCR_PID (13 bits)
            // reserved = b1111
            // program_info_length = 0x???
            //   program_info_descriptors follow
            // one or more elementary stream descriptions follow:
            //   stream_type = 0x??
            //   reserved = b111
            //   elementary_PID = b? ???? ???? ???? (13 bits)
            //   reserved = b1111
            //   ES_info_length = 0x000
            // CRC = 0x????????

            if (++mPMTContinuityCounter == 16) {
                mPMTContinuityCounter = 0;
            }

            ptr = packetDataStart.duplicate();
            ptr.put((byte) 0x47);
            ptr.put((byte) (0x40 | (PID_PMT >> 8)));
            ptr.put((byte) (PID_PMT & 0xff));
            ptr.put((byte) (0x10 | mPMTContinuityCounter));
            ptr.put((byte) 0x00);

            crcDataStart = ptr.duplicate();
            ptr.put((byte) 0x02);

            ptr.put((byte) 0x00);  // section_length to be filled in below.
            ptr.put((byte) 0x00);

            ptr.put((byte) 0x00);
            ptr.put((byte) 0x01);
            ptr.put((byte) 0xc3);
            ptr.put((byte) 0x00);
            ptr.put((byte) 0x00);
            ptr.put((byte) (0xe0 | (PID_PCR >> 8)));
            ptr.put((byte) (PID_PCR & 0xff));

            int program_info_length = 0;
            for (int i = 0; i < mProgramInfoDescriptors.size(); ++i) {
                program_info_length += mProgramInfoDescriptors.get(i).size();
            }

            CheckUtils.checkLessThan(program_info_length, 0x400);
            ptr.put((byte) (0xf0 | (program_info_length >>> 8)));
            ptr.put((byte) (program_info_length & 0xff));

            for (int i = 0; i < mProgramInfoDescriptors.size(); ++i) {
                ABuffer desc = mProgramInfoDescriptors.get(i);
                ByteBuffer descData = desc.data();
                descData.limit(descData.position() + desc.size());
                ptr.put(descData);
            }

            for (int i = 0; i < mTracks.size(); ++i) {
                track = mTracks.get(i);

                // Make sure all the decriptors have been added.
                track.makeFinalize();

                ptr.put((byte) track.getStreamType());
                ptr.put((byte) (0xe0 | (track.getPID() >>> 8)));
                ptr.put((byte) (track.getPID() & 0xff));

                int ES_info_length = 0;
                for (int j = 0; j < track.countDescriptors(); ++j) {
                    ES_info_length += track.descriptorAt(j).size();
                }
                CheckUtils.checkLessOrEqual(ES_info_length, 0xfff);

                ptr.put((byte) (0xf0 | (ES_info_length >>> 8)));
                ptr.put((byte) (ES_info_length & 0xff));

                for (int k = 0; k < track.countDescriptors(); ++k) {
                    ABuffer descriptor = track.descriptorAt(k);
                    ByteBuffer descData = descriptor.data();
                    descData.limit(descData.position() + descriptor.size());
                    ptr.put(descData);
                }
            }

            int section_length = ptr.position() - (crcDataStart.position() + 3) + 4 /* CRC */;

            int crcPos = crcDataStart.position();
            crcDataStart.put(crcPos + 1, (byte) (0xb0 | (section_length >>> 8)));
            crcDataStart.put(crcPos + 2, (byte) (section_length & 0xff));

            crc = htonl(crc32(crcDataStart.slice(), ptr.position() - crcDataStart.position()));
            ptr.putInt(crc);

            sizeLeft = packetDataStart.position() + 188 - ptr.position();
            for (int i = 0; i < sizeLeft; i++) {
                ptr.put((byte) 0xff);
            }

            packetDataStart.position(packetDataStart.position() + 188);
        }

        if ((flags & EMIT_PCR) != 0) {
            // PCR stream
            // 0x47
            // transport_error_indicator = b0
            // payload_unit_start_indicator = b1
            // transport_priority = b0
            // PID = kPCR_PID (13 bits)
            // transport_scrambling_control = b00
            // adaptation_field_control = b10 (adaptation field only, no payload)
            // continuity_counter = b0000 (does not increment)
            // adaptation_field_length = 183
            // discontinuity_indicator = b0
            // random_access_indicator = b0
            // elementary_stream_priority_indicator = b0
            // PCR_flag = b1
            // OPCR_flag = b0
            // splicing_point_flag = b0
            // transport_private_data_flag = b0
            // adaptation_field_extension_flag = b0
            // program_clock_reference_base = b?????????????????????????????????
            // reserved = b111111
            // program_clock_reference_extension = b?????????

            long nowUs = TimeUtils.getMonotonicMicroTime();

            long PCR = nowUs * 27;  // PCR based on a 27MHz clock
            long PCR_base = PCR / 300;
            int PCR_ext = (int) (PCR % 300);

            ByteBuffer ptr = packetDataStart.duplicate();
            ptr.put((byte) 0x47);
            ptr.put((byte) (0x40 | (PID_PCR >> 8)));
            ptr.put((byte) (PID_PCR & 0xff));
            ptr.put((byte) 0x20);
            ptr.put((byte) 0xb7);  // adaptation_field_length
            ptr.put((byte) 0x10);
            ptr.put((byte) ((PCR_base >>> 25) & 0xff));
            ptr.put((byte) ((PCR_base >>> 17) & 0xff));
            ptr.put((byte) ((PCR_base >>> 9) & 0xff));
            ptr.put((byte) (((PCR_base & 1) << 7) | 0x7e | ((PCR_ext >>> 8) & 1)));
            ptr.put((byte) (PCR_ext & 0xff));

            int sizeLeft = packetDataStart.position() + 188 - ptr.position();
            for (int i = 0; i < sizeLeft; i++) {
                ptr.put((byte) 0xff);
            }

            packetDataStart.position(packetDataStart.position() + 188);
        }

        long PTS = (timeUs * 9L) / 100L;

        if (PES_packet_length >= 65536) {
            // This really should only happen for video.
            CheckUtils.check(track.isVideo());

            // It's valid to set this to 0 for video according to the specs.
            PES_packet_length = 0;
        }

        int sizeAvailableForPayload = 188 - 4 - 14 - numStuffingBytes;
        if (PES_private_data_len > 0) {
            sizeAvailableForPayload -= PES_private_data_len + 1;
        }

        int copy = accessUnit.size();

        if (copy > sizeAvailableForPayload) {
            copy = sizeAvailableForPayload;

            if (alignPayload && copy > 16) {
                copy -= (copy % 16);
            }
        }

        int numPaddingBytes = sizeAvailableForPayload - copy;

        ByteBuffer ptr = packetDataStart.duplicate();
        ptr.put((byte) 0x47);
        ptr.put((byte) (0x40 | (track.getPID() >>> 8)));
        ptr.put((byte) (track.getPID() & 0xff));

        ptr.put((byte) ((numPaddingBytes > 0 ? 0x30 : 0x10)
                | track.incrementContinuityCounter()));

        if (numPaddingBytes > 0) {
            ptr.put((byte) (numPaddingBytes - 1));
            if (numPaddingBytes >= 2) {
                ptr.put((byte) 0x00);
                for (int i = 0; i < numPaddingBytes - 2; i++) {
                    ptr.put((byte) 0xff);
                }
            }
        }

        ptr.put((byte) 0x00);
        ptr.put((byte) 0x00);
        ptr.put((byte) 0x01);
        ptr.put((byte) track.getStreamID());
        ptr.put((byte) (PES_packet_length >>> 8));
        ptr.put((byte) (PES_packet_length & 0xff));
        ptr.put((byte) 0x84);
        ptr.put((byte) ((PES_private_data_len > 0) ? 0x81 : 0x80));

        int headerLength = 0x05 + numStuffingBytes;
        if (PES_private_data_len > 0) {
            headerLength += 1 + PES_private_data_len;
        }

        ptr.put((byte) headerLength);

        ptr.put((byte) (0x20 | (((PTS >>> 30) & 7) << 1) | 1));
        ptr.put((byte) ((PTS >>> 22) & 0xff));
        ptr.put((byte) ((((PTS >>> 15) & 0x7f) << 1) | 1));
        ptr.put((byte) ((PTS >>> 7) & 0xff));
        ptr.put((byte) (((PTS & 0x7f) << 1) | 1));

        if (PES_private_data_len > 0) {
            ptr.put((byte) 0x8e);  // PES_private_data_flag, reserved.
            ByteBuffer pesData = (ByteBuffer) PES_private_data.slice().limit(PES_private_data_len);
            ptr.put(pesData);
        }

        for (int i = 0; i < numStuffingBytes; ++i) {
            ptr.put((byte) 0xff);
        }

        ByteBuffer unitData = accessUnit.data();
        unitData.limit(copy);
        ptr.put(unitData);

        CheckUtils.checkEqual(ptr.position(), packetDataStart.position() + 188);
        packetDataStart.position(packetDataStart.position() + 188);

        int offset = copy;
        while (offset < accessUnit.size()) {
            // for subsequent fragments of "buffer":
            // 0x47
            // transport_error_indicator = b0
            // payload_unit_start_indicator = b0
            // transport_priority = b0
            // PID = b0 0001 1110 ???? (13 bits) [0x1e0 + 1 + sourceIndex]
            // transport_scrambling_control = b00
            // adaptation_field_control = b??
            // continuity_counter = b????
            // the fragment of "buffer" follows.

            sizeAvailableForPayload = 188 - 4;

            copy = accessUnit.size() - offset;

            if (copy > sizeAvailableForPayload) {
                copy = sizeAvailableForPayload;

                if (alignPayload && copy > 16) {
                    copy -= (copy % 16);
                }
            }

            numPaddingBytes = sizeAvailableForPayload - copy;

            ptr = packetDataStart.duplicate();
            ptr.put((byte) 0x47);
            ptr.put((byte) (0x00 | (track.getPID() >>> 8)));
            ptr.put((byte) (track.getPID() & 0xff));

            ptr.put((byte) ((numPaddingBytes > 0 ? 0x30 : 0x10)
                    | track.incrementContinuityCounter()));

            if (numPaddingBytes > 0) {
                ptr.put((byte) (numPaddingBytes - 1));
                if (numPaddingBytes >= 2) {
                    ptr.put((byte) 0x00);
                    for (int i = 0; i < numPaddingBytes - 2; i++) {
                        ptr.put((byte) 0xff);
                    }
                }
            }

            unitData = accessUnit.data();
            unitData.position(offset).limit(offset + copy);
            ptr.put(unitData);
            CheckUtils.checkEqual(ptr.position(), packetDataStart.position() + 188);

            offset += copy;
            packetDataStart.position(packetDataStart.position() + 188);
        }

        CheckUtils.check(packetDataStart.position() == buffer.capacity());

        packets[0] = buffer;

        return OK;
    }

    public int extractCSDIfNecessary(int trackIndex) {
        if (trackIndex >= mTracks.size()) {
            return -ERANGE;
        }

        Track track = mTracks.get(trackIndex);
        track.extractCSDIfNecessary();

        return OK;
    }

    // XXX to be removed once encoder config option takes care of this for
    // encrypted mode.
    public ABuffer prependCSD(int trackIndex, ABuffer accessUnit) {
        CheckUtils.checkLessThan(trackIndex, mTracks.size());

        Track track = mTracks.get(trackIndex);
        CheckUtils.check(track.isH264() && AvcUtils.isIDR(accessUnit));

        long timeUs = accessUnit.meta().getLong(TIME_US);

        ABuffer newAccessUnit = track.prependCSD(accessUnit);

        newAccessUnit.meta().setLong(TIME_US, timeUs);

        return newAccessUnit;
    }

    private static void initCrcTable() {
        int poly = 0x04C11DB7;

        for (int i = 0; i < 256; i++) {
            int crc = i << 24;
            for (int j = 0; j < 8; j++) {
                crc = (crc << 1) ^ (((crc & 0x80000000) != 0) ? (poly) : 0);
            }
            CRC_TABLE[i] = crc;
        }
    }

    private static int crc32(ByteBuffer crcData, int size) {
        int crc = 0xFFFFFFFF;

        for (int p = 0; p < size; ++p) {
            int content = crcData.get(p) & 0xFF;
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ content) & 0xFF];
        }

        return crc;
    }

    public static int htonl(int value) {
        return value;
        // No need to flip
        /*
        if (ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
            return value;
        }
        return Integer.reverseBytes(value);
        */
    }

    public static boolean isVideo(String mime) {
        return mime != null && mime.toLowerCase().startsWith("video/");
    }

    public static boolean isAudio(String mime) {
        return mime != null && mime.toLowerCase().startsWith("audio/");
    }

    public static boolean isH264(String mime) {
        return MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(mime);
    }

    public static boolean isAAC(String mime) {
        return MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(mime);
    }

    public static boolean isPCMAudio(String mime) {
        return MediaFormat.MIMETYPE_AUDIO_RAW.equalsIgnoreCase(mime);
    }

    public static class Track {
        private final MediaFormat mFormat;

        private final int mPID;
        private final int mStreamType;
        private final int mStreamID;
        private int mContinuityCounter;

        private final String mMIME;

        private final List<ABuffer> mCSD = new ArrayList<>();
        private final List<ABuffer> mDescriptors = new ArrayList<>();

        private boolean mAudioLacksATDSHeaders;
        private boolean mFinalized;
        private boolean mExtractedCSD;

        public Track(MediaFormat format, int PID, int streamType, int streamID) {
            mFormat = format;
            mPID = PID;
            mStreamType = streamType;
            mStreamID = streamID;
            mContinuityCounter = 0;
            mAudioLacksATDSHeaders = false;
            mFinalized = false;
            mExtractedCSD = false;
            mMIME = format.getString(MediaFormat.KEY_MIME);
        }

        public int getPID() {
            return mPID;
        }

        public int getStreamType() {
            return mStreamType;
        }

        public int getStreamID() {
            return mStreamID;
        }

        // Returns the previous value.
        public int incrementContinuityCounter() {
            int prevCounter = mContinuityCounter;

            if (++mContinuityCounter == 16) {
                mContinuityCounter = 0;
            }

            return prevCounter;
        }

        public boolean isAudio() {
            return TSPacketizer.isAudio(mMIME);
        }

        public boolean isVideo() {
            return TSPacketizer.isVideo(mMIME);
        }

        public boolean isH264() {
            return TSPacketizer.isH264(mMIME);
        }

        public boolean isAAC() {
            return TSPacketizer.isAAC(mMIME);
        }

        public boolean lacksADTSHeader() {
            return mAudioLacksATDSHeaders;
        }

        public boolean isPCMAudio() {
            return TSPacketizer.isPCMAudio(mMIME);
        }

        public ABuffer prependCSD(ABuffer accessUnit) {
            int size = 0;
            for (int i = 0; i < mCSD.size(); ++i) {
                size += mCSD.get(i).size();
            }

            ABuffer dup = new ABuffer(accessUnit.size() + size);
            ByteBuffer dupData = dup.data();
            for (int i = 0, len = mCSD.size(); i < len; ++i) {
                ABuffer csd = mCSD.get(i);
                ByteBuffer csdData = csd.data();
                csdData.limit(csd.size());
                dupData.put(csdData);
            }

            ByteBuffer unitData = accessUnit.data();
            unitData.limit(accessUnit.size());
            dupData.put(unitData);

            return dup;
        }

        public ABuffer prependADTSHeader(ABuffer accessUnit) {
            CheckUtils.checkEqual(mCSD.size(), 1);

            ByteBuffer codecSpecificData = mCSD.get(0).data();

            int codecSpecificData0 = codecSpecificData.get(0) & 0xff;
            int codecSpecificData1 = codecSpecificData.get(1) & 0xff;

            int aac_frame_length = accessUnit.size() + 7;

            ABuffer dup = new ABuffer(aac_frame_length);

            int profile = (codecSpecificData0 >>> 3) - 1;

            int sampling_freq_index = ((codecSpecificData0 & 7) << 1) | (codecSpecificData1 >>> 7);

            int channel_configuration = (codecSpecificData1 >>> 3) & 0x0f;

            ByteBuffer dupData = dup.data();

            dupData.put((byte) 0xff);
            dupData.put((byte) 0xf9);  // b11111001, ID=1(MPEG-2), layer=0, protection_absent=1

            dupData.put((byte) (profile << 6
                    | sampling_freq_index << 2
                    | ((channel_configuration >>> 2) & 1)));  // private_bit=0

            // original_copy=0, home=0, copyright_id_bit=0, copyright_id_start=0
            dupData.put((byte) ((channel_configuration & 3) << 6
                    | aac_frame_length >>> 11));
            dupData.put((byte) ((aac_frame_length >>> 3) & 0xff));
            dupData.put((byte) ((aac_frame_length & 7) << 5));

            // adts_buffer_fullness=0, number_of_raw_data_blocks_in_frame=0
            dupData.put((byte) 0);

            ByteBuffer unitData = accessUnit.data();
            unitData.limit(accessUnit.size());
            dupData.put(unitData);

            return dup;
        }

        public int countDescriptors() {
            return mDescriptors.size();
        }

        public ABuffer descriptorAt(int index) {
            CheckUtils.checkLessThan(index, mDescriptors.size());
            return mDescriptors.get(index);
        }

        public void makeFinalize() {
            if (mFinalized) {
                return;
            }

            if (isH264()) {
                {
                    // AVC video descriptor (40)

                    ABuffer descriptor = new ABuffer(6);
                    ByteBuffer desData = descriptor.data();
                    desData.put((byte) 40);  // descriptor_tag
                    desData.put((byte) 4);  // descriptor_length

                    if (mCSD.size() > 0) {
                        CheckUtils.checkGreaterThan(mCSD.size(), 1);
                        ABuffer sps = mCSD.get(0);
                        ByteBuffer spsData = sps.data();
                        byte[] head = new byte[4];
                        spsData.get(head);
                        CheckUtils.check(Objects.deepEquals(NAL_START_BYTE, head));
                        CheckUtils.checkGreaterThan(sps.size(), 7);
                        // profile_idc, constraint_set*, level_idc
                        // FIXME ?
                        //spsData.get(4): forbidden_zero_bit (1), nal_ref_idc (2), nal_unit_type (5)
                        spsData.position(5).limit(5 + 3);
                        desData.put(spsData);
                    } else {
                        /*
                        int profileIdc = mFormat.getInt("profile-idc");
                        int levelIdc = mFormat.getInt("level-idc");
                        int constraintSet = mFormat.getInt("constraint-set");
                        CheckUtils.checkGreaterThan(profileIdc, 0);
                        CheckUtils.checkGreaterThan(levelIdc, 0);
                        desData.put((byte) profileIdc);    // profile_idc
                        desData.put((byte) constraintSet); // constraint_set*
                        desData.put((byte) levelIdc);      // level_idc
                        */
                        // FIXME ?
                        Log.w(TAG, "Why mCSD is empty ???");
                        desData.put((byte) 66);    // profile_idc
                        desData.put((byte) 0xc0); // constraint_set*
                        desData.put((byte) 31);      // level_idc
                    }

                    // AVC_still_present=0, AVC_24_hour_picture_flag=0, reserved
                    desData.put((byte) 0x3f);

                    mDescriptors.add(descriptor);
                }

                {
                    // AVC timing and HRD descriptor (42)

                    ABuffer descriptor = new ABuffer(4);
                    ByteBuffer desData = descriptor.data();
                    desData.put((byte) 42);  // descriptor_tag
                    desData.put((byte) 2);  // descriptor_length

                    // hrd_management_valid_flag = 0
                    // reserved = 111111b
                    // picture_and_timing_info_present = 0

                    desData.put((byte) 0x7e);

                    // fixed_frame_rate_flag = 0
                    // temporal_poc_flag = 0
                    // picture_to_display_conversion_flag = 0
                    // reserved = 11111b
                    desData.put((byte) 0x1f);

                    mDescriptors.add(descriptor);
                }
            } else if (isPCMAudio()) {
                // LPCM audio stream descriptor (0x83)

                int channelCount = mFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                CheckUtils.checkEqual(channelCount, 2);

                int sampleRate = mFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                CheckUtils.check(sampleRate == 44100 || sampleRate == 48000);

                ABuffer descriptor = new ABuffer(4);
                ByteBuffer desData = descriptor.data();
                desData.put((byte) 0x83);  // descriptor_tag
                desData.put((byte) 2);  // descriptor_length

                int sampling_frequency = (sampleRate == 44100) ? 1 : 2;

                desData.put((byte) ((sampling_frequency << 5)
                        | (3 /* reserved */ << 1)
                        | 0 /* emphasis_flag */));

                desData.put((byte) ((1 /* number_of_channels = stereo */ << 5)
                        | 0xf /* reserved */));

                mDescriptors.add(descriptor);
            }

            mFinalized = true;
        }

        public void extractCSDIfNecessary() {
            if (mExtractedCSD) {
                return;
            }

            if (isH264() || isAAC()) {
                for (int i = 0; ; ++i) {
                    ByteBuffer csdBuffer = mFormat.getByteBuffer("csd-" + i);
                    if (csdBuffer == null) {
                        break;
                    }

                    csdBuffer = csdBuffer.duplicate();
                    ABuffer csd = new ABuffer(csdBuffer.remaining());
                    csd.data().put(csdBuffer);
                    mCSD.add(csd);
                    Log.w(TAG, "save csd-" + i);
                }

                if (isAAC()) {
                    int isADTS;
                    try {
                        isADTS = mFormat.getInteger(MediaFormat.KEY_IS_ADTS);
                    } catch (NullPointerException e) {
                        isADTS = 0;
                    }
                    if (isADTS == 0) {
                        mAudioLacksATDSHeaders = true;
                    }
                }
            }

            mExtractedCSD = true;
        }
    }

    // CRC32 used for PSI section. The table was generated by following command:
// $ python pycrc.py --model crc-32-mpeg --algorithm table-driven --generate c
// Visit http://www.tty1.net/pycrc/index_en.html for more details.
    private static final int[] CRC_TABLE_INT = {
            0x00000000, 0x04c11db7, 0x09823b6e, 0x0d4326d9,
            0x130476dc, 0x17c56b6b, 0x1a864db2, 0x1e475005,
            0x2608edb8, 0x22c9f00f, 0x2f8ad6d6, 0x2b4bcb61,
            0x350c9b64, 0x31cd86d3, 0x3c8ea00a, 0x384fbdbd,
            0x4c11db70, 0x48d0c6c7, 0x4593e01e, 0x4152fda9,
            0x5f15adac, 0x5bd4b01b, 0x569796c2, 0x52568b75,
            0x6a1936c8, 0x6ed82b7f, 0x639b0da6, 0x675a1011,
            0x791d4014, 0x7ddc5da3, 0x709f7b7a, 0x745e66cd,
            0x9823b6e0, 0x9ce2ab57, 0x91a18d8e, 0x95609039,
            0x8b27c03c, 0x8fe6dd8b, 0x82a5fb52, 0x8664e6e5,
            0xbe2b5b58, 0xbaea46ef, 0xb7a96036, 0xb3687d81,
            0xad2f2d84, 0xa9ee3033, 0xa4ad16ea, 0xa06c0b5d,
            0xd4326d90, 0xd0f37027, 0xddb056fe, 0xd9714b49,
            0xc7361b4c, 0xc3f706fb, 0xceb42022, 0xca753d95,
            0xf23a8028, 0xf6fb9d9f, 0xfbb8bb46, 0xff79a6f1,
            0xe13ef6f4, 0xe5ffeb43, 0xe8bccd9a, 0xec7dd02d,
            0x34867077, 0x30476dc0, 0x3d044b19, 0x39c556ae,
            0x278206ab, 0x23431b1c, 0x2e003dc5, 0x2ac12072,
            0x128e9dcf, 0x164f8078, 0x1b0ca6a1, 0x1fcdbb16,
            0x018aeb13, 0x054bf6a4, 0x0808d07d, 0x0cc9cdca,
            0x7897ab07, 0x7c56b6b0, 0x71159069, 0x75d48dde,
            0x6b93dddb, 0x6f52c06c, 0x6211e6b5, 0x66d0fb02,
            0x5e9f46bf, 0x5a5e5b08, 0x571d7dd1, 0x53dc6066,
            0x4d9b3063, 0x495a2dd4, 0x44190b0d, 0x40d816ba,
            0xaca5c697, 0xa864db20, 0xa527fdf9, 0xa1e6e04e,
            0xbfa1b04b, 0xbb60adfc, 0xb6238b25, 0xb2e29692,
            0x8aad2b2f, 0x8e6c3698, 0x832f1041, 0x87ee0df6,
            0x99a95df3, 0x9d684044, 0x902b669d, 0x94ea7b2a,
            0xe0b41de7, 0xe4750050, 0xe9362689, 0xedf73b3e,
            0xf3b06b3b, 0xf771768c, 0xfa325055, 0xfef34de2,
            0xc6bcf05f, 0xc27dede8, 0xcf3ecb31, 0xcbffd686,
            0xd5b88683, 0xd1799b34, 0xdc3abded, 0xd8fba05a,
            0x690ce0ee, 0x6dcdfd59, 0x608edb80, 0x644fc637,
            0x7a089632, 0x7ec98b85, 0x738aad5c, 0x774bb0eb,
            0x4f040d56, 0x4bc510e1, 0x46863638, 0x42472b8f,
            0x5c007b8a, 0x58c1663d, 0x558240e4, 0x51435d53,
            0x251d3b9e, 0x21dc2629, 0x2c9f00f0, 0x285e1d47,
            0x36194d42, 0x32d850f5, 0x3f9b762c, 0x3b5a6b9b,
            0x0315d626, 0x07d4cb91, 0x0a97ed48, 0x0e56f0ff,
            0x1011a0fa, 0x14d0bd4d, 0x19939b94, 0x1d528623,
            0xf12f560e, 0xf5ee4bb9, 0xf8ad6d60, 0xfc6c70d7,
            0xe22b20d2, 0xe6ea3d65, 0xeba91bbc, 0xef68060b,
            0xd727bbb6, 0xd3e6a601, 0xdea580d8, 0xda649d6f,
            0xc423cd6a, 0xc0e2d0dd, 0xcda1f604, 0xc960ebb3,
            0xbd3e8d7e, 0xb9ff90c9, 0xb4bcb610, 0xb07daba7,
            0xae3afba2, 0xaafbe615, 0xa7b8c0cc, 0xa379dd7b,
            0x9b3660c6, 0x9ff77d71, 0x92b45ba8, 0x9675461f,
            0x8832161a, 0x8cf30bad, 0x81b02d74, 0x857130c3,
            0x5d8a9099, 0x594b8d2e, 0x5408abf7, 0x50c9b640,
            0x4e8ee645, 0x4a4ffbf2, 0x470cdd2b, 0x43cdc09c,
            0x7b827d21, 0x7f436096, 0x7200464f, 0x76c15bf8,
            0x68860bfd, 0x6c47164a, 0x61043093, 0x65c52d24,
            0x119b4be9, 0x155a565e, 0x18197087, 0x1cd86d30,
            0x029f3d35, 0x065e2082, 0x0b1d065b, 0x0fdc1bec,
            0x3793a651, 0x3352bbe6, 0x3e119d3f, 0x3ad08088,
            0x2497d08d, 0x2056cd3a, 0x2d15ebe3, 0x29d4f654,
            0xc5a92679, 0xc1683bce, 0xcc2b1d17, 0xc8ea00a0,
            0xd6ad50a5, 0xd26c4d12, 0xdf2f6bcb, 0xdbee767c,
            0xe3a1cbc1, 0xe760d676, 0xea23f0af, 0xeee2ed18,
            0xf0a5bd1d, 0xf464a0aa, 0xf9278673, 0xfde69bc4,
            0x89b8fd09, 0x8d79e0be, 0x803ac667, 0x84fbdbd0,
            0x9abc8bd5, 0x9e7d9662, 0x933eb0bb, 0x97ffad0c,
            0xafb010b1, 0xab710d06, 0xa6322bdf, 0xa2f33668,
            0xbcb4666d, 0xb8757bda, 0xb5365d03, 0xb1f740b4
    };
}
