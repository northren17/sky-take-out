package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Category;
import com.sky.entity.Dish;
import com.sky.enumeration.OperationType;
import com.sky.vo.DishVO;
import com.sky.vo.AgentDishRecommendationItemVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DishMapper {
    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(id) from dish where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);
    /**
     * 插入菜品数据
     *
     * @param dish
     */
    @AutoFill(value = OperationType.INSERT)
    void insert(Dish dish);

    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);
/*
删除菜单的判断查询
 */
    @Select("select * from dish where  id=#{id}")
    Dish getByid(Long id);
/*
删除菜品和口味数据
 */
    @Delete("delete from dish where id=#{id}")
    void deleteById(Long id);
/*
修改菜品
 */
    @AutoFill(value = OperationType.UPDATE)
    void update(Dish dish);
    /**
     * 动态条件查询菜品
     * @param dish
     * @return
     */
    List<Dish> list(Dish dish);

@Select("select a.* from dish a left join setmeal_dish b on a.id = b.dish_id where b.setmeal_id = #{setmealId}")
    List<Dish> getBySetmealId(Long id);
    /**
     * 根据条件统计菜品数量
     * @param map
     * @return
     */
    Integer countByMap(Map map);

    /**
     * 查询「有在售菜品」的分类（C端首页左侧分类栏）
     * <p>
     * 只返回菜品分类（category.type = 1）、分类已启用且该分类下至少有一个起售菜品，
     * 排序与分类表一致（sort 升序）。这样首页左侧不会出现点进去是空的分类。
     *
     * @return 分类列表（返回 Category 实体，字段与前端首页所需的 id/name/type/sort 一致）
     */
    @Select("select c.id, c.name, c.type, c.sort from category c "
            + "where c.type = 1 and c.status = 1 "
            + "and exists (select 1 from dish d where d.category_id = c.id and d.status = 1) "
            + "order by c.sort asc, c.create_time desc")
    List<Category> listAvailableCategory();

    @Select("select d.name, '菜品' as type, c.name as categoryName, " +
            "coalesce(d.description, '') as description, d.price, " +
            "coalesce(f.flavor_text, '') as flavorText " +
            "from dish d join category c on c.id = d.category_id " +
            "left join (select dish_id, " +
            "group_concat(concat(name, ':', value) separator ' ') as flavor_text " +
            "from dish_flavor group by dish_id) f on f.dish_id = d.id " +
            "where d.status = 1 and c.status = 1 and c.type = 1 " +
            "order by c.sort, d.update_time desc limit 200")
    List<AgentDishRecommendationItemVO> listAvailableRecommendations();
}