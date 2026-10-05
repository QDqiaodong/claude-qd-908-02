package com.wind.farm.common;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * 陆上风电场词典与业务规则参数表：
 * 场站本地时区、机位状态与流转、检修类型 5 选 1、工单状态与流转、作业项结果、备件分类，
 * 以及额定功率 / 风速 / 日发电量上限等规则参数。
 * 规则集中在此，便于出题时被要求调整或扩展。
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 场站本地时区（运行日志 / 检修记录按它归一化存储） ----------------
    public static final List<String> TIME_ZONES = List.of(
            "Asia/Shanghai", "Asia/Chongqing", "Asia/Urumqi", "Asia/Kashgar");

    private static final Map<String, String> TIME_ZONE_NAME = Map.of(
            "Asia/Shanghai", "东八区 · 北京时",
            "Asia/Chongqing", "东八区 · 重庆时",
            "Asia/Urumqi", "东六区 · 乌鲁木齐时",
            "Asia/Kashgar", "东六区 · 喀什时");

    // ---------------- 风场状态 ----------------
    public static final List<String> FARM_STATUSES = List.of("ACTIVE", "MAINTAIN", "STOPPED");

    private static final Map<String, String> FARM_STATUS = Map.of(
            "ACTIVE", "在运",
            "MAINTAIN", "检修",
            "STOPPED", "停运");

    // ---------------- 机位状态 ----------------
    public static final List<String> TURBINE_STATUSES = List.of("ONLINE", "MAINTAIN", "FAULT", "STOPPED");

    private static final Map<String, String> TURBINE_STATUS = Map.of(
            "ONLINE", "并网",
            "MAINTAIN", "检修",
            "FAULT", "故障",
            "STOPPED", "停运");

    private static final Map<String, String> TURBINE_COLOR = Map.of(
            "ONLINE", "#2e9e5b",
            "MAINTAIN", "#f08c00",
            "FAULT", "#e2483d",
            "STOPPED", "#8b95a5");

    /** 机位状态流转：并网 ⇄ 检修 / 故障 / 停运；故障机位不允许登记发电读数 */
    private static final Map<String, List<String>> TURBINE_FLOW = Map.of(
            "ONLINE", List.of("MAINTAIN", "FAULT", "STOPPED"),
            "MAINTAIN", List.of("ONLINE", "FAULT", "STOPPED"),
            "FAULT", List.of("MAINTAIN", "ONLINE", "STOPPED"),
            "STOPPED", List.of("ONLINE", "MAINTAIN"));

    // ---------------- 检修类型 5 选 1 ----------------
    public static final List<String> MAINT_TYPES = List.of(
            "ROUTINE", "TEMPORARY", "EMERGENCY", "RETROFIT", "OVERHAUL");

    private static final Map<String, String> MAINT_TYPE = Map.of(
            "ROUTINE", "定期维护",
            "TEMPORARY", "临时检修",
            "EMERGENCY", "紧急抢修",
            "RETROFIT", "技术改造",
            "OVERHAUL", "大修");

    private static final Map<String, String> MAINT_COLOR = Map.of(
            "ROUTINE", "#00838f",
            "TEMPORARY", "#2f80c2",
            "EMERGENCY", "#e2483d",
            "RETROFIT", "#7a5cc4",
            "OVERHAUL", "#f08c00");

    // ---------------- 检修工单状态 ----------------
    public static final List<String> ORDER_STATUSES = List.of("PENDING", "WORKING", "CHECKING", "CLOSED");

    /** 工单阶段推进顺序：待派 → 作业中 → 待验收 → 已闭环，跳阶段一律拦截 */
    public static final List<String> ORDER_FLOW = List.of("PENDING", "WORKING", "CHECKING", "CLOSED");

    private static final Map<String, String> ORDER_STATUS = Map.of(
            "PENDING", "待派",
            "WORKING", "作业中",
            "CHECKING", "待验收",
            "CLOSED", "已闭环");

    /** 允许的下一阶段：验收驳回允许从 待验收 退回 作业中 */
    private static final Map<String, List<String>> ORDER_NEXT = Map.of(
            "PENDING", List.of("WORKING"),
            "WORKING", List.of("CHECKING"),
            "CHECKING", List.of("CLOSED", "WORKING"),
            "CLOSED", List.of());

    // ---------------- 作业项结果 ----------------
    public static final List<String> ITEM_RESULTS = List.of("PASS", "FAIL", "SKIP");

    private static final Map<String, String> ITEM_RESULT = Map.of(
            "PASS", "合格",
            "FAIL", "不合格",
            "SKIP", "免检");

    // ---------------- 安全隔离票状态 ----------------
    /** 草稿 → 确认中 → 已隔离 → 复电中 → 已恢复，单向推进，不可跳步 */
    public static final List<String> TICKET_STATUSES =
            List.of("DRAFT", "CONFIRMING", "ISOLATED", "RESTORING", "RESTORED");

    /** 隔离票阶段推进顺序 */
    public static final List<String> TICKET_FLOW =
            List.of("DRAFT", "CONFIRMING", "ISOLATED", "RESTORING", "RESTORED");

    private static final Map<String, String> TICKET_STATUS = Map.of(
            "DRAFT", "草稿",
            "CONFIRMING", "确认中",
            "ISOLATED", "已隔离",
            "RESTORING", "复电中",
            "RESTORED", "已恢复");

    private static final Map<String, String> TICKET_COLOR = Map.of(
            "DRAFT", "#8b95a5",
            "CONFIRMING", "#f08c00",
            "ISOLATED", "#e2483d",
            "RESTORING", "#2f80c2",
            "RESTORED", "#2e9e5b");

    /** 允许的下一状态（严格单向；复电必须逐项逆序完成后才能到 RESTORED，由 service 再校验） */
    private static final Map<String, List<String>> TICKET_NEXT = Map.of(
            "DRAFT", List.of("CONFIRMING"),
            "CONFIRMING", List.of("ISOLATED"),
            "ISOLATED", List.of("RESTORING"),
            "RESTORING", List.of("RESTORED"),
            "RESTORED", List.of());

    // ---------------- 隔离点手段 ----------------
    public static final List<String> ISOLATION_TYPES =
            List.of("SHUTDOWN", "POWER_OFF", "VOLTAGE_TEST", "LOCK_OUT", "MECH_LOCK");

    private static final Map<String, String> ISOLATION_TYPE = Map.of(
            "SHUTDOWN", "停机",
            "POWER_OFF", "停电",
            "VOLTAGE_TEST", "验电",
            "LOCK_OUT", "挂牌上锁",
            "MECH_LOCK", "机械锁定");

    // ---------------- 工单验收结论 ----------------
    public static final List<String> ACCEPT_RESULTS = List.of("PASS", "FAIL");

    private static final Map<String, String> ACCEPT_RESULT = Map.of(
            "PASS", "验收合格",
            "FAIL", "验收驳回");

    // ---------------- 电网限功率指令 ----------------
    /** 待执行 / 执行中 / 已暂停 / 已结束 / 已取消 */
    public static final List<String> GRID_COMMAND_STATUSES =
            List.of("PLANNED", "EXECUTING", "PAUSED", "FINISHED", "CANCELLED");

    private static final Map<String, String> GRID_COMMAND_STATUS = Map.of(
            "PLANNED", "待执行",
            "EXECUTING", "执行中",
            "PAUSED", "已暂停",
            "FINISHED", "已结束",
            "CANCELLED", "已取消");

    public static final List<String> GRID_ALLOCATION_MODES = List.of("PROPORTIONAL", "MANUAL");
    private static final Map<String, String> GRID_ALLOCATION_MODE = Map.of(
            "PROPORTIONAL", "按额定容量比例分摊",
            "MANUAL", "值班人员逐台确认");

    /** 读数迟报阈值：记录时刻后 15 分钟以上才上报 */
    public static final long GRID_LATE_REPORT_SECONDS = 15 * 60L;
    /** 持续越限窗口：15 分钟内连续两次及以上越限 */
    public static final long GRID_PERSIST_WINDOW_SECONDS = 15 * 60L;

    private static final Map<String, String> GRID_EVENT_CLASS = Map.of(
            "OK", "正常",
            "LATE_ONLY", "读数迟报",
            "SINGLE_EXCEED", "短时越限",
            "PERSISTENT_EXCEED", "持续越限",
            "CLEARED", "复核回落",
            "MISSING_DATA", "读数缺失");

    private static final Map<String, String> GRID_CASE_STATUS = Map.of(
            "OPEN", "待处置",
            "HANDLED", "已处置",
            "REVIEWED", "已复核",
            "RELEASED", "已解除");

    // ---------------- 备件分类 ----------------
    public static final List<String> PART_CATEGORIES = List.of(
            "BLADE", "GEARBOX", "GENERATOR", "PITCH", "ELECTRIC", "HYDRAULIC", "FASTENER", "LUBE");

    private static final Map<String, String> PART_CATEGORY = Map.of(
            "BLADE", "叶片",
            "GEARBOX", "齿轮箱",
            "GENERATOR", "发电机",
            "PITCH", "变桨系统",
            "ELECTRIC", "电气",
            "HYDRAULIC", "液压",
            "FASTENER", "紧固件",
            "LUBE", "润滑油脂");

    // ---------------- 业务规则参数 ----------------
    /** 单机位额定功率下限 / 上限（kW） */
    public static final BigDecimal MIN_RATED_POWER_KW = BigDecimal.valueOf(100);
    public static final BigDecimal MAX_RATED_POWER_KW = BigDecimal.valueOf(10000);

    /** 风速上限（m/s），超过按异常数据拦截 */
    public static final BigDecimal MAX_WIND_SPEED_MS = BigDecimal.valueOf(40);

    /** 日发电量理论上限 = 额定功率 × 24h */
    public static final BigDecimal DAILY_HOURS = BigDecimal.valueOf(24);

    /** 一次更换登记的数量上限 */
    public static final int MAX_REPLACE_QTY = 200;

    // ---------------- 备件更换登记关联规则 ----------------
    /** 只有「作业中」的工单才允许登记备件更换：待派未开工、待验收已停止领用、已闭环封档 */
    public static final String REPLACE_ORDER_STATUS = "WORKING";

    /** 旧客户端自然键幂等兜底的去重窗口（秒）：超过窗口的同内容登记视为正常的连续更换 */
    public static final int REPLACE_DEDUP_WINDOW_SECONDS = 120;

    /** 登记来源：新客户端显式确认 / 旧请求的四种兼容形态 */
    public static final String SOURCE_NEW = "NEW_CLIENT";
    public static final String SOURCE_LEGACY_BOTH = "LEGACY_BOTH";
    public static final String SOURCE_LEGACY_ORDER_ONLY = "LEGACY_ORDER_ONLY";
    public static final String SOURCE_LEGACY_TURBINE_ONLY = "LEGACY_TURBINE_ONLY";
    public static final String SOURCE_LEGACY_NONE = "LEGACY_NONE";

    private static final Map<String, String> REPLACE_SOURCE = Map.of(
            SOURCE_NEW, "本次登记（机位已确认）",
            SOURCE_LEGACY_BOTH, "旧版登记（机位+工单）",
            SOURCE_LEGACY_ORDER_ONLY, "旧版登记（机位由工单补齐）",
            SOURCE_LEGACY_TURBINE_ONLY, "旧版登记（仅机位）",
            SOURCE_LEGACY_NONE, "旧版登记（未关联机位/工单）");

    /** 工单是否允许登记备件更换：只有作业中可以，避免登记到待派 / 待验收 / 已闭环工单上污染履历 */
    public static boolean isOrderAcceptingReplacement(String status) {
        return REPLACE_ORDER_STATUS.equals(status);
    }

    public static String replacementSourceName(String source) {
        return source == null ? "历史登记" : REPLACE_SOURCE.getOrDefault(source, source);
    }

    // ---------------- 取值方法 ----------------

    public static boolean isValidTimeZone(String zone) {
        return zone != null && TIME_ZONE_NAME.containsKey(zone);
    }

    public static String timeZoneName(String zone) {
        return zone == null ? "" : TIME_ZONE_NAME.getOrDefault(zone, zone);
    }

    /** 场站本地时区；非法时区退回系统默认，避免整表读不出来 */
    public static ZoneId zoneOf(String zone) {
        if (zone != null && TIME_ZONE_NAME.containsKey(zone)) {
            return ZoneId.of(zone);
        }
        return ZoneId.systemDefault();
    }

    /** 时区的 UTC 偏移文本，如 +08:00 / +06:00（卡片上给人看的偏移提示） */
    public static String zoneOffsetText(String zone) {
        if (zone == null) {
            return "";
        }
        ZoneOffset offset = ZonedDateTime.now(zoneOf(zone)).getOffset();
        return offset.getId();
    }

    public static boolean isValidFarmStatus(String status) {
        return status != null && FARM_STATUS.containsKey(status);
    }

    public static String farmStatusName(String status) {
        return status == null ? "" : FARM_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidTurbineStatus(String status) {
        return status != null && TURBINE_STATUS.containsKey(status);
    }

    public static String turbineStatusName(String status) {
        return status == null ? "" : TURBINE_STATUS.getOrDefault(status, status);
    }

    public static String turbineStatusColor(String status) {
        return status == null ? "#8b95a5" : TURBINE_COLOR.getOrDefault(status, "#8b95a5");
    }

    /** 机位状态流转是否允许 */
    public static boolean isValidTurbineFlow(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        if (from.equals(to)) {
            return true;
        }
        List<String> next = TURBINE_FLOW.get(from);
        return next != null && next.contains(to);
    }

    /** 流转提示文案：把允许的下一状态列出来 */
    public static String turbineFlowHint(String from) {
        List<String> next = from == null ? null : TURBINE_FLOW.get(from);
        if (next == null || next.isEmpty()) {
            return Dict.turbineStatusName(from) + " 不允许再做状态流转";
        }
        StringBuilder sb = new StringBuilder();
        for (String status : next) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(turbineStatusName(status));
        }
        return Dict.turbineStatusName(from) + " 只能流转到 " + sb;
    }

    public static boolean isValidMaintType(String type) {
        return type != null && MAINT_TYPE.containsKey(type);
    }

    public static String maintTypeName(String type) {
        return type == null ? "" : MAINT_TYPE.getOrDefault(type, type);
    }

    public static String maintTypeColor(String type) {
        return type == null ? "#00838f" : MAINT_COLOR.getOrDefault(type, "#00838f");
    }

    public static boolean isValidWorkOrderStatus(String status) {
        return status != null && ORDER_STATUS.containsKey(status);
    }

    public static String workOrderStatusName(String status) {
        return status == null ? "" : ORDER_STATUS.getOrDefault(status, status);
    }

    /** 工单状态流转是否允许（跳阶段一律 false） */
    public static boolean isValidOrderFlow(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        List<String> next = ORDER_NEXT.get(from);
        return next != null && next.contains(to);
    }

    /** 工单阶段推进提示 */
    public static String orderFlowHint(String from) {
        List<String> next = from == null ? null : ORDER_NEXT.get(from);
        if (next == null || next.isEmpty()) {
            return workOrderStatusName(from) + " 为终态，不允许再流转";
        }
        StringBuilder sb = new StringBuilder();
        for (String status : next) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(workOrderStatusName(status));
        }
        return workOrderStatusName(from) + " 只能推进到 " + sb;
    }

    /** 下一阶段（用于按钮文案），终态返回 null */
    public static String nextOrderStatus(String status) {
        List<String> next = status == null ? null : ORDER_NEXT.get(status);
        if (next == null || next.isEmpty()) {
            return null;
        }
        return next.get(0);
    }

    public static boolean isValidItemResult(String result) {
        return result != null && ITEM_RESULT.containsKey(result);
    }

    public static String itemResultName(String result) {
        return result == null ? "" : ITEM_RESULT.getOrDefault(result, result);
    }

    // ---------------- 安全隔离票取值方法 ----------------

    public static boolean isValidTicketStatus(String status) {
        return status != null && TICKET_STATUS.containsKey(status);
    }

    public static String ticketStatusName(String status) {
        return status == null ? "" : TICKET_STATUS.getOrDefault(status, status);
    }

    public static String ticketStatusColor(String status) {
        return status == null ? "#8b95a5" : TICKET_COLOR.getOrDefault(status, "#8b95a5");
    }

    /** 隔离票状态流转是否允许（只允许沿 DRAFT→…→RESTORED 单向推进一步） */
    public static boolean isValidTicketFlow(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        List<String> next = TICKET_NEXT.get(from);
        return next != null && next.contains(to);
    }

    public static String ticketFlowHint(String from) {
        List<String> next = from == null ? null : TICKET_NEXT.get(from);
        if (next == null || next.isEmpty()) {
            return ticketStatusName(from) + " 为终态，不允许再流转";
        }
        StringBuilder sb = new StringBuilder();
        for (String status : next) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(ticketStatusName(status));
        }
        return ticketStatusName(from) + " 只能推进到 " + sb;
    }

    /** 下一隔离票状态（按钮文案用），终态返回 null */
    public static String nextTicketStatus(String status) {
        List<String> next = status == null ? null : TICKET_NEXT.get(status);
        if (next == null || next.isEmpty()) {
            return null;
        }
        return next.get(0);
    }

    /** 尚未恢复的票：草稿 / 确认中 / 已隔离 / 复电中（占用机位活动占位） */
    public static boolean isActiveTicket(String status) {
        return status != null && !"RESTORED".equals(status);
    }

    /** 隔离已生效：只有已隔离 / 复电中才允许工单处于作业中 */
    public static boolean isIsolationEffective(String status) {
        return "ISOLATED".equals(status) || "RESTORING".equals(status);
    }

    /**
     * 机位被隔离锁定、禁止登记发电读数 / 禁止并网的票状态：
     * 确认中（已在执行停机停电）、已隔离、复电中。草稿尚未动手，不锁机位。
     */
    public static boolean isTurbineLockedByTicket(String status) {
        return "CONFIRMING".equals(status) || "ISOLATED".equals(status) || "RESTORING".equals(status);
    }

    public static boolean isValidIsolationType(String type) {
        return type != null && ISOLATION_TYPE.containsKey(type);
    }

    public static String isolationTypeName(String type) {
        return type == null ? "" : ISOLATION_TYPE.getOrDefault(type, type);
    }

    public static String isolationTypeColor(String type) {
        if (type == null) {
            return "#8b95a5";
        }
        switch (type) {
            case "SHUTDOWN": return "#2f80c2";
            case "POWER_OFF": return "#7a5cc4";
            case "VOLTAGE_TEST": return "#f08c00";
            case "LOCK_OUT": return "#e2483d";
            case "MECH_LOCK": return "#00838f";
            default: return "#8b95a5";
        }
    }

    public static boolean isValidAcceptResult(String result) {
        return result != null && ACCEPT_RESULT.containsKey(result);
    }

    public static String acceptResultName(String result) {
        return result == null ? "" : ACCEPT_RESULT.getOrDefault(result, result);
    }

    public static boolean isValidPartCategory(String category) {
        return category != null && PART_CATEGORY.containsKey(category);
    }

    public static String partCategoryName(String category) {
        return category == null ? "" : PART_CATEGORY.getOrDefault(category, category);
    }

    // ---------------- 电网限功率指令取值 ----------------

    public static boolean isValidGridCommandStatus(String status) {
        return status != null && GRID_COMMAND_STATUS.containsKey(status);
    }

    public static String gridCommandStatusName(String status) {
        return status == null ? "" : GRID_COMMAND_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidGridAllocationMode(String mode) {
        return mode != null && GRID_ALLOCATION_MODE.containsKey(mode);
    }

    public static String gridAllocationModeName(String mode) {
        return mode == null ? "" : GRID_ALLOCATION_MODE.getOrDefault(mode, mode);
    }

    public static String gridEventClassName(String value) {
        return value == null ? "" : GRID_EVENT_CLASS.getOrDefault(value, value);
    }

    public static String gridCaseStatusName(String status) {
        return status == null ? "" : GRID_CASE_STATUS.getOrDefault(status, status);
    }

    /** 日发电量理论上限 = 额定功率 × 24h */
    public static BigDecimal dailyEnergyLimit(BigDecimal ratedPowerKw) {
        if (ratedPowerKw == null) {
            return BigDecimal.ZERO;
        }
        return ratedPowerKw.multiply(DAILY_HOURS);
    }
}
