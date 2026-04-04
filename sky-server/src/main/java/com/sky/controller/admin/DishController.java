package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜品管理
 */
@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品相关接口")
@Slf4j
public class DishController {
    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 清理缓存数据
     * @param pattern
     */
    private void cleanCache(String pattern){
        Set keys = redisTemplate.keys(pattern);
        redisTemplate.delete(keys);
    }

    /**
     * 新增菜品
     *
     * @param dishDTO
     * @return
     */
    @PostMapping
    @ApiOperation("新增菜品")
    public Result save(@RequestBody DishDTO dishDTO) {
        log.info("新增菜品：{}", dishDTO);
        dishService.saveWithFlavor(dishDTO);
        //清理缓存数据
        String key = "dish_" + dishDTO.getCategoryId();
        cleanCache(key);
        return Result.success();
    }
    /*菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("菜品分页查询")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO) {
        log.info("菜品分页查询:{}", dishPageQueryDTO);
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);//后绪步骤定义
        return Result.success(pageResult);
    }
@DeleteMapping
@ApiOperation("删除菜品")
public  Result  delete(@RequestParam List<Long> ids)
{
    log.info("菜品批量删除：{}", ids);
    dishService.deleteBatch(ids);
    //将所有的菜品缓存数据清理掉，所有以dish_开头的key
    cleanCache("dish_*");
    return Result.success();
}
/*
菜品查询回显：菜品表和口味表
 */
    @GetMapping("/{id}")
    @ApiOperation("查询菜品")
public  Result<DishVO>  getById(@PathVariable Long id)
{
    log.info("查询菜品,{}", id);
   DishVO dishVO= dishService.getByIdWithFlavor(id);
   return Result.success(dishVO);
}
/*
修改菜品
 */
    @PutMapping
    @ApiOperation("修改菜品")
    public Result update(@RequestBody DishDTO dishDTO)
    {    log.info("修改菜品：{}",dishDTO.getName());
        dishService.updateWithFlavor(dishDTO);
        //将所有的菜品缓存数据清理掉，所有以dish_开头的key
        cleanCache("dish_*");
        return Result.success();
    }
    /**
     * 根据分类条件查询列出菜品
     * @param categoryId 分类id
     * @return 菜品列表
     */
    @GetMapping("/list")
    @ApiOperation("根据分类条件查询并列出菜品")
    public Result<List<Dish>> list(Long categoryId) {
        log.info("根据分类条件查询并列出菜品: {}", categoryId);
        List<Dish> dishList = dishService.list(categoryId);
        return Result.success(dishList);
    }
    /**
     * 菜品起售停售
     * 路径：/admin/dish/status/{status}
     * 参数：status (0/1), 请求体 id
     */
    @PostMapping("/status/{status}")
    @ApiOperation("菜品起售停售")
    public Result startOrStop(@PathVariable Integer status,Long id) {


        log.info("菜品起售停售：{}, id:{}", status, id);

        // 调用Service层处理业务逻辑
        dishService.startOrStop(status, id);
        //将所有的菜品缓存数据清理掉，所有以dish_开头的key
        cleanCache("dish_*");

        return Result.success();
    }

    // 注意：实际项目中可能需要批量启停，逻辑类似，这里展示单个启停
}