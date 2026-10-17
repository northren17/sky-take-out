package com.sky.interceptor;

import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.RedisKeyConstant;
import com.sky.context.BaseContext;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * jwt令牌校验的拦截器（用户端）
 * <p>
 * 只对 /user/** 生效（在 WebMvcConfiguration 中注册），不影响管理端。
 * <p>
 * 校验分两步：
 * <ol>
 *     <li>校验 jwt 本身（签名 + 过期时间）；</li>
 *     <li>比对 Redis 中保存的令牌，实现“服务端注销 / 强制下线”。
 *         若 Redis 中没有该用户的令牌（已退出登录、或令牌被新登录覆盖），一律视为未登录。</li>
 * </ol>
 */
@Component
@Slf4j
public class JwtTokenUserInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 校验jwt
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断当前拦截到的是Controller的方法还是其他资源
        if (!(handler instanceof HandlerMethod)) {
            //当前拦截到的不是动态方法，直接放行
            return true;
        }

        //1、从请求头中获取令牌（兼容 authentication / Authorization 两种写法）
        String token = request.getHeader(jwtProperties.getUserTokenName());
        if (token == null || token.isBlank()) {
            token = request.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
        }

        //2、校验令牌
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            Long userId = Long.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());

            //3、比对 Redis 中保存的令牌，确认会话仍然有效
            String cachedToken = stringRedisTemplate.opsForValue().get(RedisKeyConstant.USER_TOKEN + userId);
            if (cachedToken == null || !cachedToken.equals(token)) {
                log.info("用户令牌已失效（Redis 中不存在或不一致），userId={}", userId);
                response.setStatus(401);
                return false;
            }

            BaseContext.setCurrentId(userId);
            log.info("当前用户的id：{}", userId);
            //4、通过，放行
            return true;
        } catch (Exception ex) {
            //5、不通过，响应401状态码
            log.info("用户 jwt 校验失败：{}", ex.getMessage());
            response.setStatus(401);
            return false;
        }
    }

    /**
     * 请求结束后清理 ThreadLocal，避免虚拟线程/线程复用场景下串号
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();
    }
}
