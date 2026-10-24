package com.sky.mapper;

import com.sky.entity.OrderReview;
import com.sky.entity.OrderReviewImage;
import com.sky.entity.OrderReviewItem;
import com.sky.vo.DishReviewVO;
import com.sky.vo.OrderReviewVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderReviewMapper {
    @Select("select count(*) from order_review where order_id=#{orderId}")
    int countByOrderId(Long orderId);

    @Insert("insert into order_review (order_id,user_id,rating,content) values (#{orderId},#{userId},#{rating},#{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderReview review);

    @Insert("insert into order_review_item (review_id,order_detail_id,dish_id,dish_name,rating) " +
            "values (#{reviewId},#{orderDetailId},#{dishId},#{dishName},#{rating})")
    int insertItem(OrderReviewItem item);

    @Insert("insert into order_review_image (review_id,image_url,sort) values (#{reviewId},#{imageUrl},#{sort})")
    int insertImage(OrderReviewImage image);

    @Select("select r.*,o.number as orderNumber from order_review r join orders o on o.id=r.order_id " +
            "where r.user_id=#{userId} order by r.create_time desc,r.id desc limit #{limit} offset #{offset}")
    List<OrderReviewVO> listMine(@Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("select * from order_review_item where review_id=#{reviewId} order by id")
    List<OrderReviewItem> listItems(Long reviewId);

    @Select("select * from order_review_image where review_id=#{reviewId} order by sort,id")
    List<OrderReviewImage> listImages(Long reviewId);

    @Select("select r.id as reviewId,r.order_id as orderId,coalesce(nullif(u.name,''),nullif(u.username,''),'食堂用户') as userName," +
            "r.rating,r.content,r.create_time as createTime from order_review r " +
            "join order_review_item i on i.review_id=r.id join user u on u.id=r.user_id " +
            "where i.dish_id=#{dishId} order by r.create_time desc,r.id desc limit #{limit} offset #{offset}")
    List<DishReviewVO> listDishReviews(@Param("dishId") Long dishId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("select count(distinct r.id) from order_review r join order_review_item i on i.review_id=r.id where i.dish_id=#{dishId}")
    long countDishReviews(Long dishId);
}
