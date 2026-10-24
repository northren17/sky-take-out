-- Package stock and the reservation/review modules.
-- Existing setmeal and setmeal_dish tables already hold package and composition data.
ALTER TABLE setmeal
    ADD COLUMN stock INT NOT NULL DEFAULT 100 COMMENT '可售库存，-1表示不限库存';

-- Reservation header. Meal periods: 1=lunch, 2=dinner.
CREATE TABLE meal_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '预约主键',
    reservation_no VARCHAR(32) NOT NULL COMMENT '预约单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    meal_date DATE NOT NULL COMMENT '预约就餐日期',
    meal_period TINYINT NOT NULL COMMENT '就餐时段：1午餐，2晚餐',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0待取餐，1已取餐，2已取消，3超时未取',
    remark VARCHAR(200) NULL COMMENT '预约备注',
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '预约商品金额',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    cancel_time DATETIME NULL COMMENT '取消时间',
    pickup_time DATETIME NULL COMMENT '取餐时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_meal_reservation_no (reservation_no),
    KEY idx_reservation_user_date (user_id, meal_date, meal_period, id),
    KEY idx_reservation_status_pickup (status, meal_date, meal_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户预约订单';

-- A reservation may contain individual dishes and/or setmeals.
CREATE TABLE meal_reservation_detail (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '预约明细主键',
    reservation_id BIGINT NOT NULL COMMENT '预约单ID',
    item_type TINYINT NOT NULL COMMENT '商品类型：1菜品，2套餐',
    item_id BIGINT NOT NULL COMMENT '菜品或套餐ID',
    item_name VARCHAR(100) NOT NULL COMMENT '商品名称快照',
    image VARCHAR(500) NULL COMMENT '商品图片快照',
    quantity INT NOT NULL COMMENT '预约数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '下单时单价',
    subtotal DECIMAL(10,2) NOT NULL COMMENT '明细金额',
    PRIMARY KEY (id),
    KEY idx_reservation_detail_parent (reservation_id),
    KEY idx_reservation_detail_item (item_type, item_id),
    CONSTRAINT fk_reservation_detail_parent
        FOREIGN KEY (reservation_id) REFERENCES meal_reservation (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约订单商品明细';

-- Per-date and per-period capacity is maintained by authorized administrators.
CREATE TABLE meal_reservation_capacity (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '预约限额主键',
    meal_date DATE NOT NULL COMMENT '配置日期',
    meal_period TINYINT NOT NULL COMMENT '就餐时段：1午餐，2晚餐',
    max_reservations INT NOT NULL COMMENT '该日期该时段最大预约单数',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否开放预约：0关闭，1开放',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    update_user BIGINT NULL COMMENT '最后修改员工ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_capacity_date_period (meal_date, meal_period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日分时段预约限额';

CREATE TABLE order_review (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '评价主键',
    order_id BIGINT NOT NULL COMMENT '已完成订单ID',
    user_id BIGINT NOT NULL COMMENT '评价用户ID',
    rating TINYINT NOT NULL COMMENT '订单整体评分：1-5',
    content VARCHAR(1000) NULL COMMENT '文字评价',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_review_order (order_id),
    KEY idx_order_review_user_time (user_id, create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单评价主表';

-- Item-level scores are stored separately from the order-level score.
CREATE TABLE order_review_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '菜品评价主键',
    review_id BIGINT NOT NULL COMMENT '评价ID',
    order_detail_id BIGINT NOT NULL COMMENT '订单明细ID',
    dish_id BIGINT NOT NULL COMMENT '被评价菜品ID',
    dish_name VARCHAR(100) NOT NULL COMMENT '菜品名称快照',
    rating TINYINT NOT NULL COMMENT '菜品评分：1-5',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_order_detail_dish (review_id, order_detail_id, dish_id),
    KEY idx_review_item_dish_time (dish_id, review_id),
    CONSTRAINT fk_review_item_parent
        FOREIGN KEY (review_id) REFERENCES order_review (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单菜品评价';

CREATE TABLE order_review_image (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '评价图片主键',
    review_id BIGINT NOT NULL COMMENT '评价ID',
    image_url VARCHAR(500) NOT NULL COMMENT '图片存储地址',
    sort INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_review_image_parent (review_id, sort, id),
    CONSTRAINT fk_review_image_parent
        FOREIGN KEY (review_id) REFERENCES order_review (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价晒图';
