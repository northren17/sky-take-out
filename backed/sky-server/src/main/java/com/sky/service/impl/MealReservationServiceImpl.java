package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.MealReservationDTO;
import com.sky.dto.ReservationItemDTO;
import com.sky.entity.Dish;
import com.sky.entity.MealReservation;
import com.sky.entity.MealReservationCapacity;
import com.sky.entity.MealReservationDetail;
import com.sky.entity.Setmeal;
import com.sky.exception.ReservationBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.MealReservationMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.service.MealReservationService;
import com.sky.service.SetmealStockService;
import com.sky.vo.MealReservationVO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MealReservationServiceImpl implements MealReservationService {
    private static final String CAPACITY_KEY_PREFIX = "reservation:capacity:";
    private static final Duration CAPACITY_TTL = Duration.ofHours(48);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofMinutes(2);
    private static final int DEFAULT_CAPACITY = 100;

    private final MealReservationMapper reservationMapper;
    private final DishMapper dishMapper;
    private final SetmealMapper setmealMapper;
    private final SetmealStockService setmealStockService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    @Override
    @Transactional
    public MealReservationVO create(MealReservationDTO dto) {
        Long userId = requireUserId();
        validateRequest(dto);
        String requestId = dto.getRequestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > 80) {
            throw new ReservationBusinessException("请提供有效的预约请求编号");
        }
        String idempotencyKey = "reservation:request:" + userId + ":" + requestId;
        Boolean firstRequest = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "processing", IDEMPOTENCY_TTL);
        if (!Boolean.TRUE.equals(firstRequest)) {
            throw new ReservationBusinessException("请勿重复提交预约");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) redisTemplate.delete(idempotencyKey);
            }
        });
        List<MealReservationDetail> details = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ReservationItemDTO item : dto.getItems()) {
            MealReservationDetail detail = buildDetail(item);
            details.add(detail);
            total = total.add(detail.getSubtotal());
        }
        reserveCapacity(dto.getMealDate(), dto.getMealPeriod());

        MealReservation reservation = new MealReservation();
        reservation.setReservationNo("YY" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        reservation.setUserId(userId);
        reservation.setMealDate(dto.getMealDate());
        reservation.setMealPeriod(dto.getMealPeriod());
        reservation.setStatus(0);
        reservation.setRemark(dto.getRemark());
        reservation.setTotalAmount(total);
        reservationMapper.insert(reservation);
        for (MealReservationDetail detail : details) {
            detail.setReservationId(reservation.getId());
            reservationMapper.insertDetail(detail);
        }
        redisTemplate.opsForValue().set(idempotencyKey, reservation.getReservationNo(), IDEMPOTENCY_TTL);
        MealReservationVO result = new MealReservationVO();
        org.springframework.beans.BeanUtils.copyProperties(reservation, result);
        result.setItems(details);
        return result;
    }

    private MealReservationDetail buildDetail(ReservationItemDTO item) {
        if (item == null || item.getItemId() == null || item.getItemType() == null
                || item.getQuantity() == null || item.getQuantity() < 1 || item.getQuantity() > 99) {
            throw new ReservationBusinessException("预约商品或数量不正确");
        }
        MealReservationDetail detail = new MealReservationDetail();
        detail.setItemType(item.getItemType());
        detail.setItemId(item.getItemId());
        detail.setQuantity(item.getQuantity());
        if (item.getItemType() == 1) {
            Dish dish = dishMapper.getByid(item.getItemId());
            if (dish == null || !Integer.valueOf(1).equals(dish.getStatus())) {
                throw new ReservationBusinessException("菜品不存在或已停售");
            }
            detail.setItemName(dish.getName());
            detail.setImage(dish.getImage());
            detail.setUnitPrice(dish.getPrice());
        } else if (item.getItemType() == 2) {
            Setmeal setmeal = setmealMapper.getById(item.getItemId());
            if (setmeal == null || !Integer.valueOf(1).equals(setmeal.getStatus())) {
                throw new ReservationBusinessException("套餐不存在或已停售");
            }
            setmealStockService.deduct(setmeal.getId(), item.getQuantity());
            detail.setItemName(setmeal.getName());
            detail.setImage(setmeal.getImage());
            detail.setUnitPrice(setmeal.getPrice());
        } else {
            throw new ReservationBusinessException("不支持的预约商品类型");
        }
        detail.setSubtotal(detail.getUnitPrice().multiply(BigDecimal.valueOf(detail.getQuantity())));
        return detail;
    }

    @Override
    public List<MealReservationVO> listMine(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 50);
        List<MealReservationVO> result = new ArrayList<>();
        for (MealReservation reservation : reservationMapper.listMine(requireUserId(), safeSize, (safePage - 1) * safeSize)) {
            MealReservationVO vo = new MealReservationVO();
            org.springframework.beans.BeanUtils.copyProperties(reservation, vo);
            vo.setItems(reservationMapper.listDetails(reservation.getId()));
            result.add(vo);
        }
        return result;
    }

    @Override
    @Transactional
    public void cancel(Long id) {
        Long userId = requireUserId();
        MealReservation reservation = reservationMapper.getMine(id, userId);
        if (reservation == null || reservation.getStatus() != 0) {
            throw new ReservationBusinessException("预约不存在或当前状态不能取消");
        }
        if (isPastCancellationDeadline(reservation)) {
            throw new ReservationBusinessException("已超过预约取消时间");
        }
        if (reservationMapper.cancel(id, userId) != 1) {
            throw new ReservationBusinessException("预约状态已变化，请刷新后重试");
        }
        restoreSetmealStock(reservation);
        releaseCapacityAfterCommit(reservation.getMealDate(), reservation.getMealPeriod());
    }

    @Override
    public void updateCapacity(MealReservationCapacity capacity) {
        if (capacity == null || capacity.getMealDate() == null || !capacity.getMealDate().isAfter(LocalDate.now())
                || capacity.getMealPeriod() == null || capacity.getMealPeriod() < 1 || capacity.getMealPeriod() > 2
                || capacity.getMaxReservations() == null || capacity.getMaxReservations() < 1
                || capacity.getMaxReservations() > 10000
                || capacity.getEnabled() == null || (capacity.getEnabled() != 0 && capacity.getEnabled() != 1)) {
            throw new ReservationBusinessException("预约限额配置不正确");
        }
        String cacheKey = capacityKey(capacity.getMealDate(), capacity.getMealPeriod());
        RLock lock = redissonClient.getLock("lock:" + cacheKey);
        lock.lock();
        try {
            if (reservationMapper.countActive(capacity.getMealDate(), capacity.getMealPeriod()) > capacity.getMaxReservations()) {
                throw new ReservationBusinessException("限额不能小于当前有效预约数");
            }
            reservationMapper.saveCapacity(capacity);
            redisTemplate.delete(cacheKey);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    @Override
    public MealReservationCapacity getCapacity(LocalDate date, Integer period) {
        if (date == null) throw new ReservationBusinessException("预约日期不能为空");
        validatePeriod(period);
        MealReservationCapacity capacity = reservationMapper.getCapacity(date, period);
        if (capacity != null) return capacity;
        MealReservationCapacity defaultCapacity = new MealReservationCapacity();
        defaultCapacity.setMealDate(date);
        defaultCapacity.setMealPeriod(period);
        defaultCapacity.setMaxReservations(DEFAULT_CAPACITY);
        defaultCapacity.setEnabled(1);
        return defaultCapacity;
    }

    @Override
    public List<MealReservation> listAll(int page, int pageSize) {
        int safePage = Math.max(1, page);
        return reservationMapper.listAll(Math.min(Math.max(1, pageSize), 100), (safePage - 1) * Math.min(Math.max(1, pageSize), 100));
    }

    @Override
    @Transactional
    public void markPickedUp(Long id) {
        if (reservationMapper.markPickedUp(id) != 1) {
            throw new ReservationBusinessException("预约不存在或当前状态不能标记为已取餐");
        }
    }

    @Override
    @Transactional
    public void expireReservations() {
        for (MealReservation reservation : reservationMapper.listExpired()) {
            if (reservationMapper.markExpired(reservation.getId()) == 1) {
                restoreSetmealStock(reservation);
                releaseCapacityAfterCommit(reservation.getMealDate(), reservation.getMealPeriod());
            }
        }
    }

    private void reserveCapacity(LocalDate date, Integer period) {
        String cacheKey = capacityKey(date, period);
        RLock lock = redissonClient.getLock("lock:" + cacheKey);
        lock.lock();
        try {
            MealReservationCapacity config = getCapacity(date, period);
            if (config.getEnabled() != 1) throw new ReservationBusinessException("该时段暂未开放预约");
            String cached = redisTemplate.opsForValue().get(cacheKey);
            long count;
            if (cached == null) {
                count = reservationMapper.countActive(date, period);
                redisTemplate.opsForValue().set(cacheKey, Long.toString(count), CAPACITY_TTL);
            } else {
                count = Long.parseLong(cached);
            }
            if (count >= config.getMaxReservations()) throw new ReservationBusinessException("该时段预约名额已满");
            redisTemplate.opsForValue().increment(cacheKey);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) releaseCapacity(date, period);
                }
            });
        }
    }

    private void releaseCapacity(LocalDate date, Integer period) {
        String cacheKey = capacityKey(date, period);
        RLock lock = redissonClient.getLock("lock:" + cacheKey);
        lock.lock();
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached == null) {
                long count = Math.max(0, reservationMapper.countActive(date, period));
                redisTemplate.opsForValue().set(cacheKey, Long.toString(count), CAPACITY_TTL);
            } else {
                long count = Long.parseLong(cached);
                if (count > 0) redisTemplate.opsForValue().decrement(cacheKey);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void releaseCapacityAfterCommit(LocalDate date, Integer period) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            releaseCapacity(date, period);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseCapacity(date, period);
            }
        });
    }

    private void restoreSetmealStock(MealReservation reservation) {
        for (MealReservationDetail detail : reservationMapper.listDetails(reservation.getId())) {
            if (detail.getItemType() == 2) setmealStockService.restore(detail.getItemId(), detail.getQuantity());
        }
    }

    private boolean isPastCancellationDeadline(MealReservation reservation) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = LocalDateTime.of(reservation.getMealDate(),
                reservation.getMealPeriod() == 1 ? LocalTime.of(11, 30) : LocalTime.of(17, 30));
        return !now.isBefore(deadline);
    }

    private void validateRequest(MealReservationDTO dto) {
        if (dto == null || dto.getMealDate() == null || !dto.getMealDate().isAfter(LocalDate.now())) {
            throw new ReservationBusinessException("预约日期只能选择明天及之后");
        }
        validatePeriod(dto.getMealPeriod());
        if (dto.getItems() == null || dto.getItems().isEmpty() || dto.getItems().size() > 50) {
            throw new ReservationBusinessException("请至少选择一项预约商品");
        }
        if (dto.getRemark() != null && dto.getRemark().length() > 200) {
            throw new ReservationBusinessException("备注不能超过200个字符");
        }
    }

    private void validatePeriod(Integer period) {
        if (period == null || (period != 1 && period != 2)) {
            throw new ReservationBusinessException("就餐时段不正确");
        }
    }

    private Long requireUserId() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) throw new ReservationBusinessException("用户登录状态无效");
        return userId;
    }

    private String capacityKey(LocalDate date, Integer period) {
        return CAPACITY_KEY_PREFIX + date + ":" + period;
    }
}
