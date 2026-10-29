package com.cms.common.image;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLAN-board-definition.md PR 5 D4 — 썸네일 생성기의 크기·형식·방향·방어 규칙. */
class ImageThumbnailerTest {

    // ------------------------------------------------------------ 픽스처

    private static byte[] write(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static BufferedImage noise(int w, int h, long seed) {
        Random random = new Random(seed);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return image;
    }

    /** 왼쪽 절반 빨강, 오른쪽 절반 파랑. */
    private static BufferedImage redBlue(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE);
        g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        return image;
    }

    private static byte[] interlacedPng(int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** SOI 바로 뒤에 EXIF Orientation 하나를 가진 APP1을 끼운다(빅엔디언). */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 0x002A).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);
        return insertApp1(jpeg, concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiff.array()));
    }

    private static byte[] insertApp1(byte[] jpeg, byte[] payload) {
        ByteBuffer segment = ByteBuffer.allocate(4 + payload.length);
        segment.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (payload.length + 2)).put(payload);
        return concat(java.util.Arrays.copyOfRange(jpeg, 0, 2), segment.array(), java.util.Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** SOF0만 있는 합성 JPEG — 정책 검사(디코딩 전)만 시험한다. {@code hv}는 컴포넌트별 (H&lt;&lt;4)|V. */
    private static byte[] syntheticJpeg(int width, int height, int... hv) {
        ByteBuffer b = ByteBuffer.allocate(2 + 2 + 2 + 6 + hv.length * 3);
        b.put((byte) 0xFF).put((byte) 0xD8).put((byte) 0xFF).put((byte) 0xC0);
        b.putShort((short) (8 + hv.length * 3)).put((byte) 8).putShort((short) height).putShort((short) width).put((byte) hv.length);
        for (int i = 0; i < hv.length; i++) {
            b.put((byte) (i + 1)).put((byte) hv[i]).put((byte) 0);
        }
        return b.array();
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(t).put(data).putInt((int) crc.getValue()).array();
    }

    private static byte[] insertAfterIhdr(byte[] png, byte[] chunks) {
        int ihdrEnd = 8 + 4 + 4 + 13 + 4;
        return concat(java.util.Arrays.copyOfRange(png, 0, ihdrEnd), chunks, java.util.Arrays.copyOfRange(png, ihdrEnd, png.length));
    }

    private static byte[] zTxt(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buffer = new byte[raw.length + 64];
        int n = deflater.deflate(buffer);
        deflater.end();
        return pngChunk("zTXt", concat("k\0\0".getBytes(StandardCharsets.ISO_8859_1), java.util.Arrays.copyOf(buffer, n)));
    }

    /** IDAT 페이로드의 앞 절반만 남기고(청크 길이·CRC는 새로 계산) 나머지 구조는 유지한다 — 구조는 정상이고 압축 스트림만 잘린 PNG. */
    private static byte[] truncateIdat(byte[] png) {
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        ByteArrayOutputStream before = new ByteArrayOutputStream();
        ByteArrayOutputStream after = new ByteArrayOutputStream();
        before.write(png, 0, 8);
        int pos = 8;
        boolean seenIdat = false;
        while (pos + 12 <= png.length) {
            int length = ByteBuffer.wrap(png, pos, 4).getInt();
            String type = new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream target = seenIdat && !"IDAT".equals(type) ? after : before;
            if ("IDAT".equals(type)) {
                idat.write(png, pos + 8, length);
                seenIdat = true;
            } else {
                target.write(png, pos, length + 12);
            }
            pos += length + 12;
        }
        byte[] all = idat.toByteArray();
        byte[] half = java.util.Arrays.copyOf(all, all.length / 2);
        return concat(before.toByteArray(), pngChunk("IDAT", half), after.toByteArray());
    }

    private static Optional<ImageThumbnailer.Thumbnail> create(byte[] bytes, String type) {
        return ImageThumbnailer.create(bytes, type);
    }

    private static void assertRejected(byte[] bytes, String type, String message) {
        assertThatThrownBy(() -> create(bytes, type)).isInstanceOf(InvalidRequestException.class).hasMessage(message);
    }

    // ------------------------------------------------------------ 크기·형식

    @Test
    @DisplayName("큰 PNG는 긴 변 480으로 줄어 PNG가 되고, 작은 PNG는 검증만 하고 empty")
    void pngSizes() throws Exception {
        ImageThumbnailer.Thumbnail thumb = create(write(new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB), "png"), "image/png").orElseThrow();
        assertThat(thumb.contentType()).isEqualTo("image/png");
        assertThat(thumb.width()).isEqualTo(480);
        assertThat(thumb.height()).isEqualTo(240);
        BufferedImage decoded = decode(thumb.content());
        assertThat(decoded.getWidth()).isEqualTo(480);
        assertThat(decoded.getHeight()).isEqualTo(240);

        assertThat(create(write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png"), "image/png")).isEmpty();
        assertThat(create(write(new BufferedImage(480, 480, BufferedImage.TYPE_INT_RGB), "png"), "image/png")).isEmpty();
        assertThat(create(write(new BufferedImage(481, 100, BufferedImage.TYPE_INT_RGB), "png"), "image/png")).isPresent();
    }

    @Test
    @DisplayName("JPEG 원본은 JPEG 썸네일이 된다")
    void jpegStaysJpeg() throws Exception {
        ImageThumbnailer.Thumbnail thumb = create(write(redBlue(1000, 500), "jpg"), "image/jpeg").orElseThrow();
        assertThat(thumb.contentType()).isEqualTo("image/jpeg");
        assertThat(decode(thumb.content()).getWidth()).isEqualTo(480);
        assertThat(create(write(redBlue(300, 200), "jpg"), "image/jpeg")).isEmpty();
    }

    @Test
    @DisplayName("4096 같은 큰 이미지는 축소 디코딩 후에도 긴 변 480으로 만들어진다")
    void veryLargeImage() throws Exception {
        ImageThumbnailer.Thumbnail thumb = create(write(new BufferedImage(4000, 1000, BufferedImage.TYPE_INT_RGB), "png"), "image/png").orElseThrow();
        assertThat(thumb.width()).isEqualTo(480);
        assertThat(thumb.height()).isEqualTo(120);
    }

    @Test
    @DisplayName("투명 PNG는 알파가 보존된다")
    void pngAlphaPreserved() throws Exception {
        BufferedImage image = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(500, 0, 500, 1000);
        g.dispose();
        BufferedImage thumb = decode(create(write(image, "png"), "image/png").orElseThrow().content());
        assertThat(new Color(thumb.getRGB(20, 240), true).getAlpha()).isZero();
        assertThat(new Color(thumb.getRGB(460, 240), true).getAlpha()).isEqualTo(255);
    }

    @Test
    @DisplayName("tRNS 팔레트 PNG의 투명도가 청크 정리 뒤에도 유지된다")
    void paletteTransparencyKept() throws Exception {
        byte[] r = {0, (byte) 255};
        byte[] gb = {0, 0};
        byte[] a = {0, (byte) 255};
        IndexColorModel model = new IndexColorModel(8, 2, r, gb, gb, a);
        BufferedImage image = new BufferedImage(1000, 1000, BufferedImage.TYPE_BYTE_INDEXED, model);
        for (int y = 0; y < 1000; y++) {
            for (int x = 500; x < 1000; x++) {
                image.getRaster().setSample(x, y, 0, 1);
            }
        }
        BufferedImage thumb = decode(create(write(image, "png"), "image/png").orElseThrow().content());
        assertThat(new Color(thumb.getRGB(20, 240), true).getAlpha()).isZero();
        assertThat(new Color(thumb.getRGB(460, 240), true).getAlpha()).isEqualTo(255);
    }

    // ------------------------------------------------------------ EXIF 방향

    @Test
    @DisplayName("EXIF 방향 6(시계 방향 90°)은 가로·세로가 바뀌고 왼쪽이 위로 간다")
    void exifRotate90() throws Exception {
        byte[] jpeg = withExifOrientation(write(redBlue(1000, 500), "jpg"), 6);
        ImageThumbnailer.Thumbnail thumb = create(jpeg, "image/jpeg").orElseThrow();
        assertThat(thumb.width()).isEqualTo(240);
        assertThat(thumb.height()).isEqualTo(480);
        BufferedImage decoded = decode(thumb.content());
        assertThat(new Color(decoded.getRGB(120, 10)).getRed()).isGreaterThan(200);
        assertThat(new Color(decoded.getRGB(120, 470)).getBlue()).isGreaterThan(200);
    }

    @Test
    @DisplayName("EXIF 방향 3(180°)은 좌우가 뒤바뀐다")
    void exifRotate180() throws Exception {
        byte[] jpeg = withExifOrientation(write(redBlue(1000, 500), "jpg"), 3);
        BufferedImage decoded = decode(create(jpeg, "image/jpeg").orElseThrow().content());
        assertThat(new Color(decoded.getRGB(10, 120)).getBlue()).isGreaterThan(200);
        assertThat(new Color(decoded.getRGB(470, 120)).getRed()).isGreaterThan(200);
    }

    @Test
    @DisplayName("EXIF 방향 8(270°)은 왼쪽이 아래로 간다, 2(좌우 반전)는 좌우만 뒤바뀐다")
    void exifRotate270AndMirror() throws Exception {
        BufferedImage rotated = decode(create(withExifOrientation(write(redBlue(1000, 500), "jpg"), 8), "image/jpeg").orElseThrow().content());
        assertThat(new Color(rotated.getRGB(120, 470)).getRed()).isGreaterThan(200);
        assertThat(new Color(rotated.getRGB(120, 10)).getBlue()).isGreaterThan(200);
        BufferedImage mirrored = decode(create(withExifOrientation(write(redBlue(1000, 500), "jpg"), 2), "image/jpeg").orElseThrow().content());
        assertThat(new Color(mirrored.getRGB(10, 120)).getBlue()).isGreaterThan(200);
    }

    @Test
    @DisplayName("깨진 EXIF는 방향 1(회전 없음)로 보고 실패하지 않는다")
    void brokenExifIsIgnored() throws Exception {
        byte[] jpeg = insertApp1(write(redBlue(1000, 500), "jpg"), concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), new byte[]{'M', 'M', 0, 0x2A, 0x7F, 0x7F, 0x7F, 0x7F, 1, 2}));
        ImageThumbnailer.Thumbnail thumb = create(jpeg, "image/jpeg").orElseThrow();
        assertThat(thumb.width()).isEqualTo(480);
        assertThat(thumb.height()).isEqualTo(240);
    }

    // ------------------------------------------------------------ 손상·정책 거부

    @Test
    @DisplayName("압축 스트림이 잘린 PNG는 작은 것·큰 것 모두 400")
    void truncatedPngRejected() throws Exception {
        assertRejected(truncateIdat(write(noise(100, 100, 1), "png")), "image/png", ImageThumbnailer.FAILED_MESSAGE);
        assertRejected(truncateIdat(write(noise(900, 900, 2), "png")), "image/png", ImageThumbnailer.FAILED_MESSAGE);
    }

    @Test
    @DisplayName("엔트로피 데이터가 잘린 JPEG는 작은 것·큰 것 모두 400(디코딩 경고도 실패)")
    void truncatedJpegRejected() throws Exception {
        for (int size : new int[]{200, 1200}) {
            byte[] jpeg = write(noise(size, size, 3), "jpg");
            assertRejected(java.util.Arrays.copyOf(jpeg, jpeg.length / 2), "image/jpeg", ImageThumbnailer.FAILED_MESSAGE);
        }
    }

    @Test
    @DisplayName("CMYK(4컴포넌트) JPEG는 디코딩 전에 정책으로 400")
    void cmykRejectedByPolicy() {
        assertRejected(syntheticJpeg(100, 100, 0x11, 0x11, 0x11, 0x11), "image/jpeg", ImageThumbnailer.FAILED_MESSAGE);
    }

    @Test
    @DisplayName("계수 버퍼 추정이 48MiB를 넘는 JPEG는 디코딩 전에 400")
    void coefficientBufferLimit() {
        // 4:4:4 4096² = 96MiB
        assertRejected(syntheticJpeg(4096, 4096, 0x11, 0x11, 0x11), "image/jpeg", ImageThumbnailer.TOO_LARGE_MESSAGE);
        // 4:2:2 3073×4073 — 샘플링 계수 패딩까지 세면 50,396,160 > 50,331,648
        assertRejected(syntheticJpeg(3073, 4073, 0x21, 0x11, 0x11), "image/jpeg", ImageThumbnailer.TOO_LARGE_MESSAGE);
    }

    @Test
    @DisplayName("계수 버퍼 추정식: 4:2:0 4096²는 정확히 48MiB, 4:2:2 3073×4073은 패딩으로 초과, 4:4:4 경계는 2896/2897")
    void coefficientEstimate() {
        assertThat(ImageThumbnailer.estimateJpegCoefficientBytes(4096, 4096, new int[]{2, 1, 1}, new int[]{2, 1, 1})).isEqualTo(50_331_648L);
        assertThat(ImageThumbnailer.estimateJpegCoefficientBytes(3073, 4073, new int[]{2, 1, 1}, new int[]{1, 1, 1})).isEqualTo(50_396_160L);
        assertThat(ImageThumbnailer.estimateJpegCoefficientBytes(2896, 2896, new int[]{1, 1, 1}, new int[]{1, 1, 1}))
                .isLessThanOrEqualTo(ImageThumbnailer.MAX_JPEG_COEFFICIENT_BYTES);
        assertThat(ImageThumbnailer.estimateJpegCoefficientBytes(2897, 2897, new int[]{1, 1, 1}, new int[]{1, 1, 1}))
                .isGreaterThan(ImageThumbnailer.MAX_JPEG_COEFFICIENT_BYTES);
    }

    @Test
    @DisplayName("샘플링 계수가 1..4 밖이거나 SOF가 없는 JPEG는 400")
    void invalidSofRejected() {
        assertRejected(syntheticJpeg(100, 100, 0x01, 0x11, 0x11), "image/jpeg", ImageThumbnailer.FAILED_MESSAGE);
        assertRejected(syntheticJpeg(100, 100, 0x51, 0x11, 0x11), "image/jpeg", ImageThumbnailer.FAILED_MESSAGE);
        assertRejected(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}, "image/jpeg", ImageThumbnailer.FAILED_MESSAGE);
    }

    @Test
    @DisplayName("인터레이스 PNG: 긴 변 960 초과는 400, 960 이하는 전부 디코딩해 썸네일을 만든다")
    void interlacedPng() throws Exception {
        assertRejected(interlacedPng(1000, 1000), "image/png", ImageThumbnailer.TOO_LARGE_MESSAGE);
        ImageThumbnailer.Thumbnail thumb = create(interlacedPng(900, 900), "image/png").orElseThrow();
        assertThat(thumb.width()).isEqualTo(480);
    }

    @Test
    @DisplayName("압축 텍스트 청크가 많은 팔레트 PNG는 청크를 정리해 정상 처리된다(1×1 포함)")
    void textChunksAreStripped() throws Exception {
        byte[] r = {0, (byte) 255};
        IndexColorModel model = new IndexColorModel(8, 2, r, r, r);
        byte[] small = write(new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_INDEXED, model), "png");
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        byte[] big = new byte[256 * 1024];
        for (int i = 0; i < 300; i++) {
            chunks.writeBytes(zTxt(big));
        }
        byte[] noisy = insertAfterIhdr(small, chunks.toByteArray());
        assertThat(new String(ImageThumbnailer.cleanPng(noisy).content(), StandardCharsets.ISO_8859_1)).doesNotContain("zTXt");
        assertThat(create(noisy, "image/png")).isEmpty();
    }

    @Test
    @DisplayName("PNG 구조가 깨졌거나 IHDR/IEND가 없으면 400")
    void brokenPngStructure() throws Exception {
        byte[] png = write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png");
        assertRejected(java.util.Arrays.copyOf(png, png.length - 12), "image/png", ImageThumbnailer.FAILED_MESSAGE); // IEND 없음
        byte[] badLength = png.clone();
        badLength[8] = 0x7F; // 첫 청크 길이를 크게
        assertRejected(badLength, "image/png", ImageThumbnailer.FAILED_MESSAGE);
        assertRejected("not a png at all, just text".getBytes(StandardCharsets.ISO_8859_1), "image/png", ImageThumbnailer.FAILED_MESSAGE);
    }

    @Test
    @DisplayName("GIF는 논리 화면 캔버스에 프레임을 오프셋대로 합성한다 — 작은 프레임·큰 캔버스가 생략되지 않는다")
    void gifLogicalScreen() throws Exception {
        byte[] gif = ImageFileValidatorTest.withFrameLeft(ImageFileValidatorTest.withLogicalScreen(ImageFileValidatorTest.gif(100, 100), 1000, 1000), 400);
        ImageThumbnailer.Thumbnail thumb = create(gif, "image/gif").orElseThrow();
        assertThat(thumb.contentType()).isEqualTo("image/png");
        assertThat(thumb.width()).isEqualTo(480);
        assertThat(thumb.height()).isEqualTo(480);
        BufferedImage decoded = decode(thumb.content());
        assertThat(new Color(decoded.getRGB(216, 20), true).getAlpha()).isEqualTo(255); // 프레임 위치(400..500 → 192..240)
        assertThat(new Color(decoded.getRGB(20, 300), true).getAlpha()).isZero();      // 프레임 밖은 투명
        // 화면이 작으면 empty
        assertThat(create(ImageFileValidatorTest.gif(100, 100), "image/gif")).isEmpty();
    }

    @Test
    @DisplayName("빈 입력·5MB 초과·미지원 형식은 400")
    void invalidInputs() {
        assertRejected(new byte[0], "image/png", ImageThumbnailer.FAILED_MESSAGE);
        assertRejected(new byte[(int) ImageThumbnailer.MAX_INPUT_BYTES + 1], "image/png", ImageThumbnailer.FAILED_MESSAGE);
        assertRejected(new byte[]{1, 2, 3}, "image/webp", ImageThumbnailer.FAILED_MESSAGE);
        assertRejected(new byte[]{1, 2, 3}, null, ImageThumbnailer.FAILED_MESSAGE);
    }

    @Test
    @DisplayName("선언한 형식과 실제 형식이 다르면 400")
    void formatMismatch() throws Exception {
        assertRejected(write(new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB), "png"), "image/gif", ImageThumbnailer.FAILED_MESSAGE);
    }

    @Test
    @DisplayName("축소 디코딩 계수는 ceil(긴 변/960)")
    void subsamplingFactor() {
        assertThat(ImageThumbnailer.subsampling(480)).isEqualTo(1);
        assertThat(ImageThumbnailer.subsampling(960)).isEqualTo(1);
        assertThat(ImageThumbnailer.subsampling(961)).isEqualTo(2);
        assertThat(ImageThumbnailer.subsampling(1919)).isEqualTo(2);
        assertThat(ImageThumbnailer.subsampling(4096)).isEqualTo(5);
    }
}
