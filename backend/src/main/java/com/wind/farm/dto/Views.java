package com.wind.farm.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 页面视图对象集中地：所有 VO 都在这里，字段一律用基本类型 / String，
 * 时间统一格式化成字符串带出来，避免前端再处理时区。
 */
public class Views {

    /** 风场卡片 */
    @Data
    public static class FarmVO {

        private Long id;
        private String farmCode;
        private String farmName;
        private String region;
        private String province;
        private String timeZone;
        private String timeZoneName;
        private String offsetText;
        private BigDecimal installedCapacityMw;
        private Integer turbineCount;
        private String gridDate;
        private String owner;
        private String status;
        private String statusName;
        private String note;
    }

    /** 机位大卡片 */
    @Data
    public static class TurbineVO {

        private Long id;
        private Long farmId;
        private String farmCode;
        private String farmName;
        private String timeZone;
        private String timeZoneName;
        private String offsetText;
        private String unitCode;
        private String unitName;
        private String model;
        private BigDecimal ratedPowerKw;
        private BigDecimal hubHeightM;
        private BigDecimal rotorDiameterM;
        private String installDate;
        private String status;
        private String statusName;
        private String statusColor;
        private BigDecimal lastPowerKw;
        private BigDecimal todayEnergyKwh;
        private BigDecimal totalEnergyKwh;
        private BigDecimal totalHours;
        private Double loadRate;
        private Boolean canRecord;
        /** 机位是否被未恢复的隔离票锁定（确认中 / 已隔离 / 复电中）：锁定时禁止并网、禁止登记发电读数 */
        private Boolean isolationLocked;
        /** 当前锁定机位的隔离票编号 / 责任人 / 状态中文名（未锁定为 null） */
        private String activeTicketNo;
        private String activeTicketOwner;
        private String activeTicketStatus;
        private String activeTicketStatusName;
        private String note;
    }

    /** 运行日志（时间字段由 @TimeZoneStorage 还原后带出） */
    @Data
    public static class RunLogVO {

        private Long id;
        private Long turbineId;
        private Long farmId;
        private String farmName;
        private String unitCode;
        private String timeZone;
        private String timeZoneName;
        /** 记录时刻原始文本（含偏移） */
        private String recordTime;
        /** 记录时刻所属时区的 ZoneId 文本（record_time_tz / recordZone） */
        private String recordTz;
        /** 记录时刻换算到该时区后的墙上时间文本（自带时区优先，取不到退回风场时区） */
        private String recordLocal;
        private String reportTime;
        private String reportTz;
        private String reportLocal;
        private BigDecimal windSpeedMs;
        private BigDecimal activePowerKw;
        private BigDecimal dailyEnergyKwh;
        private Double loadRate;
        private String windDirection;
        private BigDecimal temperatureC;
        private String operator;
        private String note;
    }

    /** 检修工单（带作业项清单与进度） */
    @Data
    public static class WorkOrderVO {

        private Long id;
        private String orderNo;
        private Long turbineId;
        private Long farmId;
        private String farmName;
        private String unitCode;
        private String maintType;
        private String maintTypeName;
        private String maintTypeColor;
        private String title;
        private String crew;
        private String planStartTime;
        private String planTz;
        private String planLocal;
        private String actualFinishTime;
        private String finishTz;
        private String finishLocal;
        private String status;
        private String statusName;
        private String nextStatus;
        private String nextStatusName;
        /** 验收结论 / 验收人 / 验收时刻本地文本 */
        private String acceptResult;
        private String acceptResultName;
        private String acceptPerson;
        private String acceptTime;
        private String acceptTz;
        private String acceptLocal;
        private String acceptNote;
        /** 关联隔离票摘要（没有票时为 null）：未隔离不能作业、未恢复不能闭环 */
        private TicketBriefVO ticket;
        private String faultDesc;
        private String note;
        private List<WorkItemVO> items = new ArrayList<>();
        private Integer itemTotal;
        private Integer itemDone;
        private Double progress;
    }

    /** 检修作业项 */
    @Data
    public static class WorkItemVO {

        private Long id;
        private Long workOrderId;
        private Integer itemSeq;
        private String itemName;
        private String standard;
        private String result;
        private String resultName;
        private String resultNote;
        private Boolean done;
        private String note;
    }

    /** 备件（带库存条与低库存标记） */
    @Data
    public static class SparePartVO {

        private Long id;
        private String partCode;
        private String partName;
        private String spec;
        private String category;
        private String categoryName;
        private String unit;
        private Integer stockQty;
        private Integer safetyStock;
        private String location;
        private BigDecimal unitPrice;
        private String supplier;
        private Boolean lowStock;
        private Double stockRate;
        private String note;
    }

    /** 备件更换登记 */
    @Data
    public static class ReplacementVO {

        private Long id;
        private Long partId;
        private String partCode;
        private String partName;
        private Long turbineId;
        private String unitCode;
        private String farmName;
        private Long workOrderId;
        private String orderNo;
        private Integer qty;
        private String replaceTime;
        private String operator;
        private Boolean warningFlag;
        private Integer stockAfter;
        private String note;
        /** 登记来源（新客户端 / 旧版兼容形态）及中文名 */
        private String source;
        private String sourceName;
        /**
         * 实时核对结论：false=正常；true=待核对。
         * 按当前工单、机位实时计算——关联对象已删除 / 机位与工单归属不一致 / 机位编号对不上，
         * 都必须显式标成待核对，不能静默改写历史。
         */
        private Boolean pendingCheck;
        /** 待核对原因（人能直接照着重选的提示），正常为 null */
        private String checkNote;
        /** 当前工单真实所属机位编号（冲突时告诉登记人应该改成哪个机位） */
        private String actualOrderUnitCode;
        /** 关联工单当前状态 / 中文名（便于登记人判断工单是否仍可登记） */
        private String orderStatus;
        private String orderStatusName;
    }

    /** 隔离票在工单 / 机位列表里的简要信息 */
    @Data
    public static class TicketBriefVO {

        private Long id;
        private String ticketNo;
        private Long workOrderId;
        private String orderNo;
        private Long turbineId;
        private String unitCode;
        private String maintType;
        private String status;
        private String statusName;
        private String statusColor;
        private String nextStatus;
        private String nextStatusName;
        private String owner;
        private String isolatedLocal;
        private String restoredLocal;
        private Integer pointTotal;
        private Integer isolatedCount;
        private Integer restoredCount;
        private Integer invalidCount;
        /** 未处理的模板新增风险点数（新增风险不被旧快照静默掩盖） */
        private Integer templatePendingCount;
        /** 进入下一阶段还差什么（人能直接照着处理的阻塞原因列表） */
        private List<String> blockers = new ArrayList<>();
    }

    /** 隔离点模板 */
    @Data
    public static class TemplateVO {

        private Long id;
        private String maintType;
        private String maintTypeName;
        private Integer pointSeq;
        private String pointName;
        private String pointType;
        private String pointTypeName;
        private String standard;
        private Boolean enabled;
        private Integer version;
        private String note;
    }

    /** 票内隔离点 */
    @Data
    public static class IsolationPointVO {

        private Long id;
        private Long ticketId;
        private Integer pointSeq;
        private String pointName;
        private String pointType;
        private String pointTypeName;
        private String standard;
        private String source;
        private String sourceName;
        private Integer snapshotVersion;
        private Boolean isolated;
        private String isolateOperator;
        private String isolateVerifier;
        private String isolateTime;
        private String isolateTz;
        private String isolateLocal;
        private Boolean validFlag;
        private Boolean restored;
        private String restoreOperator;
        private String restoreTime;
        private String restoreTz;
        private String restoreLocal;
        private String note;
        /** 复电阶段该点是否为「当前唯一可解除」的点（严格逆序，禁止跳项） */
        private Boolean canRestoreNow;
        /** 模板点在本票创建后又发布了新版本（仅提示，不改动已确认快照） */
        private Boolean templateChanged;
        private Integer templateLatestVersion;
    }

    /** 隔离票完整视图：票头 + 隔离点 + 模板差异 + 阻塞原因 + 变更记录 */
    @Data
    public static class SafetyTicketVO {

        private Long id;
        private String ticketNo;
        private Long workOrderId;
        private String orderNo;
        private String orderTitle;
        private String orderStatus;
        private String orderStatusName;
        private String acceptResult;
        private String acceptResultName;
        private String acceptPerson;
        private Long turbineId;
        private Long farmId;
        private String farmName;
        private String unitCode;
        private String maintType;
        private String maintTypeName;
        private String status;
        private String statusName;
        private String statusColor;
        private String nextStatus;
        private String nextStatusName;
        private String owner;
        private Integer templateVersion;
        private String isolatedTime;
        private String isolatedTz;
        private String isolatedLocal;
        private String restoredTime;
        private String restoredTz;
        private String restoredLocal;
        private String note;
        private List<IsolationPointVO> points = new ArrayList<>();
        private Integer pointTotal;
        private Integer isolatedCount;
        private Integer restoredCount;
        private Integer invalidCount;
        /** 建票后模板新增、尚未在本票处理（补入或留痕忽略）的风险点 */
        private List<TemplateVO> newTemplatePoints = new ArrayList<>();
        /** 模板点新版本总数（建票后模板发生变更） */
        private Integer changedTemplateCount;
        /** 阻塞原因：为什么当前不能推进 / 不能复电 / 不能恢复（可继续处理的明确动作） */
        private List<String> blockers = new ArrayList<>();
        /** 完整变更记录 */
        private List<IsolationLogVO> logs = new ArrayList<>();
    }

    /** 隔离票变更流水 */
    @Data
    public static class IsolationLogVO {

        private Long id;
        private Long ticketId;
        private Long pointId;
        private String action;
        private String actionName;
        private String fromStatus;
        private String fromStatusName;
        private String toStatus;
        private String toStatusName;
        private String operator;
        private String detail;
        private String createdAt;
        private String createdTz;
        private String createdLocal;
    }
}
