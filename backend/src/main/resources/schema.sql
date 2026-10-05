-- 陆上风电场 · 风机与检修发电管理
-- 库名 wind_farm；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 关键：镜像构建期跑 initdb 时 mysql 客户端默认 latin1，会把 UTF-8 中文存成双编码，
--       因此每个 .sql 文件的第一句必须是 SET NAMES utf8mb4。
SET NAMES utf8mb4;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

CREATE DATABASE IF NOT EXISTS wind_farm
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE wind_farm;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS grid_limit_deviation;
DROP TABLE IF EXISTS grid_limit_allocation;
DROP TABLE IF EXISTS grid_limit_turbine;
DROP TABLE IF EXISTS grid_limit_log;
DROP TABLE IF EXISTS grid_limit_order;
DROP TABLE IF EXISTS isolation_log;
DROP TABLE IF EXISTS isolation_point;
DROP TABLE IF EXISTS safety_ticket;
DROP TABLE IF EXISTS isolation_template;
DROP TABLE IF EXISTS part_replacement;
DROP TABLE IF EXISTS spare_part;
DROP TABLE IF EXISTS work_item;
DROP TABLE IF EXISTS work_order;
DROP TABLE IF EXISTS run_log;
DROP TABLE IF EXISTS turbine;
DROP TABLE IF EXISTS wind_farm;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 风场 ----------------
-- time_zone 是场站本地时区：Asia/Shanghai（东八区）/ Asia/Urumqi（东六区）/ Asia/Chongqing（东八区）
-- 运行日志、检修工单的时间字段都按它归一化存储（Hibernate @TimeZoneStorage）
-- 状态：ACTIVE 在运 / MAINTAIN 检修 / STOPPED 停运
CREATE TABLE wind_farm (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_code VARCHAR(32) NOT NULL UNIQUE,
    farm_name VARCHAR(64),
    region VARCHAR(32),
    province VARCHAR(32),
    time_zone VARCHAR(32) NOT NULL,
    installed_capacity_mw DECIMAL(10,2),
    turbine_count INT DEFAULT 0,
    grid_date DATE,
    owner VARCHAR(32),
    status VARCHAR(16),
    note VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 机位（风机） ----------------
-- unit_code 在同一风场内唯一；rated_power_kw 必须 > 0
-- 状态：ONLINE 并网 / MAINTAIN 检修 / FAULT 故障 / STOPPED 停运
CREATE TABLE turbine (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_id BIGINT NOT NULL,
    unit_code VARCHAR(32) NOT NULL,
    unit_name VARCHAR(64),
    model VARCHAR(48),
    rated_power_kw DECIMAL(10,2) NOT NULL,
    hub_height_m DECIMAL(8,2),
    rotor_diameter_m DECIMAL(8,2),
    install_date DATE,
    status VARCHAR(16),
    last_power_kw DECIMAL(10,2),
    today_energy_kwh DECIMAL(12,2),
    total_energy_kwh DECIMAL(14,2),
    total_hours DECIMAL(12,1),
    note VARCHAR(128),
    UNIQUE KEY uk_turbine_farm_unit (farm_id, unit_code),
    KEY idx_turbine_farm (farm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 运行日志 ----------------
-- record_time / report_time 是 ZonedDateTime + @TimeZoneStorage(NORMALIZE)，
-- 按 hibernate.jdbc.time_zone（Asia/Shanghai）归一化后写进 DATETIME，读出来同样是归一化时区的时刻。
-- record_time_tz / report_time_tz 是记录所属时区的 ZoneId 文本（Asia/Shanghai / Asia/Urumqi 等），
-- 对应实体字段 recordZone / reportZone，由 service 按该记录所属风场的 wind_farm.time_zone 写入。
CREATE TABLE run_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    turbine_id BIGINT NOT NULL,
    farm_id BIGINT NOT NULL,
    unit_code VARCHAR(32),
    record_time DATETIME NOT NULL,
    record_time_tz VARCHAR(32),
    report_time DATETIME,
    report_time_tz VARCHAR(32),
    wind_speed_ms DECIMAL(6,2) NOT NULL,
    active_power_kw DECIMAL(10,2) NOT NULL,
    daily_energy_kwh DECIMAL(12,2) NOT NULL,
    wind_direction VARCHAR(16),
    temperature_c DECIMAL(6,2),
    operator VARCHAR(32),
    note VARCHAR(128),
    KEY idx_runlog_turbine (turbine_id),
    KEY idx_runlog_farm (farm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 电网限功率指令 ----------------
-- 状态：PLANNED 待执行 / EXECUTING 执行中 / PAUSED 已暂停 / ENDED 已结束 / CANCELLED 已取消
-- 时间全部存绝对时刻（ZonedDateTime + NORMALIZE），另存 ZoneId；跨本地日期或调整风场时区只改展示、不产生执行空档。
-- overlap_mode=COEXIST 时多令并行，生效限值取最严格；SUPERSEDE 必须逐机位不宽于被替代旧令，防止宽松新令解除旧限。
CREATE TABLE grid_limit_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    farm_id BIGINT NOT NULL,
    target_limit_kw DECIMAL(10,2) NOT NULL,
    target_scope VARCHAR(16) NOT NULL,
    allocation_mode VARCHAR(26) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    overlap_mode VARCHAR(16) NOT NULL,
    start_time DATETIME NOT NULL,
    start_time_tz VARCHAR(32),
    end_time DATETIME NOT NULL,
    end_time_tz VARCHAR(32),
    release_condition VARCHAR(256),
    status VARCHAR(16) NOT NULL,
    duty_operator VARCHAR(32),
    confirmed_by VARCHAR(32),
    activated_at DATETIME,
    activated_at_tz VARCHAR(32),
    paused_at DATETIME,
    paused_at_tz VARCHAR(32),
    resumed_at DATETIME,
    resumed_at_tz VARCHAR(32),
    ended_at DATETIME,
    ended_at_tz VARCHAR(32),
    cancelled_at DATETIME,
    cancelled_at_tz VARCHAR(32),
    release_result VARCHAR(256),
    release_operator VARCHAR(32),
    supersedes_order_id BIGINT,
    note VARCHAR(256),
    lock_version INT DEFAULT 0,
    created_at DATETIME,
    updated_at DATETIME,
    KEY idx_grid_order_farm_time (farm_id, start_time, end_time),
    KEY idx_grid_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 指令适用机位快照；ALLOCATED 是进入执行时按当前可运行状态确认的分摊机位
CREATE TABLE grid_limit_turbine (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    turbine_id BIGINT NOT NULL,
    unit_code VARCHAR(32),
    selected TINYINT(1) NOT NULL DEFAULT 1,
    allocation_state VARCHAR(24) NOT NULL DEFAULT 'SELECTED',
    UNIQUE KEY uk_grid_order_turbine (order_id, turbine_id),
    KEY idx_grid_turbine_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 机位分摊结果：执行时不可超过额定功率；EXCLUDED 表示停运 / 检修 / 故障，不占用场站目标额度
CREATE TABLE grid_limit_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    turbine_id BIGINT NOT NULL,
    farm_id BIGINT NOT NULL,
    unit_code VARCHAR(32),
    rated_power_kw DECIMAL(10,2) NOT NULL,
    limit_kw DECIMAL(10,2) NOT NULL,
    runnable TINYINT(1) NOT NULL DEFAULT 1,
    turbine_status VARCHAR(16),
    allocation_state VARCHAR(24) NOT NULL,
    confirm_operator VARCHAR(32),
    confirmed_at DATETIME,
    confirmed_at_tz VARCHAR(32),
    note VARCHAR(256),
    UNIQUE KEY uk_grid_alloc_order_turbine (order_id, turbine_id),
    KEY idx_grid_alloc_turbine (turbine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 操作轨迹：创建 / 修改 / 开始 / 暂停 / 恢复 / 结束 / 取消 / 分摊确认均追加，不覆盖
CREATE TABLE grid_limit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator VARCHAR(32),
    detail VARCHAR(1024),
    created_at DATETIME,
    created_at_tz VARCHAR(32),
    KEY idx_gridlog_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 偏差与处置：每次读数后台按当时所有执行中指令计算；补录 / 修正生成新版本，原判断保留
CREATE TABLE grid_limit_deviation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    farm_id BIGINT NOT NULL,
    governing_order_id BIGINT,
    order_no VARCHAR(32),
    run_log_id BIGINT,
    turbine_id BIGINT,
    unit_code VARCHAR(32),
    scope VARCHAR(16) NOT NULL,
    record_time DATETIME,
    record_time_tz VARCHAR(32),
    report_time DATETIME,
    report_time_tz VARCHAR(32),
    actual_kw DECIMAL(12,2),
    limit_kw DECIMAL(10,2),
    exceeded_kw DECIMAL(12,2),
    deviation_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision_no INT NOT NULL DEFAULT 1,
    latest_flag TINYINT(1) NOT NULL DEFAULT 1,
    supersedes_deviation_id BIGINT,
    revision_reason VARCHAR(64),
    original_deviation_type VARCHAR(24),
    original_actual_kw DECIMAL(12,2),
    original_limit_kw DECIMAL(10,2),
    handle_note VARCHAR(512),
    review_note VARCHAR(512),
    release_note VARCHAR(512),
    handler VARCHAR(32),
    reviewer VARCHAR(32),
    releaser VARCHAR(32),
    handled_at DATETIME,
    handled_at_tz VARCHAR(32),
    reviewed_at DATETIME,
    reviewed_at_tz VARCHAR(32),
    released_at DATETIME,
    released_at_tz VARCHAR(32),
    created_at DATETIME,
    updated_at DATETIME,
    KEY idx_grid_dev_farm_time (farm_id, record_time),
    KEY idx_grid_dev_order (governing_order_id),
    KEY idx_grid_dev_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 检修工单 ----------------
-- order_no 唯一；maint_type 5 选 1；status 待派 → 作业中 → 待验收 → 已闭环
-- plan_start_time / actual_finish_time 同样走 @TimeZoneStorage(NORMALIZE) 归一化存 DATETIME，
-- plan_start_time_tz / actual_finish_time_tz 存 ZoneId 文本（实体字段 planZone / finishZone），
-- 由 service 按工单所属风场的时区写入
CREATE TABLE work_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    turbine_id BIGINT NOT NULL,
    farm_id BIGINT NOT NULL,
    unit_code VARCHAR(32),
    maint_type VARCHAR(24) NOT NULL,
    title VARCHAR(64),
    crew VARCHAR(32),
    plan_start_time DATETIME,
    plan_start_time_tz VARCHAR(32),
    actual_finish_time DATETIME,
    actual_finish_time_tz VARCHAR(32),
    status VARCHAR(16),
    fault_desc VARCHAR(128),
    note VARCHAR(128),
    -- 验收结论：验收人在「待验收」阶段登记，PASS 验收合格 / FAIL 验收驳回（驳回回作业中）
    -- 隔离票复电的前置条件就是验收合格；未验收不得复电，未恢复隔离不得闭环
    accept_result VARCHAR(16),
    accept_person VARCHAR(32),
    accept_time DATETIME,
    accept_time_tz VARCHAR(32),
    accept_note VARCHAR(128),
    KEY idx_order_turbine (turbine_id),
    KEY idx_order_farm (farm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 检修作业项 ----------------
-- 提验收前所有作业项都必须有 result；done 是勾选状态
CREATE TABLE work_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_order_id BIGINT NOT NULL,
    item_seq INT,
    item_name VARCHAR(64),
    standard VARCHAR(128),
    result VARCHAR(16),
    result_note VARCHAR(128),
    done TINYINT(1) DEFAULT 0,
    note VARCHAR(128),
    KEY idx_item_order (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 备件 ----------------
-- part_code 唯一；safety_stock 不得为负；stock_qty 低于 safety_stock 时允许出库但要打预警标记
CREATE TABLE spare_part (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    part_code VARCHAR(32) NOT NULL UNIQUE,
    part_name VARCHAR(64),
    spec VARCHAR(64),
    category VARCHAR(24),
    unit VARCHAR(8),
    stock_qty INT NOT NULL,
    safety_stock INT NOT NULL,
    location VARCHAR(32),
    unit_price DECIMAL(10,2),
    supplier VARCHAR(64),
    note VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 备件更换登记 ----------------
-- qty > 0 且不得超过当时的库存；warning_flag=1 表示出库后库存低于安全库存
-- client_token：客户端幂等令牌（唯一），同一次更换的重复点击 / 网络重试只留一条有效履历；
--   旧客户端不带令牌，由服务端按 备件+机位+工单+数量+登记人 短窗自然键兜底，不锁死旧登记。
-- source：登记来源（NEW_CLIENT / LEGACY_BOTH / LEGACY_ORDER_ONLY / LEGACY_TURBINE_ONLY / LEGACY_NONE）。
--   只选工单时机位由工单归属补齐；机位与工单冲突的新登记一律拒绝，历史冲突行在页面实时标成「待核对」，
--   只读出结论、不回写历史。
CREATE TABLE part_replacement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    part_id BIGINT NOT NULL,
    part_code VARCHAR(32),
    part_name VARCHAR(64),
    turbine_id BIGINT,
    unit_code VARCHAR(32),
    work_order_id BIGINT,
    order_no VARCHAR(32),
    client_token VARCHAR(64),
    source VARCHAR(24),
    qty INT NOT NULL,
    replace_time DATETIME,
    operator VARCHAR(32),
    warning_flag TINYINT(1) DEFAULT 0,
    stock_after INT,
    note VARCHAR(128),
    UNIQUE KEY uk_replace_client_token (client_token),
    KEY idx_replace_part (part_id),
    KEY idx_replace_turbine (turbine_id),
    KEY idx_replace_order (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 安全隔离点模板 ----------------
-- 按检修类型维护的标准隔离点；建隔离票时把 enabled=1 的点深拷贝成票内快照，
-- 之后模板怎么改都不影响已创建的票（快照不可变，历史确认可追溯）。
-- version 是该条模板点的版本号：任意内容修改 +1，前端用它与票内 snapshot_version 比对，
-- 把"建票后模板又变了"的新增 / 变更风险显式暴露给责任人，而不是被旧快照静默掩盖。
CREATE TABLE isolation_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    maint_type VARCHAR(24) NOT NULL,
    point_seq INT NOT NULL,
    point_name VARCHAR(64) NOT NULL,
    point_type VARCHAR(24),
    standard VARCHAR(128),
    enabled TINYINT(1) DEFAULT 1,
    version INT DEFAULT 1,
    note VARCHAR(128),
    UNIQUE KEY uk_tpl_type_seq (maint_type, point_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 安全隔离票 ----------------
-- 状态：DRAFT 草稿 → CONFIRMING 确认中 → ISOLATED 已隔离 → RESTORING 复电中 → RESTORED 已恢复
-- active_slot：非 RESTORED 恒为 1，RESTORED 置 NULL；配合部分唯一索引
-- uk_ticket_active_turbine 保证「同一机位只能存在一张尚未恢复的隔离票」，并发建票由数据库兜底。
-- 每张票归属一张工单（uk_ticket_order），未达 ISOLATED 工单不能进作业中，未达 RESTORED 不能闭环。
-- template_version：建票时模板版本基线；template_ack_fp：责任人已处理过差异的模板点指纹
--   （id:version 拼接），模板后续新增 / 变更若不在指纹里，会被差异检查重新抛出，不允许静默略过。
-- lock_version：乐观锁，两人同时确认 / 网络重试只产生一次有效推进。
CREATE TABLE safety_ticket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no VARCHAR(32) NOT NULL UNIQUE,
    work_order_id BIGINT NOT NULL,
    order_no VARCHAR(32),
    turbine_id BIGINT NOT NULL,
    farm_id BIGINT NOT NULL,
    unit_code VARCHAR(32),
    maint_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    active_slot BIGINT DEFAULT 1,
    owner VARCHAR(32),
    template_version INT DEFAULT 1,
    template_ack_fp VARCHAR(1024),
    isolated_time DATETIME,
    isolated_time_tz VARCHAR(32),
    restored_time DATETIME,
    restored_time_tz VARCHAR(32),
    note VARCHAR(128),
    lock_version INT DEFAULT 0,
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_ticket_order (work_order_id),
    UNIQUE KEY uk_ticket_active_turbine (turbine_id, active_slot),
    KEY idx_ticket_turbine (turbine_id),
    KEY idx_ticket_farm (farm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 隔离点（票内快照 + 逐项确认） ----------------
-- 每张票的隔离点按 point_seq 升序执行；复电必须严格按 point_seq 降序逐项解除，禁止跳项。
-- 隔离确认采用双人：isolate_operator 操作人 + isolate_verifier 监护人（二人不能相同）。
-- valid_flag=0 表示隔离被判定失效（复查发现挂锁脱落 / 验电异常等），必须重新双人确认后才能继续。
-- 确认 / 复电都走「带状态前置条件的条件更新」，重复点击、网络重试影响行数为 0，天然幂等。
CREATE TABLE isolation_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    point_seq INT NOT NULL,
    point_name VARCHAR(64) NOT NULL,
    point_type VARCHAR(24),
    standard VARCHAR(128),
    source VARCHAR(16) DEFAULT 'TEMPLATE',
    template_id BIGINT,
    snapshot_version INT DEFAULT 1,
    isolated TINYINT(1) DEFAULT 0,
    isolate_operator VARCHAR(32),
    isolate_verifier VARCHAR(32),
    isolate_time DATETIME,
    isolate_time_tz VARCHAR(32),
    valid_flag TINYINT(1) DEFAULT 1,
    restored TINYINT(1) DEFAULT 0,
    restore_operator VARCHAR(32),
    restore_time DATETIME,
    restore_time_tz VARCHAR(32),
    note VARCHAR(128),
    UNIQUE KEY uk_point_ticket_seq (ticket_id, point_seq),
    KEY idx_point_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 隔离票变更流水（完整可追溯记录） ----------------
-- 票的创建、状态推进、每个隔离点的确认 / 失效 / 重新确认 / 复电、模板差异处理全部在此留痕，
-- 只追加不修改；操作人员从工单和机位页面看到的「完整变更记录」就是它。
CREATE TABLE isolation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    point_id BIGINT,
    work_order_id BIGINT,
    turbine_id BIGINT,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status VARCHAR(16),
    operator VARCHAR(32),
    detail VARCHAR(512),
    created_at DATETIME,
    created_at_tz VARCHAR(32),
    KEY idx_iticket_log (ticket_id),
    KEY idx_iturbine_log (turbine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ================= 种子数据 =================
-- 注意：MySQL 容器是 UTC，而 DATETIME 按 Asia/Shanghai 归一化存储，
--       所以时间统一写成 UTC_TIMESTAMP() + INTERVAL 8 HOUR（归一化时区的墙上时间）。
--       *_tz 列存的是记录所属时区的 ZoneId 文本，不是 UTC 偏移。
-- ---------------- 风场 ----------------
INSERT INTO wind_farm (farm_code, farm_name, region, province, time_zone, installed_capacity_mw, turbine_count, grid_date, owner, status, note) VALUES
('WF-HB-01', '张北单晶河风场', '华北', '河北', 'Asia/Shanghai', 148.50, 3, '2021-06-18', '王海涛', 'ACTIVE', '一期示范风场，纳入 3 个标杆机位'),
('WF-NM-02', '乌兰察布辉腾锡勒风场', '华北', '内蒙古', 'Asia/Shanghai', 99.00, 3, '2020-11-05', '李建军', 'ACTIVE', '草原风场，冬季覆冰需重点巡检'),
('WF-XJ-03', '哈密十三间房风场', '西北', '新疆', 'Asia/Urumqi', 148.50, 3, '2022-03-28', '艾力·买买提', 'ACTIVE', '大风区，全年风速偏高，按乌鲁木齐时区排班'),
('WF-XJ-04', '达坂城小草湖风场', '西北', '新疆', 'Asia/Urumqi', 99.00, 3, '2019-09-12', '张伟民', 'MAINTAIN', '场内升压站改造中，部分机位陪停'),
('WF-SC-05', '凉山安宁河谷风场', '西南', '四川', 'Asia/Chongqing', 99.00, 3, '2018-12-20', '陈明辉', 'ACTIVE', '山地风场，运输窗口期受雨季影响');

-- ---------------- 机位 ----------------
INSERT INTO turbine (farm_id, unit_code, unit_name, model, rated_power_kw, hub_height_m, rotor_diameter_m, install_date, status, last_power_kw, today_energy_kwh, total_energy_kwh, total_hours, note) VALUES
(1, 'A01', '1 号机位', '金风 GW155-4.5', 4500.00, 100.00, 155.00, '2021-05-20', 'MAINTAIN', 0.00, 61200.00, 81234500.00, 18240.0, '主控升级隔离中，隔离票 SI-2026-0006 已隔离'),
(1, 'A02', '2 号机位', '远景 EN-171-6.25', 6250.00, 110.00, 171.00, '2021-06-02', 'ONLINE', 5410.00, 88400.00, 92647100.00, 17960.0, '主控程序已升级至 V3.2'),
(1, 'A03', '3 号机位', '明阳 MySE7.0-172', 7000.00, 115.00, 172.00, '2021-06-11', 'MAINTAIN', 0.00, 0.00, 74521800.00, 16620.0, '齿轮箱油样铁含量偏高，半年定检中'),
(2, 'B01', '1 号机位', '运达 WD175-5000', 5000.00, 100.00, 175.00, '2020-10-08', 'ONLINE', 4260.00, 70800.00, 68412500.00, 17320.0, '冬季覆冰期间启用了加热装置'),
(2, 'B02', '2 号机位', '运达 WD175-5000', 5000.00, 100.00, 175.00, '2020-10-15', 'FAULT', 0.00, 0.00, 62308400.00, 16880.0, '变桨电机过温报警停机，不允许登记读数'),
(2, 'B03', '3 号机位', '金风 GW165-4.0', 4000.00, 100.00, 165.00, '2020-11-01', 'MAINTAIN', 0.00, 52600.00, 51739600.00, 16540.0, '年度定检隔离中，隔离票 SI-2026-0005 已隔离，等待验收'),
(3, 'C01', '1 号机位', '金风 GW155-4.5', 4500.00, 100.00, 155.00, '2022-03-10', 'ONLINE', 4180.00, 72600.00, 74120300.00, 15980.0, '大风区主力机位，风速常年偏高'),
(3, 'C02', '2 号机位', '远景 EN-171-6.25', 6250.00, 110.00, 171.00, '2022-03-19', 'ONLINE', 5860.00, 95200.00, 88364500.00, 15720.0, '叶片前缘涂层已做修补'),
(3, 'C03', '3 号机位', '明阳 MySE7.0-172', 7000.00, 115.00, 172.00, '2022-03-25', 'STOPPED', 0.00, 0.00, 69204100.00, 15310.0, '配合升压站改造陪停'),
(4, 'D01', '1 号机位', '运达 WD175-5000', 5000.00, 100.00, 175.00, '2019-08-22', 'MAINTAIN', 0.00, 66400.00, 81524700.00, 21460.0, '液压站渗油抢修，隔离票 SI-2026-0009 确认中（剩高压侧挂牌 / 机械锁定）'),
(4, 'D02', '2 号机位', '运达 WD175-5000', 5000.00, 100.00, 175.00, '2019-09-01', 'MAINTAIN', 0.00, 0.00, 78316900.00, 21030.0, '发电机前轴承异响，大修中'),
(4, 'D03', '3 号机位', '金风 GW165-4.0', 4000.00, 100.00, 165.00, '2019-09-09', 'MAINTAIN', 0.00, 48300.00, 69235800.00, 20780.0, '风速仪更换完工，隔离票 SI-2026-0004 已隔离待验收复电'),
(5, 'E01', '1 号机位', '运达 WD175-5000', 5000.00, 100.00, 175.00, '2018-11-28', 'ONLINE', 3640.00, 59800.00, 74318500.00, 22160.0, '塔筒法兰螺栓已完成力矩复紧'),
(5, 'E02', '2 号机位', '金风 GW165-4.0', 4000.00, 100.00, 165.00, '2018-12-06', 'ONLINE', 2680.00, 45200.00, 65127400.00, 21840.0, '山地湍流强度偏高，功率曲线略有折损'),
(5, 'E03', '3 号机位', '远景 EN-171-6.25', 6250.00, 110.00, 171.00, '2018-12-15', 'MAINTAIN', 0.00, 0.00, 69812300.00, 21520.0, '叶片前缘保护膜整体更换中');

-- ---------------- 运行日志 ----------------
-- 每个在运机位两条读数；record_time_tz / report_time_tz 写该记录所属风场的时区（ZoneId 文本）
INSERT INTO run_log (turbine_id, farm_id, unit_code, record_time, record_time_tz, report_time, report_time_tz, wind_speed_ms, active_power_kw, daily_energy_kwh, wind_direction, temperature_c, operator, note) VALUES
(1, 1, 'A01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 35 MINUTE, 'Asia/Shanghai', 8.20, 3260.00, 38600.00, '西北', 12.60, '值长-王磊', '早班风速爬升，机组接近额定'),
(1, 1, 'A01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 35 MINUTE, 'Asia/Shanghai', 7.80, 3820.00, 61200.00, '西北', 15.20, '值班员-赵倩', '午间风速回落，出力稳定'),
(2, 1, 'A02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 30 MINUTE, 'Asia/Shanghai', 9.40, 4980.00, 52400.00, '西北', 12.10, '值长-王磊', '大机型满发时长占比高'),
(2, 1, 'A02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 30 MINUTE, 'Asia/Shanghai', 8.90, 5410.00, 88400.00, '北', 15.80, '值班员-赵倩', '偏航对风正常，无异常振动'),
(4, 2, 'B01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 40 MINUTE, 'Asia/Shanghai', 8.60, 3960.00, 43600.00, '北', 6.40, '值长-孙立新', '低温启动，齿轮箱油温 42℃'),
(4, 2, 'B01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 40 MINUTE, 'Asia/Shanghai', 8.10, 4260.00, 70800.00, '北', 9.70, '值班员-周涛', '覆冰监测系统未报警'),
(6, 2, 'B03', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 25 MINUTE, 'Asia/Shanghai', 8.00, 2980.00, 31200.00, '东北', 6.90, '值长-孙立新', '定检窗口临近，出力保持中低负荷'),
(6, 2, 'B03', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Shanghai', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 25 MINUTE, 'Asia/Shanghai', 7.50, 3120.00, 52600.00, '东北', 10.30, '值班员-周涛', '机舱温度正常'),
(7, 3, 'C01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 20 MINUTE, 'Asia/Urumqi', 12.40, 4320.00, 47200.00, '西', 21.50, '值长-艾力', '大风区满发，注意切入切出次数'),
(7, 3, 'C01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 20 MINUTE, 'Asia/Urumqi', 11.10, 4180.00, 72600.00, '西北', 24.80, '值班员-古丽', '机组未进入切出保护'),
(8, 3, 'C02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 45 MINUTE, 'Asia/Urumqi', 13.10, 5760.00, 60800.00, '西', 22.10, '值长-艾力', '风速逼近额定风速'),
(8, 3, 'C02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 45 MINUTE, 'Asia/Urumqi', 12.00, 5860.00, 95200.00, '西北', 25.30, '值班员-古丽', '叶片前缘修补后运行平稳'),
(10, 4, 'D01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 50 MINUTE, 'Asia/Urumqi', 10.60, 4380.00, 41800.00, '西北', 18.40, '值长-张伟民', '液压站压力正常'),
(10, 4, 'D01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 50 MINUTE, 'Asia/Urumqi', 9.70, 3980.00, 66400.00, '西北', 20.60, '值班员-马超', '油管接头仍有轻微渗油，已挂工单'),
(12, 4, 'D03', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 55 MINUTE, 'Asia/Urumqi', 9.10, 3260.00, 28600.00, '北', 17.90, '值长-张伟民', '风速仪更换后首次全天记录'),
(12, 4, 'D03', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 55 MINUTE, 'Asia/Urumqi', 8.30, 2860.00, 48300.00, '北', 19.40, '值班员-马超', '数据跳变问题已消除'),
(13, 5, 'E01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Chongqing', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 15 MINUTE, 'Asia/Chongqing', 8.80, 3420.00, 36400.00, '西南', 16.20, '值长-陈明辉', '山地湍流强度 0.12'),
(13, 5, 'E01', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Chongqing', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 15 MINUTE, 'Asia/Chongqing', 8.20, 3640.00, 59800.00, '西南', 18.70, '值班员-李婷', '塔筒振动在正常范围'),
(14, 5, 'E02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR, 'Asia/Chongqing', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 HOUR - INTERVAL 10 MINUTE, 'Asia/Chongqing', 7.60, 2480.00, 27400.00, '南', 16.80, '值长-陈明辉', '功率曲线受地形折损约 4%'),
(14, 5, 'E02', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 'Asia/Chongqing', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR - INTERVAL 10 MINUTE, 'Asia/Chongqing', 7.10, 2680.00, 45200.00, '南', 19.10, '值班员-李婷', '机舱温度 38℃，散热良好');

-- ---------------- 检修工单 ----------------
-- 待派 → 作业中 → 待验收 → 已闭环；同一机位不同时存在两张作业中的工单
INSERT INTO work_order (order_no, turbine_id, farm_id, unit_code, maint_type, title, crew, plan_start_time, plan_start_time_tz, actual_finish_time, actual_finish_time_tz, status, fault_desc, note) VALUES
('WO-2026-0001', 3, 1, 'A03', 'ROUTINE', '3 号机位半年定检', '检修一班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 HOUR, 'Asia/Shanghai', NULL, NULL, 'PENDING', '齿轮箱油样铁含量偏高', '待排期，需配合厂家取样'),
('WO-2026-0002', 5, 2, 'B02', 'EMERGENCY', '2 号机位变桨电机故障抢修', '抢修二班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR, 'Asia/Shanghai', NULL, NULL, 'WORKING', '变桨电机过温报警停机', '备件已从 2 号库 A 区领出'),
('WO-2026-0003', 11, 4, 'D02', 'OVERHAUL', '2 号机位发电机大修', '外协-华电检修', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR, 'Asia/Urumqi', NULL, NULL, 'WORKING', '发电机前轴承异响', '需吊车配合，窗口期两天'),
('WO-2026-0004', 12, 4, 'D03', 'TEMPORARY', '3 号机位风速仪更换', '检修三班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 HOUR, 'Asia/Urumqi', NULL, NULL, 'CHECKING', '风速仪数据跳变', '更换后观察 24 小时'),
('WO-2026-0005', 6, 2, 'B03', 'ROUTINE', '3 号机位年度定检', '检修一班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 HOUR, 'Asia/Shanghai', NULL, NULL, 'CHECKING', NULL, '按年度计划执行'),
('WO-2026-0006', 1, 1, 'A01', 'RETROFIT', '1 号机位主控程序升级', '技术组', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 48 HOUR, 'Asia/Shanghai', NULL, NULL, 'CHECKING', '主控程序版本过低', '升级包 V3.3 已验证'),
('WO-2026-0007', 8, 3, 'C02', 'ROUTINE', '2 号机位叶片巡检', '检修二班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 72 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 46 HOUR, 'Asia/Urumqi', 'CLOSED', '叶片前缘涂层轻微脱落', '已完成前缘补漆'),
('WO-2026-0008', 7, 3, 'C01', 'TEMPORARY', '1 号机位偏航对风校准', '检修一班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 96 HOUR, 'Asia/Urumqi', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 HOUR, 'Asia/Urumqi', 'CLOSED', NULL, '对风偏差由 6° 降至 1.5°'),
('WO-2026-0009', 2, 1, 'A02', 'ROUTINE', '2 号机位半年定检', '检修三班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 HOUR, 'Asia/Shanghai', NULL, NULL, 'PENDING', NULL, '与主控升级窗口错开'),
('WO-2026-0010', 10, 4, 'D01', 'EMERGENCY', '1 号机位液压站渗油处理', '抢修一班', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, 'Asia/Urumqi', NULL, NULL, 'WORKING', '液压站油管接头渗油', '需停机泄压后更换接头');

-- ---------------- 检修作业项 ----------------
INSERT INTO work_item (work_order_id, item_seq, item_name, standard, result, result_note, done, note) VALUES
(1, 1, '齿轮箱油样取样送检', '取油口清洁后取样 250ml，24 小时内送检', NULL, NULL, 0, '等厂家化验单'),
(2, 1, '变桨电机拆线验电', '断开 400V 电源并挂锁，验电确认无电压', 'PASS', '验电正常，已挂锁', 1, NULL),
(2, 2, '变桨电机整体更换', '电机法兰螺栓力矩 220N·m，接线相序核对', NULL, NULL, 0, '备件已就位'),
(3, 1, '发电机前轴承拆解检查', '记录滚道点蚀面积，拍照存档', NULL, NULL, 0, '需吊车配合'),
(3, 2, '轴承游隙复测', '径向游隙控制在 0.12~0.20mm', NULL, NULL, 0, NULL),
(4, 1, '旧风速仪拆除', '拆除前记录安装方位角，做好防水封堵', 'PASS', '封堵完好', 1, NULL),
(4, 2, '新风速仪安装标定', '与参考风速仪比对，偏差不超过 0.3m/s', 'PASS', '比对偏差 0.12m/s', 1, NULL),
(5, 1, '塔筒内附件紧固检查', '按力矩表复紧，标记线对齐', 'PASS', '复紧 46 处', 1, NULL),
(5, 2, '偏航制动器间隙测量', '间隙 1.5~2.0mm', 'PASS', '实测 1.8mm', 1, NULL),
(6, 1, '主控程序备份', '备份原版本程序与参数表', 'PASS', '已备份至场站服务器', 1, NULL),
(6, 2, '程序升级与联调', '升级后空载试运行 2 小时无报警', 'SKIP', '延至下一个窗口期联调', 1, '窗口期不足'),
(7, 1, '叶片前缘目视检查', '记录涂层脱落位置与面积', 'PASS', '脱落面积 0.3㎡', 1, NULL),
(7, 2, '叶片雷电接闪器检查', '接闪器无松动、无烧蚀', 'PASS', '状态良好', 1, NULL),
(8, 1, '偏航对风偏差测量', '用激光对中仪测量，偏差不超过 2°', 'PASS', '校准后偏差 1.5°', 1, NULL);

-- ---------------- 备件 ----------------
INSERT INTO spare_part (part_code, part_name, spec, category, unit, stock_qty, safety_stock, location, unit_price, supplier, note) VALUES
('SP-BLADE-001', '叶片前缘保护膜', '3M LM76.4-B', 'BLADE', '卷', 12, 6, '1 号库 A 区', 860.00, '戈尔特斯复合材料', '叶片前缘修补常用'),
('SP-LUBE-001', '齿轮箱润滑油', 'Mobil SHC 320', 'LUBE', '桶', 14, 6, '1 号库 B 区', 2480.00, '埃克森美孚', '半年定检换油'),
('SP-GEAR-001', '齿轮箱高速轴轴承', '32226', 'GEARBOX', '套', 3, 4, '1 号库 B 区', 18600.00, '洛阳 LYC', '库存已低于安全库存，采购在途'),
('SP-PITCH-001', '变桨电机', 'KEBA 3.5kW', 'PITCH', '台', 5, 2, '2 号库 A 区', 12800.00, 'KEBA 中国', '2 号机位抢修备件'),
('SP-PITCH-002', '变桨超级电容', '60F/48V', 'PITCH', '组', 4, 6, '2 号库 A 区', 3400.00, '南通江海', '库存偏低，需尽快补货'),
('SP-ELEC-001', '主控 PLC 模块', 'CPU1511-1', 'ELECTRIC', '件', 7, 3, '电气库房', 22400.00, '西门子', '主控程序升级配套'),
('SP-ELEC-002', '风速仪', 'WS700', 'ELECTRIC', '支', 9, 4, '电气库房', 5600.00, '维萨拉', '风速仪更换备件'),
('SP-HYD-001', '液压站油管接头', 'M22×1.5', 'HYDRAULIC', '件', 18, 8, '2 号库 B 区', 168.00, '派克汉尼汾', '液压站渗油处理'),
('SP-HYD-002', '液压油', 'ISO VG 46', 'HYDRAULIC', '桶', 14, 6, '2 号库 B 区', 1250.00, '壳牌统一', '偏航液压系统用油'),
('SP-FAST-001', '塔筒高强螺栓', 'M36×220 10.9 级', 'FASTENER', '套', 120, 50, '露天堆场', 96.00, '上海金马', '塔筒法兰紧固');

-- ---------------- 备件更换登记 ----------------
-- stock_after 与 spare_part 当前库存一致；warning_flag=1 表示出库后低于安全库存
INSERT INTO part_replacement (part_id, part_code, part_name, turbine_id, unit_code, work_order_id, order_no, qty, replace_time, operator, warning_flag, stock_after, note) VALUES
(4, 'SP-PITCH-001', '变桨电机', 5, 'B02', 2, 'WO-2026-0002', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 HOUR, '抢修二班-刘强', 0, 5, '2 号机位变桨电机整体更换'),
(7, 'SP-ELEC-002', '风速仪', 12, 'D03', 4, 'WO-2026-0004', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 HOUR, '检修三班-周涛', 0, 9, '风速仪数据跳变更换'),
(3, 'SP-GEAR-001', '齿轮箱高速轴轴承', 11, 'D02', 3, 'WO-2026-0003', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 24 HOUR, '外协-华电检修-赵鹏', 1, 3, '出库后库存 3 低于安全库存 4，已标记预警'),
(5, 'SP-PITCH-002', '变桨超级电容', 8, 'C02', 7, 'WO-2026-0007', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 HOUR, '检修二班-马超', 1, 4, '出库后库存 4 低于安全库存 6，已标记预警'),
(10, 'SP-FAST-001', '塔筒高强螺栓', 13, 'E01', NULL, NULL, 20, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 HOUR, '检修一班-孙磊', 0, 120, '塔筒法兰力矩复紧'),
(8, 'SP-HYD-001', '液压站油管接头', 10, 'D01', 10, 'WO-2026-0010', 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, '抢修一班-钱伟', 0, 18, '液压站渗油接头更换'),
(1, 'SP-BLADE-001', '叶片前缘保护膜', 15, 'E03', NULL, NULL, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 14 HOUR, '检修二班-马超', 0, 12, '叶片前缘保护膜整体更换'),
(9, 'SP-HYD-002', '液压油', 1, 'A01', NULL, NULL, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 HOUR, '检修一班-孙磊', 0, 14, '偏航液压系统换油'),
(6, 'SP-ELEC-001', '主控 PLC 模块', 2, 'A02', 6, 'WO-2026-0006', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 HOUR, '技术组-郑工', 0, 7, '旧系统登记：机位 A02 与工单 WO-2026-0006 所属机位 A01 不一致，待核对');

-- ================= 安全隔离票闭环种子数据 =================
-- 已闭环工单补验收结论（历史闭环数据自洽：验收合格 → 复电恢复 → 闭环）
UPDATE work_order SET accept_result='PASS', accept_person='风场安全员-高峰',
    accept_time = UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 47 HOUR, accept_time_tz='Asia/Urumqi',
    accept_note='叶片巡检结果合格，允许复电闭环'
WHERE order_no='WO-2026-0007';
UPDATE work_order SET accept_result='PASS', accept_person='风场安全员-高峰',
    accept_time = UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 71 HOUR, accept_time_tz='Asia/Urumqi',
    accept_note='偏航校准偏差合格，允许复电闭环'
WHERE order_no='WO-2026-0008';

-- ---------------- 安全隔离点模板（按检修类型） ----------------
-- 建票时按 maint_type 深拷贝 enabled=1 的点；version 是模板点版本，改动 +1
INSERT INTO isolation_template (maint_type, point_seq, point_name, point_type, standard, enabled, version, note) VALUES
-- 定期维护 ROUTINE（模板点 1 在建票种子后升到 v2，用来演示「模板后续调整不改动旧快照，但变更会被显式提示」）
('ROUTINE', 1, '远程停机并切维护模式', 'SHUTDOWN', '监控后台远方停机，机组进入维护模式，叶轮静止', 1, 1, '定检首步'),
('ROUTINE', 2, '箱变低压侧停电', 'POWER_OFF', '断开 690V 主断路器，操作把手置停止位', 1, 1, NULL),
('ROUTINE', 3, '逐相验电', 'VOLTAGE_TEST', '高压验电笔对三相分别验电，确认无电压', 1, 1, NULL),
('ROUTINE', 4, '主断路器挂牌上锁', 'LOCK_OUT', '挂"禁止合闸 有人工作"牌并加个人锁', 1, 1, NULL),
-- 临时检修 TEMPORARY
('TEMPORARY', 1, '远程停机并切维护模式', 'SHUTDOWN', '监控后台远方停机，机组进入维护模式，叶轮静止', 1, 1, NULL),
('TEMPORARY', 2, '箱变低压侧停电', 'POWER_OFF', '断开 690V 主断路器，操作把手置停止位', 1, 1, NULL),
('TEMPORARY', 3, '逐相验电', 'VOLTAGE_TEST', '高压验电笔对三相分别验电，确认无电压', 1, 1, NULL),
-- 紧急抢修 EMERGENCY（保留 seq 5 的空号：模板后续新增的点，用来演示新增风险不被旧票静默掩盖）
('EMERGENCY', 1, '紧急停机（急停按钮）', 'SHUTDOWN', '塔筒底部 / 机舱急停按钮拍下，机组立即停机', 1, 1, '抢修首步'),
('EMERGENCY', 2, '箱变低压侧停电', 'POWER_OFF', '断开 690V 主断路器，操作把手置停止位', 1, 1, NULL),
('EMERGENCY', 3, '逐相验电', 'VOLTAGE_TEST', '高压验电笔对三相分别验电，确认无电压', 1, 1, NULL),
('EMERGENCY', 4, '主断路器挂牌上锁', 'LOCK_OUT', '挂"禁止合闸 有人工作"牌并加个人锁', 1, 1, NULL),
-- 技术改造 RETROFIT
('RETROFIT', 1, '远程停机并切维护模式', 'SHUTDOWN', '监控后台远方停机，机组进入维护模式，叶轮静止', 1, 1, NULL),
('RETROFIT', 2, '箱变高低压侧全停', 'POWER_OFF', '断开低压 690V 及高压侧负荷开关，验明双侧无电', 1, 1, '改造涉及主控，双侧全停'),
('RETROFIT', 3, '逐相验电并放电', 'VOLTAGE_TEST', '三相验电无压后对电容 / 母排充分放电', 1, 1, NULL),
('RETROFIT', 4, '主断路器挂牌上锁', 'LOCK_OUT', '挂"禁止合闸 有人工作"牌并加个人锁', 1, 1, NULL),
-- 大修 OVERHAUL
('OVERHAUL', 1, '远程停机并切维护模式', 'SHUTDOWN', '监控后台远方停机，机组进入维护模式，叶轮静止', 1, 1, NULL),
('OVERHAUL', 2, '箱变高低压侧全停', 'POWER_OFF', '断开低压 690V 及高压侧负荷开关，验明双侧无电', 1, 1, NULL),
('OVERHAUL', 3, '逐相验电并放电', 'VOLTAGE_TEST', '三相验电无压后对电容 / 母排充分放电', 1, 1, NULL),
('OVERHAUL', 4, '叶轮机械锁定与液压泄压', 'MECH_LOCK', '插入高速轴锁销，盘车确认锁定；液压系统泄压至零', 1, 1, '大修吊装前必须确认'),
-- 停用的模板点：enabled=0 不会带入新票，也不影响旧票
('OVERHAUL', 9, '（旧版）塔筒照明支路停电', 'POWER_OFF', '停用：照明已改常备安全电', 0, 1, '历史模板点保留备查');

-- 模板后续调整演示：定期维护模板点 1 升级到 v2（已创建的票仍是 v1 快照，差异检查会提示）
UPDATE isolation_template SET version=2, standard='监控后台远方停机，机组进入维护模式，叶轮静止且持续 10 分钟无自转'
WHERE maint_type='ROUTINE' AND point_seq=1;

-- ---------------- 安全隔离票 ----------------
-- 每张工单一张票：草稿(1) / 确认中(9) / 已隔离(2,3,4,5,6) / 已恢复(7,8)
-- active_slot：未恢复=1，已恢复=NULL（部分唯一索引保证同一机位至多一张未恢复票）
INSERT INTO safety_ticket
(id, ticket_no, work_order_id, order_no, turbine_id, farm_id, unit_code, maint_type, status, active_slot, owner,
 template_version, template_ack_fp, isolated_time, isolated_time_tz, restored_time, restored_time_tz, note, lock_version, created_at, updated_at) VALUES
(1, 'SI-2026-0001', 1, 'WO-2026-0001', 3, 1, 'A03', 'ROUTINE', 'DRAFT', 1, '检修一班-孙磊', 1, NULL,
 NULL, NULL, NULL, NULL, '草稿：模板点已带入，尚未开始确认；模板点 1 已有新版本', 0,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 HOUR),
(2, 'SI-2026-0002', 2, 'WO-2026-0002', 5, 2, 'B02', 'EMERGENCY', 'ISOLATED', 1, '抢修二班-刘强', 1, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 HOUR, 'Asia/Shanghai', NULL, NULL,
 '已隔离，抢修作业中；模板后续新增「变桨储能放电」点，本票待补风险提示中', 3,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 HOUR),
(3, 'SI-2026-0003', 3, 'WO-2026-0003', 11, 4, 'D02', 'OVERHAUL', 'ISOLATED', 1, '外协-华电检修-赵鹏', 1, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 HOUR, 'Asia/Urumqi', NULL, NULL,
 '已隔离，机械锁定与液压泄压均双人确认', 2,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 HOUR),
(4, 'SI-2026-0004', 4, 'WO-2026-0004', 12, 4, 'D03', 'TEMPORARY', 'ISOLATED', 1, '检修三班-周涛', 1, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 33 HOUR, 'Asia/Urumqi', NULL, NULL,
 '作业已完工待验收；未验收前不允许开始复电', 1,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 33 HOUR),
(5, 'SI-2026-0005', 5, 'WO-2026-0005', 6, 2, 'B03', 'ROUTINE', 'ISOLATED', 1, '检修一班-孙磊', 1, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 39 HOUR, 'Asia/Shanghai', NULL, NULL,
 '定检完成等待验收；模板点 1 已升级 v2，本票隔离时按 v1 执行并已确认，历史记录不可变', 1,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 39 HOUR),
(6, 'SI-2026-0006', 6, 'WO-2026-0006', 1, 1, 'A01', 'RETROFIT', 'ISOLATED', 1, '技术组-郑工', 1, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 47 HOUR, 'Asia/Shanghai', NULL, NULL,
 '主控升级隔离中，延至下一窗口联调', 1,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 48 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 47 HOUR),
(7, 'SI-2026-0007', 7, 'WO-2026-0007', 8, 3, 'C02', 'ROUTINE', 'RESTORED', NULL, '检修二班-马超', 1, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 51 HOUR, 'Asia/Urumqi',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 46 HOUR, 'Asia/Urumqi',
 '验收合格后按隔离逆序逐项复电恢复，机位已重新并网', 5,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 52 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 46 HOUR),
(8, 'SI-2026-0008', 8, 'WO-2026-0008', 7, 3, 'C01', 'TEMPORARY', 'RESTORED', NULL, '检修一班-孙磊', 1, NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 75 HOUR, 'Asia/Urumqi',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 HOUR, 'Asia/Urumqi',
 '验收合格后逆序复电恢复', 5,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 76 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 70 HOUR),
(9, 'SI-2026-0009', 10, 'WO-2026-0010', 10, 4, 'D01', 'EMERGENCY', 'CONFIRMING', 1, '抢修一班-钱伟', 1, NULL,
 NULL, NULL, NULL, NULL,
 '确认中：停机 / 停电已双人确认，待验电、挂牌上锁；模板新增「变桨储能放电」点尚未处理，不能直接隔离', 1,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- ---------------- 隔离点（票内快照） ----------------
-- 已隔离票：全部 isolated=1、valid_flag=1；确认中票：按已完成进度部分 isolated=1；
-- 已恢复票：isolated=1 且 restored=1，复电时刻按 seq 逆序递增（先恢复高序号）。
-- 定期维护 / 临时检修：3~4 点；紧急抢修：4 点（模板新增点不进旧票快照）；技术改造 4 点；大修 4 点。
INSERT INTO isolation_point
(ticket_id, point_seq, point_name, point_type, standard, source, template_id, snapshot_version, isolated,
 isolate_operator, isolate_verifier, isolate_time, isolate_time_tz, valid_flag,
 restored, restore_operator, restore_time, restore_time_tz, note)
SELECT t.id, tp.point_seq, tp.point_name, tp.point_type, tp.standard, 'TEMPLATE', tp.id, 1,
  CASE WHEN t.status IN ('ISOLATED','RESTORED','CONFIRMING') AND
            (t.status <> 'CONFIRMING' OR tp.point_seq <= 2) THEN 1 ELSE 0 END,
  CASE WHEN t.status IN ('ISOLATED','RESTORED') OR (t.status='CONFIRMING' AND tp.point_seq <= 2) THEN t.owner END,
  CASE WHEN t.status IN ('ISOLATED','RESTORED') OR (t.status='CONFIRMING' AND tp.point_seq <= 2)
       THEN CASE WHEN t.farm_id=4 THEN '风场安全员-王安全' ELSE '值班员-周涛' END END,
  CASE WHEN t.status IN ('ISOLATED','RESTORED') THEN t.isolated_time
       WHEN t.status='CONFIRMING' AND tp.point_seq <= 2 THEN UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR END,
  CASE WHEN t.status IN ('ISOLATED','RESTORED') THEN t.isolated_time_tz
       WHEN t.status='CONFIRMING' AND tp.point_seq <= 2 THEN 'Asia/Urumqi' END,
  1,
  CASE WHEN t.status='RESTORED' THEN 1 ELSE 0 END,
  CASE WHEN t.status='RESTORED' THEN t.owner END,
  CASE WHEN t.status='RESTORED'
       THEN DATE_ADD(t.restored_time, INTERVAL (1 - tp.point_seq) MINUTE) END,
  CASE WHEN t.status='RESTORED' THEN t.restored_time_tz END,
  NULL
FROM safety_ticket t
JOIN isolation_template tp
  ON tp.maint_type=t.maint_type AND tp.enabled=1 AND tp.point_seq < 9;

-- 票 1（草稿）的点全部置为未确认
UPDATE isolation_point SET isolated=0, isolate_operator=NULL, isolate_verifier=NULL,
 isolate_time=NULL, isolate_time_tz=NULL WHERE ticket_id=1;

-- ---------------- 隔离票变更流水（种子演示完整变更记录） ----------------
INSERT INTO isolation_log (ticket_id, point_id, work_order_id, turbine_id, action, from_status, to_status, operator, detail, created_at, created_at_tz)
SELECT t.id, NULL, t.work_order_id, t.turbine_id, 'CREATE', NULL, 'DRAFT', t.owner,
 CONCAT('创建隔离票 ', t.ticket_no, '，按「', t.maint_type, '」模板带入隔离点'), t.created_at,
 (SELECT wf.time_zone FROM wind_farm wf WHERE wf.id=t.farm_id)
FROM safety_ticket t;

INSERT INTO isolation_log (ticket_id, point_id, work_order_id, turbine_id, action, from_status, to_status, operator, detail, created_at, created_at_tz)
SELECT t.id, NULL, t.work_order_id, t.turbine_id, 'START_CONFIRM', 'DRAFT', 'CONFIRMING', t.owner,
 '开始安全隔离确认，机位转为检修状态', DATE_ADD(t.created_at, INTERVAL 10 MINUTE),
 (SELECT wf.time_zone FROM wind_farm wf WHERE wf.id=t.farm_id)
FROM safety_ticket t WHERE t.status IN ('CONFIRMING','ISOLATED','RESTORED');

INSERT INTO isolation_log (ticket_id, point_id, work_order_id, turbine_id, action, from_status, to_status, operator, detail, created_at, created_at_tz)
SELECT t.id, p.id, t.work_order_id, t.turbine_id, 'ISOLATE_POINT', NULL, NULL, p.isolate_operator,
 CONCAT(p.point_seq, '. ', p.point_name, ' 已隔离确认（监护人：', IFNULL(p.isolate_verifier,''), '）'),
 p.isolate_time, p.isolate_time_tz
FROM isolation_point p JOIN safety_ticket t ON t.id=p.ticket_id
WHERE p.isolated=1;

INSERT INTO isolation_log (ticket_id, point_id, work_order_id, turbine_id, action, from_status, to_status, operator, detail, created_at, created_at_tz)
SELECT t.id, NULL, t.work_order_id, t.turbine_id, 'ISOLATED', 'CONFIRMING', 'ISOLATED', t.owner,
 '全部隔离点双人确认完成，隔离生效，允许工单进入作业中', t.isolated_time, t.isolated_time_tz
FROM safety_ticket t WHERE t.status IN ('ISOLATED','RESTORED');

INSERT INTO isolation_log (ticket_id, point_id, work_order_id, turbine_id, action, from_status, to_status, operator, detail, created_at, created_at_tz)
SELECT t.id, NULL, t.work_order_id, t.turbine_id, 'START_RESTORE', 'ISOLATED', 'RESTORING', t.owner,
 '工单验收合格，开始按隔离逆序复电', t.restored_time, t.restored_time_tz
FROM safety_ticket t WHERE t.status='RESTORED';

INSERT INTO isolation_log (ticket_id, point_id, work_order_id, turbine_id, action, from_status, to_status, operator, detail, created_at, created_at_tz)
SELECT t.id, p.id, t.work_order_id, t.turbine_id, 'RESTORE_POINT', NULL, NULL, p.restore_operator,
 CONCAT(p.point_seq, '. ', p.point_name, ' 已按逆序解除'), p.restore_time, p.restore_time_tz
FROM isolation_point p JOIN safety_ticket t ON t.id=p.ticket_id
WHERE p.restored=1;

INSERT INTO isolation_log (ticket_id, point_id, work_order_id, turbine_id, action, from_status, to_status, operator, detail, created_at, created_at_tz)
SELECT t.id, NULL, t.work_order_id, t.turbine_id, 'RESTORED', 'RESTORING', 'RESTORED', t.owner,
 '全部隔离点逆序解除完毕，隔离恢复，机位允许重新并网', t.restored_time, t.restored_time_tz
FROM safety_ticket t WHERE t.status='RESTORED';

-- 模板后续新增风险点演示：紧急抢修新增 seq 5（已创建的抢修票 2 / 9 快照里没有，差异检查会强制提示）
INSERT INTO isolation_template (maint_type, point_seq, point_name, point_type, standard, enabled, version, note)
VALUES ('EMERGENCY', 5, '变桨超级电容残余电压放电', 'VOLTAGE_TEST', '断开变桨电源后对超级电容组逐组放电至 0V 并复测', 1, 1, '新增风险点：旧抢修票需补确认或责任人留痕忽略');
