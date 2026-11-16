package com.cms.admin.member.totp;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpAuthUriTest {

    @Test
    @DisplayName("Key Uri Format: otpauth://totp/{issuer}:{account}?secret=…&issuer=…&algorithm=SHA1&digits=6&period=30")
    void format() {
        assertThat(OtpAuthUri.build("CMS", "admin", "JBSWY3DPEHPK3PXP"))
                .isEqualTo("otpauth://totp/CMS:admin?secret=JBSWY3DPEHPK3PXP&issuer=CMS&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    @DisplayName("계정 이름은 RFC 3986으로 인코딩한다 — 공백은 %20(폼 인코딩 + 아님), +는 %2B, 한글은 UTF-8 퍼센트, 콜론은 _로 치환")
    void encodesAccountName() {
        assertThat(OtpAuthUri.build("My CMS", "a b+c:d한", "AAAA"))
                .isEqualTo("otpauth://totp/My%20CMS:a%20b%2Bc_d%ED%95%9C?secret=AAAA&issuer=My%20CMS&algorithm=SHA1&digits=6&period=30");
    }

    @ParameterizedTest
    @DisplayName("issuer는 비어 있거나 콜론을 포함하면 거부한다(레이블 구분자와 충돌)")
    @ValueSource(strings = {"", " ", "C:MS"})
    void rejectsInvalidIssuer(String issuer) {
        assertThatThrownBy(() -> OtpAuthUri.validateIssuer(issuer)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("QR PNG를 ZXing 디코더로 읽으면 같은 URI가 나온다(한글·공백·+·콜론 포함 아이디)")
    void qrRoundTrip() throws Exception {
        String uri = OtpAuthUri.build("CMS", "관리 자+1:x", Base32.encode(new byte[20]));
        byte[] png = TotpQrCodes.png(uri);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        String decoded = new QRCodeReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels))))
                .getText();
        assertThat(decoded).isEqualTo(uri);
    }
}
