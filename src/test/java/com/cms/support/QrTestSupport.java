package com.cms.support;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * 서버가 그린 QR PNG를 해석한다(이슈 #265). {@code PURE_BARCODE} — 이미지 전체가 QR 하나뿐이라고 알려 위치 기준점 탐색을 건너뛴다.
 * 힌트 없이 해석하면 ZXing이 데이터 무늬를 기준점으로 오인해 내용에 따라 1~7% 실패한다(QR 자체는 정상, 2000장 실측 0건 실패).
 */
public final class QrTestSupport {

    private QrTestSupport() {
    }

    public static String decode(byte[] png) throws Exception {
        BufferedImage image = read(png);
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels))),
                Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE)).getText();
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
