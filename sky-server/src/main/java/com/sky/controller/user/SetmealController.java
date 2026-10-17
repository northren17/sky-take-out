package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.entity.Setmeal;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import java.util.concurrent.TimeUnit;

@RestController("userSetmealController")
@RequestMapping("/user/setmeal")
@Tag(name = "C端-套餐浏览接口")
@Slf4j
public class SetmealController {
    @Autowired
    private SetmealService setmealService;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 条件查询
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @Operation(summary = "根据分类id查询套餐")
    public Result<List<Setmeal>> list(Long categoryId) {
        String key = "setmeal_" + categoryId;
        String lockKey = "lock:setmeal_" + categoryId;

        // 1. 先查缓存
        List<Setmeal> list = (List<Setmeal>) redisTemplate.opsForValue().get(key);
        if (list != null) {
            log.info("Redis 命中套餐缓存: {}", key);
            return Result.success(list);
        }

        // 2. 缓存未命中，加锁解决击穿
        Boolean isLock = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        try {
            if (Boolean.TRUE.equals(isLock)) {
                // 双重检查
                list = (List<Setmeal>) redisTemplate.opsForValue().get(key);
                if (list != null) return Result.success(list);

                log.info("套餐缓存失效，查询数据库: {}", key);
                Setmeal setmeal = new Setmeal();
                setmeal.setCategoryId(categoryId);
                setmeal.setStatus(StatusConstant.ENABLE);
                list = setmealService.list(setmeal);

                // 3. 写入缓存，随机过期时间解决雪崩
                int randomMinutes = new java.util.Random().nextInt(10);
                redisTemplate.opsForValue().set(key, list, 60 + randomMinutes, TimeUnit.MINUTES);
            } else {
                Thread.sleep(100);
                return list(categoryId);
            }
        } catch (InterruptedException e) {
            log.error("获取锁异常", e);
            Thread.currentThread().interrupt();
        } finally {
            redisTemplate.delete(lockKey);
        }

        return Result.success(list);
    }

    /**
     * 根据套餐id查询包含的菜品列表
     *
     * @param id
     * @return
     */
    @GetMapping("/dish/{id}")
    @Operation(summary = "根据套餐id查询包含的菜品列表")
    public Result<List<DishItemVO>> dishList(@PathVariable("id") Long id) {
        List<DishItemVO> list = setmealService.getDishItemById(id);
        return Result.success(list);
    }
}
