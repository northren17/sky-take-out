package com.sky.task;

import com.sky.service.MealReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MealReservationTask {
    private final MealReservationService reservationService;

    @Scheduled(fixedDelay = 60000)
    public void expireUncollectedReservations() {
        reservationService.expireReservations();
    }
}
