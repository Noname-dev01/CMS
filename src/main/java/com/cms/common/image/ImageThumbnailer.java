package com.cms.common.image;

import com.cms.common.exception.InvalidRequestException;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 대표 이미지 썸네일 생성(PLAN-board-definition.md PR 5 D4). JDK {@code javax.imageio}만 쓰는 순수 함수라 상태가 없다 — 동시 실행 수 제한·저장·카운터는
 * 호출 서비스가 맡는다.
 *
 * <p>업로드 검증은 헤더만 보므로(전체 디코드 없음) 여기가 처음으로 픽셀을 읽는 곳이다. 그래서 <b>만들 수 없거나 원본과 다른 썸네일</b>이 저장되지 않게
 * 모든 위반을 {@link InvalidRequestException}(400)으로 거부한다: 픽셀 손상, 디코딩 경고(잘린 JPEG의 EOI 보충 등), CMYK JPEG, JPEG 계수 버퍼
 * 상한 초과, 긴 변 960 초과 인터레이스 PNG. 긴 변이 {@value #THUMB_MAX_SIDE} 이하인 작은 원본도 <b>전체 디코딩으로 검증</b>한 뒤 썸네일만 만들지 않는다
 * ({@link Optional#empty()}) — 크기에 따라 계약이 갈리지 않게.
 *
 * <p>메모리: 축소 디코딩({@code ceil(긴 변/960)})으로 반환 래스터를 긴 변 960 이하로 줄이고, 리더는 메타데이터를 무시한다(GIF 제외). JPEG는
 * 디코더가 원본 해상도 계수 버퍼(네이티브)를 만들 수 있어 디코딩 전에 추정해 {@link #MAX_JPEG_COEFFICIENT_BYTES}를 넘으면 거부한다. PNG는 허용 목록
 * 청크만 남겨 다시 조립한다(팔레트 PNG에는 {@code ignoreMetadata}가 적용되지 않아 압축 텍스트 청크가 누적 상한 없이 보관되기 때문).
 */
public final class ImageThumbnailer {

    /** 썸네일 긴 변 목표(px). 원본 긴 변이 이 값 이하이면 썸네일을 만들지 않는다. */
    public static final int THUMB_MAX_SIDE = 480;

    /** 이미지 한 장의 입력 바이트 상한 — 업로드 상한과 같다(DB를 직접 고친 행 방어). */
    public static final long MAX_INPUT_BYTES = 5L * 1024 * 1024;

    /** 업로드 검증과 같은 픽셀 상한(DB를 직접 고친 행 방어). */
    static final int MAX_DIMENSION = 4096;
    static final long MAX_TOTAL_PIXELS = 4096L * 4096;

    /** 축소 디코딩 후 긴 변이 이 값 이하가 되도록 계수를 고른다(ARGB 최대 약 3.5MiB). */
    static final int DECODE_TARGET_SIDE = 960;

    /** JPEG 디코더 계수 버퍼(네이티브) 추정 상한 — 4:2:0 4096²가 정확히 이 값이다. */
    static final long MAX_JPEG_COEFFICIENT_BYTES = 48L * 1024 * 1024;

    private static final float JPEG_QUALITY = 0.85f;

    public static final String FAILED_MESSAGE = "이 이미지로 대표 이미지를 만들 수 없습니다. 다른 이미지를 고르세요.";
    public static final String TOO_LARGE_MESSAGE = "이 이미지는 너무 커서 대표 이미지를 만들 수 없습니다. 더 작은 이미지를 고르세요.";

    /** 생성된 썸네일. {@code contentType}은 {@code image/jpeg}(JPEG 원본) 또는 {@code image/png}(PNG·GIF 원본). */
    public record Thumbnail(byte[] content, String contentType, int width, int height) {
    }

    private ImageThumbnailer() {
    }

    /**
     * @return 썸네일. 원본 긴 변이 {@value #THUMB_MAX_SIDE} 이하이면 empty(원본을 그대로 쓴다 — 단 디코딩 검증은 마쳤다)
     * @throws InvalidRequestException 만들 수 없는 이미지(문구는 {@link #FAILED_MESSAGE}·{@link #TOO_LARGE_MESSAGE})
     */
    public static Optional<Thumbnail> create(byte[] original, String contentType) {
        if (original == null || original.length == 0 || original.length > MAX_INPUT_BYTES || contentType == null) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
        return switch (contentType) {
            case "image/jpeg" -> createJpeg(original);
            case "image/png" -> createPng(original);
            case "image/gif" -> createGif(original);
            default -> throw new InvalidRequestException(FAILED_MESSAGE);
        };
    }

    // ------------------------------------------------------------------ JPEG

    private static Optional<Thumbnail> createJpeg(byte[] original) {
        JpegInfo info = scanJpeg(original);
        if (info.components() != 1 && info.components() != 3) {
            // JDK 21은 CMYK·YCCK도 디코딩하지만 브라우저마다 표시가 달라 썸네일 색이 원본과 어긋나기 쉽다 — 정책으로 거부
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
        requireDimensions(info.width(), info.height());
        if (estimateJpegCoefficientBytes(info.width(), info.height(), info.hSamp(), info.vSamp()) > MAX_JPEG_COEFFICIENT_BYTES) {
            throw new InvalidRequestException(TOO_LARGE_MESSAGE);
        }
        BufferedImage decoded = decode(original, "image/jpeg", Math.max(info.width(), info.height()), true);
        BufferedImage oriented = applyOrientation(decoded, info.orientation());
        return finish(oriented, "image/jpeg");
    }

    /** SOF·EXIF 방향을 한 번의 마커 순회로 읽는다. SOF가 없거나 구조가 깨졌으면 400. */
    static JpegInfo scanJpeg(byte[] data) {
        try {
            if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
                throw new InvalidRequestException(FAILED_MESSAGE);
            }
            int orientation = 1;
            int pos = 2;
            while (pos + 4 <= data.length) {
                if ((data[pos] & 0xFF) != 0xFF) {
                    throw new InvalidRequestException(FAILED_MESSAGE);
                }
                int marker = data[pos + 1] & 0xFF;
                if (marker == 0xFF) { // 채움 바이트
                    pos++;
                    continue;
                }
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { // 길이 없는 마커
                    pos += 2;
                    continue;
                }
                if (marker == 0xD9 || marker == 0xDA) { // EOI·SOS — SOF는 그 전에 있어야 한다
                    throw new InvalidRequestException(FAILED_MESSAGE);
                }
                int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                if (length < 2 || pos + 2 + length > data.length) {
                    throw new InvalidRequestException(FAILED_MESSAGE);
                }
                int start = pos + 4;
                int end = pos + 2 + length;
                if (marker == 0xE1 && orientation == 1) {
                    orientation = exifOrientation(data, start, end);
                }
                boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (sof) {
                    if (end - start < 6) {
                        throw new InvalidRequestException(FAILED_MESSAGE);
                    }
                    int height = ((data[start + 1] & 0xFF) << 8) | (data[start + 2] & 0xFF);
                    int width = ((data[start + 3] & 0xFF) << 8) | (data[start + 4] & 0xFF);
                    int count = data[start + 5] & 0xFF;
                    if (count < 1 || start + 6 + count * 3 > end) {
                        throw new InvalidRequestException(FAILED_MESSAGE);
                    }
                    int[] h = new int[count];
                    int[] v = new int[count];
                    for (int i = 0; i < count; i++) {
                        int hv = data[start + 6 + i * 3 + 1] & 0xFF;
                        h[i] = hv >> 4;
                        v[i] = hv & 0x0F;
                        if (h[i] < 1 || h[i] > 4 || v[i] < 1 || v[i] > 4) {
                            throw new InvalidRequestException(FAILED_MESSAGE);
                        }
                    }
                    return new JpegInfo(width, height, count, h, v, orientation);
                }
                pos = end;
            }
            throw new InvalidRequestException(FAILED_MESSAGE);
        } catch (IndexOutOfBoundsException e) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
    }

    /** APP1 Exif의 IFD0 Orientation(0x0112). 읽을 수 없거나 1..8 밖이면 1(회전 없음). */
    private static int exifOrientation(byte[] d, int start, int end) {
        try {
            if (end - start < 14 || d[start] != 'E' || d[start + 1] != 'x' || d[start + 2] != 'i' || d[start + 3] != 'f'
                    || d[start + 4] != 0 || d[start + 5] != 0) {
                return 1;
            }
            int tiff = start + 6;
            boolean little;
            if (d[tiff] == 'I' && d[tiff + 1] == 'I') {
                little = true;
            } else if (d[tiff] == 'M' && d[tiff + 1] == 'M') {
                little = false;
            } else {
                return 1;
            }
            if (u16(d, tiff + 2, little) != 0x002A) {
                return 1;
            }
            long ifd = tiff + (u32(d, tiff + 4, little));
            if (ifd < tiff || ifd + 2 > end) {
                return 1;
            }
            int entries = u16(d, (int) ifd, little);
            for (int i = 0; i < entries; i++) {
                long entry = ifd + 2 + 12L * i;
                if (entry + 12 > end) {
                    return 1;
                }
                if (u16(d, (int) entry, little) == 0x0112) {
                    int type = u16(d, (int) entry + 2, little);
                    long count = u32(d, (int) entry + 4, little);
                    if (type != 3 || count != 1) {
                        return 1;
                    }
                    int value = u16(d, (int) entry + 8, little);
                    return value >= 1 && value <= 8 ? value : 1;
                }
            }
            return 1;
        } catch (IndexOutOfBoundsException e) {
            return 1;
        }
    }

    private static int u16(byte[] d, int p, boolean little) {
        int a = d[p] & 0xFF;
        int b = d[p + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private static long u32(byte[] d, int p, boolean little) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int b = d[p + (little ? 3 - i : i)] & 0xFF;
            r = (r << 8) | b;
        }
        return r;
    }

    /**
     * libjpeg({@code jdcoefct.c})가 프로그레시브·다중 스캔 JPEG에 할당하는 DCT 계수 버퍼의 추정 바이트. 컴포넌트마다 블록 수(폭·높이)를 구한 뒤
     * 샘플링 계수의 배수로 다시 올림하고 블록당 128바이트(계수 64개 × 2바이트)를 곱한다.
     */
    static long estimateJpegCoefficientBytes(int width, int height, int[] h, int[] v) {
        int hMax = 1;
        int vMax = 1;
        for (int i = 0; i < h.length; i++) {
            hMax = Math.max(hMax, h[i]);
            vMax = Math.max(vMax, v[i]);
        }
        long total = 0;
        for (int i = 0; i < h.length; i++) {
            long blocksW = ceilDiv((long) width * h[i], (long) hMax * 8);
            long blocksH = ceilDiv((long) height * v[i], (long) vMax * 8);
            total += roundUp(blocksW, h[i]) * roundUp(blocksH, v[i]) * 128L;
        }
        return total;
    }

    record JpegInfo(int width, int height, int components, int[] hSamp, int[] vSamp, int orientation) {
    }

    // ------------------------------------------------------------------ PNG

    private static Optional<Thumbnail> createPng(byte[] original) {
        PngCleaned cleaned = cleanPng(original);
        requireDimensions(cleaned.width(), cleaned.height());
        int longSide = Math.max(cleaned.width(), cleaned.height());
        // 인터레이스 PNG는 축소 디코딩이 건너뛰는 Adam7 패스의 손상을 경고 없이 통과시킨다 — 긴 변 960 이하는 전부 디코딩, 초과는 거부
        if (cleaned.interlaced() && longSide > DECODE_TARGET_SIDE) {
            throw new InvalidRequestException(TOO_LARGE_MESSAGE);
        }
        BufferedImage decoded = decode(cleaned.content(), "image/png", cleaned.interlaced() ? DECODE_TARGET_SIDE : longSide, true);
        return finish(decoded, "image/png");
    }

    /**
     * PNG 청크를 길이 경계 검사하며 순회해 {@code IHDR·PLTE·tRNS·IDAT·IEND}만 남긴 바이트로 다시 조립한다(CRC는 원본 그대로 복사).
     * 구조 위반은 400이다.
     */
    static PngCleaned cleanPng(byte[] d) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (d.length < signature.length + 12) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
        for (int i = 0; i < signature.length; i++) {
            if (d[i] != signature[i]) {
                throw new InvalidRequestException(FAILED_MESSAGE);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, signature.length);
        int pos = signature.length;
        int width = 0;
        int height = 0;
        boolean interlaced = false;
        boolean first = true;
        boolean ended = false;
        boolean sawData = false;
        while (pos + 12 <= d.length) {
            long length = u32(d, pos, false);
            if (length < 0 || length > d.length - pos - 12L) {
                throw new InvalidRequestException(FAILED_MESSAGE);
            }
            String type = new String(d, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int total = (int) length + 12;
            if (first) {
                if (!"IHDR".equals(type) || length != 13) {
                    throw new InvalidRequestException(FAILED_MESSAGE);
                }
                width = (int) u32(d, pos + 8, false);
                height = (int) u32(d, pos + 12, false);
                interlaced = (d[pos + 8 + 12] & 0xFF) == 1;
                first = false;
            }
            switch (type) {
                case "IHDR", "PLTE", "tRNS", "IDAT", "IEND" -> out.write(d, pos, total);
                default -> {
                    // 텍스트·색 관리·보조 청크는 버린다
                }
            }
            if ("IDAT".equals(type)) {
                sawData = true;
            }
            pos += total;
            if ("IEND".equals(type)) {
                ended = true;
                break;
            }
        }
        if (first || !ended || !sawData || width <= 0 || height <= 0) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
        return new PngCleaned(out.toByteArray(), width, height, interlaced);
    }

    record PngCleaned(byte[] content, int width, int height, boolean interlaced) {
    }

    // ------------------------------------------------------------------ GIF

    private static Optional<Thumbnail> createGif(byte[] original) {
        // GIF는 논리 화면 캔버스에 프레임을 오프셋대로 합성한 결과가 브라우저가 그리는 모습이다 — 메타데이터가 필요해 ignoreMetadata를 쓰지 않는다
        AtomicBoolean warned = new AtomicBoolean(false);
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            ImageReader reader = pickReader(iis, "image/gif");
            try {
                reader.setInput(iis, true, false);
                reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
                int frameW = reader.getWidth(0);
                int frameH = reader.getHeight(0);
                NamedNodeMap screen = child(reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                NamedNodeMap frame = child(reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0"), "ImageDescriptor");
                int screenW = intAttr(screen, "logicalScreenWidth");
                int screenH = intAttr(screen, "logicalScreenHeight");
                int left = intAttr(frame, "imageLeftPosition");
                int top = intAttr(frame, "imageTopPosition");
                requireDimensions(screenW, screenH);
                requireDimensions(frameW, frameH);
                if ((long) left + frameW > screenW || (long) top + frameH > screenH) {
                    throw new InvalidRequestException(FAILED_MESSAGE);
                }
                int f = subsampling(Math.max(screenW, screenH));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(f, f, 0, 0);
                BufferedImage framePixels = reader.read(0, param);
                requireNoWarning(warned);
                BufferedImage canvas = new BufferedImage((int) ceilDiv(screenW, f), (int) ceilDiv(screenH, f), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = canvas.createGraphics();
                try {
                    g.drawImage(framePixels, left / f, top / f, null);
                } finally {
                    g.dispose();
                }
                return finish(canvas, "image/gif");
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidRequestException invalid) {
                throw invalid;
            }
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
    }

    private static NamedNodeMap child(Node root, String name) {
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (name.equals(node.getNodeName())) {
                return node.getAttributes();
            }
        }
        throw new InvalidRequestException(FAILED_MESSAGE);
    }

    private static int intAttr(NamedNodeMap attributes, String name) {
        Node attribute = attributes.getNamedItem(name);
        if (attribute == null) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
        return Integer.parseInt(attribute.getNodeValue());
    }

    // ------------------------------------------------------------------ 공통

    /** 메타데이터를 무시하고 축소 디코딩한다. 디코딩 중 경고가 하나라도 오면 실패로 본다. */
    private static BufferedImage decode(byte[] bytes, String contentType, int longSide, boolean ignoreMetadata) {
        AtomicBoolean warned = new AtomicBoolean(false);
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = pickReader(iis, contentType);
            try {
                reader.setInput(iis, true, ignoreMetadata);
                reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
                requireDimensions(reader.getWidth(0), reader.getHeight(0));
                int f = subsampling(longSide);
                ImageReadParam param = reader.getDefaultReadParam();
                if (f > 1) {
                    param.setSourceSubsampling(f, f, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                requireNoWarning(warned);
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidRequestException invalid) {
                throw invalid;
            }
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
    }

    private static ImageReader pickReader(ImageInputStream iis, String contentType) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
        if (!readers.hasNext()) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
        ImageReader reader = readers.next();
        try {
            String format = reader.getFormatName();
            String actual = format == null ? null : switch (format.toUpperCase(java.util.Locale.ROOT)) {
                case "PNG" -> "image/png";
                case "JPEG", "JPG" -> "image/jpeg";
                case "GIF" -> "image/gif";
                default -> null;
            };
            if (!contentType.equals(actual)) {
                reader.dispose();
                throw new InvalidRequestException(FAILED_MESSAGE);
            }
        } catch (IOException e) {
            reader.dispose();
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
        return reader;
    }

    private static void requireNoWarning(AtomicBoolean warned) {
        if (warned.get()) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
    }

    private static void requireDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_TOTAL_PIXELS) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
    }

    /** 디코딩 결과의 긴 변이 {@link #DECODE_TARGET_SIDE} 이하가 되는 가장 작은 정수 계수. */
    static int subsampling(int longSide) {
        return (int) Math.max(1, ceilDiv(longSide, DECODE_TARGET_SIDE));
    }

    /** 읽은 래스터가 작으면 empty, 아니면 긴 변 480으로 줄여 인코딩한다. */
    private static Optional<Thumbnail> finish(BufferedImage image, String originalType) {
        int w = image.getWidth();
        int h = image.getHeight();
        // 작은 원본 판정은 원본 크기 기준이어야 한다 — 축소 디코딩된 래스터가 480 이하라도 원본이 더 크면 썸네일이 필요하다.
        // 디코딩 계수 f로 줄어든 래스터는 긴 변이 481 이상 960 이하이거나(f=1) 원본이 f배 큰 경우라 아래 비교가 원본 판정과 같다.
        if (Math.max(w, h) <= THUMB_MAX_SIDE) {
            return Optional.empty();
        }
        double scale = (double) THUMB_MAX_SIDE / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        boolean jpeg = "image/jpeg".equals(originalType);
        BufferedImage scaled = new BufferedImage(tw, th, jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        try {
            return Optional.of(jpeg ? new Thumbnail(encodeJpeg(scaled), "image/jpeg", tw, th)
                    : new Thumbnail(encodePng(scaled), "image/png", tw, th));
        } catch (IOException e) {
            throw new InvalidRequestException(FAILED_MESSAGE);
        }
    }

    private static byte[] encodeJpeg(BufferedImage rgb) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("jpeg writer 없음");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (javax.imageio.stream.ImageOutputStream ios = new javax.imageio.stream.MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] encodePng(BufferedImage argb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(argb, "png", out)) {
            throw new IOException("png writer 없음");
        }
        return out.toByteArray();
    }

    /**
     * EXIF 방향(1..8)을 적용한 이미지를 돌려준다. 1이면 그대로. 5~8은 가로·세로가 바뀐다. 브라우저는 원본 {@code <img>}에 방향을 적용하므로
     * 적용하지 않으면 사진 썸네일이 원본과 다르게 눕는다.
     */
    static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8
        };
        BufferedImage dest = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dest.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return dest;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static long roundUp(long value, long multiple) {
        return ceilDiv(value, multiple) * multiple;
    }
}
