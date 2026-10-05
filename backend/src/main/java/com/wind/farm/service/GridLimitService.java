package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.GridCaseLog;
import com.wind.farm.domain.GridLimitCase;
import com.wind.farm.domain.GridLimitEvent;
import com.wind.farm.domain.GridPowerAllocation;
import com.wind.farm.domain.GridPowerCommand;
import com.wind.farm.domain.RunLog;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.GridCaseHandleRequest;
import com.wind.farm.dto.GridCaseReleaseRequest;
import com.wind.farm.dto.GridCaseReviewRequest;
import com.wind.farm.dto.GridViews.CaseLogVO;
import com.wind.farm.dto.GridViews.CaseVO;
import com.wind.farm.dto.GridViews.EventVO;
import com.wind.farm.repository.GridCaseLogRepository;
import com.wind.farm.repository.GridLimitCaseRepository;
import com.wind.farm.repository.GridLimitEventRepository;
import com.wind.farm.repository.GridPowerAllocationRepository;
import com.wind.farm.repository.GridPowerCommandRepository;
import com.wind.farm.repository.RunLogRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 限值执行判定和偏差处置。
 *
 * <p>迟报、短时波动、持续越限均在写读数后由后端落事件；读数修正 / 补录只会追加新版本并置旧版本
 * SUPERSEDED，不删除原始判断。执行限值取同一时刻所有执行中指令在该机位的最小当前额度。
 */
@Service
public class GridLimitService {

    private static final Set<String> OPEN_EVENT_STATUSES = Set.of("OPEN", "HANDLED", "REVIEWED");

    private final GridPowerCommandRepository commandRepository;
    private final GridPowerAllocationRepository allocationRepository;
    private final GridLimitEventRepository eventRepository;
    private final GridLimitCaseRepository caseRepository;
    private final GridCaseLogRepository caseLogRepository;
    private final RunLogRepository runLogRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;

    public GridLimitService(GridPowerCommandRepository commandRepository,
            GridPowerAllocationRepository allocationRepository,
            GridLimitEventRepository eventRepository,
            GridLimitCaseRepository caseRepository,
            GridCaseLogRepository caseLogRepository,
            RunLogRepository runLogRepository,
            TurbineRepository turbineRepository,
            WindFarmRepository farmRepository) {
        this.commandRepository = commandRepository;
        this.allocationRepository = allocationRepository;
        this.eventRepository = eventRepository;
        this.caseRepository = caseRepository;
        this.caseLogRepository = caseLogRepository;
        this.runLogRepository = runLogRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
    }

    @Transactional
    public void evaluateLog(RunLog log, String trigger, String operator) {
        if (log == null || log.getFarmId() == null) {
            return;
        }
        farmRepository.findByIdForUpdate(log.getFarmId()).orElse(null);
        ZonedDateTime at = log.getRecordTime();
        List<GridPowerCommand> commands = commandRepository
                .findByFarmIdAndStatusInOrderByStartTimeAscIdAsc(log.getFarmId(), List.of("EXECUTING"))
                .stream()
                .filter(c -> !at.isBefore(c.getStartTime()) && at.isBefore(c.getEndTime()))
                .toList();
        for (GridPowerCommand command : commands) {
            evaluateCommand(command, trigger, operator, List.of(log));
        }
    }

    @Transactional
    public void evaluateCommand(GridPowerCommand command, String trigger, String operator) {
        evaluateCommand(command, trigger, operator, null);
    }

    private void evaluateCommand(GridPowerCommand command, String trigger, String operator, List<RunLog> affectedLogs) {
        WindFarm farm = farmRepository.findById(command.getFarmId()).orElse(null);
        String zone = farm == null ? command.getStartZone() : farm.getTimeZone();
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(zone));
        List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId());
        Map<Long, GridPowerAllocation> byTurbine = new LinkedHashMap<>();
        for (GridPowerAllocation row : rows) {
            if (Boolean.TRUE.equals(row.getIncludedFlag())) {
                byTurbine.put(row.getTurbineId(), row);
            }
        }

        if (affectedLogs != null && !affectedLogs.isEmpty()) {
            for (RunLog log : affectedLogs) {
                if (byTurbine.containsKey(log.getTurbineId())) {
                    evaluateTurbine(command, byTurbine, log, trigger, operator, zone, now);
                    evaluateFarmAt(command, rows, log.getRecordTime(), trigger, operator, zone, now);
                }
            }
            return;
        }

        ZonedDateTime start = command.getStartTime();
        ZonedDateTime end = min(command.getEndTime(), now.plusMinutes(1));
        List<RunLog> logs = runLogRepository
                .findByFarmIdAndRecordTimeBetweenOrderByRecordTimeAscUnitCodeAsc(command.getFarmId(), start, end);
        List<ZonedDateTime> times = logs.stream()
                .filter(l -> byTurbine.containsKey(l.getTurbineId()))
                .map(RunLog::getRecordTime)
                .distinct()
                .sorted()
                .toList();
        for (RunLog log : logs) {
            if (byTurbine.containsKey(log.getTurbineId())) {
                evaluateTurbine(command, byTurbine, log, trigger, operator, zone, now);
            }
        }
        for (ZonedDateTime time : times) {
            evaluateFarmAt(command, rows, time, trigger, operator, zone, now);
        }
    }

    @Transactional
    public void onCommandInactive(GridPowerCommand command, String trigger, String operator) {
        List<GridLimitEvent> events = eventRepository.findByCommandIdOrderByObservedTimeDescIdDesc(command.getId());
        for (GridLimitEvent event : events) {
            if (!"SUPERSEDED".equals(event.getStatus())) {
                supersede(event, null, trigger, operator, "指令暂停 / 结束 / 取消，本事件不再作为当前限值执行依据，原判断保留");
            }
        }
    }

    @Transactional
    public void onLogDeleted(RunLog log, String operator) {
        if (log == null) {
            return;
        }
        for (GridLimitEvent event : eventRepository.findByRunLogIdOrderByIdDesc(log.getId())) {
            if (!"SUPERSEDED".equals(event.getStatus())) {
                supersede(event, null, "DELETE", operator, "运行读数被删除，该限值判定版本失效；原判断仍保留备查");
            }
        }
        Set<Long> commandIds = eventRepository.findByRunLogIdOrderByIdDesc(log.getId()).stream()
                .map(GridLimitEvent::getCommandId)
                .collect(java.util.stream.Collectors.toSet());
        for (Long commandId : commandIds) {
            commandRepository.findById(commandId).ifPresent(c -> evaluateCommand(c, "DELETE", operator));
        }
    }

    @Transactional
    public GridLimitCase handleCase(Long caseId, GridCaseHandleRequest request) {
        GridLimitCase c = lockCase(caseId);
        if (!"OPEN".equals(c.getStatus())) {
            throw new BizException("只有待处置偏差可以登记处置");
        }
        requireText(request == null ? null : request.getAction(), "请填写处置动作");
        requireText(request == null ? null : request.getOperator(), "请填写处置值班员");
        String zone = zoneOfCase(c);
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(zone));
        c.setStatus("HANDLED");
        c.setHandleAction(request.getAction().trim());
        c.setHandleOperator(request.getOperator().trim());
        c.setHandledTime(now);
        c.setHandledZone(zone);
        c.setHandleNote(trim(request.getNote()));
        c.setUpdatedAt(now);
        c = caseRepository.save(c);
        eventRepository.findById(c.getLatestEventId()).ifPresent(e -> {
            e.setStatus("HANDLED");
            e.setOperator(request.getOperator().trim());
            eventRepository.save(e);
        });
        caseLog(c, "HANDLE", c.getLatestEventId(), request.getOperator(),
                "处置：" + request.getAction() + appendNote(request.getNote()));
        return c;
    }

    @Transactional
    public GridLimitCase reviewCase(Long caseId, GridCaseReviewRequest request) {
        GridLimitCase c = lockCase(caseId);
        if (!"HANDLED".equals(c.getStatus())) {
            throw new BizException("只有已处置偏差可以登记复核");
        }
        if (request == null || !Set.of("CONFIRMED", "DATA_CORRECTED", "ESCALATE").contains(request.getResult())) {
            throw new BizException("请选择复核结论：确认偏差 / 读数已修正 / 升级处置");
        }
        requireText(request.getOperator(), "请填写复核人");
        String zone = zoneOfCase(c);
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(zone));
        c.setStatus("REVIEWED");
        c.setReviewResult(request.getResult());
        c.setReviewOperator(request.getOperator().trim());
        c.setReviewedTime(now);
        c.setReviewedZone(zone);
        c.setReviewNote(trim(request.getNote()));
        c.setUpdatedAt(now);
        c = caseRepository.save(c);
        eventRepository.findById(c.getLatestEventId()).ifPresent(e -> {
            e.setStatus("REVIEWED");
            e.setOperator(request.getOperator().trim());
            eventRepository.save(e);
        });
        caseLog(c, "REVIEW", c.getLatestEventId(), request.getOperator(),
                "复核结论：" + reviewName(request.getResult()) + appendNote(request.getNote()));
        return c;
    }

    @Transactional
    public GridLimitCase releaseCase(Long caseId, GridCaseReleaseRequest request) {
        GridLimitCase c = lockCase(caseId);
        if (!"REVIEWED".equals(c.getStatus())) {
            throw new BizException("只有已复核偏差可以登记解除结果");
        }
        if (request == null || !Set.of("RESOLVED", "COMMAND_LIFTED", "MAINTAINED").contains(request.getResult())) {
            throw new BizException("请选择解除结果：已恢复限值内 / 指令已解除 / 维持跟踪");
        }
        requireText(request.getOperator(), "请填写解除确认人");
        String zone = zoneOfCase(c);
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(zone));
        c.setStatus("RELEASED");
        c.setReleaseResult(request.getResult());
        c.setReleaseOperator(request.getOperator().trim());
        c.setReleasedTime(now);
        c.setReleasedZone(zone);
        c.setReleaseNote(trim(request.getNote()));
        c.setUpdatedAt(now);
        c = caseRepository.save(c);
        eventRepository.findById(c.getLatestEventId()).ifPresent(e -> {
            e.setStatus("RELEASED");
            e.setOperator(request.getOperator().trim());
            eventRepository.save(e);
        });
        caseLog(c, "RELEASE", c.getLatestEventId(), request.getOperator(),
                "解除结果：" + releaseName(request.getResult()) + appendNote(request.getNote()));
        return c;
    }

    public List<GridLimitEvent> listEvents(Long farmId) {
        return farmId == null ? eventRepository.findAll(
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
                        "observedTime", "id"))
                : eventRepository.findByFarmIdOrderByObservedTimeDescIdDesc(farmId);
    }

    public List<GridLimitCase> listCases(Long farmId) {
        return farmId == null ? caseRepository.findAll(
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
                        "observedTime", "id"))
                : caseRepository.findByFarmIdOrderByObservedTimeDescIdDesc(farmId);
    }

    public EventVO toEventVO(GridLimitEvent event) {
        WindFarm farm = farmRepository.findById(event.getFarmId()).orElse(null);
        String zone = farm == null ? event.getObservedZone() : farm.getTimeZone();
        EventVO vo = new EventVO();
        vo.setId(event.getId());
        vo.setFarmId(event.getFarmId());
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setCommandId(event.getCommandId());
        vo.setCommandNo(event.getCommandNo());
        vo.setScope(event.getScope());
        vo.setScopeName("FARM".equals(event.getScope()) ? "全场/子集" : "机位");
        vo.setTurbineId(event.getTurbineId());
        vo.setUnitCode(event.getUnitCode());
        vo.setRunLogId(event.getRunLogId());
        vo.setEventKey(event.getEventKey());
        vo.setObservedLocal(TimeText.local(event.getObservedTime(), zone));
        vo.setObservedTz(event.getObservedZone());
        vo.setReportLocal(TimeText.local(event.getReportTime(), zone));
        vo.setReportTz(event.getReportZone());
        vo.setEvaluatedLocal(TimeText.local(event.getEvaluatedTime(), zone));
        vo.setReportDelaySeconds(event.getReportDelaySeconds());
        vo.setLateFlag(event.getLateFlag());
        vo.setActivePowerKw(event.getActivePowerKw());
        vo.setLimitKw(event.getLimitKw());
        vo.setTotalPowerKw(event.getTotalPowerKw());
        vo.setTotalLimitKw(event.getTotalLimitKw());
        vo.setOverageKw(event.getOverageKw());
        vo.setConsecutiveCount(event.getConsecutiveCount());
        vo.setClassification(event.getClassification());
        vo.setClassificationName(Dict.gridEventClassName(event.getClassification()));
        vo.setStatus(event.getStatus());
        vo.setStatusName("SUPERSEDED".equals(event.getStatus()) ? "已被新版本替代"
                : Dict.gridCaseStatusName(event.getStatus()));
        vo.setTrigger(event.getTrigger());
        vo.setRevisionNo(event.getRevisionNo());
        vo.setSupersedesEventId(event.getSupersedesEventId());
        vo.setSupersededByEventId(event.getSupersededByEventId());
        vo.setCaseId(event.getCaseId());
        vo.setOperator(event.getOperator());
        vo.setNote(event.getNote());
        vo.setCreatedAt(TimeText.iso(event.getCreatedAt()));
        return vo;
    }

    public GridLimitCase getCase(Long id) {
        return caseRepository.findById(id).orElseThrow(() -> new BizException("偏差处置单不存在：" + id));
    }

    public CaseVO toCaseVO(GridLimitCase c) {
        WindFarm farm = farmRepository.findById(c.getFarmId()).orElse(null);
        String zone = farm == null ? c.getObservedZone() : farm.getTimeZone();
        CaseVO vo = new CaseVO();
        vo.setId(c.getId());
        vo.setFarmId(c.getFarmId());
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setCommandId(c.getCommandId());
        GridPowerCommand command = commandRepository.findById(c.getCommandId()).orElse(null);
        vo.setCommandNo(command == null ? null : command.getCommandNo());
        vo.setInitialEventId(c.getInitialEventId());
        vo.setLatestEventId(c.getLatestEventId());
        vo.setScope(c.getScope());
        vo.setScopeName("FARM".equals(c.getScope()) ? "全场/子集" : "机位");
        vo.setTurbineId(c.getTurbineId());
        vo.setUnitCode(c.getUnitCode());
        vo.setRunLogId(c.getRunLogId());
        vo.setClassification(c.getClassification());
        vo.setClassificationName(Dict.gridEventClassName(c.getClassification()));
        vo.setStatus(c.getStatus());
        vo.setStatusName(Dict.gridCaseStatusName(c.getStatus()));
        vo.setObservedLocal(TimeText.local(c.getObservedTime(), zone));
        vo.setActivePowerKw(c.getActivePowerKw());
        vo.setLimitKw(c.getLimitKw());
        vo.setTotalPowerKw(c.getTotalPowerKw());
        vo.setTotalLimitKw(c.getTotalLimitKw());
        vo.setOverageKw(c.getOverageKw());
        vo.setHandleAction(c.getHandleAction());
        vo.setHandleOperator(c.getHandleOperator());
        vo.setHandledLocal(TimeText.local(c.getHandledTime(), zone));
        vo.setHandleNote(c.getHandleNote());
        vo.setReviewResult(c.getReviewResult());
        vo.setReviewOperator(c.getReviewOperator());
        vo.setReviewedLocal(TimeText.local(c.getReviewedTime(), zone));
        vo.setReviewNote(c.getReviewNote());
        vo.setReleaseResult(c.getReleaseResult());
        vo.setReleaseOperator(c.getReleaseOperator());
        vo.setReleasedLocal(TimeText.local(c.getReleasedTime(), zone));
        vo.setReleaseNote(c.getReleaseNote());
        vo.setLogs(caseLogRepository.findByCaseIdOrderByIdAsc(c.getId()).stream().map(l -> toCaseLog(l, zone)).toList());
        return vo;
    }

    private void evaluateTurbine(GridPowerCommand command, Map<Long, GridPowerAllocation> byTurbine,
            RunLog log, String trigger, String operator, String zone, ZonedDateTime now) {
        GridPowerAllocation allocation = byTurbine.get(log.getTurbineId());
        if (allocation == null) {
            return;
        }
        List<GridPowerCommand> commands = commandRepository
                .findByFarmIdAndStatusInOrderByStartTimeAscIdAsc(command.getFarmId(), List.of("EXECUTING"))
                .stream()
                .filter(c -> !log.getRecordTime().isBefore(c.getStartTime())
                        && log.getRecordTime().isBefore(c.getEndTime()))
                .toList();
        BigDecimal strict = strictTurbineLimit(commands, log.getTurbineId(), log.getRecordTime());
        if (strict == null) {
            return;
        }
        long delaySeconds = delaySeconds(log);
        boolean late = delaySeconds > Dict.GRID_LATE_REPORT_SECONDS;
        boolean exceed = log.getActivePowerKw().compareTo(strict) > 0;
        String classification;
        int consecutive = 1;
        if (exceed) {
            List<RunLog> history = runLogRepository.findByTurbineIdAndRecordTimeBetweenOrderByRecordTimeAsc(
                    log.getTurbineId(),
                    log.getRecordTime().minusSeconds(Dict.GRID_PERSIST_WINDOW_SECONDS),
                    log.getRecordTime().plusSeconds(1));
            List<RunLog> overHistory = history.stream()
                    .filter(l -> l.getActivePowerKw().compareTo(strict) > 0)
                    .toList();
            consecutive = overHistory.size();
            boolean persistent = consecutive >= 2 && Duration.between(overHistory.get(0).getRecordTime(),
                    overHistory.get(overHistory.size() - 1).getRecordTime()).getSeconds() >= 60;
            classification = persistent ? "PERSISTENT_EXCEED" : "SINGLE_EXCEED";
        } else {
            classification = late ? "LATE_ONLY" : "OK";
        }
        String key = eventKey(command, "TURBINE", log.getTurbineId(), log.getRecordTime());
        GridLimitEvent event = baseEvent(command, "TURBINE", log.getTurbineId(), log, key, zone, now, trigger);
        event.setLimitKw(strict);
        event.setActivePowerKw(log.getActivePowerKw());
        event.setOverageKw(exceed ? log.getActivePowerKw().subtract(strict) : BigDecimal.ZERO);
        event.setReportDelaySeconds(delaySeconds);
        event.setLateFlag(late);
        event.setConsecutiveCount(consecutive);
        event.setClassification(classification);
        event.setOperator(operator);
        event.setNote(late && exceed ? "读数迟报且功率越限，按越限分类并保留迟报标记"
                : late ? "读数迟报，仅提示数据时效性" : null);
        replaceEvent(event);
    }

    private void evaluateFarmAt(GridPowerCommand command, List<GridPowerAllocation> rows, ZonedDateTime at,
            String trigger, String operator, String zone, ZonedDateTime now) {
        Set<Long> applicable = rows.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()))
                .map(GridPowerAllocation::getTurbineId)
                .collect(java.util.stream.Collectors.toSet());
        List<RunLog> snapshot = runLogRepository
                .findByFarmIdAndRecordTimeBetweenOrderByRecordTimeAscUnitCodeAsc(command.getFarmId(), at, at.plusSeconds(1))
                .stream()
                .filter(l -> applicable.contains(l.getTurbineId()))
                .toList();
        List<GridPowerCommand> commands = commandRepository
                .findByFarmIdAndStatusInOrderByStartTimeAscIdAsc(command.getFarmId(), List.of("EXECUTING"))
                .stream()
                .filter(c -> !at.isBefore(c.getStartTime()) && at.isBefore(c.getEndTime()))
                .toList();
        BigDecimal totalLimit = BigDecimal.ZERO;
        for (GridPowerAllocation row : rows) {
            if (!Boolean.TRUE.equals(row.getIncludedFlag())) {
                continue;
            }
            BigDecimal strict = strictTurbineLimit(
                    commands, row.getTurbineId(), at);
            totalLimit = totalLimit.add(strict == null ? BigDecimal.ZERO : strict);
        }
        BigDecimal totalPower = snapshot.stream().map(RunLog::getActivePowerKw).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean late = snapshot.stream().anyMatch(l -> delaySeconds(l) > Dict.GRID_LATE_REPORT_SECONDS);
        boolean exceed = totalPower.compareTo(totalLimit) > 0;
        String classification;
        int consecutive = 1;
        if (exceed) {
            List<RunLog> window = runLogRepository.findByFarmIdAndRecordTimeBetweenOrderByRecordTimeAscUnitCodeAsc(
                    command.getFarmId(), at.minusSeconds(Dict.GRID_PERSIST_WINDOW_SECONDS), at.plusSeconds(1));
            List<ZonedDateTime> overTimes = new ArrayList<>();
            for (ZonedDateTime time : window.stream().map(RunLog::getRecordTime).distinct().sorted().toList()) {
                List<RunLog> one = window.stream()
                        .filter(l -> l.getRecordTime().isEqual(time) && applicable.contains(l.getTurbineId()))
                        .toList();
                BigDecimal power = one.stream().map(RunLog::getActivePowerKw).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (power.compareTo(totalLimit) > 0) {
                    overTimes.add(time);
                }
            }
            consecutive = overTimes.size();
            boolean persistent = consecutive >= 2
                    && Duration.between(overTimes.get(0), overTimes.get(overTimes.size() - 1)).getSeconds() >= 60;
            classification = persistent ? "PERSISTENT_EXCEED" : "SINGLE_EXCEED";
        } else {
            classification = late ? "LATE_ONLY" : "OK";
        }
        String key = eventKey(command, "FARM", null, at);
        GridLimitEvent event = baseEvent(command, "FARM", null, null, key, zone, now, trigger);
        event.setObservedTime(at);
        event.setObservedZone(zone);
        event.setTotalPowerKw(totalPower);
        event.setTotalLimitKw(totalLimit);
        event.setOverageKw(exceed ? totalPower.subtract(totalLimit) : BigDecimal.ZERO);
        event.setLateFlag(late);
        event.setReportDelaySeconds(late ? Dict.GRID_LATE_REPORT_SECONDS + 1 : 0);
        event.setConsecutiveCount(consecutive);
        event.setClassification(classification);
        event.setOperator(operator);
        replaceEvent(event);
    }

    private GridLimitEvent baseEvent(GridPowerCommand command, String scope, Long turbineId, RunLog log,
            String key, String zone, ZonedDateTime now, String trigger) {
        GridLimitEvent event = new GridLimitEvent();
        event.setFarmId(command.getFarmId());
        event.setCommandId(command.getId());
        event.setCommandNo(command.getCommandNo());
        event.setScope(scope);
        event.setTurbineId(turbineId);
        event.setUnitCode(log == null ? null : log.getUnitCode());
        event.setRunLogId(log == null ? null : log.getId());
        event.setEventKey(key);
        event.setObservedTime(log == null ? now : log.getRecordTime());
        event.setObservedZone(log == null ? zone : log.getRecordZone());
        event.setReportTime(log == null ? null : log.getReportTime());
        event.setReportZone(log == null ? null : log.getReportZone());
        event.setEvaluatedTime(now);
        event.setEvaluatedZone(zone);
        event.setReportDelaySeconds(0L);
        event.setLateFlag(false);
        event.setOverageKw(BigDecimal.ZERO);
        event.setConsecutiveCount(1);
        event.setStatus("OPEN");
        event.setTrigger(trigger == null ? "READING" : trigger);
        event.setRevisionNo(1);
        event.setCreatedAt(now);
        return event;
    }

    private void replaceEvent(GridLimitEvent event) {
        Optional<GridLimitEvent> latest = eventRepository.findFirstByEventKeyAndStatusNotOrderByIdDesc(
                event.getEventKey(), "SUPERSEDED");
        if (latest.isPresent()) {
            GridLimitEvent old = latest.get();
            event.setRevisionNo((old.getRevisionNo() == null ? 1 : old.getRevisionNo()) + 1);
            event.setSupersedesEventId(old.getId());
            boolean clearsDeviation = ("OK".equals(event.getClassification()) || "LATE_ONLY".equals(event.getClassification()))
                    && isDeviation(old.getClassification());
            if (clearsDeviation) {
                event.setClassification("CLEARED");
            }
            if (old.getCaseId() != null && (isDeviation(event.getClassification()) || clearsDeviation)
                    && !"RELEASED".equals(old.getStatus())) {
                event.setCaseId(old.getCaseId());
                event.setStatus(old.getStatus());
            }
            event = eventRepository.save(event);
            old.setStatus("SUPERSEDED");
            old.setSupersededByEventId(event.getId());
            eventRepository.save(old);
        } else {
            event = eventRepository.save(event);
        }

        if (isDeviation(event.getClassification())) {
            GridLimitCase c = findCase(event);
            if (c == null) {
                createCase(event);
            } else {
                c.setLatestEventId(event.getId());
                c.setClassification(event.getClassification());
                copyEventToCase(event, c);
                c.setUpdatedAt(event.getCreatedAt());
                c = caseRepository.save(c);
                event.setCaseId(c.getId());
                event.setStatus(c.getStatus());
                eventRepository.save(event);
                caseLog(c, "EVENT_REVISION", event.getId(), event.getOperator(),
                        "读数补录 / 修正或分摊变化触发重算，追加事件 v" + event.getRevisionNo() + "，原判断保留");
            }
        } else if ("CLEARED".equals(event.getClassification())) {
            GridLimitCase c = findCase(event);
            if (c != null) {
                caseLog(c, "EVENT_CLEAR", event.getId(), event.getOperator(), "复核读数已回落到限值内，保留原处置轨迹");
            }
        }
    }

    private void supersede(GridLimitEvent old, GridLimitEvent replacement, String trigger, String operator, String note) {
        old.setStatus("SUPERSEDED");
        if (replacement != null) {
            old.setSupersededByEventId(replacement.getId());
        }
        old.setNote(joinNote(old.getNote(), note));
        eventRepository.save(old);
    }

    private void createCase(GridLimitEvent event) {
        GridLimitCase c = new GridLimitCase();
        c.setFarmId(event.getFarmId());
        c.setCommandId(event.getCommandId());
        c.setInitialEventId(event.getId());
        c.setLatestEventId(event.getId());
        c.setScope(event.getScope());
        c.setTurbineId(event.getTurbineId());
        c.setUnitCode(event.getUnitCode());
        c.setRunLogId(event.getRunLogId());
        c.setClassification(event.getClassification());
        c.setStatus("OPEN");
        copyEventToCase(event, c);
        ZonedDateTime now = event.getCreatedAt();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        c = caseRepository.save(c);
        event.setCaseId(c.getId());
        eventRepository.save(event);
        caseLog(c, "CREATE", event.getId(), event.getOperator(),
                Dict.gridEventClassName(event.getClassification()) + "，越限量 "
                        + (event.getOverageKw() == null ? BigDecimal.ZERO : event.getOverageKw()) + " kW，自动生成偏差处置单");
    }

    private void copyEventToCase(GridLimitEvent event, GridLimitCase c) {
        c.setObservedTime(event.getObservedTime());
        c.setObservedZone(event.getObservedZone());
        c.setActivePowerKw(event.getActivePowerKw());
        c.setLimitKw(event.getLimitKw());
        c.setTotalPowerKw(event.getTotalPowerKw());
        c.setTotalLimitKw(event.getTotalLimitKw());
        c.setOverageKw(event.getOverageKw());
    }

    private GridLimitCase findCase(GridLimitEvent event) {
        if ("TURBINE".equals(event.getScope())) {
            return caseRepository.findFirstByCommandIdAndScopeAndTurbineIdAndStatusNotOrderByIdDesc(
                    event.getCommandId(), "TURBINE", event.getTurbineId(), "RELEASED").orElse(null);
        }
        return caseRepository.findFirstByCommandIdAndScopeAndTurbineIdIsNullAndStatusNotOrderByIdDesc(
                event.getCommandId(), "FARM", "RELEASED").orElse(null);
    }

    private BigDecimal strictTurbineLimit(List<GridPowerCommand> commands, Long turbineId, ZonedDateTime at) {
        BigDecimal result = null;
        for (GridPowerCommand command : commands) {
            List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId());
            BigDecimal value = strictTurbineLimit(List.of(command), turbineId, at, rows);
            if (value != null) {
                result = result == null ? value : result.min(value);
            }
        }
        return result;
    }

    private BigDecimal strictTurbineLimit(List<GridPowerCommand> commands, Long turbineId, ZonedDateTime at,
            List<GridPowerAllocation> commandRows) {
        BigDecimal result = null;
        for (GridPowerCommand command : commands) {
            List<GridPowerAllocation> rows = commandRows == null
                    ? allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId())
                    : commandRows;
            for (GridPowerAllocation row : rows) {
                if (row.getTurbineId().equals(turbineId)
                        && Boolean.TRUE.equals(row.getIncludedFlag())
                        && Boolean.TRUE.equals(row.getRunnableFlag())
                        && !at.isBefore(command.getStartTime())
                        && at.isBefore(command.getEndTime())) {
                    BigDecimal v = row.getCurrentLimitKw();
                    if (v != null) {
                        result = result == null ? v : result.min(v);
                    }
                }
            }
        }
        return result;
    }

    private long delaySeconds(RunLog log) {
        if (log.getRecordTime() == null || log.getReportTime() == null) {
            return 0L;
        }
        long seconds = Duration.between(log.getRecordTime(), log.getReportTime()).getSeconds();
        return Math.max(0L, seconds);
    }

    private boolean isDeviation(String classification) {
        return "SINGLE_EXCEED".equals(classification) || "PERSISTENT_EXCEED".equals(classification);
    }

    private String eventKey(GridPowerCommand command, String scope, Long turbineId, ZonedDateTime at) {
        return command.getId() + ":" + scope + ":" + (turbineId == null ? "FARM" : turbineId)
                + ":" + at.toInstant().toEpochMilli();
    }

    private GridLimitCase lockCase(Long id) {
        return caseRepository.findById(id).orElseThrow(() -> new BizException("偏差处置单不存在：" + id));
    }

    private void caseLog(GridLimitCase c, String action, Long eventId, String operator, String detail) {
        String zone = zoneOfCase(c);
        GridCaseLog log = new GridCaseLog();
        log.setCaseId(c.getId());
        log.setEventId(eventId);
        log.setAction(action);
        log.setOperator(operator);
        log.setDetail(detail);
        log.setCreatedAt(ZonedDateTime.now(Dict.zoneOf(zone)));
        log.setCreatedZone(zone);
        caseLogRepository.save(log);
    }

    private CaseLogVO toCaseLog(GridCaseLog log, String zone) {
        CaseLogVO vo = new CaseLogVO();
        vo.setId(log.getId());
        vo.setCaseId(log.getCaseId());
        vo.setEventId(log.getEventId());
        vo.setAction(log.getAction());
        vo.setActionName(switch (log.getAction()) {
            case "CREATE" -> "创建";
            case "HANDLE" -> "处置";
            case "REVIEW" -> "复核";
            case "RELEASE" -> "解除";
            case "EVENT_REVISION" -> "判定重算";
            case "EVENT_CLEAR" -> "回落确认";
            default -> log.getAction();
        });
        vo.setOperator(log.getOperator());
        vo.setDetail(log.getDetail());
        vo.setCreatedAt(TimeText.iso(log.getCreatedAt()));
        vo.setCreatedTz(log.getCreatedZone());
        vo.setCreatedLocal(TimeText.local(log.getCreatedAt(), zone));
        return vo;
    }

    private String zoneOfCase(GridLimitCase c) {
        return farmRepository.findById(c.getFarmId()).map(WindFarm::getTimeZone).orElse("Asia/Shanghai");
    }

    private ZonedDateTime min(ZonedDateTime a, ZonedDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private void requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BizException(message);
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String appendNote(String note) {
        return note == null || note.trim().isEmpty() ? "" : "；备注：" + note.trim();
    }

    private String joinNote(String oldNote, String newNote) {
        if (oldNote == null || oldNote.isBlank()) {
            return newNote;
        }
        if (newNote == null || newNote.isBlank()) {
            return oldNote;
        }
        return oldNote + "；" + newNote;
    }

    private String reviewName(String value) {
        return switch (value) {
            case "CONFIRMED" -> "确认偏差";
            case "DATA_CORRECTED" -> "读数已修正";
            case "ESCALATE" -> "升级处置";
            default -> value;
        };
    }

    private String releaseName(String value) {
        return switch (value) {
            case "RESOLVED" -> "已恢复限值内";
            case "COMMAND_LIFTED" -> "指令已解除";
            case "MAINTAINED" -> "维持跟踪";
            default -> value;
        };
    }
}
