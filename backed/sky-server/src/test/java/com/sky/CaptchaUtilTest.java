package com.sky;

import com.sky.utils.CaptchaUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图形验证码工具测试（纯 JDK 实现，无需 Redis / 容器）。
 * <p>
 * 同时验证 AWT 在无图形环境（headless）下能正常出图 —— 这是部署到 Linux 服务器时最容易踩的坑。
 */
@DisplayName("图形验证码工具")
class CaptchaUtilTest {

    @Test
    @DisplayName("生成的验证码为 4 位、仅含去混淆字符集")
    void randomCodeFormat() {
        for (int i = 0; i < 50; i++) {
            String code = CaptchaUtil.randomCode();
            assertThat(code).hasSize(4);
            // 去掉了 0/O、1/I/L 等易混字符
            assertThat(code).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}");
        }
    }

    @Test
    @DisplayName("多次生成不重复（随机性）")
    void randomCodeIsRandom() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(CaptchaUtil.randomCode());
        }
        // 200 次抽取 31^4 ≈ 92 万种组合，去重后应远多于 100
        assertThat(codes.size()).isGreaterThan(100);
    }

    @Test
    @DisplayName("能渲染出合法 PNG 图片（Base64 可解码，且是 PNG 文件头）")
    void renderBase64ProducesPng() {
        String base64 = CaptchaUtil.renderBase64("AB3D");

        assertThat(base64).isNotBlank();
        byte[] png = Base64.getDecoder().decode(base64);

        // PNG 文件头：89 50 4E 47 0D 0A 1A 0A
        assertThat(png).hasSizeGreaterThan(200);
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }

    @Test
    @DisplayName("headless 环境下也能出图（服务器/Docker 场景）")
    void worksInHeadlessMode() {
        String previous = System.getProperty("java.awt.headless");
        try {
            System.setProperty("java.awt.headless", "true");
            assertThat(CaptchaUtil.renderBase64(CaptchaUtil.randomCode())).isNotBlank();
        } finally {
            if (previous == null) {
                System.clearProperty("java.awt.headless");
            } else {
                System.setProperty("java.awt.headless", previous);
            }
        }
    }
}
