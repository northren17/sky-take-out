package com.sky.service;

import com.sky.entity.Setmeal;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.SetmealMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class SetmealStockService {
    private static final String STOCK_KEY_PREFIX = "setmeal:stock:";
    private static final Duration STOCK_CACHE_TTL = Duration.ofMinutes(5);

    private final SetmealMapper setmealMapper;
    private final StringRedisTemplate redisTemplate;

    public int getStock(Setmeal setmeal) {
        String key = STOCK_KEY_PREFIX + setmeal.getId();
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) return Integer.parseInt(cached);
        int stock = setmeal.getStock() == null ? 0 : setmeal.getStock();
        redisTemplate.opsForValue().set(key, Integer.toString(stock), STOCK_CACHE_TTL);
        return stock;
    }

    public void deduct(Long setmealId, int quantity) {
        if (quantity <= 0 || setmealMapper.deductStock(setmealId, quantity) != 1) {
            throw new OrderBusinessException("套餐库存不足或已停售，请调整购物车后重试");
        }
        invalidateAfterCommit(setmealId);
    }

    public void restore(Long setmealId, int quantity) {
        if (quantity <= 0) return;
        setmealMapper.restoreStock(setmealId, quantity);
        invalidateAfterCommit(setmealId);
    }

    public void invalidate(Long setmealId) {
        if (setmealId != null) redisTemplate.delete(STOCK_KEY_PREFIX + setmealId);
    }

    private void invalidateAfterCommit(Long setmealId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(setmealId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(setmealId);
            }
        });
    }
}
