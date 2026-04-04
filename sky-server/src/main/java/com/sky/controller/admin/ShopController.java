package com.sky.controller.admin;

import com.sky.result.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/shop")
@Api(tags="管理员店铺相关接口")
@Slf4j
public class ShopController {
    public static final String KEY = "SHOP_STATUS";
@Autowired
private RedisTemplate redisTemplate;
@PutMapping ("/{status}")
@ApiOperation("设置店铺营业状态")
/**
 * 设置营业
 */
    public Result setStatus(@PathVariable Integer status) {
      redisTemplate.opsForValue().set(KEY, status);
      log.info("设置店铺状态为：{}",status==1?"营业中":"打烊了");
        return Result.success();
    }
 @GetMapping("/status")
 @ApiOperation("查询营业状态")
 public  Result<Integer> getStatus()
 {
    Integer status = (Integer) redisTemplate.opsForValue().get(KEY);
     log.info("店铺状态为：{}",status==1?"营业中":"打烊了");
     return Result.success(status);
 }
}

