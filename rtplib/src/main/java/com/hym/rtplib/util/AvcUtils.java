package com.hym.rtplib.util;

import android.util.Log;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABitReader;
import com.hym.rtplib.foundation.ABuffer;

import java.nio.ByteBuffer;

public class AvcUtils implements MediaConstants, Errno {
    private static final String TAG = AvcUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int AVC_PROFILE_BASELINE = 0x42;
    public static final int AVC_PROFILE_MAIN = 0x4d;
    public static final int AVC_PROFILE_EXTENDED = 0x58;
    public static final int AVC_PROFILE_HIGH = 0x64;
    public static final int AVC_PROFILE_HIGH_10 = 0x6e;
    public static final int AVC_PROFILE_HIGH_422 = 0x7a;
    public static final int AVC_PROFILE_HIGH_444 = 0xf4;
    public static final int AVC_PROFILE_CAVLC_444_INTRA = 0x2c;

    private static final int[][] FIXED_SARS = {
            {0, 0}, // Invalid
            {1, 1},
            {12, 11},
            {10, 11},
            {16, 11},
            {40, 33},
            {24, 11},
            {20, 11},
            {32, 11},
            {80, 33},
            {18, 11},
            {15, 11},
            {64, 33},
            {160, 99},
            {4, 3},
            {3, 2},
            {2, 1},
    };

    public static class NALPosition {
        int nalOffset;
        int nalSize;
    }

    private AvcUtils() {
    }

    /**
     * Optionally returns sample aspect ratio as well.
     */
    public static void findAVCDimensions(ABuffer seqParamSet,
            final int[] widthHeight, final int[] sarWidthHeight) {
        ByteBuffer brData = ((ByteBuffer) seqParamSet.data().position(1)).slice();
        ABitReader br = new ABitReader(brData, seqParamSet.size() - 1);

        int profile_idc = br.getBits(8);
        br.skipBits(16);
        parseUE(br);  // seq_parameter_set_id

        int chroma_format_idc = 1;  // 4:2:0 chroma format

        if (profile_idc == 100 || profile_idc == 110
                || profile_idc == 122 || profile_idc == 244
                || profile_idc == 44 || profile_idc == 83 || profile_idc == 86) {
            chroma_format_idc = parseUE(br);
            if (chroma_format_idc == 3) {
                br.skipBits(1);  // residual_colour_transform_flag
            }
            parseUE(br);  // bit_depth_luma_minus8
            parseUE(br);  // bit_depth_chroma_minus8
            br.skipBits(1);  // qpprime_y_zero_transform_bypass_flag

            if (br.getBits(1) != 0) {  // seq_scaling_matrix_present_flag
                for (int i = 0; i < 8; ++i) {
                    if (br.getBits(1) != 0) {  // seq_scaling_list_present_flag[i]

                        // WARNING: the code below has not ever been exercised...
                        // need a real-world example.

                        if (i < 6) {
                            // ScalingList4x4[i],16,...
                            skipScalingList(br, 16);
                        } else {
                            // ScalingList8x8[i-6],64,...
                            skipScalingList(br, 64);
                        }
                    }
                }
            }
        }

        parseUE(br);  // log2_max_frame_num_minus4
        int pic_order_cnt_type = parseUE(br);

        if (pic_order_cnt_type == 0) {
            parseUE(br);  // log2_max_pic_order_cnt_lsb_minus4
        } else if (pic_order_cnt_type == 1) {
            // offset_for_non_ref_pic, offset_for_top_to_bottom_field and
            // offset_for_ref_frame are technically se(v), but since we are
            // just skipping over them the midpoint does not matter.

            br.getBits(1);  // delta_pic_order_always_zero_flag
            parseUE(br);  // offset_for_non_ref_pic
            parseUE(br);  // offset_for_top_to_bottom_field

            int num_ref_frames_in_pic_order_cnt_cycle = parseUE(br);
            for (int i = 0; i < num_ref_frames_in_pic_order_cnt_cycle; ++i) {
                parseUE(br);  // offset_for_ref_frame
            }
        }

        parseUE(br);  // num_ref_frames
        br.getBits(1);  // gaps_in_frame_num_value_allowed_flag

        int pic_width_in_mbs_minus1 = parseUE(br);
        int pic_height_in_map_units_minus1 = parseUE(br);
        int frame_mbs_only_flag = br.getBits(1);

        widthHeight[0] = pic_width_in_mbs_minus1 * 16 + 16;

        widthHeight[1] = (2 - frame_mbs_only_flag)
                * (pic_height_in_map_units_minus1 * 16 + 16);

        if (frame_mbs_only_flag == 0) {
            br.getBits(1);  // mb_adaptive_frame_field_flag
        }

        br.getBits(1);  // direct_8x8_inference_flag

        if (br.getBits(1) != 0) {  // frame_cropping_flag
            int frame_crop_left_offset = parseUE(br);
            int frame_crop_right_offset = parseUE(br);
            int frame_crop_top_offset = parseUE(br);
            int frame_crop_bottom_offset = parseUE(br);

            int cropUnitX, cropUnitY;
            if (chroma_format_idc == 0  /* monochrome */) {
                cropUnitX = 1;
                cropUnitY = 2 - frame_mbs_only_flag;
            } else {
                int subWidthC = (chroma_format_idc == 3) ? 1 : 2;
                int subHeightC = (chroma_format_idc == 1) ? 2 : 1;

                cropUnitX = subWidthC;
                cropUnitY = subHeightC * (2 - frame_mbs_only_flag);
            }

            Log.d(TAG, String.format("frame_crop=(%d, %d, %d, %d), cropUnitX=%d, cropUnitY=%d",
                    frame_crop_left_offset, frame_crop_right_offset,
                    frame_crop_top_offset, frame_crop_bottom_offset,
                    cropUnitX, cropUnitY));

            widthHeight[0] -= (frame_crop_left_offset + frame_crop_right_offset) * cropUnitX;
            widthHeight[1] -= (frame_crop_top_offset + frame_crop_bottom_offset) * cropUnitY;
        }

        if (sarWidthHeight != null) {
            sarWidthHeight[0] = 0;
            sarWidthHeight[1] = 0;
        }

        if (br.getBits(1) != 0) {  // vui_parameters_present_flag
            int sar_width = 0, sar_height = 0;

            if (br.getBits(1) != 0) {  // aspect_ratio_info_present_flag
                int aspect_ratio_idc = br.getBits(8);

                if (aspect_ratio_idc == 255 /* extendedSAR */) {
                    sar_width = br.getBits(16);
                    sar_height = br.getBits(16);
                } else {
                    /*static const struct {
                        int width, height;
                    }*/


                    if (aspect_ratio_idc > 0 && aspect_ratio_idc < FIXED_SARS.length) {
                        sar_width = FIXED_SARS[aspect_ratio_idc][0];
                        sar_height = FIXED_SARS[aspect_ratio_idc][1];
                    }
                }
            }

            Log.d(TAG, String.format("sample aspect ratio = %d : %d", sar_width, sar_height));

            if (sarWidthHeight != null) {
                sarWidthHeight[0] = sar_width;
                sarWidthHeight[1] = sar_height;
            }
        }
    }

    /**
     * Gets and returns an int exp-golomb (ue) value from a bit reader |br|. Aborts if the value
     * is more than 64 bits long (>=0xFFFF (!)) or the bit reader overflows.
     */
    public static int parseUE(ABitReader br) {
        int codeNum = parseUE(br);

        return ((codeNum & 1) != 0) ? (codeNum + 1) / 2 : -(codeNum / 2);
    }

    /**
     * Gets and returns a int exp-golomb (se) value from a bit reader |br|. Aborts if the value is
     * more than 64 bits long (>0x7FFF || <-0x7FFF (!)) or the bit reader overflows.
     */
    public static int parseSE(ABitReader br) {
        int numZeroes = 0;
        while (br.getBits(1) == 0) {
            ++numZeroes;
        }

        int x = br.getBits(numZeroes);

        return x + (1 << numZeroes) - 1;
    }

    /**
     * Gets an int exp-golomb (ue) value from a bit reader |br|, and returns it if it was
     * successful.
     * Returns |fallback| if it was unsuccessful. Note: if the value was longer that 64 bits,
     * it reads past the value and still returns |fallback|.
     */
    public static int parseUEWithFallback(ABitReader br, int fallback) {
        int numZeroes = 0;
        while (br.getBitsWithFallback(1, 1) == 0) {
            ++numZeroes;
        }
        final int[] x = new int[1];
        if (numZeroes < 32) {
            if (br.getBitsGraceful(numZeroes, x)) {
                return x[0] + (1 << numZeroes) - 1;
            } else {
                return fallback;
            }
        } else {
            br.skipBits(numZeroes);
            return fallback;
        }
    }

    /**
     * Gets a int exp-golomb (se) value from a bit reader |br|, and returns it if it was successful.
     * Returns |fallback| if it was unsuccessful. Note: if the value was longer that 64 bits, it
     * reads past the value and still returns |fallback|.
     */
    public static int parseSEWithFallback(ABitReader br, int fallback) {
        // NOTE: parseUE cannot normally return ~0 as the max supported value is 0xFFFE
        int codeNum = parseUEWithFallback(br, ~0);
        if (codeNum == ~0) {
            return fallback;
        }
        return ((codeNum & 1) != 0) ? (codeNum + 1) / 2 : -(codeNum / 2);
    }

    /**
     * Skips an int exp-golomb (ue) value from bit reader |br|.
     */
    public static void skipUE(ABitReader br) {
        parseUEWithFallback(br, 0);
    }

    /**
     * Skips a int exp-golomb (se) value from bit reader |br|.
     */
    public static void skipSE(ABitReader br) {
        parseSEWithFallback(br, 0);
    }

    public static int getNextNALUnit(final ByteBuffer[] inOutData, final int[] inOutSize,
            final ByteBuffer[] nalStart, final int[] nalSize,
            boolean startCodeFollows) {
        ByteBuffer data = inOutData[0];
        int size = inOutSize[0];

        nalStart[0] = null;
        nalSize[0] = 0;

        if (size < 3) {
            return -EAGAIN;
        }

        int offset = 0;

        // A valid startcode consists of at least two 0x00 bytes followed by 0x01.
        for (; offset + 2 < size; ++offset) {
            if ((data.get(offset + 2) & 0xFF) == 0x01 && (data.get(offset) & 0xFF) == 0x00
                    && (data.get(offset + 1) & 0xFF) == 0x00) {
                break;
            }
        }
        if (offset + 2 >= size) {
            inOutData[0] = ((ByteBuffer) data.duplicate().position(offset)).slice();
            inOutSize[0] = 2;
            return -EAGAIN;
        }
        offset += 3;

        int startOffset = offset;

        while (true) {
            while (offset < size && (data.get(offset) & 0xFF) != 0x01) {
                ++offset;
            }

            if (offset == size) {
                if (startCodeFollows) {
                    offset = size + 2;
                    break;
                }

                return -EAGAIN;
            }

            if ((data.get(offset - 1) & 0xFF) == 0x00 && (data.get(offset - 2) & 0xFF) == 0x00) {
                break;
            }

            ++offset;
        }

        int endOffset = offset - 2;
        while (endOffset > startOffset + 1 && (data.get(endOffset - 1) & 0xFF) == 0x00) {
            --endOffset;
        }

        nalStart[0] = ((ByteBuffer) data.duplicate().position(startOffset)).slice();
        nalSize[0] = endOffset - startOffset;

        if (offset + 2 < size) {
            inOutData[0] = ((ByteBuffer) data.duplicate().position(offset - 2)).slice();
            inOutSize[0] = size - offset + 2;
        } else {
            inOutData[0] = null;
            inOutSize[0] = 0;
        }

        return OK;
    }

    /*public static MetaData makeAVCCodecSpecificData(ABuffer accessUnit) {
        ByteBuffer data = accessUnit.data();
        int size = accessUnit.size();

        ABuffer seqParamSet = findNAL(data, size, 7);
        if (seqParamSet == null) {
            return null;
        }

        final int[] widthHeight = new int[2];
        final int[] sarWidthHeight = new int[2];
        findAVCDimensions(seqParamSet, widthHeight, sarWidthHeight);

        ABuffer picParamSet = findNAL(data, size, 8);
        CheckUtils.check(picParamSet != null);

        int csdSize =
                1 + 3 + 1 + 1
                        + 2 * 1 + seqParamSet.size()
                        + 1 + 2 * 1 + picParamSet.size();

        ABuffer csd = new ABuffer(csdSize);
        ByteBuffer out = csd.data();

        out.put((byte) 0x01);  // configurationVersion
        ByteBuffer seqParamSetData = seqParamSet.data();
        seqParamSetData.position(1).limit(1 + 3);
        out.put(seqParamSetData);  // profile/level...

        byte profile = out.get(1); // out[0];
        byte level = out.get(3); // out[2];

        out.put((byte) ((0x3f << 2) | 1));  // lengthSize == 2 bytes
        out.put((byte) (0xe0 | 1));

        out.put((byte) (seqParamSet.size() >> 8));
        out.put((byte) (seqParamSet.size() & 0xff));
        seqParamSetData = seqParamSet.data();
        seqParamSetData.limit(seqParamSet.size());
        out.put(seqParamSetData);

        out.put((byte) 1);

        out.put((byte) (picParamSet.size() >> 8));
        out.put((byte) (picParamSet.size() & 0xff));
        ByteBuffer picParamSetData = picParamSet.data();
        picParamSetData.limit(picParamSet.size());
        out.put(picParamSetData);

//#if 0
        if (DEBUG) {
            Log.d(TAG, "AVC seq param set");
            final byte[] seqParamSetBytes = new byte[seqParamSet.size()];
            seqParamSet.data().get(seqParamSetBytes);
            Log.d(TAG, HexDump.dumpHexString(seqParamSetBytes));
        }
//#endif

        MetaData meta = new MetaData();
        meta.setString(KEY_MIME_TYPE, MediaFormat.MIMETYPE_VIDEO_AVC);

        meta.setByteData(KEY_AVCC, TYPE_AVCC, csd.data(), csd.size(), true);
        meta.setInt(KEY_WIDTH, widthHeight[0]);
        meta.setInt(KEY_HEIGHT, widthHeight[1]);

        if ((sarWidthHeight[0] > 0 && sarWidthHeight[1] > 0)
                && (sarWidthHeight[0] != 1 || sarWidthHeight[1] != 1)) {
            // We treat *:0 and 0:* (unspecified) as 1:1.

            meta.setInt(KEY_SAR_WIDTH, sarWidthHeight[0]);
            meta.setInt(KEY_SAR_HEIGHT, sarWidthHeight[1]);

            Log.d(TAG, String.format("found AVC codec config (%d x %d, %s-profile level %d.%d) "
                            + "SAR %d : %d",
                    widthHeight[0],
                    widthHeight[1],
                    convertAVCProfileToString(profile),
                    level / 10,
                    level % 10,
                    sarWidthHeight[0],
                    sarWidthHeight[1]));
        } else {
            Log.d(TAG, String.format("found AVC codec config (%d x %d, %s-profile level %d.%d)",
                    widthHeight[0],
                    widthHeight[1],
                    convertAVCProfileToString(profile),
                    level / 10,
                    level % 10));
        }

        return meta;
    }*/

    public static boolean isIDR(ABuffer accessUnit) {
        if (accessUnit.meta().get(IS_IDR, false)) {
            return true;
        }
        return isIDRInternal(accessUnit);
    }

    public static boolean isAVCReferenceFrame(ABuffer accessUnit) {
        ByteBuffer data = accessUnit.data();
        int size = accessUnit.size();
        if (data == null) {
            Log.e(TAG, String.format("IsAVCReferenceFrame: called on NULL data (%s, %d)",
                    accessUnit, size));
            return false;
        }

        final ByteBuffer[] inOutData = new ByteBuffer[]{data};
        final int[] inOutSize = new int[]{size};
        final ByteBuffer[] nalStart = new ByteBuffer[1];
        final int[] nalSize = new int[1];
        while (getNextNALUnit(inOutData, inOutSize, nalStart, nalSize, true)
                == OK) {
            if (nalSize[0] == 0) {
                Log.e(TAG, String.format("IsAVCReferenceFrame: invalid nalSize: 0 (%s, %d)",
                        accessUnit, size));
                return false;
            }

            int nalType = nalStart[0].get(0) & 0x1f;

            if (nalType == 5) {
                return true;
            } else if (nalType == 1) {
                int nal_ref_idc = ((nalStart[0].get(0) & 0xFF) >>> 5) & 3;
                return nal_ref_idc != 0;
            }
        }

        return true;
    }

    public static int findAVCLayerId(ByteBuffer data, int size) {
        CheckUtils.check(data != null);

        int kSvcNalType = 0xE;
        int kSvcNalSearchRange = 32;
        // SVC NAL
        // |---0 1110|1--- ----|---- ----|iii- ---|
        //       ^                        ^
        //   NAL-type = 0xE               layer-Id
        //
        // layer_id 0 is for base layer, while 1, 2, ... are enhancement layers.
        // Layer n uses reference frames from layer 0, 1, ..., n-1.

        int layerId = 0;
        ABuffer svcNAL = findNAL(data, Math.min(size, kSvcNalSearchRange), kSvcNalType);
        if (svcNAL != null && svcNAL.size() >= 4) {
            layerId = ((svcNAL.data().get(3) & 0xFF) >>> 5) & 0x7;
        }
        return layerId;
    }

    public static String convertAVCProfileToString(byte profile) {
        switch (profile & 0xFF) {
            case AVC_PROFILE_BASELINE:
                return "Baseline";
            case AVC_PROFILE_MAIN:
                return "Main";
            case AVC_PROFILE_EXTENDED:
                return "Extended";
            case AVC_PROFILE_HIGH:
                return "High";
            case AVC_PROFILE_HIGH_10:
                return "High 10";
            case AVC_PROFILE_HIGH_422:
                return "High 422";
            case AVC_PROFILE_HIGH_444:
                return "High 444";
            case AVC_PROFILE_CAVLC_444_INTRA:
                return "CAVLC 444 Intra";
            default:
                return "Unknown";
        }
    }

    /*public static MetaData makeAACCodecSpecificData(
            int profile, int sampling_freq_index, int channel_configuration) {
        MetaData meta = new MetaData();
        meta.setString(KEY_MIME_TYPE, MediaFormat.MIMETYPE_AUDIO_AAC);

        CheckUtils.checkLessThan(sampling_freq_index, 11);
        final int[] kSamplingFreq = {
                96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                16000, 12000, 11025, 8000
        };
        meta.setInt(KEY_SAMPLE_RATE, kSamplingFreq[sampling_freq_index]);
        meta.setInt(KEY_CHANNEL_COUNT, channel_configuration);

        final byte[] kStaticESDS = {
                0x03, 22,
                0x00, 0x00,     // ES_ID
                0x00,           // streamDependenceFlag, URL_Flag, OCRstreamFlag

                0x04, 17,
                0x40,                       // Audio ISO/IEC 14496-3
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,

                0x05, 2,
                // AudioSpecificInfo follows

                // oooo offf fccc c000
                // o - audioObjectType
                // f - samplingFreqIndex
                // c - channelConfig
        };
        ABuffer csd = new ABuffer(kStaticESDS.length + 2);
        ByteBuffer csdData = csd.data();
        csdData.put(kStaticESDS);

        csdData.put((byte) (((profile + 1) << 3) | (sampling_freq_index >> 1)));

        csdData.put((byte) (((sampling_freq_index << 7) & 0x80) | (channel_configuration << 3)));

        meta.setByteData(KEY_ESDS, 0, csd.data(), csd.size(), false);

        return meta;
    }*/

    /**
     * Given an MPEG4 video VOL-header chunk (starting with 0x00 0x00 0x01 0x2?)
     * parse it and fill in dimensions, returns true iff successful.
     */
    public static boolean extractDimensionsFromVOLHeader(
            ByteBuffer data, int size, final int[] widthHeight) {
        ABitReader br = new ABitReader(((ByteBuffer) data.duplicate().position(4)).slice(),
                size - 4);
        br.skipBits(1);  // random_accessible_vol
        int video_object_type_indication = br.getBits(8);

        CheckUtils.checkNotEqual(video_object_type_indication,
                0x21 /* Fine Granularity Scalable */);

        int video_object_layer_verid;
        int video_object_layer_priority;
        if (br.getBits(1) != 0) {
            video_object_layer_verid = br.getBits(4);
            video_object_layer_priority = br.getBits(3);
        }
        int aspect_ratio_info = br.getBits(4);
        if (aspect_ratio_info == 0x0f /* extended PAR */) {
            br.skipBits(8);  // par_width
            br.skipBits(8);  // par_height
        }
        if (br.getBits(1) != 0) {  // vol_control_parameters
            br.skipBits(2);  // chroma_format
            br.skipBits(1);  // low_delay
            if (br.getBits(1) != 0) {  // vbv_parameters
                br.skipBits(15);  // first_half_bit_rate
                CheckUtils.check(br.getBits(1) != 0);  // marker_bit
                br.skipBits(15);  // latter_half_bit_rate
                CheckUtils.check(br.getBits(1) != 0);  // marker_bit
                br.skipBits(15);  // first_half_vbv_buffer_size
                CheckUtils.check(br.getBits(1) != 0);  // marker_bit
                br.skipBits(3);  // latter_half_vbv_buffer_size
                br.skipBits(11);  // first_half_vbv_occupancy
                CheckUtils.check(br.getBits(1) != 0);  // marker_bit
                br.skipBits(15);  // latter_half_vbv_occupancy
                CheckUtils.check(br.getBits(1) != 0);  // marker_bit
            }
        }
        int video_object_layer_shape = br.getBits(2);
        CheckUtils.checkEqual(video_object_layer_shape, 0x00 /* rectangular */);

        CheckUtils.check(br.getBits(1) != 0);  // marker_bit
        int vop_time_increment_resolution = br.getBits(16);
        CheckUtils.check(br.getBits(1) != 0);  // marker_bit

        if (br.getBits(1) != 0) {  // fixed_vop_rate
            // range [0..vop_time_increment_resolution)

            // vop_time_increment_resolution
            // 2 => 0..1, 1 bit
            // 3 => 0..2, 2 bits
            // 4 => 0..3, 2 bits
            // 5 => 0..4, 3 bits
            // ...

            CheckUtils.checkGreaterThan(vop_time_increment_resolution, 0);
            --vop_time_increment_resolution;

            int numBits = 0;
            while (vop_time_increment_resolution > 0) {
                ++numBits;
                vop_time_increment_resolution >>>= 1;
            }

            br.skipBits(numBits);  // fixed_vop_time_increment
        }

        CheckUtils.check(br.getBits(1) != 0);  // marker_bit
        int video_object_layer_width = br.getBits(13);
        CheckUtils.check(br.getBits(1) != 0);  // marker_bit
        int video_object_layer_height = br.getBits(13);
        CheckUtils.check(br.getBits(1) != 0);  // marker_bit

        int interlaced = br.getBits(1);

        widthHeight[0] = video_object_layer_width;
        widthHeight[1] = video_object_layer_height;

        return true;
    }

    public static boolean getMPEGAudioFrameSize(int header,
            final int[] frame_size,
            final int[] out_sampling_rate,
            final int[] out_channels,
            final int[] out_bitrate,
            final int[] out_num_samples) {
        frame_size[0] = 0;

        if (out_sampling_rate != null) {
            out_sampling_rate[0] = 0;
        }

        if (out_channels != null) {
            out_channels[0] = 0;
        }

        if (out_bitrate != null) {
            out_bitrate[0] = 0;
        }

        if (out_num_samples != null) {
            out_num_samples[0] = 1152;
        }

        if ((header & 0xffe00000) != 0xffe00000) {
            return false;
        }

        int version = (header >>> 19) & 3;

        if (version == 0x01) {
            return false;
        }

        int layer = (header >>> 17) & 3;

        if (layer == 0x00) {
            return false;
        }

        int protection = (header >>> 16) & 1;

        int bitrate_index = (header >>> 12) & 0x0f;

        if (bitrate_index == 0 || bitrate_index == 0x0f) {
            // Disallow "free" bitrate.
            return false;
        }

        int sampling_rate_index = (header >>> 10) & 3;

        if (sampling_rate_index == 3) {
            return false;
        }

        final int[] kSamplingRateV1 = {44100, 48000, 32000};
        int sampling_rate = kSamplingRateV1[sampling_rate_index];
        if (version == 2 /* V2 */) {
            sampling_rate /= 2;
        } else if (version == 0 /* V2.5 */) {
            sampling_rate /= 4;
        }

        int padding = (header >>> 9) & 1;

        if (layer == 3) {
            // layer I

            final int[] kBitrateV1 = {
                    32, 64, 96, 128, 160, 192, 224, 256,
                    288, 320, 352, 384, 416, 448
            };

            final int[] kBitrateV2 = {
                    32, 48, 56, 64, 80, 96, 112, 128,
                    144, 160, 176, 192, 224, 256
            };

            int bitrate =
                    (version == 3 /* V1 */)
                            ? kBitrateV1[bitrate_index - 1]
                            : kBitrateV2[bitrate_index - 1];

            if (out_bitrate != null) {
                out_bitrate[0] = bitrate;
            }

            frame_size[0] = (12000 * bitrate / sampling_rate + padding) * 4;

            if (out_num_samples != null) {
                out_num_samples[0] = 384;
            }
        } else {
            // layer II or III

            final int[] kBitrateV1L2 = {
                    32, 48, 56, 64, 80, 96, 112, 128,
                    160, 192, 224, 256, 320, 384
            };

            final int[] kBitrateV1L3 = {
                    32, 40, 48, 56, 64, 80, 96, 112,
                    128, 160, 192, 224, 256, 320
            };

            final int[] kBitrateV2 = {
                    8, 16, 24, 32, 40, 48, 56, 64,
                    80, 96, 112, 128, 144, 160
            };

            int bitrate;
            if (version == 3 /* V1 */) {
                bitrate = (layer == 2 /* L2 */)
                        ? kBitrateV1L2[bitrate_index - 1]
                        : kBitrateV1L3[bitrate_index - 1];

                if (out_num_samples != null) {
                    out_num_samples[0] = 1152;
                }
            } else {
                // V2 (or 2.5)

                bitrate = kBitrateV2[bitrate_index - 1];
                if (out_num_samples != null) {
                    out_num_samples[0] = (layer == 1 /* L3 */) ? 576 : 1152;
                }
            }

            if (out_bitrate != null) {
                out_bitrate[0] = bitrate;
            }

            if (version == 3 /* V1 */) {
                frame_size[0] = 144000 * bitrate / sampling_rate + padding;
            } else {
                // V2 or V2.5
                int tmp = (layer == 1 /* L3 */) ? 72000 : 144000;
                frame_size[0] = tmp * bitrate / sampling_rate + padding;
            }
        }

        if (out_sampling_rate != null) {
            out_sampling_rate[0] = sampling_rate;
        }

        if (out_channels != null) {
            int channel_mode = (header >>> 6) & 3;

            out_channels[0] = (channel_mode == 3) ? 1 : 2;
        }

        return true;
    }

    private static void skipScalingList(ABitReader br, int sizeOfScalingList) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < sizeOfScalingList; ++j) {
            if (nextScale != 0) {
                int delta_scale = parseSE(br);
                // ISO_IEC_14496-10_201402-ITU, 7.4.2.1.1.1, The value of delta_scale
                // shall be in the range of −128 to +127, inclusive.
                if (delta_scale < -128) {
                    Log.w(TAG, String.format("delta_scale (%d) is below range, capped to -128",
                            delta_scale));
                    delta_scale = -128;
                } else if (delta_scale > 127) {
                    Log.w(TAG, String.format("delta_scale (%d) is above range, capped to 127",
                            delta_scale));
                    delta_scale = 127;
                }
                nextScale = (lastScale + (delta_scale + 256)) % 256;
            }

            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }

    public static ABuffer findNAL(ByteBuffer data, int size, int nalType) {
        final ByteBuffer[] inOutData = new ByteBuffer[]{data};
        final int[] inOutSize = new int[]{size};
        final ByteBuffer[] nalStart = new ByteBuffer[1];
        final int[] nalSize = new int[1];
        while (getNextNALUnit(inOutData, inOutSize, nalStart, nalSize, true)
                == OK) {
            if (nalSize[0] > 0 && (nalStart[0].get(0) & 0x1f) == nalType) {
                ABuffer buffer = new ABuffer(nalSize[0]);
                nalStart[0].limit(nalStart[0].position() + nalSize[0]);
                buffer.data().put(nalStart[0]);
                return buffer;
            }
        }

        return null;
    }

    private static boolean isIDRInternal(ABuffer buffer) {
        ByteBuffer data = buffer.data();
        int size = buffer.size();

        boolean foundIDR = false;

        final ByteBuffer[] inOutData = new ByteBuffer[]{data};
        final int[] inOutSize = new int[]{size};
        final ByteBuffer[] nalStart = new ByteBuffer[1];
        final int[] nalSize = new int[1];
        while (getNextNALUnit(inOutData, inOutSize, nalStart, nalSize, true)
                == OK) {
            if (nalSize[0] == 0) {
                Log.w(TAG, "skipping empty nal unit from potentially malformed bitstream");
                continue;
            }

            int nalType = nalStart[0].get(0) & 0x1f;

            if (nalType == 5) {
                foundIDR = true;
                break;
            }
        }

        return foundIDR;
    }
}
