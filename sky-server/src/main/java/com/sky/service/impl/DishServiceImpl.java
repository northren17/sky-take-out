package com.sky.service.impl;


import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.DeletionException;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service
@Slf4j
public class DishServiceImpl implements DishService {

    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;

    /**
     * 新增菜品和对应的口味
     *
     * @param dishDTO
     */
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {

        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //向菜品表插入1条数据
        dishMapper.insert(dish);//后绪步骤实现

        //获取insert语句生成的主键值
        Long dishId = dish.getId();

        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);//后绪步骤实现
        }
    }

    @Override
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());//启动分页拦截器
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);//后绪步骤实现
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 删除菜品逻辑判断
     *
     * @param ids
     */
    @Override
    public Result<String> deleteBatch(List<Long> ids) {
        for (Long id : ids) {
            Dish dish = dishMapper.getByid(id);
            if (dish.getStatus() == StatusConstant.ENABLE) {
                //菜品售卖状态，无法删除
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && setmealIds.size() > 0) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }
        //删除菜品表中的菜品数据
        for (Long id : ids) {
            dishMapper.deleteById(id);//后绪步骤实现
            //删除菜品关联的口味数据
            dishFlavorMapper.deleteByDishId(id);//后绪步骤实现
        }
        return Result.success("删除成功");
    }

    /*
    查询菜品
     */
    @Override
    public DishVO getByIdWithFlavor(Long id) {
        Dish dish = dishMapper.getByid(id);
        List<DishFlavor> dishFlavor = dishFlavorMapper.getByDishId(id);
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(dishFlavor);
        return dishVO;
    }

    /*
    修改菜品---dish表和flavor表
     */
    @Override
    public void updateWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.update(dish);
        //删除原有的口味数据
        dishFlavorMapper.deleteByDishId(dishDTO.getId());
        log.info("删除原口味");

        //重新插入口味数据
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishDTO.getId());
            });
            //向口味表插入n条数据
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    /**
     * 根据分类条件查询列出菜品
     *
     * @param categoryId 分类id
     * @return 菜品列表
     */
    @Override
    public List<Dish> list(Long categoryId) {
        Dish dish = Dish.builder()
                .status(StatusConstant.ENABLE) // 注意这里是只查询起售状态的菜品
                .categoryId(categoryId)
                .build();
        return dishMapper.list(dish);
    }
    @Override
        @Transactional // 开启事务，保证数据一致性
        public void startOrStop(Integer status, Long id) {
            // 1. 如果要起售 (status=1)，需要检查是否有口味数据
            if (status == StatusConstant.ENABLE) {
                // 查询该菜品的口味列表
                List flavors = dishFlavorMapper.getByDishId(id);

                // 如果没有口味数据，抛出业务异常，不允许起售
                if (flavors == null || flavors.isEmpty()) {
                    throw new DeletionException(MessageConstant.FLAVOR_NULL);
                    // 注：DeletionException 是项目中自定义的业务异常类，也可用 BusinessException
                }
            }

            // 2. 更新菜品状态
            // 构造更新对象，只更新 id 和 status
            Dish dish = Dish.builder()
                    .id(id)
                    .status(status)
                    .build();

            dishMapper.update(dish);
        }

    }



