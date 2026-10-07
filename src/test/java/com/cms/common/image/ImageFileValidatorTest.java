package com.cms.common.image;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ImageFileValidatorTest {

    /** 본문 이미지 상한(ContentImageService와 같은 값) — 헤더 검사만. */
    private static final ImageFileValidator.Limits CONTENT = new ImageFileValidator.Limits(4096, 4096L * 4096, false);

    public static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    public static byte[] gif(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "gif", out);
        return out.toByteArray();
    }

    /** IHDR 바로 뒤에 올바른 CRC를 가진 acTL(APNG 애니메이션 제어) 청크를 끼워 넣는다. */
    public static byte[] apng(byte[] png) {
        int ihdrEnd = 8 + 4 + 4 + 13 + 4;
        byte[] type = "acTL".getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = ByteBuffer.allocate(8).putInt(2).putInt(0).array();
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length)
                .putInt(data.length).put(type).put(data).putInt((int) crc.getValue());
        byte[] result = new byte[png.length + chunk.capacity()];
        System.arraycopy(png, 0, result, 0, ihdrEnd);
        System.arraycopy(chunk.array(), 0, result, ihdrEnd, chunk.capacity());
        System.arraycopy(png, ihdrEnd, result, ihdrEnd + chunk.capacity(), png.length - ihdrEnd);
        return result;
    }

    /** GIF 논리 화면 크기(헤더 6~9바이트, 리틀엔디언)를 바꾼다. */
    public static byte[] withLogicalScreen(byte[] gif, int width, int height) {
        byte[] result = gif.clone();
        result[6] = (byte) width;
        result[7] = (byte) (width >> 8);
        result[8] = (byte) height;
        result[9] = (byte) (height >> 8);
        return result;
    }

    /** 첫 이미지 서술자(0x2C)의 왼쪽 위치를 바꾼다. */
    public static byte[] withFrameLeft(byte[] gif, int left) {
        byte[] result = gif.clone();
        int offset = 13;
        if ((result[10] & 0x80) != 0) {
            offset += 3 * (1 << ((result[10] & 0x07) + 1));
        }
        while (result[offset] == 0x21) { // 확장 블록 건너뛰기: 0x21 label [size data]* 0x00
            offset += 2;
            while (result[offset] != 0) {
                offset += (result[offset] & 0xFF) + 1;
            }
            offset++;
        }
        if (result[offset] != 0x2C) {
            throw new IllegalStateException("이미지 서술자를 찾지 못함");
        }
        result[offset + 1] = (byte) left;
        result[offset + 2] = (byte) (left >> 8);
        return result;
    }

    @Test
    @DisplayName("본문 상한: 1920x1080 스크린샷 크기 PNG는 통과한다(프로필 상한이면 거부되던 크기)")
    void screenshotSizePasses() throws IOException {
        assertThatCode(() -> ImageFileValidator.validate(png(1920, 1080), "image/png", CONTENT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("본문 상한: 한 변 4097px은 거부된다")
    void tooWideRejected() throws IOException {
        byte[] wide = png(4097, 1);

        assertThatThrownBy(() -> ImageFileValidator.validate(wide, "image/png", CONTENT))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("크기");
    }

    @Test
    @DisplayName("APNG(acTL 청크)는 단일 프레임 보고와 무관하게 애니메이션으로 거부된다")
    void apngRejected() throws IOException {
        byte[] apng = apng(png(4, 4));

        assertThatThrownBy(() -> ImageFileValidator.validate(apng, "image/png", CONTENT))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("애니메이션");
    }

    @Test
    @DisplayName("청크 길이가 파일 끝을 넘는 PNG는 해석 불가로 거부된다")
    void corruptedChunkLengthRejected() {
        byte[] broken = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0x7F, 0, 0, 0, 'I', 'H', 'D', 'R'};

        assertThatThrownBy(() -> ImageFileValidator.hasApngAnimationChunk(broken))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("일반 PNG에는 acTL이 없다")
    void plainPngHasNoAnimationChunk() throws IOException {
        org.assertj.core.api.Assertions.assertThat(ImageFileValidator.hasApngAnimationChunk(png(4, 4))).isFalse();
    }

    @Test
    @DisplayName("GIF 논리 화면이 상한을 넘으면 프레임이 1x1이어도 거부된다")
    void largeGifLogicalScreenRejected() throws IOException {
        byte[] bomb = withLogicalScreen(gif(1, 1), 5000, 5000);

        assertThatThrownBy(() -> ImageFileValidator.validate(bomb, "image/gif", CONTENT))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("크기");
    }

    @Test
    @DisplayName("GIF 프레임이 논리 화면 밖으로 나가면 거부된다")
    void gifFrameOutsideScreenRejected() throws IOException {
        byte[] outside = withFrameLeft(gif(4, 4), 10);

        assertThatThrownBy(() -> ImageFileValidator.validate(outside, "image/gif", CONTENT))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("정상 GIF는 통과한다")
    void plainGifPasses() throws IOException {
        assertThatCode(() -> ImageFileValidator.validate(gif(8, 8), "image/gif", CONTENT)).doesNotThrowAnyException();
    }
}
