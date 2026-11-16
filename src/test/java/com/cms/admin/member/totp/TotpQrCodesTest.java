package com.cms.admin.member.totp;

import com.cms.support.QrTestSupport;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 등록 QR 해석 회귀 시험(이슈 #265). ZXing의 위치 기준점 탐색은 서버가 그린 깨끗한 QR에서 데이터 무늬를 기준점으로 오인해
 * 내용에 따라 1~7% 해석에 실패한다(2000장 실측). QR 자체는 정상이다 — 같은 QR 20장을 Google Authenticator가 모두 등록했다.
 */
class TotpQrCodesTest {

    /** 실측에서 힌트 없는 해석이 실패한 실제 형식의 URI(비율을 바꿔 다시 그려도 실패한 경우) — 시험용 무작위 비밀값 */
    private static final String DETECTOR_CONFUSING_URI = "otpauth://totp/CMS:totp-api-1747165459587300-669"
            + "?secret=DATBIFKEF7Y66ZEDR3CIQVUS6S7NYC42&issuer=CMS&algorithm=SHA1&digits=6&period=30";

    @Test
    @DisplayName("#265: 검출기가 기준점을 오인하는 내용의 QR도 시험 해석 방식으로 원문 그대로 읽힌다")
    void decodesQrThatConfusesFinderPatternSearch() throws Exception {
        byte[] png = TotpQrCodes.png(DETECTOR_CONFUSING_URI);

        assertThat(QrTestSupport.decode(png)).isEqualTo(DETECTOR_CONFUSING_URI);
        // 전제: 힌트 없는 해석은 이 QR에서 실패한다 — ZXing이 바뀌어 통과하게 되면 이 시험이 지키는 입력이 사라졌다는 뜻이다
        assertThatThrownBy(() -> decodeWithoutHints(png)).isInstanceOf(Exception.class);
    }

    private static String decodeWithoutHints(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)))).getText();
    }
}
