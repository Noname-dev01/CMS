package com.cms.admin.member.service;

import com.cms.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ProfileImageValidator는 업로드(AdminMemberService)·마이그레이션(ProfileImageMigrationRunner)
 * 양쪽이 공유하는 검증 로직이라 여기서 독립적으로 검증한다(adversarial-review/plan/
 * PLAN-profile-image-storage.md 쟁점 3).
 */
class ProfileImageValidatorTest {

    private byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] singleFrameGifBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", out);
        return out.toByteArray();
    }

    private byte[] animatedGifBytes() throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersBySuffix("gif");
        ImageWriter gifWriter = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            gifWriter.setOutput(ios);
            gifWriter.prepareWriteSequence(null);
            BufferedImage frame = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            IIOImage iioImage = new IIOImage(frame, null, null);
            gifWriter.writeToSequence(iioImage, gifWriter.getDefaultWriteParam());
            gifWriter.writeToSequence(iioImage, gifWriter.getDefaultWriteParam());
            gifWriter.endWriteSequence();
        } finally {
            gifWriter.dispose();
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("정상 PNG는 통과한다")
    void validPng_passes() throws IOException {
        assertDoesNotThrow(() -> ProfileImageValidator.validate(pngBytes(10, 10), "image/png"));
    }

    @Test
    @DisplayName("단일 프레임 GIF는 통과한다")
    void singleFrameGif_passes() throws IOException {
        assertDoesNotThrow(() -> ProfileImageValidator.validate(singleFrameGifBytes(), "image/gif"));
    }

    @Test
    @DisplayName("화이트리스트 밖 MIME(webp)은 거부된다")
    void webpMime_rejected() {
        assertThrows(InvalidRequestException.class,
                () -> ProfileImageValidator.validate(new byte[]{1, 2, 3}, "image/webp"));
    }

    @Test
    @DisplayName("화이트리스트 밖 MIME(svg)은 거부된다")
    void svgMime_rejected() {
        assertThrows(InvalidRequestException.class,
                () -> ProfileImageValidator.validate("<svg></svg>".getBytes(), "image/svg+xml"));
    }

    @Test
    @DisplayName("변 길이 상한(2000px) 초과 이미지는 거부된다")
    void tooWide_rejected() throws IOException {
        byte[] bytes = pngBytes(2001, 10);
        assertThrows(InvalidRequestException.class, () -> ProfileImageValidator.validate(bytes, "image/png"));
    }

    @Test
    @DisplayName("변 길이는 상한 이하지만 총 픽셀이 상한(200만)을 넘으면 거부된다")
    void totalPixelBudgetExceeded_rejected() throws IOException {
        // 1500 x 1500 = 2,250,000 > 2,000,000, 두 변 모두 2000px 이하
        byte[] bytes = pngBytes(1500, 1500);
        assertThrows(InvalidRequestException.class, () -> ProfileImageValidator.validate(bytes, "image/png"));
    }

    @Test
    @DisplayName("다중 프레임(애니메이션) GIF는 거부된다")
    void animatedGif_rejected() throws IOException {
        byte[] bytes = animatedGifBytes();
        assertThrows(InvalidRequestException.class, () -> ProfileImageValidator.validate(bytes, "image/gif"));
    }

    @Test
    @DisplayName("선언 MIME과 실제 이미지 포맷이 다르면 거부된다(PNG를 image/jpeg로 선언)")
    void declaredMimeMismatch_rejected() throws IOException {
        byte[] pngBytes = pngBytes(10, 10);
        assertThrows(InvalidRequestException.class, () -> ProfileImageValidator.validate(pngBytes, "image/jpeg"));
    }

    @Test
    @DisplayName("이미지로 해석할 수 없는 바이트는 거부된다")
    void unparsableBytes_rejected() {
        assertThrows(InvalidRequestException.class,
                () -> ProfileImageValidator.validate("not an image".getBytes(), "image/png"));
    }
}
