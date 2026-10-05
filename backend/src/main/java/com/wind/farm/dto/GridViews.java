package com.wind.farm.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 电网限功率模块页面对象。 */
public class GridViews {

    @Data
    public static class CommandVO {
        private Long id;
        private String commandNo;
        private Long farmId;
        private String farmName;
        private String timeZone;
        private BigDecimal targetLimitKw;
        private String allocationMode;
        private String allocationModeName;
        private String status;
        private String statusName;
        private Integer priorityRank;
        private String overlapPolicy;
        private String startTime;
        private String startTz;
        private String startLocal;
        private String endTime;
        private String endTz;
        private String endLocal;
        private String releaseCondition;
        private String allocationStatus;
        private String allocationStatusName;
        private String activatedLocal;
        private String pausedLocal;
        private String finishedLocal;
        private String cancelledLocal;
        private String owner;
        private String note;
        private Integer lockVersion;
        private BigDecimal allocatedTotalKw;
        private BigDecimal currentTotalKw;
        private BigDecimal runnableRatedTotalKw;
        private Boolean withinWindow;
        private List<AllocationVO> allocations = new ArrayList<>();
        private List<OverlapVO> overlaps = new ArrayList<>();
        private List<CommandLogVO> logs = new ArrayList<>();
        private List<String> blockers = new ArrayList<>();
    }

    @Data
    public static class AllocationVO {
        private Long id;
        private Long commandId;
        private Long turbineId;
        private String unitCode;
        private String unitName;
        private String turbineStatus;
        private String turbineStatusName;
        private BigDecimal ratedPowerKw;
        private Boolean includedFlag;
        private Boolean runnableFlag;
        private String allocationMode;
        private String allocationModeName;
        private BigDecimal allocatedLimitKw;
        private BigDecimal currentLimitKw;
        private BigDecimal latestPowerKw;
        private Boolean manualConfirmed;
        private String status;
        private String statusName;
        private String reason;
        private String allocatedLocal;
        private BigDecimal strictLimitKw;
        private String strictCommandNos;
    }

    @Data
    public static class CommandLogVO {
        private Long id;
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

    @Data
    public static class OverlapVO {
        private Long commandId;
        private String commandNo;
        private String status;
        private String statusName;
        private Integer priorityRank;
        private String relation;
        private String relationName;
        private Boolean stricter;
        private String startLocal;
        private String endLocal;
        private List<String> sharedUnitCodes = new ArrayList<>();
        private String effectiveRule;
    }

    @Data
    public static class EventVO {
        private Long id;
        private Long farmId;
        private String farmName;
        private Long commandId;
        private String commandNo;
        private String scope;
        private String scopeName;
        private Long turbineId;
        private String unitCode;
        private Long runLogId;
        private String eventKey;
        private String observedLocal;
        private String observedTz;
        private String reportLocal;
        private String reportTz;
        private String evaluatedLocal;
        private Long reportDelaySeconds;
        private Boolean lateFlag;
        private BigDecimal activePowerKw;
        private BigDecimal limitKw;
        private BigDecimal totalPowerKw;
        private BigDecimal totalLimitKw;
        private BigDecimal overageKw;
        private Integer consecutiveCount;
        private String classification;
        private String classificationName;
        private String status;
        private String statusName;
        private String trigger;
        private Integer revisionNo;
        private Long supersedesEventId;
        private Long supersededByEventId;
        private Long caseId;
        private String operator;
        private String note;
        private String createdAt;
    }

    @Data
    public static class CaseVO {
        private Long id;
        private Long farmId;
        private String farmName;
        private Long commandId;
        private String commandNo;
        private Long initialEventId;
        private Long latestEventId;
        private String scope;
        private String scopeName;
        private Long turbineId;
        private String unitCode;
        private Long runLogId;
        private String classification;
        private String classificationName;
        private String status;
        private String statusName;
        private String observedLocal;
        private BigDecimal activePowerKw;
        private BigDecimal limitKw;
        private BigDecimal totalPowerKw;
        private BigDecimal totalLimitKw;
        private BigDecimal overageKw;
        private String handleAction;
        private String handleOperator;
        private String handledLocal;
        private String handleNote;
        private String reviewResult;
        private String reviewOperator;
        private String reviewedLocal;
        private String reviewNote;
        private String releaseResult;
        private String releaseOperator;
        private String releasedLocal;
        private String releaseNote;
        private List<CaseLogVO> logs = new ArrayList<>();
    }

    @Data
    public static class CaseLogVO {
        private Long id;
        private Long caseId;
        private Long eventId;
        private String action;
        private String actionName;
        private String operator;
        private String detail;
        private String createdAt;
        private String createdTz;
        private String createdLocal;
    }
}
