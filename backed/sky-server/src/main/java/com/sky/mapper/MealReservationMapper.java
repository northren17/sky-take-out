package com.sky.mapper;

import com.sky.entity.MealReservation;
import com.sky.entity.MealReservationCapacity;
import com.sky.entity.MealReservationDetail;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MealReservationMapper {
    @Select("select * from meal_reservation_capacity where meal_date=#{date} and meal_period=#{period}")
    MealReservationCapacity getCapacity(@Param("date") LocalDate date, @Param("period") int period);

    @Insert("insert into meal_reservation_capacity (meal_date,meal_period,max_reservations,enabled,update_user) " +
            "values (#{mealDate},#{mealPeriod},#{maxReservations},#{enabled},#{updateUser}) " +
            "on duplicate key update max_reservations=values(max_reservations), enabled=values(enabled), " +
            "update_user=values(update_user)")
    int saveCapacity(MealReservationCapacity capacity);

    @Select("select count(*) from meal_reservation where meal_date=#{date} and meal_period=#{period} and status=0")
    long countActive(@Param("date") LocalDate date, @Param("period") int period);

    @Insert("insert into meal_reservation (reservation_no,user_id,meal_date,meal_period,status,remark,total_amount) " +
            "values (#{reservationNo},#{userId},#{mealDate},#{mealPeriod},0,#{remark},#{totalAmount})")
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MealReservation reservation);

    @Insert("insert into meal_reservation_detail (reservation_id,item_type,item_id,item_name,image,quantity,unit_price,subtotal) " +
            "values (#{reservationId},#{itemType},#{itemId},#{itemName},#{image},#{quantity},#{unitPrice},#{subtotal})")
    int insertDetail(MealReservationDetail detail);

    @Select("select * from meal_reservation where id=#{id} and user_id=#{userId}")
    MealReservation getMine(@Param("id") Long id, @Param("userId") Long userId);

    @Select("select * from meal_reservation where user_id=#{userId} order by meal_date desc, meal_period desc, id desc limit #{limit} offset #{offset}")
    List<MealReservation> listMine(@Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("select * from meal_reservation_detail where reservation_id=#{reservationId} order by id")
    List<MealReservationDetail> listDetails(Long reservationId);

    @Update("update meal_reservation set status=2,cancel_time=current_timestamp where id=#{id} and user_id=#{userId} and status=0")
    int cancel(@Param("id") Long id, @Param("userId") Long userId);

    @Select("select * from meal_reservation where status=0 and " +
            "(meal_date < current_date or (meal_date=current_date and " +
            "((meal_period=1 and current_time >= '13:30:00') or (meal_period=2 and current_time >= '20:00:00')))) " +
            "order by id limit 200")
    List<MealReservation> listExpired();

    @Update("update meal_reservation set status=3 where id=#{id} and status=0")
    int markExpired(Long id);

    @Update("update meal_reservation set status=1,pickup_time=current_timestamp where id=#{id} and status=0")
    int markPickedUp(Long id);

    @Select("select * from meal_reservation order by create_time desc limit #{limit} offset #{offset}")
    List<MealReservation> listAll(@Param("limit") int limit, @Param("offset") int offset);
}
