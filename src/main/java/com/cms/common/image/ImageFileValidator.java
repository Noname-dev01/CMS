package com.cms.common.image;

import com.cms.common.exception.InvalidRequestException;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 업로드 이미지 공용 검증(프로필 이미지·본문 이미지). JDK 표준 {@code javax.imageio}만 사용한다(신규 의존성 없음).
 * 상한·전체 디코드 여부는 호출자가 {@link Limits}로 정한다(PLAN-html-editor.md 쟁점 8).
 *
 * <p>검사 순서: 화이트리스트 MIME → 헤더 크기(변·총 픽셀) → GIF 논리 화면 크기·프레임 범위 → 단일 프레임(애니메이션 거부,
 * PNG는 APNG {@code acTL} 청크까지) → 선언 MIME과 실제 포맷 일치 → (선택) 전체 픽셀 디코드. 헤더 검사를 모두 통과한
 * 뒤에만 디코드해 decompression bomb을 방어한다. 위반은 항상 {@link InvalidRequestException}(400)이다.
 */
public final class ImageFileValidator {

    /** WebP는 JDK 표준 ImageIO가 지원하지 않아 제외한다. */
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/gif");

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /**
     * @param maxDimension   한 변 상한(px)
     * @param maxTotalPixels 총 픽셀 상한
     * @param fullDecode     헤더 검사 통과 후 실제 픽셀 디코드까지 할지
     */
    public record Limits(int maxDimension, long maxTotalPixels, boolean fullDecode) {
    }

    private ImageFileValidator() {
    }

    public static void validate(byte[] content, String declaredContentType, Limits limits) {
        if (declaredContentType == null || !ALLOWED_CONTENT_TYPES.contains(declaredContentType)) {
            throw new InvalidRequestException("이미지 파일만 업로드할 수 있습니다.");
        }

        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (iis == null) {
                throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                requireWithinLimits(reader.getWidth(0), reader.getHeight(0), limits);
                String actualMime = canonicalMimeFor(reader.getFormatName());
                if ("image/gif".equals(actualMime)) {
                    requireGifScreenWithinLimits(reader, limits);
                }
                // allowSearch=false는 GIF 등에서 -1을 반환할 수 있어 애니메이션 검사가 우회된다(JDK ImageReader 계약)
                if (reader.getNumImages(true) != 1) {
                    throw new InvalidRequestException("애니메이션 이미지는 지원하지 않습니다.");
                }
                // JDK PNG reader는 APNG를 1프레임으로 보고하므로 acTL 청크를 직접 확인한다(리뷰 R1-6)
                if ("image/png".equals(actualMime) && hasApngAnimationChunk(content)) {
                    throw new InvalidRequestException("애니메이션 이미지는 지원하지 않습니다.");
                }
                if (!declaredContentType.equals(actualMime)) {
                    throw new InvalidRequestException("파일 형식과 실제 이미지가 일치하지 않습니다.");
                }
                if (limits.fullDecode()) {
                    reader.read(0);
                }
            } catch (IOException | RuntimeException e) {
                if (e instanceof InvalidRequestException invalid) {
                    throw invalid;
                }
                throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
        }
    }

    private static void requireWithinLimits(long width, long height, Limits limits) {
        if (width <= 0 || height <= 0) {
            throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
        }
        if (width > limits.maxDimension() || height > limits.maxDimension()
                || width * height > limits.maxTotalPixels()) {
            throw new InvalidRequestException("이미지 크기가 너무 큽니다.");
        }
    }

    /**
     * GIF는 브라우저가 그리는 캔버스(논리 화면)가 프레임 크기와 별개다 — 1×1 프레임에 5000×5000 화면이면 {@code getWidth(0)}
     * 검사를 우회한다(리뷰 R3-3). 화면 크기에도 같은 상한을 걸고, 프레임이 화면 밖으로 나가면 거부한다.
     */
    private static void requireGifScreenWithinLimits(ImageReader reader, Limits limits) throws IOException {
        IIOMetadata streamMetadata = reader.getStreamMetadata();
        IIOMetadata imageMetadata = reader.getImageMetadata(0);
        if (streamMetadata == null || imageMetadata == null) {
            throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
        }
        NamedNodeMap screen = child(streamMetadata.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
        NamedNodeMap frame = child(imageMetadata.getAsTree("javax_imageio_gif_image_1.0"), "ImageDescriptor");
        int screenWidth = intAttr(screen, "logicalScreenWidth");
        int screenHeight = intAttr(screen, "logicalScreenHeight");
        requireWithinLimits(screenWidth, screenHeight, limits);
        long right = (long) intAttr(frame, "imageLeftPosition") + intAttr(frame, "imageWidth");
        long bottom = (long) intAttr(frame, "imageTopPosition") + intAttr(frame, "imageHeight");
        if (right > screenWidth || bottom > screenHeight) {
            throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
        }
    }

    private static NamedNodeMap child(Node root, String name) {
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (name.equals(node.getNodeName())) {
                return node.getAttributes();
            }
        }
        throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
    }

    private static int intAttr(NamedNodeMap attributes, String name) {
        Node attribute = attributes.getNamedItem(name);
        if (attribute == null) {
            throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
        }
        return Integer.parseInt(attribute.getNodeValue());
    }

    /**
     * PNG 청크를 순서대로 읽어 첫 {@code IDAT} 전에 {@code acTL}(APNG 애니메이션 제어)이 있는지 본다. 청크 길이가 남은
     * 바이트를 넘는 등 구조가 깨졌으면 해석 불가로 거부한다.
     */
    static boolean hasApngAnimationChunk(byte[] content) {
        if (content.length < PNG_SIGNATURE.length) {
            throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
        }
        int offset = PNG_SIGNATURE.length;
        while (offset + 8 <= content.length) {
            long length = ((content[offset] & 0xFFL) << 24) | ((content[offset + 1] & 0xFFL) << 16)
                    | ((content[offset + 2] & 0xFFL) << 8) | (content[offset + 3] & 0xFFL);
            String type = new String(content, offset + 4, 4, StandardCharsets.ISO_8859_1);
            if ("acTL".equals(type)) {
                return true;
            }
            if ("IDAT".equals(type) || "IEND".equals(type)) {
                return false;
            }
            long next = offset + 12L + length;
            if (next > content.length) {
                throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
            }
            offset = (int) next;
        }
        throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
    }

    private static String canonicalMimeFor(String formatName) {
        if (formatName == null) {
            return null;
        }
        return switch (formatName.toUpperCase(Locale.ROOT)) {
            case "PNG" -> "image/png";
            case "JPEG", "JPG" -> "image/jpeg";
            case "GIF" -> "image/gif";
            default -> null;
        };
    }
}
