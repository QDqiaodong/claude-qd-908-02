package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.GridCommandLog;
import com.wind.farm.domain.GridPowerAllocation;
import com.wind.farm.domain.GridPowerCommand;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.GridAllocationRequest;
import com.wind.farm.dto.GridCommandActionRequest;
import com.wind.farm.dto.GridCommandRequest;
import com.wind.farm.dto.GridViews.AllocationVO;
import com.wind.farm.dto.GridViews.CommandLogVO;
import com.wind.farm.dto.GridViews.CommandVO;
import com.wind.farm.dto.GridViews.OverlapVO;
import com.wind.farm.repository.GridCommandLogRepository;
import com.wind.farm.repository.GridPowerAllocationRepository;
import com.wind.farm.repository.GridPowerCommandRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电网限功率指令台账、分摊、状态轨迹和重叠覆盖规则。
 *
 * <p>多条执行中指令重叠时不在写入端互相覆盖或取消，而是在每个适用机位上取当前有效最小值：
 * 新指令即使更宽松，也只会成为候选额度，不能解除更严格旧指令。
 */
@Service
public class GridCommandService {

    private static final Set<String> ACTIVE_STATUSES = Set.of("PLANNED", "EXECUTING", "PAUSED");
    private static final Set<String> EFFECTIVE_STATUSES = Set.of("EXECUTING");

    private final GridPowerCommandRepository commandRepository;
    private final GridPowerAllocationRepository allocationRepository;
    private final GridCommandLogRepository commandLogRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final ObjectProvider<GridLimitService> limitServiceProvider;

    public GridCommandService(GridPowerCommandRepository commandRepository,
            GridPowerAllocationRepository allocationRepository,
            GridCommandLogRepository commandLogRepository,
            TurbineRepository turbineRepository,
            WindFarmRepository farmRepository,
            ObjectProvider<GridLimitService> limitServiceProvider) {
        this.commandRepository = commandRepository;
        this.allocationRepository = allocationRepository;
        this.commandLogRepository = commandLogRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.limitServiceProvider = limitServiceProvider;
    }

    @Transactional
    public GridPowerCommand create(GridCommandRequest request) {
        WindFarm farm = requireFarm(request.getFarmId());
        validateCommandNo(request.getCommandNo(), null);
        validateBase(request, farm);

        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
        GridPowerCommand command = new GridPowerCommand();
        command.setCommandNo(request.getCommandNo().trim());
        command.setFarmId(farm.getId());
        command.setTargetLimitKw(request.getTargetLimitKw());
        command.setAllocationMode(request.getAllocationMode());
        command.setStatus("PLANNED");
        command.setActiveSlot(1L);
        command.setPriorityRank(request.getPriorityRank() == null ? 100 : request.getPriorityRank());
        command.setOverlapPolicy("STRICT_MIN");
        command.setStartTime(request.getStartTime());
        command.setStartZone(farm.getTimeZone());
        command.setEndTime(request.getEndTime());
        command.setEndZone(farm.getTimeZone());
        command.setReleaseCondition(trim(request.getReleaseCondition()));
        command.setAllocationStatus("NOT_CONFIRMED");
        command.setOwner(trim(request.getOwner()));
        command.setNote(trim(request.getNote()));
        command.setCreatedAt(now);
        command.setUpdatedAt(now);
        command = commandRepository.save(command);

        List<Turbine> turbines = selectedTurbines(farm.getId(), request.getTurbineIds());
        rebuildAllocations(command, turbines, farm.getTimeZone(), now);
        log(command, "CREATE", null, "PLANNED", command.getOwner(),
                "登记电网限功率指令，适用机位 " + turbines.size() + " 台；重叠规则：逐机位取最严限值");
        return command;
    }

    @Transactional
    public GridPowerCommand update(Long id, GridCommandRequest request) {
        GridPowerCommand command = commandRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("限功率指令不存在：" + id));
        if (!"PLANNED".equals(command.getStatus())) {
            throw new BizException("只有待执行指令允许修改；执行中变更请通过暂停 / 新指令 / 分摊调整留痕");
        }
        WindFarm farm = requireFarm(command.getFarmId());
        validateCommandNo(request.getCommandNo(), id);
        validateBase(request, farm);

        command.setCommandNo(request.getCommandNo().trim());
        command.setTargetLimitKw(request.getTargetLimitKw());
        command.setAllocationMode(request.getAllocationMode());
        command.setPriorityRank(request.getPriorityRank() == null ? 100 : request.getPriorityRank());
        command.setStartTime(request.getStartTime());
        command.setStartZone(farm.getTimeZone());
        command.setEndTime(request.getEndTime());
        command.setEndZone(farm.getTimeZone());
        command.setReleaseCondition(trim(request.getReleaseCondition()));
        command.setOwner(trim(request.getOwner()));
        command.setNote(trim(request.getNote()));
        command.setUpdatedAt(ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone())));
        command = commandRepository.save(command);

        allocationRepository.deleteAll(allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(id));
        List<Turbine> turbines = selectedTurbines(farm.getId(), request.getTurbineIds());
        rebuildAllocations(command, turbines, farm.getTimeZone(), ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone())));
        log(command, "UPDATE", "PLANNED", "PLANNED", command.getOwner(), "修改指令基本信息 / 适用机位，已重新生成分摊预览");
        return command;
    }

    @Transactional
    public void remove(Long id) {
        GridPowerCommand command = get(id);
        if (!"PLANNED".equals(command.getStatus())) {
            throw new BizException("只有待执行指令允许删除；已发生执行轨迹的指令请取消或结束");
        }
        allocationRepository.deleteAll(allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(id));
        commandLogRepository.deleteAll(commandLogRepository.findByCommandIdOrderByIdAsc(id));
        commandRepository.delete(command);
    }

    @Transactional
    public List<GridPowerAllocation> previewAllocation(Long id) {
        GridPowerCommand command = get(id);
        if (!"PLANNED".equals(command.getStatus())) {
            throw new BizException("只有待执行指令需要启动前分摊预览");
        }
        WindFarm farm = requireFarm(command.getFarmId());
        List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(id);
        refreshRunnableState(rows, farm.getTimeZone(), ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone())));
        List<Turbine> turbines = rows.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()) && Boolean.TRUE.equals(a.getRunnableFlag()))
                .map(a -> turbineRepository.findById(a.getTurbineId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        calculateProportional(command, rows, turbines, farm.getTimeZone(),
                ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone())));
        command.setAllocationStatus("PREVIEWED");
        command.setUpdatedAt(ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone())));
        commandRepository.save(command);
        log(command, "ALLOC_PREVIEW", null, null, command.getOwner(), "按当前可运行机位额定容量重新生成分摊预览");
        return allocationRepository.saveAll(rows);
    }

    @Transactional
    public List<GridPowerAllocation> confirmAllocation(Long id, GridAllocationRequest request) {
        GridPowerCommand command = commandRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("限功率指令不存在：" + id));
        if (!Set.of("PLANNED", "EXECUTING", "PAUSED").contains(command.getStatus())) {
            throw new BizException("已结束 / 已取消指令不能再确认分摊");
        }
        WindFarm farm = requireFarm(command.getFarmId());
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
        List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(id);
        refreshRunnableState(rows, farm.getTimeZone(), now);

        if ("PROPORTIONAL".equals(command.getAllocationMode())) {
            List<Turbine> turbines = rows.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()) && Boolean.TRUE.equals(a.getRunnableFlag()))
                    .map(a -> turbineRepository.findById(a.getTurbineId()).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
            calculateProportional(command, rows, turbines, farm.getTimeZone(), now);
        } else if ("MANUAL".equals(command.getAllocationMode())) {
            applyManualAllocation(command, rows, request, now, farm.getTimeZone());
        } else {
            throw new BizException("未知分摊方式：" + command.getAllocationMode());
        }

        rows = allocationRepository.saveAll(rows);
        command.setAllocationStatus("CONFIRMED");
        command.setUpdatedAt(now);
        commandRepository.save(command);
        log(command, "ALLOC_CONFIRM", command.getStatus(), command.getStatus(),
                request == null ? null : trim(request.getOperator()),
                "PROPORTIONAL".equals(command.getAllocationMode())
                        ? "确认按额定容量比例分摊结果，总额度不超过场站目标"
                        : "值班人员逐台确认手动分摊，总额度不超过场站目标");
        if ("EXECUTING".equals(command.getStatus())) {
            limitServiceProvider.ifAvailable(s -> s.evaluateCommand(command, "COMMAND_CHANGE",
                    request == null ? null : request.getOperator()));
        }
        return rows;
    }

    @Transactional
    public GridPowerCommand start(Long id, GridCommandActionRequest request) {
        GridPowerCommand command = commandRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("限功率指令不存在：" + id));
        WindFarm farm = requireFarm(command.getFarmId());
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
        if (!"PLANNED".equals(command.getStatus())) {
            throw new BizException("只有待执行指令可以进入执行中");
        }
        if (now.isBefore(command.getStartTime())) {
            throw new BizException("尚未到指令开始时间，不能提前进入执行中");
        }
        if (!now.isBefore(command.getEndTime())) {
            throw new BizException("指令已过结束时间，请登记新指令或直接结束该指令");
        }
        if (!"CONFIRMED".equals(command.getAllocationStatus())) {
            throw new BizException("请先完成机位分摊确认，再进入执行");
        }
        List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(id);
        refreshRunnableState(rows, farm.getTimeZone(), now);
        if ("PROPORTIONAL".equals(command.getAllocationMode())) {
            List<Turbine> turbines = rows.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()) && Boolean.TRUE.equals(a.getRunnableFlag()))
                    .map(a -> turbineRepository.findById(a.getTurbineId()).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
            calculateProportional(command, rows, turbines, farm.getTimeZone(), now);
            allocationRepository.saveAll(rows);
        } else {
            validateManualCurrent(command, rows);
            allocationRepository.saveAll(rows);
        }
        if (sumCurrent(rows).compareTo(command.getTargetLimitKw()) > 0) {
            throw new BizException("机位当前额度合计超过场站目标，不能进入执行");
        }

        String from = command.getStatus();
        command.setStatus("EXECUTING");
        command.setActiveSlot(1L);
        command.setActivatedTime(now);
        command.setActivatedZone(farm.getTimeZone());
        command.setUpdatedAt(now);
        command = commandRepository.save(command);
        final GridPowerCommand saved = command;
        final String actor = operator(request, saved.getOwner());
        log(saved, "START", from, "EXECUTING", actor,
                "指令进入执行；后台按有效指令逐机位取最小限值，重叠宽松指令不会解除严格旧限制");
        limitServiceProvider.ifAvailable(s -> s.evaluateCommand(saved, "COMMAND_CHANGE", actor));
        return saved;
    }

    @Transactional
    public GridPowerCommand pause(Long id, GridCommandActionRequest request) {
        GridPowerCommand command = lockActive(id);
        if (!"EXECUTING".equals(command.getStatus())) {
            throw new BizException("只有执行中的指令可以暂停");
        }
        WindFarm farm = requireFarm(command.getFarmId());
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
        command.setStatus("PAUSED");
        command.setPausedTime(now);
        command.setPausedZone(farm.getTimeZone());
        command.setUpdatedAt(now);
        command = commandRepository.save(command);
        final GridPowerCommand saved = command;
        final String actor = operator(request, saved.getOwner());
        log(saved, "PAUSE", "EXECUTING", "PAUSED", actor,
                reason(request, "值班人员暂停执行，暂停期间保留原轨迹；恢复后按绝对时间继续，不补写空档"));
        limitServiceProvider.ifAvailable(s -> s.onCommandInactive(saved, "COMMAND_PAUSED", actor));
        return saved;
    }

    @Transactional
    public GridPowerCommand resume(Long id, GridCommandActionRequest request) {
        GridPowerCommand command = lockActive(id);
        if (!"PAUSED".equals(command.getStatus())) {
            throw new BizException("只有已暂停指令可以恢复执行");
        }
        WindFarm farm = requireFarm(command.getFarmId());
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
        if (!now.isBefore(command.getEndTime())) {
            return finish(id, new GridCommandActionRequest());
        }
        command.setStatus("EXECUTING");
        command.setUpdatedAt(now);
        command = commandRepository.save(command);
        final GridPowerCommand saved = command;
        final String actor = operator(request, saved.getOwner());
        log(saved, "RESUME", "PAUSED", "EXECUTING", actor,
                reason(request, "暂停结束，恢复执行；按绝对时刻衔接，不产生本地日期空档"));
        limitServiceProvider.ifAvailable(s -> s.evaluateCommand(saved, "COMMAND_CHANGE", actor));
        return saved;
    }

    @Transactional
    public GridPowerCommand finish(Long id, GridCommandActionRequest request) {
        GridPowerCommand command = commandRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("限功率指令不存在：" + id));
        if (!Set.of("EXECUTING", "PAUSED").contains(command.getStatus())) {
            throw new BizException("只有执行中 / 已暂停指令可以结束");
        }
        WindFarm farm = requireFarm(command.getFarmId());
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
        String from = command.getStatus();
        command.setStatus("FINISHED");
        command.setActiveSlot(null);
        command.setFinishedTime(now);
        command.setFinishedZone(farm.getTimeZone());
        command.setUpdatedAt(now);
        command = commandRepository.save(command);
        final GridPowerCommand saved = command;
        final String actor = operator(request, saved.getOwner());
        log(saved, "FINISH", from, "FINISHED", actor,
                reason(request, "满足解除条件或到达结束时间，指令结束"));
        limitServiceProvider.ifAvailable(s -> s.onCommandInactive(saved, "COMMAND_FINISHED", actor));
        return saved;
    }

    @Transactional
    public GridPowerCommand cancel(Long id, GridCommandActionRequest request) {
        GridPowerCommand command = commandRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("限功率指令不存在：" + id));
        if (!Set.of("PLANNED", "EXECUTING", "PAUSED").contains(command.getStatus())) {
            throw new BizException("只有待执行 / 执行中 / 已暂停指令可以取消");
        }
        WindFarm farm = requireFarm(command.getFarmId());
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
        String from = command.getStatus();
        command.setStatus("CANCELLED");
        command.setActiveSlot(null);
        command.setCancelledTime(now);
        command.setCancelledZone(farm.getTimeZone());
        command.setUpdatedAt(now);
        command = commandRepository.save(command);
        final GridPowerCommand saved = command;
        final String actor = operator(request, saved.getOwner());
        log(saved, "CANCEL", from, "CANCELLED", actor,
                reason(request, "指令取消；若仍有其他严格指令，其限值继续有效"));
        if (!"PLANNED".equals(from)) {
            limitServiceProvider.ifAvailable(s -> s.onCommandInactive(saved, "COMMAND_CANCELLED", actor));
        }
        return saved;
    }

    @Transactional
    public int syncFarm(Long farmId) {
        requireFarm(farmId);
        int changed = 0;
        for (GridPowerCommand command : commandRepository.findByFarmIdOrderByStartTimeDescIdDesc(farmId)) {
            if (Set.of("EXECUTING", "PAUSED").contains(command.getStatus())) {
                WindFarm farm = requireFarm(farmId);
                ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
                if (!now.isBefore(command.getEndTime())) {
                    GridCommandActionRequest req = new GridCommandActionRequest();
                    req.setOperator("SYSTEM");
                    req.setReason("到达指令结束时刻，系统自动结束");
                    finish(command.getId(), req);
                    changed++;
                }
            }
        }
        return changed;
    }

    @Transactional
    public void refreshExecutionForTurbine(Turbine turbine, String operator, String reason) {
        if (turbine == null || turbine.getFarmId() == null) {
            return;
        }
        for (GridPowerCommand command : commandRepository.findByFarmIdAndStatusInOrderByStartTimeAscIdAsc(
                turbine.getFarmId(), List.of("EXECUTING", "PAUSED"))) {
            Optional<GridPowerAllocation> optional = allocationRepository
                    .findByCommandIdAndTurbineId(command.getId(), turbine.getId());
            if (optional.isEmpty() || !Boolean.TRUE.equals(optional.get().getIncludedFlag())) {
                continue;
            }
            WindFarm farm = requireFarm(command.getFarmId());
            ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(farm.getTimeZone()));
            List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId());
            refreshRunnableState(rows, farm.getTimeZone(), now);
            if ("PROPORTIONAL".equals(command.getAllocationMode())) {
                List<Turbine> turbines = rows.stream()
                        .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()) && Boolean.TRUE.equals(a.getRunnableFlag()))
                        .map(a -> turbineRepository.findById(a.getTurbineId()).orElse(null))
                        .filter(Objects::nonNull)
                        .toList();
                calculateProportional(command, rows, turbines, farm.getTimeZone(), now);
            } else {
                for (GridPowerAllocation row : rows) {
                    if (row.getTurbineId().equals(turbine.getId()) && !Boolean.TRUE.equals(row.getRunnableFlag())) {
                        row.setCurrentLimitKw(BigDecimal.ZERO);
                        row.setStatus("EXCLUDED");
                        row.setReason("机位当前不可运行，释放已占用额度");
                    }
                }
            }
            allocationRepository.saveAll(rows);
            log(command, "ALLOC_REFRESH", command.getStatus(), command.getStatus(), operator, reason);
            if ("EXECUTING".equals(command.getStatus())) {
                limitServiceProvider.ifAvailable(s -> s.evaluateCommand(command, "COMMAND_CHANGE", operator));
            }
        }
    }

    public List<GridPowerCommand> list(Long farmId) {
        if (farmId != null) {
            return commandRepository.findByFarmIdOrderByStartTimeDescIdDesc(farmId);
        }
        return commandRepository.findAll(org.springframework.data.domain.Sort
                .by(org.springframework.data.domain.Sort.Direction.DESC, "startTime", "id"));
    }

    public GridPowerCommand get(Long id) {
        return commandRepository.findById(id).orElseThrow(() -> new BizException("限功率指令不存在：" + id));
    }

    public CommandVO toVO(GridPowerCommand command) {
        WindFarm farm = farmRepository.findById(command.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        CommandVO vo = new CommandVO();
        vo.setId(command.getId());
        vo.setCommandNo(command.getCommandNo());
        vo.setFarmId(command.getFarmId());
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setTimeZone(zone);
        vo.setTargetLimitKw(command.getTargetLimitKw());
        vo.setAllocationMode(command.getAllocationMode());
        vo.setAllocationModeName(Dict.gridAllocationModeName(command.getAllocationMode()));
        vo.setStatus(command.getStatus());
        vo.setStatusName(Dict.gridCommandStatusName(command.getStatus()));
        vo.setPriorityRank(command.getPriorityRank());
        vo.setOverlapPolicy(command.getOverlapPolicy());
        vo.setStartTime(TimeText.iso(command.getStartTime()));
        vo.setStartTz(command.getStartZone());
        vo.setStartLocal(TimeText.local(command.getStartTime(), zone));
        vo.setEndTime(TimeText.iso(command.getEndTime()));
        vo.setEndTz(command.getEndZone());
        vo.setEndLocal(TimeText.local(command.getEndTime(), zone));
        vo.setReleaseCondition(command.getReleaseCondition());
        vo.setAllocationStatus(command.getAllocationStatus());
        vo.setAllocationStatusName(allocationStatusName(command.getAllocationStatus()));
        vo.setActivatedLocal(TimeText.local(command.getActivatedTime(), zone));
        vo.setPausedLocal(TimeText.local(command.getPausedTime(), zone));
        vo.setFinishedLocal(TimeText.local(command.getFinishedTime(), zone));
        vo.setCancelledLocal(TimeText.local(command.getCancelledTime(), zone));
        vo.setOwner(command.getOwner());
        vo.setNote(command.getNote());
        vo.setLockVersion(command.getLockVersion());
        ZonedDateTime now = ZonedDateTime.now(Dict.zoneOf(zone));
        vo.setWithinWindow(!now.isBefore(command.getStartTime()) && now.isBefore(command.getEndTime()));

        List<GridPowerAllocation> rows = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId());
        Map<Long, BigDecimal> strict = effectiveTurbineLimits(command.getFarmId(), now, command.getId());
        Map<Long, List<GridPowerCommand>> source = effectiveSourceCommands(command.getFarmId(), now);
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal current = BigDecimal.ZERO;
        BigDecimal runnableRated = BigDecimal.ZERO;
        for (GridPowerAllocation row : rows) {
            vo.getAllocations().add(toAllocationVO(row, zone, strict, source));
            allocated = allocated.add(nullZero(row.getAllocatedLimitKw()));
            current = current.add(nullZero(row.getCurrentLimitKw()));
            if (Boolean.TRUE.equals(row.getRunnableFlag()) && Boolean.TRUE.equals(row.getIncludedFlag())) {
                runnableRated = runnableRated.add(nullZero(row.getRatedPowerKw()));
            }
        }
        vo.setAllocatedTotalKw(allocated);
        vo.setCurrentTotalKw(current);
        vo.setRunnableRatedTotalKw(runnableRated);
        vo.setOverlaps(overlaps(command));
        vo.setLogs(commandLogRepository.findByCommandIdOrderByIdAsc(command.getId()).stream()
                .map(l -> toLogVO(l, zone)).toList());
        vo.setBlockers(blockers(command, rows));
        return vo;
    }

    public List<GridPowerAllocation> allocations(Long commandId) {
        return allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(commandId);
    }

    public Map<Long, BigDecimal> effectiveTurbineLimits(Long farmId, ZonedDateTime at, Long... excludeCommandIds) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (GridPowerCommand command : effectiveCommands(farmId, at, excludeCommandIds)) {
            for (GridPowerAllocation row : allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId())) {
                if (!Boolean.TRUE.equals(row.getIncludedFlag()) || !Boolean.TRUE.equals(row.getRunnableFlag())) {
                    continue;
                }
                BigDecimal value = nullZero(row.getCurrentLimitKw());
                result.merge(row.getTurbineId(), value, BigDecimal::min);
            }
        }
        return result;
    }

    private List<GridPowerCommand> effectiveCommands(Long farmId, ZonedDateTime at, Long... excludeCommandIds) {
        Set<Long> excludes = excludeCommandIds == null ? Set.of() : new HashSet<>(List.of(excludeCommandIds));
        return commandRepository.findByFarmIdAndStatusInOrderByStartTimeAscIdAsc(farmId, List.of("EXECUTING")).stream()
                .filter(c -> !excludes.contains(c.getId()))
                .filter(c -> !at.isBefore(c.getStartTime()) && at.isBefore(c.getEndTime()))
                .sorted(Comparator.comparing(GridPowerCommand::getPriorityRank).thenComparing(GridPowerCommand::getId))
                .toList();
    }

    private Map<Long, List<GridPowerCommand>> effectiveSourceCommands(Long farmId, ZonedDateTime at) {
        Map<Long, List<GridPowerCommand>> result = new LinkedHashMap<>();
        for (GridPowerCommand command : effectiveCommands(farmId, at)) {
            for (GridPowerAllocation row : allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(command.getId())) {
                if (Boolean.TRUE.equals(row.getIncludedFlag()) && Boolean.TRUE.equals(row.getRunnableFlag())) {
                    result.computeIfAbsent(row.getTurbineId(), k -> new ArrayList<>()).add(command);
                }
            }
        }
        return result;
    }

    private AllocationVO toAllocationVO(GridPowerAllocation row, String zone, Map<Long, BigDecimal> strict,
            Map<Long, List<GridPowerCommand>> sources) {
        AllocationVO vo = new AllocationVO();
        vo.setId(row.getId());
        vo.setCommandId(row.getCommandId());
        vo.setTurbineId(row.getTurbineId());
        vo.setUnitCode(row.getUnitCode());
        Turbine turbine = turbineRepository.findById(row.getTurbineId()).orElse(null);
        vo.setUnitName(turbine == null ? null : turbine.getUnitName());
        vo.setTurbineStatus(turbine == null ? null : turbine.getStatus());
        vo.setTurbineStatusName(turbine == null ? null : turbine.getStatusName());
        vo.setRatedPowerKw(row.getRatedPowerKw());
        vo.setIncludedFlag(row.getIncludedFlag());
        vo.setRunnableFlag(row.getRunnableFlag());
        vo.setAllocationMode(row.getAllocationMode());
        vo.setAllocationModeName(Dict.gridAllocationModeName(row.getAllocationMode()));
        vo.setAllocatedLimitKw(row.getAllocatedLimitKw());
        vo.setCurrentLimitKw(row.getCurrentLimitKw());
        vo.setLatestPowerKw(turbine == null ? null : turbine.getLastPowerKw());
        vo.setManualConfirmed(row.getManualConfirmed());
        vo.setStatus(row.getStatus());
        vo.setStatusName(allocationRowStatusName(row.getStatus()));
        vo.setReason(row.getReason());
        vo.setAllocatedLocal(TimeText.local(row.getAllocatedTime(), zone));
        vo.setStrictLimitKw(strict.get(row.getTurbineId()));
        List<GridPowerCommand> list = sources.getOrDefault(row.getTurbineId(), List.of());
        vo.setStrictCommandNos(list.stream().map(GridPowerCommand::getCommandNo).collect(Collectors.joining(" / ")));
        return vo;
    }

    private CommandLogVO toLogVO(GridCommandLog log, String zone) {
        CommandLogVO vo = new CommandLogVO();
        vo.setId(log.getId());
        vo.setAction(log.getAction());
        vo.setActionName(commandActionName(log.getAction()));
        vo.setFromStatus(log.getFromStatus());
        vo.setFromStatusName(Dict.gridCommandStatusName(log.getFromStatus()));
        vo.setToStatus(log.getToStatus());
        vo.setToStatusName(Dict.gridCommandStatusName(log.getToStatus()));
        vo.setOperator(log.getOperator());
        vo.setDetail(log.getDetail());
        vo.setCreatedAt(TimeText.iso(log.getCreatedAt()));
        vo.setCreatedTz(log.getCreatedZone());
        vo.setCreatedLocal(TimeText.local(log.getCreatedAt(), zone));
        return vo;
    }

    private List<OverlapVO> overlaps(GridPowerCommand target) {
        Set<Long> targetUnits = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(target.getId()).stream()
                .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()))
                .map(GridPowerAllocation::getTurbineId)
                .collect(Collectors.toSet());
        List<OverlapVO> result = new ArrayList<>();
        for (GridPowerCommand other : commandRepository.findByFarmIdOrderByStartTimeDescIdDesc(target.getFarmId())) {
            if (other.getId().equals(target.getId()) || !ACTIVE_STATUSES.contains(other.getStatus())) {
                continue;
            }
            if (target.getEndTime().isBefore(other.getStartTime()) || other.getEndTime().isBefore(target.getStartTime())) {
                continue;
            }
            Set<Long> otherUnits = allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(other.getId()).stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()))
                    .map(GridPowerAllocation::getTurbineId)
                    .collect(Collectors.toSet());
            Set<Long> shared = new HashSet<>(targetUnits);
            shared.retainAll(otherUnits);
            if (shared.isEmpty()) {
                continue;
            }
            OverlapVO vo = new OverlapVO();
            vo.setCommandId(other.getId());
            vo.setCommandNo(other.getCommandNo());
            vo.setStatus(other.getStatus());
            vo.setStatusName(Dict.gridCommandStatusName(other.getStatus()));
            vo.setPriorityRank(other.getPriorityRank());
            vo.setRelation(relation(target, other));
            vo.setRelationName(overlapRelationName(vo.getRelation()));
            vo.setStricter(target.getTargetLimitKw().compareTo(other.getTargetLimitKw()) < 0);
            vo.setStartLocal(TimeText.local(other.getStartTime(), target.getStartZone()));
            vo.setEndLocal(TimeText.local(other.getEndTime(), target.getEndZone()));
            vo.setSharedUnitCodes(allocationRepository.findByCommandIdOrderByUnitCodeAscIdAsc(other.getId()).stream()
                    .filter(a -> shared.contains(a.getTurbineId()))
                    .map(GridPowerAllocation::getUnitCode)
                    .toList());
            vo.setEffectiveRule("执行窗口重叠时逐机位取所有执行中指令的最小当前额度；优先级仅用于协调展示，不替代最严限值");
            result.add(vo);
        }
        return result.stream()
                .sorted(Comparator.comparing(OverlapVO::getStartLocal, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private String relation(GridPowerCommand target, GridPowerCommand other) {
        boolean sameStart = !target.getStartTime().isBefore(other.getStartTime())
                && !target.getStartTime().isAfter(other.getStartTime());
        boolean sameEnd = !target.getEndTime().isBefore(other.getEndTime())
                && !target.getEndTime().isAfter(other.getEndTime());
        if (sameStart && sameEnd) {
            return "EQUAL";
        }
        if (!target.getStartTime().isAfter(other.getStartTime()) && !target.getEndTime().isBefore(other.getEndTime())) {
            return "COVERS";
        }
        if (!other.getStartTime().isAfter(target.getStartTime()) && !other.getEndTime().isBefore(target.getEndTime())) {
            return "COVERED_BY";
        }
        return "INTERSECTS";
    }

    private List<String> blockers(GridPowerCommand command, List<GridPowerAllocation> rows) {
        List<String> result = new ArrayList<>();
        if (!"CONFIRMED".equals(command.getAllocationStatus())) {
            result.add("PROPORTIONAL".equals(command.getAllocationMode())
                    ? "请预览并确认按额定容量比例分摊结果"
                    : "请由值班人员逐台确认每台可运行机位上限");
        }
        BigDecimal current = sumCurrent(rows);
        if (current.compareTo(command.getTargetLimitKw()) > 0) {
            result.add("机位当前额度合计 " + current + " kW 超过目标 " + command.getTargetLimitKw() + " kW");
        }
        if ("MANUAL".equals(command.getAllocationMode())) {
            List<String> missing = rows.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getIncludedFlag()) && Boolean.TRUE.equals(a.getRunnableFlag()))
                    .filter(a -> a.getCurrentLimitKw() == null || !Boolean.TRUE.equals(a.getManualConfirmed()))
                    .map(GridPowerAllocation::getUnitCode)
                    .toList();
            if (!missing.isEmpty()) {
                result.add("以下可运行机位尚未逐台确认：" + String.join("、", missing));
            }
        }
        return result;
    }

    private void rebuildAllocations(GridPowerCommand command, List<Turbine> turbines, String zone, ZonedDateTime now) {
        List<GridPowerAllocation> rows = new ArrayList<>();
        for (Turbine turbine : turbines) {
            GridPowerAllocation row = new GridPowerAllocation();
            row.setCommandId(command.getId());
            row.setFarmId(command.getFarmId());
            row.setTurbineId(turbine.getId());
            row.setUnitCode(turbine.getUnitCode());
            row.setRatedPowerKw(turbine.getRatedPowerKw());
            row.setIncludedFlag(true);
            row.setRunnableFlag(isRunnable(turbine));
            row.setAllocationMode(command.getAllocationMode());
            row.setManualConfirmed(false);
            row.setStatus("DRAFT");
            row.setReason(isRunnable(turbine) ? null : "机位当前不可运行，启动时不占用额度");
            rows.add(row);
        }
        if ("PROPORTIONAL".equals(command.getAllocationMode())) {
            calculateProportional(command, rows, turbines.stream().filter(this::isRunnable).toList(), zone, now);
            command.setAllocationStatus("PREVIEWED");
        } else {
            command.setAllocationStatus("NOT_CONFIRMED");
        }
        allocationRepository.saveAll(rows);
    }

    private void calculateProportional(GridPowerCommand command, List<GridPowerAllocation> rows,
            List<Turbine> runnableTurbines, String zone, ZonedDateTime now) {
        refreshRunnableState(rows, zone, now);
        BigDecimal ratedTotal = runnableTurbines.stream()
                .filter(t -> rows.stream().anyMatch(r -> r.getTurbineId().equals(t.getId())
                        && Boolean.TRUE.equals(r.getIncludedFlag())))
                .map(Turbine::getRatedPowerKw)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal target = command.getTargetLimitKw();
        for (GridPowerAllocation row : rows) {
            if (!Boolean.TRUE.equals(row.getIncludedFlag())) {
                row.setCurrentLimitKw(BigDecimal.ZERO);
                row.setAllocatedLimitKw(BigDecimal.ZERO);
                row.setStatus("EXCLUDED");
                row.setReason("不在适用机位集合");
                continue;
            }
            Turbine turbine = turbineRepository.findById(row.getTurbineId()).orElse(null);
            if (turbine == null || !isRunnable(turbine) || ratedTotal.compareTo(BigDecimal.ZERO) <= 0) {
                row.setRunnableFlag(false);
                row.setCurrentLimitKw(BigDecimal.ZERO);
                row.setAllocatedLimitKw(row.getAllocatedLimitKw() == null ? BigDecimal.ZERO : row.getAllocatedLimitKw());
                row.setStatus("EXCLUDED");
                row.setReason("机位当前为检修 / 故障 / 停运，不占用场站额度");
                continue;
            }
            BigDecimal value = target.compareTo(ratedTotal) >= 0
                    ? row.getRatedPowerKw()
                    : target.multiply(row.getRatedPowerKw()).divide(ratedTotal, 2, RoundingMode.DOWN);
            value = value.min(row.getRatedPowerKw());
            row.setRunnableFlag(true);
            row.setCurrentLimitKw(value);
            if (row.getAllocatedLimitKw() == null || "PLANNED".equals(command.getStatus())) {
                row.setAllocatedLimitKw(value);
            }
            row.setManualConfirmed(false);
            row.setStatus("CONFIRMED");
            row.setReason("按可运行机位额定容量 " + ratedTotal + " kW 比例分摊");
            row.setAllocatedTime(now);
            row.setAllocatedZone(zone);
        }
        BigDecimal sum = rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIncludedFlag()) && Boolean.TRUE.equals(r.getRunnableFlag()))
                .map(GridPowerAllocation::getCurrentLimitKw)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(target) > 0) {
            throw new BizException("比例分摊结果合计超过场站目标，请检查机位额定功率");
        }
    }

    private void applyManualAllocation(GridPowerCommand command, List<GridPowerAllocation> rows,
            GridAllocationRequest request, ZonedDateTime now, String zone) {
        if (request == null || request.getLimits() == null || request.getLimits().isEmpty()) {
            throw new BizException("手动分摊必须由值班人员逐台填入上限");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (GridPowerAllocation row : rows) {
            if (!Boolean.TRUE.equals(row.getIncludedFlag())) {
                row.setCurrentLimitKw(BigDecimal.ZERO);
                row.setStatus("EXCLUDED");
                continue;
            }
            if (!Boolean.TRUE.equals(row.getRunnableFlag())) {
                row.setCurrentLimitKw(BigDecimal.ZERO);
                row.setAllocatedLimitKw(row.getAllocatedLimitKw() == null ? BigDecimal.ZERO : row.getAllocatedLimitKw());
                row.setManualConfirmed(false);
                row.setStatus("EXCLUDED");
                row.setReason("机位当前不可运行，不允许占用额度");
                continue;
            }
            BigDecimal value = request.getLimits().get(row.getTurbineId());
            if (value == null) {
                throw new BizException("机位 " + row.getUnitCode() + " 尚未逐台确认上限");
            }
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(row.getRatedPowerKw()) > 0) {
                throw new BizException("机位 " + row.getUnitCode() + " 上限必须在 0 至额定功率 "
                        + row.getRatedPowerKw() + " kW 之间");
            }
            sum = sum.add(value);
            row.setCurrentLimitKw(value);
            if ("PLANNED".equals(command.getStatus())) {
                row.setAllocatedLimitKw(value);
            }
            row.setManualConfirmed(true);
            row.setStatus("CONFIRMED");
            row.setReason("值班人员逐台确认");
            row.setAllocatedTime(now);
            row.setAllocatedZone(zone);
        }
        if (sum.compareTo(command.getTargetLimitKw()) > 0) {
            throw new BizException("手动分摊合计 " + sum + " kW 超过场站目标 " + command.getTargetLimitKw() + " kW");
        }
    }

    private void validateManualCurrent(GridPowerCommand command, List<GridPowerAllocation> rows) {
        if (!"MANUAL".equals(command.getAllocationMode())) {
            return;
        }
        for (GridPowerAllocation row : rows) {
            if (Boolean.TRUE.equals(row.getIncludedFlag()) && Boolean.TRUE.equals(row.getRunnableFlag())
                    && (!Boolean.TRUE.equals(row.getManualConfirmed()) || row.getCurrentLimitKw() == null)) {
                throw new BizException("机位 " + row.getUnitCode() + " 状态已变化，请重新逐台确认手动分摊");
            }
        }
    }

    private void refreshRunnableState(List<GridPowerAllocation> rows, String zone, ZonedDateTime now) {
        for (GridPowerAllocation row : rows) {
            Turbine turbine = turbineRepository.findById(row.getTurbineId()).orElse(null);
            boolean runnable = turbine != null && Boolean.TRUE.equals(row.getIncludedFlag()) && isRunnable(turbine);
            boolean before = Boolean.TRUE.equals(row.getRunnableFlag());
            row.setRunnableFlag(runnable);
            if (!runnable) {
                row.setCurrentLimitKw(BigDecimal.ZERO);
                if (row.getAllocatedLimitKw() == null) {
                    row.setAllocatedLimitKw(BigDecimal.ZERO);
                }
                row.setStatus("EXCLUDED");
                row.setReason("机位当前不可运行，释放并不再占用场站额度");
            } else if (before && "EXCLUDED".equals(row.getStatus())) {
                row.setStatus("CONFIRMED");
                row.setReason("机位恢复可运行");
            }
        }
    }

    private void validateBase(GridCommandRequest request, WindFarm farm) {
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new BizException("请填写指令开始和结束时间");
        }
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BizException("结束时间必须晚于开始时间");
        }
        if (request.getTargetLimitKw() == null || request.getTargetLimitKw().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("目标上限不能为负");
        }
        if (!Dict.isValidGridAllocationMode(request.getAllocationMode())) {
            throw new BizException("请选择分摊方式：按额定容量比例分摊或值班人员逐台确认");
        }
        if (request.getPriorityRank() != null && (request.getPriorityRank() < 1 || request.getPriorityRank() > 999)) {
            throw new BizException("优先级必须在 1~999 之间，数值越小优先级越高");
        }
    }

    private void validateCommandNo(String no, Long excludeId) {
        if (no == null || no.trim().isEmpty()) {
            throw new BizException("请登记指令编号");
        }
        if (excludeId == null ? commandRepository.existsByCommandNo(no.trim())
                : commandRepository.existsByCommandNoAndIdNot(no.trim(), excludeId)) {
            throw new BizException("指令编号已存在：" + no.trim());
        }
    }

    private List<Turbine> selectedTurbines(Long farmId, List<Long> turbineIds) {
        List<Turbine> all = turbineRepository.findByFarmIdOrderByUnitCodeAsc(farmId);
        if (turbineIds == null || turbineIds.isEmpty()) {
            return all;
        }
        List<Turbine> selected = all.stream().filter(t -> turbineIds.contains(t.getId())).toList();
        if (selected.size() != new HashSet<>(turbineIds).size()) {
            throw new BizException("适用机位中包含不属于该风场或不存在的机位");
        }
        return selected;
    }

    private WindFarm requireFarm(Long farmId) {
        if (farmId == null) {
            throw new BizException("请选择风场");
        }
        return farmRepository.findById(farmId).orElseThrow(() -> new BizException("风场不存在：" + farmId));
    }

    private GridPowerCommand lockActive(Long id) {
        return commandRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("限功率指令不存在：" + id));
    }

    private void log(GridPowerCommand command, String action, String from, String to, String operator, String detail) {
        WindFarm farm = farmRepository.findById(command.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        GridCommandLog log = new GridCommandLog();
        log.setCommandId(command.getId());
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperator(trim(operator));
        log.setDetail(detail);
        log.setCreatedAt(ZonedDateTime.now(Dict.zoneOf(zone)));
        log.setCreatedZone(zone);
        commandLogRepository.save(log);
    }

    private boolean isRunnable(Turbine turbine) {
        return turbine != null && "ONLINE".equals(turbine.getStatus());
    }

    private BigDecimal sumCurrent(List<GridPowerAllocation> rows) {
        return rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIncludedFlag()) && Boolean.TRUE.equals(r.getRunnableFlag()))
                .map(GridPowerAllocation::getCurrentLimitKw)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal nullZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String operator(GridCommandActionRequest request, String fallback) {
        if (request != null && request.getOperator() != null && !request.getOperator().trim().isEmpty()) {
            return request.getOperator().trim();
        }
        return fallback;
    }

    private String reason(GridCommandActionRequest request, String fallback) {
        if (request != null && request.getReason() != null && !request.getReason().trim().isEmpty()) {
            return request.getReason().trim();
        }
        return fallback;
    }

    private String allocationStatusName(String value) {
        return switch (value == null ? "" : value) {
            case "NOT_CONFIRMED" -> "待逐台确认";
            case "PREVIEWED" -> "比例预览";
            case "CONFIRMED" -> "已确认";
            default -> value;
        };
    }

    private String allocationRowStatusName(String value) {
        return switch (value == null ? "" : value) {
            case "DRAFT" -> "草稿";
            case "CONFIRMED" -> "已确认";
            case "EXCLUDED" -> "不可运行/不占额度";
            case "RELEASED" -> "已释放";
            default -> value;
        };
    }

    private String commandActionName(String action) {
        return switch (action == null ? "" : action) {
            case "CREATE" -> "创建";
            case "UPDATE" -> "修改";
            case "ALLOC_PREVIEW" -> "分摊预览";
            case "ALLOC_CONFIRM" -> "分摊确认";
            case "ALLOC_REFRESH" -> "执行期分摊刷新";
            case "START" -> "开始执行";
            case "PAUSE" -> "暂停";
            case "RESUME" -> "恢复";
            case "FINISH" -> "结束";
            case "CANCEL" -> "取消";
            default -> action;
        };
    }

    private String overlapRelationName(String value) {
        return switch (value == null ? "" : value) {
            case "EQUAL" -> "时间/范围相同";
            case "COVERS" -> "本指令覆盖对方";
            case "COVERED_BY" -> "对方覆盖本指令";
            case "INTERSECTS" -> "部分重叠";
            default -> value;
        };
    }
}
