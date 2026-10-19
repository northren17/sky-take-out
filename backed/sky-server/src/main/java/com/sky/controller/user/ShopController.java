package com.sky.controller.user;

import com.sky.result.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController("userShopController")
@RequestMapping("/user/shop")
@Tag(name = "店铺相关接口")
@Slf4j
public class ShopController {

    public static final String KEY = "SHOP_STATUS";

    /** 营业中 */
    private static final int OPEN = 1;

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 获取店铺的营业状态
     * <p>
     * 修复说明：原实现直接把 Redis 中的值强转 Integer 并参与比较，
     * 当管理端从未设置过营业状态（key 不存在）时会抛 NullPointerException，
     * 接口返回 500，前端只能把店铺当成"休息中"，首页还会盖上一层打烊遮罩。
     * 这里改为：取不到或类型不符时按「营业中」返回，并且不再对 null 做拆箱比较。
     *
     * @return 1 营业中，0 打烊
     */
    @GetMapping("/status")
    @Operation(summary = "获取店铺的营业状态")
    public Result<Integer> getStatus() {
        Object value = redisTemplate.opsForValue().get(KEY);
        Integer status = toStatus(value);
        if (value == null) {
            log.warn("Redis 中不存在 {}，按默认营业中返回（可在管理端设置营业状态）", KEY);
        } else {
            log.info("获取到店铺的营业状态为：{}", status == OPEN ? "营业中" : "打烊中");
        }
        return Result.success(status);
    }

    /**
     * 兼容 Integer / String 两种存储形式，非法值按营业中处理
     *
     * @param value Redis 中的原始值
     * @return 1 营业中，0 打烊
     */
    private Integer toStatus(Object value) {
        if (value == null) {
            return OPEN;
        }
        if (value instanceof Number number) {
            return number.intValue() == 0 ? 0 : OPEN;
        }
        try {
            return Integer.parseInt(value.toString().trim()) == 0 ? 0 : OPEN;
        } catch (NumberFormatException e) {
            log.warn("店铺营业状态值非法：{}，按营业中处理", value);
            return OPEN;
        }
    }
}
