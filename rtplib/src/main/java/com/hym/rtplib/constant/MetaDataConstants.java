/*
package com.hym.rtplib.constant;

public interface MetaDataConstants {
    // The following keys map to int data unless indicated otherwise.
    int KEY_MIME_TYPE = stringToInt("mime");  // cstring
    int KEY_WIDTH = stringToInt("widt");  // int, image pixel
    int KEY_HEIGHT = stringToInt("heig");  // int, image pixel
    int KEY_DISPLAY_WIDTH = stringToInt("dWid");  // int, display/presentation
    int KEY_DISPLAY_HEIGHT = stringToInt("dHgt");  // int, display/presentation
    int KEY_SAR_WIDTH = stringToInt("sarW");  // int, sampleAspectRatio width
    int KEY_SAR_HEIGHT = stringToInt("sarH");  // int, sampleAspectRatio height
    int KEY_THUMBNAIL_WIDTH = stringToInt("thbW");  // int, thumbnail width
    int KEY_THUMBNAIL_HEIGHT = stringToInt("thbH");  // int, thumbnail height

    // a rectangle, if absent assumed to be (0, 0, width - 1, height - 1)
    int KEY_CROP_RECT = stringToInt("crop");

    int KEY_ROTATION = stringToInt("rotA");  // int (angle in degrees)
    int KEY_I_FRAMES_INTERVAL = stringToInt("ifiv");  // int
    int KEY_STRIDE = stringToInt("strd");  // int
    int KEY_SLICE_HEIGHT = stringToInt("slht");  // int
    int KEY_CHANNEL_COUNT = stringToInt("#chn");  // int
    int KEY_CHANNEL_MASK = stringToInt("chnm");  // int
    int KEY_SAMPLE_RATE = stringToInt("srte");  // int (audio sampling rate Hz)
    int KEY_PCM_ENCODING = stringToInt("PCMe");  // int (audio encoding enum)
    int KEY_FRAME_RATE = stringToInt("frmR");  // int (video frame rate fps)
    int KEY_BIT_RATE = stringToInt("brte");  // int (bps)
    int KEY_MAX_BIT_RATE = stringToInt("mxBr");  // int (bps)
    int KEY_STREAM_HEADER = stringToInt("stHd");  // raw data
    int KEY_ESDS = stringToInt("esds");  // raw data
    int KEY_AAC_PROFILE = stringToInt("aacp");  // int
    int KEY_AVCC = stringToInt("avcc");  // raw data
    int KEY_HVCC = stringToInt("hvcc");  // raw data
    int KEY_THUMBNAIL_HVCC = stringToInt("thvc");  // raw data
    int KEY_D263 = stringToInt("d263");  // raw data
    int KEY_VORBIS_INFO = stringToInt("vinf");  // raw data
    int KEY_VORBIS_BOOKS = stringToInt("vboo");  // raw data
    int KEY_OPUS_HEADER = stringToInt("ohdr");  // raw data
    int KEY_OPUS_CODEC_DELAY = stringToInt("ocod");  // long (codec delay in ns)
    int KEY_OPUS_SEEK_PRE_ROLL = stringToInt("ospr");  // long (seek preroll in ns)
    int KEY_FLAC_METADATA = stringToInt("flMd");  // raw data
    int KEY_VP9_CODEC_PRIVATE = stringToInt("vp9p");  // raw data (vp9 csd information)
    int KEY_WANTS_NAL_FRAGMENTS = stringToInt("NALf");
    int KEY_IS_SYNC_FRAME = stringToInt("sync");  // int (boolean)
    int KEY_IS_CODEC_CONFIG = stringToInt("conf");  // int (boolean)
    int KEY_TIME = stringToInt("time");  // long (usecs)
    int KEY_DECODING_TIME = stringToInt("decT");  // long (decoding timestamp in usecs)
    int KEY_NTP_TIME = stringToInt("ntpT");  // long (ntp-timestamp)
    int KEY_TARGET_TIME = stringToInt("tarT");  // long (usecs)
    int KEY_DRIFT_TIME = stringToInt("dftT");  // long (usecs)
    int KEY_ANCHOR_TIME = stringToInt("ancT");  // long (usecs)
    int KEY_DURATION = stringToInt("dura");  // long (usecs)
    int KEY_PIXEL_FORMAT = stringToInt("pixf");  // int
    int KEY_COLOR_FORMAT = stringToInt("colf");  // int
    int KEY_COLOR_SPACE = stringToInt("cols");  // int
    int KEY_PLATFORM_PRIVATE = stringToInt("priv");  // pointer
    int KEY_DECODER_COMPONENT = stringToInt("decC");  // cstring
    int KEY_BUFFER_ID = stringToInt("bfID");
    int KEY_MAX_INPUT_SIZE = stringToInt("inpS");
    int KEY_MAX_WIDTH = stringToInt("maxW");
    int KEY_MAX_HEIGHT = stringToInt("maxH");
    int KEY_THUMBNAIL_TIME = stringToInt("thbT");  // long (usecs)
    int KEY_TRACK_ID = stringToInt("trID");
    int KEY_IS_DRM = stringToInt("idrm");  // int (boolean)
    int KEY_ENCODER_DELAY = stringToInt("encd");  // int (frames)
    int KEY_ENCODER_PADDING = stringToInt("encp");  // int (frames)

    int KEY_ALBUM = stringToInt("albu");  // cstring
    int KEY_ARTIST = stringToInt("arti");  // cstring
    int KEY_ALBUM_ARTIST = stringToInt("aart");  // cstring
    int KEY_COMPOSER = stringToInt("comp");  // cstring
    int KEY_GENRE = stringToInt("genr");  // cstring
    int KEY_TITLE = stringToInt("titl");  // cstring
    int KEY_YEAR = stringToInt("year");  // cstring
    int KEY_ALBUM_ART = stringToInt("albA");  // compressed image data
    int KEY_ALBUM_ART_MIME = stringToInt("alAM");  // cstring
    int KEY_AUTHOR = stringToInt("auth");  // cstring
    int KEY_CD_TRACK_NUMBER = stringToInt("cdtr");  // cstring
    int KEY_DISC_NUMBER = stringToInt("dnum");  // cstring
    int KEY_DATE = stringToInt("date");  // cstring
    int KEY_WRITER = stringToInt("writ");  // cstring
    int KEY_COMPILATION = stringToInt("cpil");  // cstring
    int KEY_LOCATION = stringToInt("loc ");  // cstring
    int KEY_TIME_SCALE = stringToInt("tmsl");  // int
    int KEY_CAPTURE_FRAMERATE = stringToInt("capF");  // float (capture fps)

    // video profile and level
    int KEY_VIDEO_PROFILE = stringToInt("vprf");  // int
    int KEY_VIDEO_LEVEL = stringToInt("vlev");  // int

    // Set this key to enable authoring files in 64-bit offset
    int KEY_64_BIT_FILE_OFFSET = stringToInt("fobt");  // int (boolean)
    int KEY_2_BYTE_NAL_LENGTH = stringToInt("2NAL");  // int (boolean)

    // Identify the file output format for authoring
    // Please see <media/mediarecorder.h> for the supported
    // file output formats.
    int KEY_FILE_TYPE = stringToInt("ftyp");  // int

    // Track authoring progress status
    // KEY_TRACK_TIME_STATUS is used to track progress in elapsed time
    int KEY_TRACK_TIME_STATUS = stringToInt("tktm");  // long

    int KEY_REAL_TIME_RECORDING = stringToInt("rtrc");  // boolean (int)
    int KEY_NUM_BUFFERS = stringToInt("nbbf");  // int

    // Ogg files can be tagged to be automatically looping...
    int KEY_AUTO_LOOP = stringToInt("autL");  // boolean (int)

    int KEY_VALID_SAMPLES = stringToInt("valD");  // int

    int KEY_IS_UNREADABLE = stringToInt("unre");  // boolean (int)

    // An indication that a video buffer has been rendered.
    int KEY_RENDERED = stringToInt("rend");  // boolean (int)

    // The language code for this media
    int KEY_MEDIA_LANGUAGE = stringToInt("lang");  // cstring

    // To store the timed text format data
    int KEY_TEXT_FORMAT_DATA = stringToInt("text");  // raw data

    int KEY_REQUIRES_SECURE_BUFFERS = stringToInt("secu");  // boolean (int)

    int KEY_IS_ADTS = stringToInt("adts");  // boolean (int)
    int KEY_AAC_AOT = stringToInt("aaot");  // int

    // If a MediaBuffer's data represents (at least partially) encrypted
    // data, the following fields aid in decryption.
    // The data can be thought of as pairs of plain and encrypted data
    // fragments, i.e. plain and encrypted data alternate.
    // The first fragment is by convention plain data (if that's not the
    // case, simply specify plain fragment size of 0).
    // KEY_ENCRYPTED_SIZES and KEY_PLAIN_SIZES each map to an array of
    // size_t values. The sum total of all size_t values of both arrays
    // must equal the amount of data (i.e. MediaBuffer's range_length()).
    // If both arrays are present, they must be of the same size.
    // If only encrypted sizes are present it is assumed that all
    // plain sizes are 0, i.e. all fragments are encrypted.
    // To programmatically set these array, use the MetaData::setData API, i.e.
    // const size_t encSizes[];
    // meta->setData(
    //  KEY_ENCRYPTED_SIZES, 0 (type), encSizes, sizeof(encSizes));
    // A plain sizes array by itself makes no sense.
    int KEY_ENCRYPTED_SIZES = stringToInt("encr");  // size_t[]
    int KEY_PLAIN_SIZES = stringToInt("plai");  // size_t[]
    int KEY_CRYPTO_KEY = stringToInt("cryK");  // uint8_t[16]
    int KEY_CRYPTO_IV = stringToInt("cryI");  // uint8_t[16]
    int KEY_CRYPTO_MODE = stringToInt("cryM");  // int

    int KEY_CRYPTO_DEFAULT_IV_SIZE = stringToInt("cryS");  // int

    int KEY_PSSH = stringToInt("pssh");  // raw data
    int KEY_CA_SYSTEM_ID = stringToInt("caid");  // int
    int KEY_CA_SESSION_ID = stringToInt("seid");  // raw data

    // Please see MediaFormat.KEY_IS_AUTOSELECT.
    int KEY_TRACK_IS_AUTOSELECT = stringToInt("auto"); // boolean (int)
    // Please see MediaFormat.KEY_IS_DEFAULT.
    int KEY_TRACK_IS_DEFAULT = stringToInt("dflt"); // boolean (int)
    // Similar to MediaFormat.KEY_IS_FORCED_SUBTITLE but pertains to av tracks as well.
    int KEY_TRACK_IS_FORCED = stringToInt("frcd"); // boolean (int)

    // H264 supplemental enhancement information offsets/sizes
    int KEY_SEI = stringToInt("sei "); // raw data

    // MPEG user data offsets
    int KEY_MPEG_USER_DATA = stringToInt("mpud"); // size_t[]

    // Size of NALU length in mkv/mp4
    int KEY_NAL_LENGTH_SIZE = stringToInt("nals"); // int

    // HDR related
    int KEY_HDR_STATIC_INFO = stringToInt("hdrS"); // HDRStaticInfo

    // color aspects
    int KEY_COLOR_RANGE = stringToInt("cRng");
    // int, color range, value defined by ColorAspects.Range
    int KEY_COLOR_PRIMARIES = stringToInt("cPrm"); // int,
    // color Primaries, value defined by ColorAspects.Primaries
    int KEY_TRANSFER_FUNCTION = stringToInt("tFun"); // int,
    // transfer Function, value defined by ColorAspects.Transfer.
    int KEY_COLOR_MATRIX = stringToInt("cMtx"); // int,
    // color Matrix, value defined by ColorAspects.MatrixCoeffs.
    int KEY_TEMPORAL_LAYER_ID = stringToInt("iLyr");
    // int, temporal layer-id. 0-based (0 => base layer)
    int KEY_TEMPORAL_LAYER_COUNT = stringToInt("cLyr");
    // int, number of temporal layers encoded

    int KEY_GRID_WIDTH = stringToInt("grdW"); // int, HEIF grid width
    int KEY_GRID_HEIGHT = stringToInt("grdH"); // int, HEIF grid height
    int KEY_GRID_ROWS = stringToInt("grdR"); // int, HEIF grid rows
    int KEY_GRID_COLS = stringToInt("grdC"); // int, HEIF grid columns
    int KEY_ICC_PROFILE = stringToInt("prof"); // raw data, ICC prifile data


    int TYPE_ESDS = stringToInt("esds");
    int TYPE_AVCC = stringToInt("avcc");
    int TYPE_HVCC = stringToInt("hvcc");
    int TYPE_D263 = stringToInt("d263");


    static int stringToInt(String str) {
        if (str == null || str.length() != 4) {
            throw new IllegalArgumentException("str(" + str + ") is null or lenght is not 4 !");
        }
        char[] arr = str.toCharArray();
        return arr[0] << 24 | arr[1] << 16 | arr[2] << 8 | arr[3];
    }
}
*/
