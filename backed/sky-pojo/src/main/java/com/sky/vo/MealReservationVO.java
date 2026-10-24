package com.sky.vo;

import com.sky.entity.MealReservation;
import com.sky.entity.MealReservationDetail;
import lombok.Data;

import java.util.List;

@Data
public class MealReservationVO extends MealReservation {
    private List<MealReservationDetail> items;
}
