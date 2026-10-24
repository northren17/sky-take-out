package com.sky.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.context.BaseContext;
import com.sky.dto.OrderReviewDTO;
import com.sky.dto.ReviewItemDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.OrderReview;
import com.sky.entity.OrderReviewImage;
import com.sky.entity.OrderReviewItem;
import com.sky.entity.Orders;
import com.sky.entity.SetmealDish;
import com.sky.exception.ReviewBusinessException;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.OrderReviewMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.service.OrderReviewService;
import com.sky.vo.DishReviewVO;
import com.sky.vo.OrderReviewVO;
import com.sky.vo.PendingReviewOrderVO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrderReviewServiceImpl implements OrderReviewService {
    private static final String HOT_DISH_REVIEW_KEY = "review:dish:";
    private static final Duration HOT_REVIEW_TTL = Duration.ofMinutes(5);

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final OrderReviewMapper reviewMapper;
    private final SetmealDishMapper setmealDishMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;

    @Override
    public List<PendingReviewOrderVO> listPending(int page, int pageSize) {
        Long userId = requireUserId();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 50);
        List<PendingReviewOrderVO> result = new ArrayList<>();
        for (Orders order : orderMapper.listCompletedUnreviewed(userId, safeSize, (safePage - 1) * safeSize)) {
            PendingReviewOrderVO vo = new PendingReviewOrderVO();
            org.springframework.beans.BeanUtils.copyProperties(order, vo);
            vo.setOrderDetails(orderDetailMapper.getByOrderId(order.getId()));
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional
    public void submit(OrderReviewDTO dto) {
        Long userId = requireUserId();
        if (dto == null || dto.getOrderId() == null || !validRating(dto.getRating())) {
            throw new ReviewBusinessException("订单和整体评分不能为空，评分范围为1到5");
        }
        RLock orderLock = redissonClient.getLock("lock:review:order:" + dto.getOrderId());
        orderLock.lock();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (orderLock.isHeldByCurrentThread()) orderLock.unlock();
                    }
                });
        if (dto.getContent() != null && dto.getContent().length() > 1000) {
            throw new ReviewBusinessException("评价内容不能超过1000个字符");
        }
        List<String> images = dto.getImages() == null ? List.of() : dto.getImages();
        if (images.size() > 9 || images.stream().anyMatch(url -> url == null || url.isBlank() || url.length() > 500)) {
            throw new ReviewBusinessException("晒图最多9张，图片地址不能为空且不能超过500个字符");
        }
        Orders order = orderMapper.getByIdAndUserId(dto.getOrderId(), userId);
        if (order == null || !Integer.valueOf(Orders.COMPLETED).equals(order.getStatus())) {
            throw new ReviewBusinessException("仅可评价本人已完成的订单");
        }
        if (reviewMapper.countByOrderId(order.getId()) > 0) {
            throw new ReviewBusinessException("该订单已经评价，不能重复提交");
        }

        List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(order.getId());
        Map<Long, OrderDetail> detailsById = new HashMap<>();
        orderDetails.forEach(detail -> detailsById.put(detail.getId(), detail));
        OrderReview review = new OrderReview();
        review.setOrderId(order.getId());
        review.setUserId(userId);
        review.setRating(dto.getRating());
        review.setContent(dto.getContent());
        reviewMapper.insert(review);

        Set<Long> invalidatedDishIds = new HashSet<>();
        Set<String> uniqueness = new HashSet<>();
        if (dto.getItems() != null) {
            for (ReviewItemDTO item : dto.getItems()) {
                if (item == null || item.getOrderDetailId() == null || item.getDishId() == null || !validRating(item.getRating())) {
                    throw new ReviewBusinessException("菜品评分信息不正确");
                }
                OrderDetail detail = detailsById.get(item.getOrderDetailId());
                if (detail == null || !containsDish(detail, item.getDishId())) {
                    throw new ReviewBusinessException("菜品不属于该订单");
                }
                if (!uniqueness.add(item.getOrderDetailId() + ":" + item.getDishId())) {
                    throw new ReviewBusinessException("同一菜品不能重复评分");
                }
                OrderReviewItem reviewItem = new OrderReviewItem();
                reviewItem.setReviewId(review.getId());
                reviewItem.setOrderDetailId(detail.getId());
                reviewItem.setDishId(item.getDishId());
                reviewItem.setDishName(resolveDishName(detail, item.getDishId()));
                reviewItem.setRating(item.getRating());
                reviewMapper.insertItem(reviewItem);
                invalidatedDishIds.add(item.getDishId());
            }
        }

        for (int i = 0; i < images.size(); i++) {
            OrderReviewImage image = new OrderReviewImage();
            image.setReviewId(review.getId());
            image.setImageUrl(images.get(i));
            image.setSort(i);
            reviewMapper.insertImage(image);
        }
        invalidatedDishIds.forEach(id -> redisTemplate.delete(HOT_DISH_REVIEW_KEY + id + ":1"));
    }

    private boolean containsDish(OrderDetail detail, Long dishId) {
        if (detail.getDishId() != null) return detail.getDishId().equals(dishId);
        if (detail.getSetmealId() == null) return false;
        return setmealDishMapper.getBySetmealId(detail.getSetmealId()).stream()
                .anyMatch(setmealDish -> dishId.equals(setmealDish.getDishId()));
    }

    private String resolveDishName(OrderDetail detail, Long dishId) {
        if (detail.getDishId() != null) return detail.getName();
        return setmealDishMapper.getBySetmealId(detail.getSetmealId()).stream()
                .filter(setmealDish -> dishId.equals(setmealDish.getDishId()))
                .map(SetmealDish::getName)
                .findFirst()
                .orElseThrow(() -> new ReviewBusinessException("订单菜品信息不存在"));
    }

    @Override
    public List<OrderReviewVO> listMine(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 50);
        List<OrderReviewVO> reviews = reviewMapper.listMine(requireUserId(), safeSize, (safePage - 1) * safeSize);
        for (OrderReviewVO review : reviews) {
            review.setImages(reviewMapper.listImages(review.getId()));
            review.setItems(reviewMapper.listItems(review.getId()));
        }
        return reviews;
    }

    @Override
    public List<DishReviewVO> listDishReviews(Long dishId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 20);
        String key = HOT_DISH_REVIEW_KEY + dishId + ":" + safePage;
        if (safePage == 1) {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                try {
                    return objectMapper.readValue(cached, new TypeReference<List<DishReviewVO>>() {});
                } catch (JsonProcessingException e) {
                    throw new ReviewBusinessException("菜品评价缓存格式异常，请稍后重试");
                }
            }
        }
        List<DishReviewVO> reviews = reviewMapper.listDishReviews(dishId, safeSize, (safePage - 1) * safeSize);
        for (DishReviewVO review : reviews) review.setImages(reviewMapper.listImages(review.getReviewId()));
        if (safePage == 1) {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(reviews), HOT_REVIEW_TTL);
            } catch (JsonProcessingException e) {
                throw new ReviewBusinessException("菜品评价缓存序列化失败");
            }
        }
        return reviews;
    }

    private boolean validRating(Integer rating) {
        return rating != null && rating >= 1 && rating <= 5;
    }

    private Long requireUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) throw new ReviewBusinessException("用户登录状态无效");
        return userId;
    }
}
