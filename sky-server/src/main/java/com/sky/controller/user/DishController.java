package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.entity.Category;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Tag(name = "C端-菜品浏览接口")
public class DishController {
    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 首页左侧分类列表
     * <p>
     * 用户端网页版首页调用的是 `/user/dish/category`（无参），此前后端没有该接口，
     * 导致分类拿不到、菜品区一直显示空状态。这里补齐：
     * 返回「有在售菜品」的菜品分类（category.type = 1 且 status = 1），按 sort 升序。
     *
     * @return 分类列表
     */
    @GetMapping("/category")
    @Operation(summary = "查询菜品分类（首页左侧分类栏）")
    public Result<List<Category>> category() {
        return Result.success(dishService.listAvailableCategory());
    }

    /**
     * 根据分类id查询菜品
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @Operation(summary = "根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {
        String key = "dish_" + categoryId;
        String lockKey = "lock:dish_" + categoryId;

        // 1. 先查缓存
        List<DishVO> list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        if (list != null) {
            log.info("Redis 命中缓存: {}", key);
            return Result.success(list);
        }

        // 2. 缓存未命中，尝试获取分布式锁解决缓存击穿
        Boolean isLock = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
        try {
            if (Boolean.TRUE.equals(isLock)) {
                // 双重检查，防止在等待锁的过程中其他线程已经更新了缓存
                list = (List<DishVO>) redisTemplate.opsForValue().get(key);
                if (list != null) {
                    return Result.success(list);
                }

                log.info("缓存失效，加锁查询数据库: {}", key);
                // 3. 查数据库
                Dish dish = new Dish();
                dish.setCategoryId(categoryId);
                dish.setStatus(StatusConstant.ENABLE);
                list = dishService.listWithFlavor(dish);

                // 4. 写入缓存，使用随机过期时间解决缓存雪崩 (60分钟 + 0-10分钟随机)
                int randomMinutes = new java.util.Random().nextInt(10);
                redisTemplate.opsForValue().set(key, list, 60 + randomMinutes, TimeUnit.MINUTES);
            } else {
                // 未获得锁，休眠并重试
                Thread.sleep(100);
                return list(categoryId);
            }
        } catch (InterruptedException e) {
            log.error("获取锁异常", e);
            Thread.currentThread().interrupt();
        } finally {
            // 释放锁
            redisTemplate.delete(lockKey);
        }

        return Result.success(list);
    }

}
