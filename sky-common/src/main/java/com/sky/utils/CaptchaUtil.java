package com.sky.utils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 图形验证码生成工具
 * <p>
 * 纯 JDK 实现（java.awt + ImageIO），不引入第三方验证码库。
 * <p>
 * 注意：服务器通常没有图形环境，AWT 需要 headless 模式；本类不依赖任何本地字体文件，
 * 使用 JDK 逻辑字体（SansSerif），因此在无桌面环境（含 Docker、Linux 服务器）下也能正常出图。
 */
public final class CaptchaUtil {

    /** 去掉容易混淆的 0/O、1/I/l */
    private static final char[] CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 图片宽度 */
    private static final int WIDTH = 120;
    /** 图片高度 */
    private static final int HEIGHT = 40;
    /** 验证码字符个数 */
    private static final int CODE_LENGTH = 4;
    /** 干扰线数量 */
    private static final int LINE_COUNT = 6;

    private CaptchaUtil() {
    }

    /**
     * 生成随机验证码文本
     *
     * @return 验证码（大写字母与数字）
     */
    public static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CHARS[RANDOM.nextInt(CHARS.length)]);
        }
        return sb.toString();
    }

    /**
     * 把验证码文本渲染成 PNG 图片，并以 Base64 返回
     *
     * @param code 验证码文本
     * @return Base64 编码的 PNG（不含 data URI 前缀）
     */
    public static String renderBase64(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            // 抗锯齿，避免字符边缘毛刺
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 背景
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线
            for (int i = 0; i < LINE_COUNT; i++) {
                g.setColor(randomColor(150, 210));
                g.setStroke(new BasicStroke(1.2f));
                int x1 = RANDOM.nextInt(WIDTH);
                int y1 = RANDOM.nextInt(HEIGHT);
                int x2 = RANDOM.nextInt(WIDTH);
                int y2 = RANDOM.nextInt(HEIGHT);
                g.drawLine(x1, y1, x2, y2);
            }

            // 噪点
            for (int i = 0; i < 60; i++) {
                g.setColor(randomColor(170, 230));
                int x = RANDOM.nextInt(WIDTH);
                int y = RANDOM.nextInt(HEIGHT);
                g.fillRect(x, y, 2, 2);
            }

            // 字符：逐个轻微旋转、上下浮动，增加机器识别难度
            int charWidth = WIDTH / (CODE_LENGTH + 1);
            for (int i = 0; i < code.length(); i++) {
                AffineTransform origin = g.getTransform();
                double angle = (RANDOM.nextDouble() - 0.5) * Math.toRadians(40);
                int x = charWidth / 2 + i * charWidth + RANDOM.nextInt(6);
                int y = HEIGHT - 10 - RANDOM.nextInt(6);
                g.rotate(angle, x, y);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
                g.setColor(randomColor(20, 120));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(origin);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片输出失败", e);
        }
    }

    private static Color randomColor(int min, int max) {
        int bound = max - min;
        return new Color(min + RANDOM.nextInt(bound), min + RANDOM.nextInt(bound), min + RANDOM.nextInt(bound));
    }
}
