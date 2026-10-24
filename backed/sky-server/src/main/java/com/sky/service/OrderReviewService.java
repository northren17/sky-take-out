package com.sky.service;

import com.sky.dto.OrderReviewDTO;
import com.sky.entity.Orders;
import com.sky.vo.DishReviewVO;
import com.sky.vo.OrderReviewVO;
import com.sky.vo.PendingReviewOrderVO;
import java.util.List;

public interface OrderReviewService {
    List<PendingReviewOrderVO> listPending(int page, int pageSize);
    void submit(OrderReviewDTO dto);
    List<OrderReviewVO> listMine(int page, int pageSize);
    List<DishReviewVO> listDishReviews(Long dishId, int page, int pageSize);
}
