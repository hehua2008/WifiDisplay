package com.hym.rtplib;

import android.util.Log;

import com.hym.rtplib.util.CheckUtils;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VideoFormats {
    private static final String TAG = VideoFormats.class.getSimpleName();

    private static class Config {
        final int width;
        final int height;
        final int framesPerSecond;
        final boolean interlaced;
        int profile, level;

        private Config(int w, int h, int f, boolean i, int p, int l) {
            width = w;
            height = h;
            framesPerSecond = f;
            interlaced = i;
            profile = p;
            level = l;
        }

        @Override
        public String toString() {
            return "\n" + width + "x" + height + (interlaced ? "i " : "p ")
                    + framesPerSecond + "fps " + "profile=" + profile + " level=" + level;
        }
    }

    public static class FormatConfig {
        int width, height, framesPerSecond;
        boolean interlaced;
        ProfileType profileType;
        LevelType levelType;

        public FormatConfig() {
        }

        public FormatConfig(int w, int h, int f, boolean i) {
            width = w;
            height = h;
            framesPerSecond = f;
            interlaced = i;
        }

        public FormatConfig(int w, int h, int f, boolean i, ProfileType p, LevelType l) {
            width = w;
            height = h;
            framesPerSecond = f;
            interlaced = i;
            profileType = p;
            levelType = l;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("width=").append(width).append(", ");
            sb.append("height=").append(height).append(", ");
            sb.append("framesPerSecond=").append(framesPerSecond).append(", ");
            sb.append("interlaced=").append(interlaced).append(", ");
            sb.append("profileType=").append(profileType).append(", ");
            sb.append("levelType=").append(levelType);
            return sb.toString();
        }
    }

    public enum ProfileType {
        PROFILE_CBP,
        PROFILE_CHP;

        public static final int NUM_PROFILE_TYPES = ProfileType.values().length;

        public static ProfileType valueOf(int index) {
            return ProfileType.values()[index];
        }
    }

    public enum LevelType {
        LEVEL_31,
        LEVEL_32,
        LEVEL_40,
        LEVEL_41,
        LEVEL_42;

        public static final int NUM_LEVEL_TYPES = LevelType.values().length;

        public static LevelType valueOf(int index) {
            return LevelType.values()[index];
        }
    }

    public enum ResolutionType {
        RESOLUTION_CEA,
        RESOLUTION_VESA,
        RESOLUTION_HH;

        public static final int NUM_RESOLUTION_TYPES = ResolutionType.values().length;

        public static ResolutionType valueOf(int index) {
            return ResolutionType.values()[index];
        }
    }

    private static final int[] PROFILE_IDC = {
            66,     // PROFILE_CBP
            100     // PROFILE_CHP
    };

    private static final int[] LEVEL_IDC = {
            31,     // LEVEL_31
            32,     // LEVEL_32
            40,     // LEVEL_40
            41,     // LEVEL_41
            42      // LEVEL_42
    };

    private static final int[] CONSTRAINT_SET = {
            0xc0,   // PROFILE_CBP
            0x0c    // PROFILE_CHP
    };

    public VideoFormats() {
        for (int i = 0; i < mConfigs.length; i++) {
            mConfigs[i] = Arrays.copyOf(RESOLUTION_TABLE[i], RESOLUTION_TABLE[i].length);
        }

        Arrays.fill(mResolutionEnabled, 0);

        setNativeResolution(ResolutionType.RESOLUTION_CEA, 0);  // default to 640x480 p60
    }

    public void setNativeResolution(ResolutionType type, int index) {
        CheckUtils.checkLessThan(type.ordinal(), ResolutionType.NUM_RESOLUTION_TYPES);
        CheckUtils.check(getConfiguration(type, index) != null);

        mNativeType = type;
        mNativeIndex = index;

        setResolutionEnabled(type, index);
    }

    public Config getNativeResolution() {
        return RESOLUTION_TABLE[mNativeType.ordinal()][mNativeIndex];
    }

    public void disableAll() {
        for (int i = 0; i < ResolutionType.NUM_RESOLUTION_TYPES; ++i) {
            mResolutionEnabled[i] = 0;
            for (int j = 0; j < 32; j++) {
                mConfigs[i][j].profile = mConfigs[i][j].level = 0;
            }
        }
    }

    public void enableAll() {
        for (int i = 0; i < ResolutionType.NUM_RESOLUTION_TYPES; ++i) {
            mResolutionEnabled[i] = 0xffffffff;
            for (int j = 0; j < 32; j++) {
                mConfigs[i][j].profile = (1 << ProfileType.PROFILE_CBP.ordinal());
                mConfigs[i][j].level = (1 << LevelType.LEVEL_31.ordinal());
            }
        }
    }

    public void enableResolutionUpto(ResolutionType type, int index,
            ProfileType profile, LevelType level) {
        FormatConfig fc = getConfiguration(type, index);
        if (fc == null) {
            Log.e(TAG, "Maximum resolution not found!");
            return;
        }
        int score = fc.width * fc.height * fc.framesPerSecond * (fc.interlaced ? 1 : 2);
        for (int i = 0; i < ResolutionType.NUM_RESOLUTION_TYPES; ++i) {
            for (int j = 0; j < 32; j++) {
                fc = getConfiguration(ResolutionType.valueOf(i), j);
                if (fc != null
                        && score >= fc.width * fc.height * fc.framesPerSecond
                        * (fc.interlaced ? 1 : 2)) {
                    setResolutionEnabled(ResolutionType.valueOf(i), j);
                    setProfileLevel(ResolutionType.valueOf(i), j, profile, level);
                }
            }
        }
    }

    public void setResolutionEnabled(ResolutionType type, int index) {
        setResolutionEnabled(type, index, true);
    }

    public void setResolutionEnabled(ResolutionType type, int index, boolean enabled) {
        CheckUtils.checkLessThan(type.ordinal(), ResolutionType.NUM_RESOLUTION_TYPES);
        CheckUtils.check(getConfiguration(type, index) != null);

        int typeIndex = type.ordinal();
        if (enabled) {
            mResolutionEnabled[typeIndex] |= (1 << index);
            mConfigs[typeIndex][index].profile = (1 << ProfileType.PROFILE_CBP.ordinal());
            mConfigs[typeIndex][index].level = (1 << LevelType.LEVEL_31.ordinal());
        } else {
            mResolutionEnabled[typeIndex] &= ~(1 << index);
            mConfigs[typeIndex][index].profile = 0;
            mConfigs[typeIndex][index].level = 0;
        }
    }

    public boolean isResolutionEnabled(ResolutionType type, int index) {
        CheckUtils.checkLessThan(type.ordinal(), ResolutionType.NUM_RESOLUTION_TYPES);
        CheckUtils.check(getConfiguration(type, index) != null);

        return (mResolutionEnabled[type.ordinal()] & (1 << index)) != 0;
    }

    public void setProfileLevel(ResolutionType type, int index,
            ProfileType profile, LevelType level) {
        CheckUtils.checkLessThan(type.ordinal(), ResolutionType.NUM_RESOLUTION_TYPES);
        CheckUtils.check(getConfiguration(type, index) != null);

        int typeIndex = type.ordinal();
        mConfigs[typeIndex][index].profile = (1 << profile.ordinal());
        mConfigs[typeIndex][index].level = (1 << level.ordinal());
    }

    public void getProfileLevel(ResolutionType type, int index,
            final ProfileType[] profile, final LevelType[] level) {
        CheckUtils.checkLessThan(type.ordinal(), ResolutionType.NUM_RESOLUTION_TYPES);
        CheckUtils.check(getConfiguration(type, index) != null);

        int typeIndex = type.ordinal();
        int bestProfile = -1, bestLevel = -1;

        for (int i = 0; i < ProfileType.NUM_PROFILE_TYPES; ++i) {
            if ((mConfigs[typeIndex][index].profile & (1 << i)) != 0) {
                bestProfile = i;
            }
        }

        for (int i = 0; i < LevelType.NUM_LEVEL_TYPES; ++i) {
            if ((mConfigs[typeIndex][index].level & (1 << i)) != 0) {
                bestLevel = i;
            }
        }

        if (bestProfile == -1 || bestLevel == -1) {
            Log.e(TAG, String.format("Profile or level not set for resolution type %s, index %d",
                    type, index));
            bestProfile = ProfileType.PROFILE_CBP.ordinal();
            bestLevel = LevelType.LEVEL_31.ordinal();
        }

        profile[0] = ProfileType.valueOf(bestProfile);
        level[0] = LevelType.valueOf(bestLevel);
    }

    private static final Pattern NATIVE_REG = Pattern.compile("(\\p{XDigit}{2}) (\\p{XDigit}{2}) ");

    public boolean parseFormatSpec(String spec) {
        CheckUtils.checkEqual(ResolutionType.NUM_RESOLUTION_TYPES, 3);

        disableAll();

        int nativeInfo, dummy;
        int size = spec.length();
        int offset = 0;

        Matcher m = NATIVE_REG.matcher(spec);
        if (!m.find() || m.groupCount() < 2) {
            return false;
        }
        nativeInfo = Integer.parseInt(m.group(1), 16);
        dummy = Integer.parseInt(m.group(2), 16);

        offset += 6; // skip native and preferred-display-mode-supported
        CheckUtils.checkLessOrEqual(offset + 58, size);
        while (offset < size) {
            parseH264Codec(spec.substring(offset));
            offset += 60; // skip H.264-codec + ", "
        }

        mNativeIndex = nativeInfo >>> 3;

        boolean success;
        if ((nativeInfo & 7) >= ResolutionType.NUM_RESOLUTION_TYPES) {
            success = false;
        } else {
            mNativeType = ResolutionType.valueOf(nativeInfo & 7);
            success = getConfiguration(mNativeType, mNativeIndex) != null;
        }

        if (!success) {
            Log.w(TAG, "sink advertised an illegal native resolution, fortunately "
                    + "this value is ignored for the time being...");
        }

        return true;
    }

    private static final Pattern FORMATS_REG = Pattern.compile(
            "(\\p{XDigit}{2}) (\\p{XDigit}{2}) (\\p{XDigit}{8}) (\\p{XDigit}{8}) (\\p{XDigit}{8})");

    private boolean parseH264Codec(String spec) {
        int profile;
        int level;
        int[] res = new int[3];

        Matcher m = FORMATS_REG.matcher(spec);
        if (!m.find() || m.groupCount() < 5) {
            return false;
        } else {
            profile = Integer.parseInt(m.group(1), 16);
            level = Integer.parseInt(m.group(2), 16);
            res[0] = Integer.parseUnsignedInt(m.group(3), 16);
            res[1] = Integer.parseUnsignedInt(m.group(4), 16);
            res[2] = Integer.parseUnsignedInt(m.group(5), 16);
        }

        for (int i = 0, len = ResolutionType.NUM_RESOLUTION_TYPES; i < len; ++i) {
            for (int j = 0; j < 32; ++j) {
                if ((res[i] & (1 << j)) != 0) {
                    mResolutionEnabled[i] |= (1 << j);
                    if (profile > mConfigs[i][j].profile) {
                        // prefer higher profile (even if level is lower)
                        mConfigs[i][j].profile = profile;
                        mConfigs[i][j].level = level;
                    } else if (profile == mConfigs[i][j].profile &&
                            level > mConfigs[i][j].level) {
                        mConfigs[i][j].level = level;
                    }
                }
            }
        }

        return true;
    }

    public String getFormatSpec() {
        return getFormatSpec(false);
    }

    public String getFormatSpec(boolean forM4Message) {
        CheckUtils.checkEqual(ResolutionType.NUM_RESOLUTION_TYPES, 3);

        // wfd_video_formats:
        // 1 byte "native"
        // 1 byte "preferred-display-mode-supported" 0 or 1
        // one or more avc codec structures
        //   1 byte profile
        //   1 byte level
        //   4 byte CEA mask
        //   4 byte VESA mask
        //   4 byte HH mask
        //   1 byte latency
        //   2 byte min-slice-slice
        //   2 byte slice-enc-params
        //   1 byte framerate-control-support
        //   max-hres (none or 2 byte)
        //   max-vres (none or 2 byte)

        return String.format(
                "%02x 00 %02x %02x %08x %08x %08x 00 0000 0000 00 none none",
                forM4Message ? 0x00 : ((mNativeIndex << 3) | mNativeType.ordinal()),
                mConfigs[mNativeType.ordinal()][mNativeIndex].profile,
                mConfigs[mNativeType.ordinal()][mNativeIndex].level,
                mResolutionEnabled[0],
                mResolutionEnabled[1],
                mResolutionEnabled[2]);
    }

    public static FormatConfig getConfiguration(ResolutionType type, int index) {
        CheckUtils.checkLessThan(type.ordinal(), ResolutionType.NUM_RESOLUTION_TYPES);

        if (index >= 32) {
            return null;
        }

        Config config = RESOLUTION_TABLE[type.ordinal()][index];

        if (config.width == 0) {
            return null;
        }

        FormatConfig formatConfig = new FormatConfig(config.width, config.height,
                config.framesPerSecond, config.interlaced);

        return formatConfig;
    }

    public static boolean getProfileLevel(
            ProfileType profile, LevelType level,
            final int[] profileIdc, final int[] levelIdc,
            final int[] constraintSet) {
        CheckUtils.checkLessThan(profile.ordinal(), ProfileType.NUM_PROFILE_TYPES);
        CheckUtils.checkLessThan(level.ordinal(), LevelType.NUM_LEVEL_TYPES);

        if (profileIdc != null) {
            profileIdc[0] = PROFILE_IDC[profile.ordinal()];
        }

        if (levelIdc != null) {
            levelIdc[0] = LEVEL_IDC[level.ordinal()];
        }

        if (constraintSet != null) {
            constraintSet[0] = CONSTRAINT_SET[profile.ordinal()];
        }

        return true;
    }

    public static boolean pickBestFormat(VideoFormats sinkSupported, VideoFormats sourceSupported,
            final ResolutionType[] chosenType,
            final int[] chosenIndex,
            final ProfileType[] chosenProfile,
            final LevelType[] chosenLevel) {
        //Log.w(TAG, "sinkSupported:\n" + sinkSupported);
        //Log.w(TAG, "sourceSupported:\n" + sourceSupported);
        boolean first = true;
        int bestScore = 0;
        int bestType = 0;
        int bestIndex = 0;

        for (int i = 0; i < ResolutionType.NUM_RESOLUTION_TYPES; ++i) {
            for (int j = 0; j < 32; ++j) {
                FormatConfig fc = getConfiguration(ResolutionType.valueOf(i), j);
                if (fc == null) {
                    break;
                }

                if (!sinkSupported.isResolutionEnabled(ResolutionType.valueOf(i), j)
                        || !sourceSupported.isResolutionEnabled(
                        ResolutionType.valueOf(i), j)) {
                    continue;
                }

                Log.d(TAG, String.format("type %d, index %d, %s supported", i, j, fc));

                int score = fc.width * fc.height * fc.framesPerSecond;
                if (!fc.interlaced) {
                    score *= 2;
                }

                if (first || score > bestScore) {
                    bestScore = score;
                    bestType = i;
                    bestIndex = j;

                    first = false;
                }
            }
        }

        if (first) {
            return false;
        }

        chosenType[0] = ResolutionType.valueOf(bestType);
        chosenIndex[0] = bestIndex;

        // Pick the best profile/level supported by both sink and source.
        ProfileType[] srcProfile = new ProfileType[1];
        ProfileType[] sinkProfile = new ProfileType[1];
        LevelType[] srcLevel = new LevelType[1];
        LevelType[] sinkLevel = new LevelType[1];
        sourceSupported.getProfileLevel(
                ResolutionType.valueOf(bestType), bestIndex,
                srcProfile, srcLevel);
        sinkSupported.getProfileLevel(
                ResolutionType.valueOf(bestType), bestIndex,
                sinkProfile, sinkLevel);
        chosenProfile[0] =
                srcProfile[0].ordinal() < sinkProfile[0].ordinal() ? srcProfile[0] : sinkProfile[0];
        chosenLevel[0] =
                srcLevel[0].ordinal() < sinkLevel[0].ordinal() ? srcLevel[0] : sinkLevel[0];

        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("mNativeType : ").append(mNativeType).append('\n');
        sb.append("mNativeIndex: ").append(mNativeIndex).append('\n');
        sb.append("CEA : ").append(
                String.format("%32s", Integer.toBinaryString(mResolutionEnabled[0]))).append('\n');
        sb.append("VESA: ").append(
                String.format("%32s", Integer.toBinaryString(mResolutionEnabled[1]))).append('\n');
        sb.append("HH  : ").append(
                String.format("%32s", Integer.toBinaryString(mResolutionEnabled[2]))).append('\n');
        sb.append("mConfigs:\n").append(Arrays.deepToString(mConfigs));
        return sb.toString();
    }

    private ResolutionType mNativeType;
    private int mNativeIndex;

    private final int[] mResolutionEnabled = new int[ResolutionType.NUM_RESOLUTION_TYPES];
    private final Config[][] mConfigs = new Config[ResolutionType.NUM_RESOLUTION_TYPES][/*32*/];

    private static final Config[][] RESOLUTION_TABLE =
            new Config[/*NUM_RESOLUTION_TYPES*/][/*32*/]{
                    {
                            // CEA Resolutions
                            new Config(640, 480, 60, false, 0, 0),
                            new Config(720, 480, 60, false, 0, 0),
                            new Config(720, 480, 60, true, 0, 0),
                            new Config(720, 576, 50, false, 0, 0),
                            new Config(720, 576, 50, true, 0, 0),
                            new Config(1280, 720, 30, false, 0, 0),
                            new Config(1280, 720, 60, false, 0, 0),
                            new Config(1920, 1080, 30, false, 0, 0),
                            new Config(1920, 1080, 60, false, 0, 0),
                            new Config(1920, 1080, 60, true, 0, 0),
                            new Config(1280, 720, 25, false, 0, 0),
                            new Config(1280, 720, 50, false, 0, 0),
                            new Config(1920, 1080, 25, false, 0, 0),
                            new Config(1920, 1080, 50, false, 0, 0),
                            new Config(1920, 1080, 50, true, 0, 0),
                            new Config(1280, 720, 24, false, 0, 0),
                            new Config(1920, 1080, 24, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                    },
                    {
                            // VESA Resolutions
                            new Config(800, 600, 30, false, 0, 0),
                            new Config(800, 600, 60, false, 0, 0),
                            new Config(1024, 768, 30, false, 0, 0),
                            new Config(1024, 768, 60, false, 0, 0),
                            new Config(1152, 864, 30, false, 0, 0),
                            new Config(1152, 864, 60, false, 0, 0),
                            new Config(1280, 768, 30, false, 0, 0),
                            new Config(1280, 768, 60, false, 0, 0),
                            new Config(1280, 800, 30, false, 0, 0),
                            new Config(1280, 800, 60, false, 0, 0),
                            new Config(1360, 768, 30, false, 0, 0),
                            new Config(1360, 768, 60, false, 0, 0),
                            new Config(1366, 768, 30, false, 0, 0),
                            new Config(1366, 768, 60, false, 0, 0),
                            new Config(1280, 1024, 30, false, 0, 0),
                            new Config(1280, 1024, 60, false, 0, 0),
                            new Config(1400, 1050, 30, false, 0, 0),
                            new Config(1400, 1050, 60, false, 0, 0),
                            new Config(1440, 900, 30, false, 0, 0),
                            new Config(1440, 900, 60, false, 0, 0),
                            new Config(1600, 900, 30, false, 0, 0),
                            new Config(1600, 900, 60, false, 0, 0),
                            new Config(1600, 1200, 30, false, 0, 0),
                            new Config(1600, 1200, 60, false, 0, 0),
                            new Config(1680, 1024, 30, false, 0, 0),
                            new Config(1680, 1024, 60, false, 0, 0),
                            new Config(1680, 1050, 30, false, 0, 0),
                            new Config(1680, 1050, 60, false, 0, 0),
                            new Config(1920, 1200, 30, false, 0, 0),
                            new Config(1920, 1200, 60, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                    },
                    {
                            // HH Resolutions
                            new Config(800, 480, 30, false, 0, 0),
                            new Config(800, 480, 60, false, 0, 0),
                            new Config(854, 480, 30, false, 0, 0),
                            new Config(854, 480, 60, false, 0, 0),
                            new Config(864, 480, 30, false, 0, 0),
                            new Config(864, 480, 60, false, 0, 0),
                            new Config(640, 360, 30, false, 0, 0),
                            new Config(640, 360, 60, false, 0, 0),
                            new Config(960, 540, 30, false, 0, 0),
                            new Config(960, 540, 60, false, 0, 0),
                            new Config(848, 480, 30, false, 0, 0),
                            new Config(848, 480, 60, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                            new Config(0, 0, 0, false, 0, 0),
                    }
            };
}
