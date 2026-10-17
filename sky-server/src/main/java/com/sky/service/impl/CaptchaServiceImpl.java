package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.RedisKeyConstant;
import com.sky.exception.CaptchaErrorException;
import com.sky.service.CaptchaService;
import com.sky.utils.CaptchaUtil;
import com.sky.vo.CaptchaVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 图形验证码
 * <p>
 * 答案存在 Redis 中，key 由服务端随机生成（前端只持有 captchaKey，拿不到答案），
 * 校验成功后立即删除，保证“一次一用”防重放。
 */
@Service
@Slf4j
public class CaptchaServiceImpl implements CaptchaService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public CaptchaVO generate() {
        String code = CaptchaUtil.randomCode();
        String captchaKey = UUID.randomUUID().toString().replace("-", "");
        String redisKey = RedisKeyConstant.CAPTCHA + captchaKey;

        stringRedisTemplate.opsForValue()
                .set(redisKey, code, RedisKeyConstant.CAPTCHA_TTL_SECONDS, TimeUnit.SECONDS);
        log.info("生成验证码：captchaKey={}", captchaKey);

        return CaptchaVO.builder()
                .captchaKey(captchaKey)
                .image(CaptchaUtil.renderBase64(code))
                .expireIn(RedisKeyConstant.CAPTCHA_TTL_SECONDS)
                .build();
    }

    @Override
    public boolean verify(String captchaKey, String code) {
        if (!StringUtils.hasText(captchaKey) || !StringUtils.hasText(code)) {
            throw new CaptchaErrorException(MessageConstant.CAPTCHA_IS_NULL);
        }

        String redisKey = RedisKeyConstant.CAPTCHA + captchaKey;
        // 先取后删：无论对错都作废，避免同一验证码被暴力重试
        String expected = stringRedisTemplate.opsForValue().get(redisKey);
        stringRedisTemplate.delete(redisKey);

        if (expected == null) {
            throw new CaptchaErrorException(MessageConstant.CAPTCHA_ERROR);
        }
        if (!expected.equalsIgnoreCase(code.trim())) {
            throw new CaptchaErrorException(MessageConstant.CAPTCHA_ERROR);
        }
        return true;
    }
}
