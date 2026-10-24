package com.sky.service;

import com.sky.dto.MealReservationDTO;
import com.sky.entity.MealReservation;
import com.sky.entity.MealReservationCapacity;
import com.sky.vo.MealReservationVO;
import java.util.List;

public interface MealReservationService {
    MealReservationVO create(MealReservationDTO dto);
    List<MealReservationVO> listMine(int page, int pageSize);
    void cancel(Long id);
    void updateCapacity(MealReservationCapacity capacity);
    MealReservationCapacity getCapacity(java.time.LocalDate date, Integer period);
    List<MealReservation> listAll(int page, int pageSize);
    void markPickedUp(Long id);
    void expireReservations();
}
