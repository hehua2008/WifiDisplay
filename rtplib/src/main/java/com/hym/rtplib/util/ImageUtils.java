/*
 * Copyright 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hym.rtplib.util;

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.Image.Plane;
import android.util.Size;

import java.nio.ByteBuffer;

/**
 * Package private utility class for hosting commonly used Image related methods.
 */
public class ImageUtils {
    public static final int Y16 = 0x20363159;
    public static final int RAW_DEPTH = 0x1002;

    private ImageUtils() {
    }

    /**
     * Only a subset of the formats defined in
     * {@link ImageFormat ImageFormat} and
     * {@link PixelFormat PixelFormat} are supported by
     * ImageReader. When reading RGB data from a surface, the formats defined in
     * {@link PixelFormat PixelFormat} can be used; when
     * reading YUV, JPEG or raw sensor data (for example, from the camera or video
     * decoder), formats from {@link ImageFormat ImageFormat}
     * are used.
     */
    public static int getNumPlanesForFormat(int format) {
        switch (format) {
            case ImageFormat.YV12:
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
                return 3;
            case ImageFormat.NV16:
                return 2;
            case PixelFormat.RGB_565:
            case PixelFormat.RGBA_8888:
            case PixelFormat.RGBX_8888:
            case PixelFormat.RGB_888:
            case ImageFormat.JPEG:
            case ImageFormat.YUY2:
            case ImageFormat.Y8:
            case Y16: // ImageFormat.Y16
            case ImageFormat.RAW_SENSOR:
            case ImageFormat.RAW_PRIVATE:
            case ImageFormat.RAW10:
            case ImageFormat.RAW12:
            case ImageFormat.DEPTH16:
            case ImageFormat.DEPTH_POINT_CLOUD:
            case RAW_DEPTH: // ImageFormat.RAW_DEPTH
                return 1;
            case ImageFormat.PRIVATE:
                return 0;
            default:
                throw new UnsupportedOperationException(
                        String.format("Invalid format specified %d", format));
        }
    }

    /**
     * <p>
     * Copy source image data to destination Image.
     * </p>
     * <p>
     * Only support the copy between two non-{@link ImageFormat#PRIVATE PRIVATE} format
     * images with same properties (format, size, etc.). The data from the
     * source image will be copied to the byteBuffers from the destination Image
     * starting from position zero, and the destination image will be rewound to
     * zero after copy is done.
     * </p>
     *
     * @param src The source image to be copied from.
     * @param dst The destination image to be copied to.
     * @throws IllegalArgumentException If the source and destination images
     *                                  have different format, or one of the images is not copyable.
     */
    public static void imageCopy(Image src, Image dst) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("Images should be non-null");
        }
        if (src.getFormat() != dst.getFormat()) {
            throw new IllegalArgumentException("Src and dst images should have the same format");
        }
        if (src.getFormat() == ImageFormat.PRIVATE ||
                dst.getFormat() == ImageFormat.PRIVATE) {
            throw new IllegalArgumentException("PRIVATE format images are not copyable");
        }
        if (src.getFormat() == ImageFormat.RAW_PRIVATE) {
            throw new IllegalArgumentException(
                    "Copy of RAW_OPAQUE format has not been implemented");
        }
        if (src.getFormat() == RAW_DEPTH /*ImageFormat.RAW_DEPTH*/) {
            throw new IllegalArgumentException(
                    "Copy of RAW_DEPTH format has not been implemented");
        }
        /*
        if (!(getOwner(dst) instanceof ImageWriter)) {
            throw new IllegalArgumentException("Destination image is not from ImageWriter. Only"
                    + " the images from ImageWriter are writable");
        }
        */
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        int dstWidth = dst.getWidth();
        int dstHeight = dst.getHeight();
        if (srcWidth != dstWidth || srcHeight != dstHeight) {
            throw new IllegalArgumentException("source image size " + srcWidth + 'x' + srcHeight
                    + " is different with destination image size " + dstWidth + 'x' + dstHeight);
        }

        Plane[] srcPlanes = src.getPlanes();
        Plane[] dstPlanes = dst.getPlanes();
        ByteBuffer srcBuffer = null;
        ByteBuffer dstBuffer = null;
        for (int i = 0; i < srcPlanes.length; i++) {
            int srcRowStride = srcPlanes[i].getRowStride();
            int dstRowStride = dstPlanes[i].getRowStride();
            srcBuffer = srcPlanes[i].getBuffer();
            dstBuffer = dstPlanes[i].getBuffer();
            if (!(srcBuffer.isDirect() && dstBuffer.isDirect())) {
                throw new IllegalArgumentException("Source and destination ByteBuffers must be"
                        + " direct byteBuffer!");
            }
            if (srcPlanes[i].getPixelStride() != dstPlanes[i].getPixelStride()) {
                throw new IllegalArgumentException("Source plane image pixel stride " +
                        srcPlanes[i].getPixelStride() +
                        " must be same as destination image pixel stride " +
                        dstPlanes[i].getPixelStride());
            }

            int srcPos = srcBuffer.position();
            srcBuffer.rewind();
            dstBuffer.rewind();
            if (srcRowStride == dstRowStride) {
                // Fast path, just copy the content if the byteBuffer all together.
                dstBuffer.put(srcBuffer);
            } else {
                // Source and destination images may have different alignment requirements,
                // therefore may have different strides. Copy row by row for such case.
                int srcOffset = srcBuffer.position();
                int dstOffset = dstBuffer.position();
                Size effectivePlaneSize = getEffectivePlaneSizeForImage(src, i);
                int srcByteCount = effectivePlaneSize.getWidth() * srcPlanes[i].getPixelStride();
                for (int row = 0; row < effectivePlaneSize.getHeight(); row++) {
                    if (row == effectivePlaneSize.getHeight() - 1) {
                        // Special case for NV21 backed YUV420_888: need handle the last row
                        // carefully to avoid memory corruption. Check if we have enough bytes to
                        // copy.
                        int remainingBytes = srcBuffer.remaining() - srcOffset;
                        if (srcByteCount > remainingBytes) {
                            srcByteCount = remainingBytes;
                        }
                    }
                    srcBuffer.position(srcOffset).limit(srcOffset + srcByteCount);
                    dstBuffer.position(dstOffset);
                    dstBuffer.put(srcBuffer);
                    srcOffset += srcRowStride;
                    dstOffset += dstRowStride;
                }
            }

            srcBuffer.position(srcPos);
            dstBuffer.rewind();
        }

        dst.setTimestamp(src.getTimestamp());
        dst.setCropRect(src.getCropRect());
    }

    /**
     * Return the estimated native allocation size in bytes based on width, height, format,
     * and number of images.
     *
     * <p>This is a very rough estimation and should only be used for native allocation
     * registration in VM so it can be accounted for during GC.</p>
     *
     * @param width     The width of the images.
     * @param height    The height of the images.
     * @param format    The format of the images.
     * @param numImages The number of the images.
     */
    public static int getEstimatedNativeAllocBytes(int width, int height, int format,
            int numImages) {
        double estimatedBytePerPixel;
        switch (format) {
            // 10x compression from RGB_888
            case ImageFormat.JPEG:
            case ImageFormat.DEPTH_POINT_CLOUD:
                estimatedBytePerPixel = 0.3;
                break;
            case ImageFormat.Y8:
                estimatedBytePerPixel = 1.0;
                break;
            case ImageFormat.RAW10:
                estimatedBytePerPixel = 1.25;
                break;
            case ImageFormat.YV12:
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.RAW12:
            case ImageFormat.PRIVATE: // A rough estimate because the real size is unknown.
                estimatedBytePerPixel = 1.5;
                break;
            case ImageFormat.NV16:
            case PixelFormat.RGB_565:
            case ImageFormat.YUY2:
            case Y16: // ImageFormat.Y16
            case RAW_DEPTH: // ImageFormat.RAW_DEPTH
            case ImageFormat.RAW_SENSOR:
            case ImageFormat.RAW_PRIVATE: // round estimate, real size is unknown
            case ImageFormat.DEPTH16:
                estimatedBytePerPixel = 2.0;
                break;
            case PixelFormat.RGB_888:
                estimatedBytePerPixel = 3.0;
                break;
            case PixelFormat.RGBA_8888:
            case PixelFormat.RGBX_8888:
                estimatedBytePerPixel = 4.0;
                break;
            default:
                throw new UnsupportedOperationException(
                        String.format("Invalid format specified %d", format));
        }

        return (int) (width * height * estimatedBytePerPixel * numImages);
    }

    private static Size getEffectivePlaneSizeForImage(Image image, int planeIdx) {
        switch (image.getFormat()) {
            case ImageFormat.YV12:
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
                if (planeIdx == 0) {
                    return new Size(image.getWidth(), image.getHeight());
                } else {
                    return new Size(image.getWidth() / 2, image.getHeight() / 2);
                }
            case ImageFormat.NV16:
                if (planeIdx == 0) {
                    return new Size(image.getWidth(), image.getHeight());
                } else {
                    return new Size(image.getWidth(), image.getHeight() / 2);
                }
            case PixelFormat.RGB_565:
            case PixelFormat.RGBA_8888:
            case PixelFormat.RGBX_8888:
            case PixelFormat.RGB_888:
            case ImageFormat.JPEG:
            case ImageFormat.YUY2:
            case ImageFormat.Y8:
            case Y16: // ImageFormat.Y16
            case ImageFormat.RAW_SENSOR:
            case ImageFormat.RAW10:
            case ImageFormat.RAW12:
            case RAW_DEPTH: // ImageFormat.RAW_DEPTH
                return new Size(image.getWidth(), image.getHeight());
            case ImageFormat.PRIVATE:
                return new Size(0, 0);
            default:
                throw new UnsupportedOperationException(
                        String.format("Invalid image format %d", image.getFormat()));
        }
    }

    /*
    private static Object getOwner(Image image) {
        try {
            @SuppressLint("SoonBlockedPrivateApi")
            Method method = Image.class.getDeclaredMethod("getOwner");
            method.setAccessible(true);
            return method.invoke(image);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
    */
}
