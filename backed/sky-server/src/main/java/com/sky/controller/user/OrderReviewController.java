package com.sky.controller.user;

import com.sky.dto.OrderReviewDTO;
import com.sky.result.Result;
import com.sky.service.OrderReviewService;
import com.sky.utils.AliOssUtil;
import com.sky.vo.DishReviewVO;
import com.sky.vo.OrderReviewVO;
import com.sky.vo.PendingReviewOrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/user/review")
@RequiredArgsConstructor
@Tag(name = "C端-评价晒单接口")
@Slf4j
public class OrderReviewController {
    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    private final OrderReviewService reviewService;
    private final AliOssUtil aliOssUtil;

    @GetMapping("/pending")
    @Operation(summary = "查询待评价订单")
    public Result<List<PendingReviewOrderVO>> pending(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reviewService.listPending(page, pageSize));
    }

    @PostMapping
    @Operation(summary = "提交订单评价")
    public Result<Void> submit(@RequestBody OrderReviewDTO dto) {
        reviewService.submit(dto);
        return Result.success();
    }

    @GetMapping("/mine")
    @Operation(summary = "查询我的评价")
    public Result<List<OrderReviewVO>> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reviewService.listMine(page, pageSize));
    }

    @GetMapping("/dish/{dishId}")
    @Operation(summary = "查询菜品评价")
    public Result<List<DishReviewVO>> dishReviews(
            @PathVariable Long dishId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(reviewService.listDishReviews(dishId, page, pageSize));
    }

    @PostMapping("/upload")
    @Operation(summary = "上传评价图片")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_IMAGE_BYTES) {
            return Result.error("图片不能为空且不能超过5MB");
        }
        String contentType = file.getContentType();
        String extension = contentType == null ? null : IMAGE_EXTENSIONS.get(contentType);
        if (extension == null) return Result.error("仅支持JPG、PNG或WebP图片");
        try {
            return Result.success(aliOssUtil.upload(file.getBytes(), UUID.randomUUID() + extension));
        } catch (IOException e) {
            log.error("评价图片上传失败", e);
            return Result.error("图片上传失败，请稍后重试");
        }
    }
}
