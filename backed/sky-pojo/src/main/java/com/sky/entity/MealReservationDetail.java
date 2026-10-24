package com.sky.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MealReservationDetail {
    private Long id;
    private Long reservationId;
    private Integer itemType;
    private Long itemId;
    private String itemName;
    private String image;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}
