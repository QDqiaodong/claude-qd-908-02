package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.GridLimitAllocation;
import com.wind.farm.domain.GridLimitDeviation;
import com.wind.farm.domain.GridLimitLog;
import com.wind.farm.domain.GridLimitOrder;
import com.wind.farm.domain.GridLimitTurbine;
import com.wind.farm.domain.RunLog;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.GridAllocationRequest;
import com.wind.farm.dto.GridDeviationHandleRequest;
import com.wind.farm.dto.GridLimitOrderRequest;
import com.wind.farm.dto.GridStatusRequest;
import com.wind.farm.dto.Views.GridLimitAllocationVO;
import com.wind.farm.dto.Views.GridLimitDeviationVO;
import com.wind.farm.dto.Views.GridLimitLogVO;
import com.wind.farm.dto.Views.GridLimitOrderVO;
import com.wind.farm.dto.Views.GridLimitOverlapVO;
import com.wind.farm.dto.Views.GridLimitPreviewVO;
import com.wind.farm.repository.GridLimitAllocationRepository;
import com.wind.farm.repository.GridLimitDeviationRepository;
import com.wind.farm.repository.GridLimitLogRepository;
import com.wind.farm.repository.GridLimitOrderRepository;
import com.wind.farm.repository.GridLimitTurbineRepository;
import com.wind.farm.repository.RunLogRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 电网限功率指令闭环。
 *
 * <p>所有调度时间按绝对时刻存储，风场时区只用于展示和值班排班，跨本地日期 / 时区调整不会产生空档。
 * 并行指令默认 COEXIST：同一机位生效上限取最小值；SUPERSEDE 只能覆盖范围更全且逐机位更严、结束时间
 * 不早于旧令的指令，防止一条较宽松的新令意外解除旧限制。
 */
@Service
public class GridLimitService {

    private final GridLimitOrderRepository orderRepository;
    private final GridLimitTurbineRepository turbineRefRepository;
    private final GridLimitAllocationRepository allocationRepository;
    private final GridLimitLogRepository logRepository;
    private final GridLimitDeviationRepository deviationRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final RunLogRepository runLogRepository;

    public GridLimitService(GridLimitOrderRepository orderRepository,
            GridLimitTurbineRepository turbineRefRepository,
            GridLimitAllocationRepository allocationRepository,
            GridLimitLogRepository logRepository,
            GridLimitDeviationRepository deviationRepository,
            TurbineRepository turbineRepository,
            WindFarmRepository farmRepository,
            RunLogRepository runLogRepository) {
        this.orderRepository = orderRepository;
        this.turbineRefRepository = turbineRefRepository;
        this.allocationRepository = allocationRepository;
        this.logRepository = logRepository;
        this.deviationRepository = deviationRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.runLogRepository = runLogRepository;
    }

    // ================= 指令生命周期 =================

    public List<GridLimitOrderVO> listVO(Long farmId) {
        List<GridLimitOrder> orders = farmId == null
                ? orderRepository.findAll(org.springframework.data.domain.Sort.by("startTime").descending())
                : orderRepository.findByFarmIdOrderByStartTimeDescIdDesc(farmId);
        return orders.stream().map(this::toVO).collect(Collectors.toList());
    }

    public GridLimitOrder get(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new BizException("限功率指令不存在：" + id));
    }

    public GridLimitOrderVO getVO(Long id) {
        return toVO(get(id));
    }

    @Transactional
    public GridLimitOrder create(GridLimitOrderRequest request) {
        WindFarm farm = lockFarm(request.getFarmId());
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        GridLimitOrder order = new GridLimitOrder();
        order.setOrderNo(StringUtils.hasText(request.getOrderNo()) ? request.getOrderNo().trim() : generateOrderNo(now));
        if (orderRepository.existsByOrderNo(order.getOrderNo())) {
            throw new BizException("指令编号已存在：" + order.getOrderNo());
        }
        order.setFarmId(farm.getId());
        order.setStatus("PLANNED");
        order.setPriority(request.getPriority() == null ? 100 : request.getPriority());
        order.setOverlapMode(Dict.GRID_OVERLAP_COEXIST);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        applyFields(order, request, farm);
        order = orderRepository.save(order);
        replaceTurbineRefs(order, request, farm);
        writeLog(order, "CREATE", null, "PLANNED", request.getDutyOperator(),
                "登记电网限功率指令，目标上限 " + order.getTargetLimitKw() + " kW，"
                        + Dict.gridAllocationModeName(order.getAllocationMode()) + "，重叠关系："
                        + Dict.gridOverlapModeName(order.getOverlapMode()));
        return order;
    }

    @Transactional
    public GridLimitOrder update(Long id, GridLimitOrderRequest request) {
        GridLimitOrder loaded = get(id);
        if (!"PLANNED".equals(loaded.getStatus())) {
            throw new BizException("只有待执行指令允许修改；执行轨迹请使用暂停 / 结束 / 取消并留痕");
        }
        WindFarm farm = lockFarm(loaded.getFarmId());
        GridLimitOrder order = orderRepository.lockById(id).orElseThrow();
        String old = order.getTargetLimitKw() + "kW/" + order.getStartTime() + "~" + order.getEndTime();
        applyFields(order, request, farm);
        order.setUpdatedAt(ZonedDateTime.now(zone(farm)));
        order = orderRepository.save(order);
        replaceTurbineRefs(order, request, farm);
        writeLog(order, "UPDATE", "PLANNED", "PLANNED", request.getDutyOperator(),
                "修改指令基础信息，原关键参数：" + old);
        return order;
    }

    @Transactional
    public GridLimitPreviewVO preview(Long id, GridAllocationRequest request) {
        GridLimitOrder order = get(id);
        WindFarm farm = lockFarm(order.getFarmId());
        List<Turbine> selected = selectedTurbines(order);
        Map<Long, BigDecimal> manual = new HashMap<>();
        if (Dict.GRID_ALLOC_MANUAL.equals(order.getAllocationMode())) {
            if (request != null && request.getItems() != null && !request.getItems().isEmpty()) {
                request.getItems().forEach(i -> manual.put(i.getTurbineId(), i.getLimitKw()));
            } else {
                allocationRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                        .filter(a -> Boolean.TRUE.equals(a.getRunnable()))
                        .forEach(a -> manual.put(a.getTurbineId(), a.getLimitKw()));
            }
        }
        CalculatedAllocation calc = calculate(order, selected, farm, manual);
        GridLimitPreviewVO vo = new GridLimitPreviewVO();
        vo.setFarmId(order.getFarmId());
        vo.setTargetLimitKw(order.getTargetLimitKw());
        vo.setRunnableCapacityKw(calc.runnableCapacity);
        vo.setAllocatedTotalKw(calc.total);
        vo.setWithinTarget(calc.total.compareTo(order.getTargetLimitKw()) <= 0);
        vo.setAllocations(calc.rows.stream().map(a -> toAllocationVO(a, null)).collect(Collectors.toList()));
        vo.setBlockers(calc.blockers);
        return vo;
    }

    /** 执行前确认分摊：比例分摊由后台计算；手动模式必须逐台给出上限。 */
    @Transactional
    public List<GridLimitAllocation> confirmAllocations(Long id, GridAllocationRequest request) {
        GridLimitOrder loaded = get(id);
        if (!"PLANNED".equals(loaded.getStatus())) {
            throw new BizException("只有待执行指令允许在执行前确认分摊");
        }
        WindFarm farm = lockFarm(loaded.getFarmId());
        GridLimitOrder order = orderRepository.lockById(id).orElseThrow();
        List<Turbine> selected = selectedTurbines(order);
        Map<Long, BigDecimal> manual = new HashMap<>();
        if (Dict.GRID_ALLOC_MANUAL.equals(order.getAllocationMode())) {
            if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
                throw new BizException("已选择「值班人员逐台确认」，必须逐台填写可运行机位上限");
            }
            request.getItems().forEach(i -> manual.put(i.getTurbineId(), i.getLimitKw()));
        }
        CalculatedAllocation calc = calculate(order, selected, farm, manual);
        if (!calc.blockers.isEmpty()) {
            throw new BizException(calc.blockers.get(0));
        }
        allocationRepository.deleteAll(allocationRepository.findByOrderIdOrderByIdAsc(order.getId()));
        List<GridLimitAllocation> saved = allocationRepository.saveAll(calc.rows);
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        for (GridLimitAllocation a : saved) {
            if (Boolean.TRUE.equals(a.getRunnable())) {
                a.setConfirmOperator(request == null ? null : request.getOperator());
                a.setConfirmedAt(now);
                a.setConfirmedZone(zone(farm).getId());
            }
        }
        order.setConfirmedBy(request == null ? null : request.getOperator());
        order.setUpdatedAt(now);
        orderRepository.save(order);
        writeLog(order, "ALLOCATE", "PLANNED", "PLANNED", request == null ? null : request.getOperator(),
                "执行前确认机位分摊：可运行容量 " + calc.runnableCapacity + " kW，分摊合计 " + calc.total
                        + " kW，停运 / 检修 / 故障机位 " + calc.excluded + " 台不占额度");
        return saved;
    }

    @Transactional
    public GridLimitOrder start(Long id, GridStatusRequest request) {
        GridLimitOrder loaded = get(id);
        if (!"PLANNED".equals(loaded.getStatus())) {
            throw new BizException("只有待执行指令可以开始执行");
        }
        WindFarm farm = lockFarm(loaded.getFarmId());
        GridLimitOrder order = orderRepository.lockById(id).orElseThrow();
        List<Turbine> selected = selectedTurbines(order);
        List<GridLimitAllocation> existingAllocations = allocationRepository.findByOrderIdOrderByIdAsc(id);
        if (existingAllocations.isEmpty() && Dict.GRID_ALLOC_MANUAL.equals(order.getAllocationMode())) {
            throw new BizException("手动分摊模式必须先逐台确认机位上限，不能自动开始执行");
        }
        if (existingAllocations.isEmpty()) {
            GridAllocationRequest allocRequest = new GridAllocationRequest();
            allocRequest.setOperator(request == null ? null : request.getOperator());
            confirmAllocations(id, allocRequest);
            order = get(id);
        }
        List<GridLimitAllocation> allocations = allocationRepository.findByOrderIdOrderByIdAsc(id);
        validateOverlapOnStart(order, allocations, selected);
        backfillWindow(order, farm);

        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        order.setStatus("EXECUTING");
        order.setActivatedAt(now);
        order.setActivatedZone(zone(farm).getId());
        order.setUpdatedAt(now);
        orderRepository.save(order);
        writeLog(order, "START", "PLANNED", "EXECUTING", request == null ? null : request.getOperator(),
                "指令进入执行，后台以已确认分摊作为运行读数限值");
        return order;
    }

    @Transactional
    public GridLimitOrder pause(Long id, GridStatusRequest request) {
        return changeStatus(id, "PAUSED", "PAUSE", request);
    }

    @Transactional
    public GridLimitOrder resume(Long id, GridStatusRequest request) {
        return changeStatus(id, "EXECUTING", "RESUME", request);
    }

    @Transactional
    public GridLimitOrder end(Long id, GridStatusRequest request) {
        GridLimitOrder loaded = get(id);
        if (!"EXECUTING".equals(loaded.getStatus()) && !"PAUSED".equals(loaded.getStatus())) {
            throw new BizException("只有执行中 / 已暂停指令可以登记结束");
        }
        if (request == null || !StringUtils.hasText(request.getReleaseResult())) {
            throw new BizException("请登记解除条件核对和解除结果后再结束指令");
        }
        WindFarm farm = lockFarm(loaded.getFarmId());
        GridLimitOrder order = orderRepository.lockById(id).orElseThrow();
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        order.setStatus("ENDED");
        order.setEndedAt(now);
        order.setEndedZone(zone(farm).getId());
        order.setReleaseResult(request.getReleaseResult());
        order.setReleaseOperator(request.getOperator());
        order.setUpdatedAt(now);
        orderRepository.save(order);
        writeLog(order, "END", null, "ENDED", request.getOperator(), "解除结果：" + request.getReleaseResult());
        return order;
    }

    @Transactional
    public GridLimitOrder cancel(Long id, GridStatusRequest request) {
        GridLimitOrder loaded = get(id);
        if ("ENDED".equals(loaded.getStatus()) || "CANCELLED".equals(loaded.getStatus())) {
            throw new BizException("已结束 / 已取消指令不能再次取消");
        }
        WindFarm farm = lockFarm(loaded.getFarmId());
        GridLimitOrder order = orderRepository.lockById(id).orElseThrow();
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        String from = order.getStatus();
        order.setStatus("CANCELLED");
        order.setCancelledAt(now);
        order.setCancelledZone(zone(farm).getId());
        order.setReleaseResult(request == null ? null : request.getReleaseResult());
        order.setReleaseOperator(request == null ? null : request.getOperator());
        order.setUpdatedAt(now);
        orderRepository.save(order);
        writeLog(order, "CANCEL", from, "CANCELLED", request == null ? null : request.getOperator(),
                request == null ? null : request.getReleaseResult());
        return order;
    }

    @Transactional
    public List<GridLimitAllocation> refreshRuntimeAllocation(Long id, GridStatusRequest request) {
        GridLimitOrder loaded = get(id);
        if (!"EXECUTING".equals(loaded.getStatus()) && !"PAUSED".equals(loaded.getStatus())) {
            throw new BizException("仅执行中 / 已暂停指令需要按机位状态刷新分摊");
        }
        WindFarm farm = lockFarm(loaded.getFarmId());
        GridLimitOrder order = orderRepository.lockById(id).orElseThrow();
        List<Turbine> selected = selectedTurbines(order);
        Map<Long, BigDecimal> manual = new HashMap<>();
        if (Dict.GRID_ALLOC_MANUAL.equals(order.getAllocationMode())) {
            allocationRepository.findByOrderIdOrderByIdAsc(id).stream()
                    .filter(a -> Boolean.TRUE.equals(a.getRunnable()))
                    .forEach(a -> manual.put(a.getTurbineId(), a.getLimitKw()));
        }
        CalculatedAllocation calc = calculate(order, selected, farm, manual);
        allocationRepository.deleteAll(allocationRepository.findByOrderIdOrderByIdAsc(id));
        List<GridLimitAllocation> saved = allocationRepository.saveAll(calc.rows);
        writeLog(order, "REALLOCATE", order.getStatus(), order.getStatus(),
                request == null ? null : request.getOperator(),
                "按当前可运行状态刷新分摊：停运 / 检修 / 故障机位不占额度，分摊合计 " + calc.total + " kW");
        return saved;
    }

    private void backfillWindow(GridLimitOrder order, WindFarm farm) {
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        ZonedDateTime from = order.getStartTime();
        ZonedDateTime to = order.getEndTime().isBefore(now) ? order.getEndTime() : now;
        if (!to.isAfter(from)) {
            return;
        }
        runLogRepository.findByFarmIdAndRecordTimeBetweenOrderByRecordTimeAsc(order.getFarmId(), from, to)
                .stream().map(RunLog::getRecordTime).filter(Objects::nonNull).distinct()
                .forEach(t -> evaluateFarmAtTime(order.getFarmId(), t, "指令开始执行后回溯既有读数"));
    }

    // ================= 偏差与处置 =================

    public List<GridLimitDeviationVO> deviations(Long farmId, Long orderId, Boolean openOnly) {
        List<GridLimitDeviation> list;
        if (orderId != null) {
            list = deviationRepository.findByGoverningOrderIdOrderByRecordTimeDescIdDesc(orderId);
        } else if (farmId != null) {
            list = deviationRepository.findByFarmIdOrderByRecordTimeDescIdDesc(farmId);
        } else if (Boolean.TRUE.equals(openOnly)) {
            list = deviationRepository.findAll();
            list.sort(Comparator.comparing(GridLimitDeviation::getRecordTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        } else {
            list = deviationRepository.findAll();
            list.sort(Comparator.comparing(GridLimitDeviation::getRecordTime,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        }
        if (Boolean.TRUE.equals(openOnly)) {
            list = list.stream().filter(d -> Boolean.TRUE.equals(d.getLatestFlag())
                    && !"RELEASED".equals(d.getStatus()) && !"SUPERSEDED".equals(d.getStatus())).toList();
        }
        return list.stream().map(this::toDeviationVO).collect(Collectors.toList());
    }

    @Transactional
    public void recalculateFarmAtTime(Long farmId, ZonedDateTime recordTime, String reason) {
        if (farmId == null || recordTime == null) {
            return;
        }
        WindFarm farm = farmRepository.lockById(farmId).orElse(null);
        if (farm == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        for (GridLimitDeviation d : deviationRepository.findByFarmIdAndRecordTimeOrderByIdDesc(farmId, recordTime)) {
            if (Boolean.TRUE.equals(d.getLatestFlag())) {
                d.setLatestFlag(false);
                d.setStatus("SUPERSEDED");
                d.setRevisionReason(reason);
                d.setUpdatedAt(now);
                deviationRepository.save(d);
            }
        }
        evaluateFarmAtTime(farmId, recordTime, reason);
    }

    /**
     * 运行读数写入 / 补录 / 修正后的后台重算。旧版本置 SUPERSEDED 但保留原判断、原值和处置记录；
     * 新版本另存一行，禁止用前端比较替代后台写入。
     */
    @Transactional
    public void evaluateFarmAtTime(Long farmId, ZonedDateTime recordTime, String reason) {
        if (farmId == null || recordTime == null) {
            return;
        }
        WindFarm farm = farmRepository.lockById(farmId).orElse(null);
        if (farm == null) {
            return;
        }
        List<RunLog> logsAtTime = runLogRepository.findByFarmIdAndRecordTimeOrderByTurbineIdAsc(farmId, recordTime);
        List<GridLimitOrder> activeOrders = orderRepository.findByStatusOrderByPriorityAscIdAsc("EXECUTING").stream()
                .filter(o -> Objects.equals(o.getFarmId(), farmId))
                .filter(o -> !recordTime.isBefore(o.getStartTime()) && recordTime.isBefore(o.getEndTime()))
                .toList();
        if (activeOrders.isEmpty()) {
            return;
        }

        // 单机位：同一时刻覆盖该机位的多条指令取最小上限，governing 为最严格指令。
        for (RunLog log : logsAtTime) {
            List<LimitContext> contexts = new ArrayList<>();
            for (GridLimitOrder o : activeOrders) {
                allocationRepository.findByOrderIdOrderByIdAsc(o.getId()).stream()
                        .filter(a -> Objects.equals(a.getTurbineId(), log.getTurbineId()))
                        .filter(a -> Boolean.TRUE.equals(a.getRunnable()))
                        .findFirst()
                        .ifPresent(a -> contexts.add(new LimitContext(o, a.getLimitKw())));
            }
            if (contexts.isEmpty()) {
                continue;
            }
            contexts.sort(Comparator.comparing((LimitContext c) -> c.limit).thenComparing(c -> c.order.getPriority()));
            LimitContext gov = contexts.get(0);
            BigDecimal tol = Dict.gridTolerance(gov.limit);
            BigDecimal exceeded = log.getActivePowerKw().subtract(gov.limit.add(tol));
            String type = classify(log, gov.limit, farm, true);
            if (exceeded.compareTo(BigDecimal.ZERO) <= 0 && !Dict.GRID_DEVIATION_LATE.equals(type)) {
                continue;
            }
            if (exceeded.compareTo(BigDecimal.ZERO) <= 0) {
                exceeded = BigDecimal.ZERO;
            }
            upsertDeviation(buildDeviation(farm, gov, log, log.getTurbineId(), log.getUnitCode(), "TURBINE",
                    log.getActivePowerKw(), gov.limit, exceeded.max(BigDecimal.ZERO), type, reason));
        }

        // 全场 / 范围汇总：FULL 指令同范围取最严格目标；每条 PART 指令单独按适用机位求和，不能被其他范围指令掩盖。
        List<LimitContext> farmContexts = new ArrayList<>();
        List<GridLimitOrder> fullOrders = activeOrders.stream()
                .filter(o -> Dict.GRID_SCOPE_FULL.equals(o.getTargetScope())).toList();
        if (!fullOrders.isEmpty()) {
            fullOrders.stream().min(Comparator.comparing(GridLimitOrder::getTargetLimitKw)
                    .thenComparing(GridLimitOrder::getPriority))
                    .ifPresent(o -> farmContexts.add(new LimitContext(o, o.getTargetLimitKw(), null)));
        }
        activeOrders.stream()
                .filter(o -> !Dict.GRID_SCOPE_FULL.equals(o.getTargetScope()))
                .forEach(o -> farmContexts.add(new LimitContext(o, o.getTargetLimitKw(), selectedTurbineIds(o))));
        for (LimitContext gov : farmContexts) {
            Set<Long> covered = gov.turbineIds == null ? null : new HashSet<>(gov.turbineIds);
            BigDecimal total = logsAtTime.stream()
                    .filter(l -> covered == null || covered.contains(l.getTurbineId()))
                    .map(RunLog::getActivePowerKw).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tol = Dict.gridTolerance(gov.limit);
            BigDecimal exceeded = total.subtract(gov.limit.add(tol));
            String type = classify(logsAtTime.stream()
                    .filter(l -> covered == null || covered.contains(l.getTurbineId()))
                    .findFirst().orElse(null), gov.limit, farm, false);
            if (exceeded.compareTo(BigDecimal.ZERO) > 0) {
                upsertDeviation(buildDeviation(farm, gov, null, null, null, "FARM",
                        total, gov.limit, exceeded, type, reason));
            }
        }
    }

    @Transactional
    public GridLimitDeviation handleDeviation(Long id, GridDeviationHandleRequest request) {
        return advanceDeviation(id, "HANDLED", request == null ? null : request.getOperator(),
                request == null ? null : request.getNote(), "handle");
    }

    @Transactional
    public GridLimitDeviation reviewDeviation(Long id, GridDeviationHandleRequest request) {
        return advanceDeviation(id, "REVIEWED", request == null ? null : request.getOperator(),
                request == null ? null : request.getNote(), "review");
    }

    @Transactional
    public GridLimitDeviation releaseDeviation(Long id, GridDeviationHandleRequest request) {
        return advanceDeviation(id, "RELEASED", request == null ? null : request.getOperator(),
                request == null ? null : request.getNote(), "release");
    }

    // ================= 核心计算 =================

    private CalculatedAllocation calculate(GridLimitOrder order, List<Turbine> selected, WindFarm farm,
            Map<Long, BigDecimal> manualLimits) {
        CalculatedAllocation result = new CalculatedAllocation();
        BigDecimal runnableCapacity = selected.stream()
                .filter(t -> Dict.isGridRunnableTurbine(t.getStatus()))
                .map(Turbine::getRatedPowerKw).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        result.runnableCapacity = runnableCapacity;
        if (runnableCapacity.compareTo(BigDecimal.ZERO) <= 0) {
            result.blockers.add("适用机位当前均不是并网状态，没有可分摊额度的机位");
        }
        BigDecimal budget = order.getTargetLimitKw().min(runnableCapacity);
        Map<Long, BigDecimal> computed = new LinkedHashMap<>();
        if (Dict.GRID_ALLOC_PROPORTIONAL.equals(order.getAllocationMode())) {
            computed.putAll(proportionalLimits(selected, budget));
        } else if (Dict.GRID_ALLOC_MANUAL.equals(order.getAllocationMode())) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Turbine t : selected) {
                if (!Dict.isGridRunnableTurbine(t.getStatus())) {
                    continue;
                }
                BigDecimal v = manualLimits.get(t.getId());
                if (v == null) {
                    result.blockers.add("机位 " + t.getUnitCode() + " 尚未逐台确认上限");
                    continue;
                }
                v = v.setScale(2, RoundingMode.HALF_UP);
                if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(t.getRatedPowerKw()) > 0) {
                    result.blockers.add("机位 " + t.getUnitCode() + " 上限必须在 0 与额定功率 "
                            + t.getRatedPowerKw() + " kW 之间");
                }
                computed.put(t.getId(), v);
                sum = sum.add(v);
            }
            if (sum.compareTo(order.getTargetLimitKw()) > 0) {
                result.blockers.add("逐台确认上限合计 " + sum + " kW，超过场站目标 " + order.getTargetLimitKw() + " kW");
            }
        }
        for (Turbine t : selected) {
            boolean runnable = Dict.isGridRunnableTurbine(t.getStatus());
            GridLimitAllocation a = new GridLimitAllocation();
            a.setOrderId(order.getId());
            a.setTurbineId(t.getId());
            a.setFarmId(order.getFarmId());
            a.setUnitCode(t.getUnitCode());
            a.setRatedPowerKw(t.getRatedPowerKw());
            a.setRunnable(runnable);
            a.setTurbineStatus(t.getStatus());
            if (runnable) {
                BigDecimal limit = computed.getOrDefault(t.getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                a.setLimitKw(limit);
                a.setAllocationState("ALLOCATED");
                a.setNote(null);
                result.total = result.total.add(limit);
            } else {
                a.setLimitKw(BigDecimal.ZERO);
                a.setAllocationState("EXCLUDED");
                a.setNote("执行前状态为「" + Dict.turbineStatusName(t.getStatus()) + "」，不占用场站目标额度");
                result.excluded++;
            }
            result.rows.add(a);
        }
        if (result.total.compareTo(order.getTargetLimitKw()) > 0) {
            result.blockers.add("分摊结果合计 " + result.total + " kW 超过场站目标 " + order.getTargetLimitKw() + " kW");
        }
        return result;
    }

    private Map<Long, BigDecimal> proportionalLimits(List<Turbine> turbines, BigDecimal budget) {
        List<Turbine> runnable = turbines.stream()
                .filter(t -> Dict.isGridRunnableTurbine(t.getStatus()))
                .sorted(Comparator.comparing(Turbine::getUnitCode, Comparator.nullsLast(String::compareTo)))
                .toList();
        BigDecimal capacity = runnable.stream().map(Turbine::getRatedPowerKw).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        if (capacity.compareTo(BigDecimal.ZERO) <= 0) {
            return out;
        }
        long budgetUnits = budget.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        long allocated = 0;
        Map<Long, Long> floors = new LinkedHashMap<>();
        Map<Long, BigDecimal> fractions = new LinkedHashMap<>();
        for (Turbine t : runnable) {
            BigDecimal exact = budget.multiply(t.getRatedPowerKw()).divide(capacity, 6, RoundingMode.HALF_UP);
            long floor = Math.max(0, exact.setScale(2, RoundingMode.DOWN).movePointRight(2).longValueExact());
            floors.put(t.getId(), floor);
            fractions.put(t.getId(), exact.subtract(exact.setScale(2, RoundingMode.DOWN)));
            allocated += floor;
        }
        long remainder = Math.max(0, budgetUnits - allocated);
        List<Long> order = runnable.stream().map(Turbine::getId)
                .sorted(Comparator.comparing((Long id) -> fractions.getOrDefault(id, BigDecimal.ZERO)).reversed()
                        .thenComparing(Long::longValue))
                .toList();
        Map<Long, Long> units = new LinkedHashMap<>(floors);
        for (Long id : order) {
            if (remainder <= 0) {
                break;
            }
            units.put(id, units.get(id) + 1);
            remainder--;
        }
        for (Turbine t : runnable) {
            BigDecimal v = BigDecimal.valueOf(units.getOrDefault(t.getId(), 0L)).movePointLeft(2);
            if (v.compareTo(t.getRatedPowerKw()) > 0) {
                v = t.getRatedPowerKw();
            }
            out.put(t.getId(), v);
        }
        return out;
    }

    private String classify(RunLog log, BigDecimal limit, WindFarm farm, boolean turbineScope) {
        if (log != null && log.getRecordTime() != null && log.getReportTime() != null
                && Duration.between(log.getRecordTime(), log.getReportTime()).toMinutes() > Dict.GRID_LATE_REPORT_MINUTES) {
            return Dict.GRID_DEVIATION_LATE;
        }
        if (log != null && turbineScope) {
            List<RunLog> history = runLogRepository.findByTurbineIdOrderByRecordTimeDesc(log.getTurbineId()).stream()
                    .filter(l -> !l.getId().equals(log.getId()))
                    .filter(l -> l.getRecordTime() != null && log.getRecordTime() != null)
                    .filter(l -> {
                        long gap = Math.abs(Duration.between(l.getRecordTime(), log.getRecordTime()).toMinutes());
                        return gap <= Dict.GRID_PERSISTENT_GAP_MINUTES;
                    }).toList();
            for (RunLog near : history) {
                BigDecimal nearLimit = effectiveTurbineLimit(log.getTurbineId(), near.getRecordTime(), farm.getId());
                if (nearLimit != null && near.getActivePowerKw() != null
                        && near.getActivePowerKw().compareTo(nearLimit.add(Dict.gridTolerance(nearLimit))) > 0) {
                    return Dict.GRID_DEVIATION_PERSISTENT;
                }
            }
        }
        return Dict.GRID_DEVIATION_FLUCTUATION;
    }

    public EffectiveLimit effectiveTurbineLimitAt(Long farmId, Long turbineId, ZonedDateTime time) {
        if (farmId == null || turbineId == null || time == null) {
            return null;
        }
        return orderRepository.findByStatusOrderByPriorityAscIdAsc("EXECUTING").stream()
                .filter(o -> Objects.equals(o.getFarmId(), farmId))
                .filter(o -> !time.isBefore(o.getStartTime()) && time.isBefore(o.getEndTime()))
                .flatMap(o -> allocationRepository.findByOrderIdOrderByIdAsc(o.getId()).stream()
                        .filter(a -> Objects.equals(a.getTurbineId(), turbineId) && Boolean.TRUE.equals(a.getRunnable()))
                        .map(a -> new EffectiveLimit(o, a.getLimitKw())))
                .min(Comparator.comparing((EffectiveLimit c) -> c.limitKw)
                        .thenComparing(c -> c.order.getPriority()))
                .orElse(null);
    }

    private BigDecimal effectiveTurbineLimit(Long turbineId, ZonedDateTime time, Long farmId) {
        EffectiveLimit limit = effectiveTurbineLimitAt(farmId, turbineId, time);
        return limit == null ? null : limit.limitKw;
    }

    private GridLimitDeviation buildDeviation(WindFarm farm, LimitContext ctx, RunLog log, Long turbineId,
            String unitCode, String scope, BigDecimal actual, BigDecimal limit, BigDecimal exceeded, String type,
            String reason) {
        ZonedDateTime time = log == null ? null : log.getRecordTime();
        GridLimitDeviation d = new GridLimitDeviation();
        d.setFarmId(farm.getId());
        d.setGoverningOrderId(ctx.order.getId());
        d.setOrderNo(ctx.order.getOrderNo());
        d.setRunLogId(log == null ? null : log.getId());
        d.setTurbineId(turbineId);
        d.setUnitCode(unitCode);
        d.setScope(scope);
        d.setRecordTime(time);
        d.setRecordZone(log == null ? farm.getTimeZone() : log.getRecordZone());
        d.setReportTime(log == null ? null : log.getReportTime());
        d.setReportZone(log == null ? null : log.getReportZone());
        d.setActualKw(actual);
        d.setLimitKw(limit);
        d.setExceededKw(exceeded);
        d.setDeviationType(type);
        d.setStatus("OPEN");
        d.setRevisionNo(1);
        d.setLatestFlag(true);
        d.setRevisionReason(reason);
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    private void upsertDeviation(GridLimitDeviation fresh) {
        Optional<GridLimitDeviation> oldOptional;
        if ("FARM".equals(fresh.getScope())) {
            oldOptional = deviationRepository
                    .findFirstByFarmIdAndScopeAndRecordTimeAndLatestFlagTrueOrderByRevisionNoDesc(
                            fresh.getFarmId(), "FARM", fresh.getRecordTime());
        } else {
            oldOptional = deviationRepository
                    .findFirstByFarmIdAndScopeAndTurbineIdAndRecordTimeAndLatestFlagTrueOrderByRevisionNoDesc(
                            fresh.getFarmId(), "TURBINE", fresh.getTurbineId(), fresh.getRecordTime());
        }
        if (oldOptional.isPresent() && sameConclusion(oldOptional.get(), fresh)) {
            return;
        }
        oldOptional.ifPresent(old -> {
            old.setLatestFlag(false);
            old.setStatus("SUPERSEDED");
            old.setUpdatedAt(ZonedDateTime.now());
            deviationRepository.save(old);
            fresh.setRevisionNo((old.getRevisionNo() == null ? 1 : old.getRevisionNo()) + 1);
            fresh.setSupersedesDeviationId(old.getId());
            fresh.setOriginalDeviationType(old.getOriginalDeviationType() == null
                    ? old.getDeviationType() : old.getOriginalDeviationType());
            fresh.setOriginalActualKw(old.getOriginalActualKw() == null ? old.getActualKw() : old.getOriginalActualKw());
            fresh.setOriginalLimitKw(old.getOriginalLimitKw() == null ? old.getLimitKw() : old.getOriginalLimitKw());
        });
        deviationRepository.save(fresh);
    }

    private boolean sameConclusion(GridLimitDeviation a, GridLimitDeviation b) {
        return Objects.equals(a.getGoverningOrderId(), b.getGoverningOrderId())
                && Objects.equals(a.getDeviationType(), b.getDeviationType())
                && sameNumber(a.getActualKw(), b.getActualKw())
                && sameNumber(a.getLimitKw(), b.getLimitKw());
    }

    private boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null && b == null || a != null && b != null && a.compareTo(b) == 0;
    }

    private GridLimitDeviation advanceDeviation(Long id, String toStatus, String operator, String note, String action) {
        GridLimitDeviation d = deviationRepository.findById(id)
                .orElseThrow(() -> new BizException("偏差记录不存在：" + id));
        if (!Boolean.TRUE.equals(d.getLatestFlag())) {
            throw new BizException("原始判断已被补录 / 修正后的新版本替代，不能再操作；请处理最新版本");
        }
        if (!StringUtils.hasText(note)) {
            throw new BizException("请填写处置、复核或解除说明");
        }
        String from = d.getStatus();
        boolean valid = switch (toStatus) {
            case "HANDLED" -> "OPEN".equals(from);
            case "REVIEWED" -> "HANDLED".equals(from);
            case "RELEASED" -> "REVIEWED".equals(from);
            default -> false;
        };
        if (!valid) {
            throw new BizException("偏差处置顺序：待处置 → 已处置 → 已复核 → 已解除");
        }
        WindFarm farm = farmRepository.findById(d.getFarmId()).orElseThrow();
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        d.setStatus(toStatus);
        d.setUpdatedAt(now);
        if ("handle".equals(action)) {
            d.setHandler(operator); d.setHandleNote(note); d.setHandledAt(now); d.setHandledZone(zone(farm).getId());
        } else if ("review".equals(action)) {
            d.setReviewer(operator); d.setReviewNote(note); d.setReviewedAt(now); d.setReviewedZone(zone(farm).getId());
        } else {
            d.setReleaser(operator); d.setReleaseNote(note); d.setReleasedAt(now); d.setReleasedZone(zone(farm).getId());
        }
        deviationRepository.save(d);
        if (d.getGoverningOrderId() != null) {
            GridLimitOrder order = orderRepository.findById(d.getGoverningOrderId()).orElse(null);
            if (order != null) {
                writeLog(order, "DEVIATION_" + action.toUpperCase(), order.getStatus(), order.getStatus(),
                        operator, Dict.gridDeviationTypeName(d.getDeviationType()) + "（" + d.getScope()
                                + "）" + note);
            }
        }
        return d;
    }

    // ================= 校验 / 组装 =================

    private void applyFields(GridLimitOrder order, GridLimitOrderRequest request, WindFarm farm) {
        if (request.getTargetLimitKw() == null || request.getTargetLimitKw().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("目标上限必须大于 0 kW");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new BizException("请填写开始时间和结束时间");
        }
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BizException("结束时间必须晚于开始时间；时间按绝对时刻判断，跨风场本地日期不会形成空档");
        }
        if (!Dict.isValidGridScope(request.getTargetScope())) {
            throw new BizException("适用范围非法");
        }
        if (!Dict.isValidGridAllocationMode(request.getAllocationMode())) {
            throw new BizException("分摊方式非法");
        }
        if (request.getOverlapMode() != null && !Dict.isValidGridOverlapMode(request.getOverlapMode())) {
            throw new BizException("重叠处理方式非法");
        }
        order.setTargetLimitKw(request.getTargetLimitKw().setScale(2, RoundingMode.HALF_UP));
        order.setTargetScope(request.getTargetScope());
        order.setAllocationMode(request.getAllocationMode());
        if (request.getPriority() != null) {
            if (request.getPriority() < 1 || request.getPriority() > 999) {
                throw new BizException("优先级范围为 1~999，数值越小越优先");
            }
            order.setPriority(request.getPriority());
        }
        if (request.getOverlapMode() != null) {
            order.setOverlapMode(request.getOverlapMode());
        }
        order.setStartTime(request.getStartTime());
        order.setStartZone(zone(farm).getId());
        order.setEndTime(request.getEndTime());
        order.setEndZone(zone(farm).getId());
        order.setReleaseCondition(request.getReleaseCondition());
        order.setDutyOperator(request.getDutyOperator());
        order.setNote(request.getNote());
    }

    private void replaceTurbineRefs(GridLimitOrder order, GridLimitOrderRequest request, WindFarm farm) {
        List<Long> ids;
        if (Dict.GRID_SCOPE_FULL.equals(request.getTargetScope())) {
            ids = turbineRepository.findByFarmIdOrderByUnitCodeAsc(farm.getId()).stream()
                    .map(Turbine::getId).toList();
        } else {
            ids = Optional.ofNullable(request.getTurbineIds()).orElse(List.of()).stream().distinct().toList();
            if (ids.isEmpty()) {
                throw new BizException("指定机位范围至少选择一个机位");
            }
        }        List<Turbine> turbines = turbineRepository.findAllById(ids);
        if (turbines.size() != ids.size()) {
            throw new BizException("存在无效机位，请重新选择适用机位");
        }
        for (Turbine t : turbines) {
            if (!Objects.equals(t.getFarmId(), farm.getId())) {
                throw new BizException("机位 " + t.getUnitCode() + " 不属于当前风场");
            }
        }
        turbineRefRepository.deleteAll(turbineRefRepository.findByOrderIdOrderByIdAsc(order.getId()));
        List<GridLimitTurbine> refs = turbines.stream().sorted(Comparator.comparing(Turbine::getUnitCode)).map(t -> {
            GridLimitTurbine ref = new GridLimitTurbine();
            ref.setOrderId(order.getId());
            ref.setTurbineId(t.getId());
            ref.setUnitCode(t.getUnitCode());
            ref.setSelected(true);
            ref.setAllocationState(Dict.isGridRunnableTurbine(t.getStatus()) ? "SELECTED" : "SELECTED_UNAVAILABLE");
            return ref;
        }).toList();
        turbineRefRepository.saveAll(refs);
    }

    private void validateOverlapOnStart(GridLimitOrder order, List<GridLimitAllocation> newAllocations,
            List<Turbine> selected) {
        List<GridLimitOrder> others = orderRepository.findAll().stream()
                .filter(o -> Objects.equals(o.getFarmId(), order.getFarmId()) && !Objects.equals(o.getId(), order.getId()))
                .filter(o -> !"ENDED".equals(o.getStatus()) && !"CANCELLED".equals(o.getStatus()))
                .filter(o -> overlaps(o, order)).toList();
        if (others.isEmpty()) {
            return;
        }
        if (Dict.GRID_OVERLAP_COEXIST.equals(order.getOverlapMode())) {
            return;
        }
        List<GridLimitOrder> activeOthers = others.stream()
                .filter(o -> "EXECUTING".equals(o.getStatus()) || "PAUSED".equals(o.getStatus())).toList();
        Set<Long> newTurbineIds = selected.stream().map(Turbine::getId).collect(Collectors.toSet());
        Map<Long, BigDecimal> newLimit = newAllocations.stream()
                .collect(Collectors.toMap(GridLimitAllocation::getTurbineId, GridLimitAllocation::getLimitKw, (a, b) -> a));
        for (GridLimitOrder old : others) {
            if (!activeOthers.contains(old)) {
                throw new BizException("显式替代指令 " + old.getOrderNo()
                        + " 失败：新令与待执行旧令重叠，请改为并行取严，或先取消 / 修改待执行旧令");
            }
            Set<Long> oldTurbineIds = selectedTurbineIds(old);
            if (!newTurbineIds.containsAll(oldTurbineIds)) {
                throw new BizException("显式替代指令 " + old.getOrderNo() + " 失败：新指令未覆盖其全部适用机位，会产生局部空档");
            }
            for (GridLimitAllocation oldAlloc : allocationRepository.findByOrderIdOrderByIdAsc(old.getId())) {
                if (!Boolean.TRUE.equals(oldAlloc.getRunnable())) {
                    continue;
                }
                BigDecimal nl = newLimit.get(oldAlloc.getTurbineId());
                if (nl == null || nl.compareTo(oldAlloc.getLimitKw()) > 0) {
                    throw new BizException("显式替代指令 " + old.getOrderNo() + " 失败：机位 "
                            + oldAlloc.getUnitCode() + " 新上限 " + nl + " kW 宽于旧上限 "
                            + oldAlloc.getLimitKw() + " kW，宽松新令不得解除旧限制");
                }
            }
            if (order.getEndTime().isBefore(old.getEndTime())) {
                throw new BizException("显式替代指令 " + old.getOrderNo() + " 的结束时间早于旧令，替代结束后会产生空档；请改用并行取严");
            }
        }
        ZonedDateTime now = ZonedDateTime.now();
        for (GridLimitOrder old : activeOthers) {
            String oldStatus = old.getStatus();
            old.setStatus("CANCELLED");
            old.setCancelledAt(now);
            old.setCancelledZone(Dict.zoneOf(farmRepository.findById(old.getFarmId()).orElseThrow().getTimeZone()).getId());
            old.setReleaseResult("已被更严格且覆盖范围完整的指令 " + order.getOrderNo() + " 显式替代");
            old.setReleaseOperator(order.getDutyOperator());
            old.setUpdatedAt(now);
            orderRepository.save(old);
            writeLog(old, "SUPERSEDED", oldStatus, "CANCELLED", order.getDutyOperator(),
                    "被指令 " + order.getOrderNo() + " 显式替代；新令逐机位限值不宽于本令且覆盖至本令结束之后");
        }
    }

    private boolean overlaps(GridLimitOrder a, GridLimitOrder b) {
        return a.getStartTime().isBefore(b.getEndTime()) && b.getStartTime().isBefore(a.getEndTime());
    }

    private GridLimitOrder changeStatus(Long id, String to, String action, GridStatusRequest request) {
        GridLimitOrder loaded = get(id);
        WindFarm farm = lockFarm(loaded.getFarmId());
        GridLimitOrder order = orderRepository.lockById(id).orElseThrow();
        String from = order.getStatus();
        boolean valid = "PAUSE".equals(action) ? "EXECUTING".equals(from)
                : "RESUME".equals(action) && "PAUSED".equals(from);
        if (!valid) {
            throw new BizException("当前状态不允许该操作：" + Dict.gridOrderStatusName(from));
        }
        ZonedDateTime now = ZonedDateTime.now(zone(farm));
        order.setStatus(to);
        order.setUpdatedAt(now);
        if ("PAUSED".equals(to)) {
            order.setPausedAt(now); order.setPausedZone(zone(farm).getId());
        } else {
            order.setResumedAt(now); order.setResumedZone(zone(farm).getId());
        }
        orderRepository.save(order);
        writeLog(order, action, from, to, request == null ? null : request.getOperator(),
                request == null ? null : request.getNote());
        return order;
    }

    private WindFarm lockFarm(Long farmId) {
        if (farmId == null) {
            throw new BizException("请选择风场");
        }
        return farmRepository.lockById(farmId).orElseThrow(() -> new BizException("风场不存在：" + farmId));
    }

    private List<Turbine> selectedTurbines(GridLimitOrder order) {
        List<Long> ids = selectedTurbineIds(order);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Turbine> map = turbineRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Turbine::getId, t -> t));
        return ids.stream().map(map::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Turbine::getUnitCode, Comparator.nullsLast(String::compareTo))).toList();
    }

    private List<Long> selectedTurbineIds(GridLimitOrder order) {
        return turbineRefRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .filter(r -> Boolean.TRUE.equals(r.getSelected())).map(GridLimitTurbine::getTurbineId).toList();
    }

    private void writeLog(GridLimitOrder order, String action, String from, String to, String operator, String detail) {
        ZoneId z = zone(farmRepository.findById(order.getFarmId()).map(WindFarm::getTimeZone).orElse(null));
        GridLimitLog l = new GridLimitLog();
        l.setOrderId(order.getId());
        l.setAction(action);
        l.setFromStatus(from);
        l.setToStatus(to);
        l.setOperator(operator);
        l.setDetail(detail);
        l.setCreatedAt(ZonedDateTime.now(z));
        l.setCreatedZone(z.getId());
        logRepository.save(l);
    }

    private GridLimitOrderVO toVO(GridLimitOrder order) {
        WindFarm farm = farmRepository.findById(order.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        GridLimitOrderVO vo = new GridLimitOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setFarmId(order.getFarmId());
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setTimeZone(zone);
        vo.setTargetLimitKw(order.getTargetLimitKw());
        vo.setTargetScope(order.getTargetScope());
        vo.setTargetScopeName(Dict.gridScopeName(order.getTargetScope()));
        vo.setAllocationMode(order.getAllocationMode());
        vo.setAllocationModeName(Dict.gridAllocationModeName(order.getAllocationMode()));
        vo.setPriority(order.getPriority());
        vo.setOverlapMode(order.getOverlapMode());
        vo.setOverlapModeName(Dict.gridOverlapModeName(order.getOverlapMode()));
        vo.setStartTime(TimeText.iso(order.getStartTime()));
        vo.setStartTz(order.getStartZone());
        vo.setStartLocal(TimeText.local(order.getStartTime(), zone));
        vo.setEndTime(TimeText.iso(order.getEndTime()));
        vo.setEndTz(order.getEndZone());
        vo.setEndLocal(TimeText.local(order.getEndTime(), zone));
        vo.setReleaseCondition(order.getReleaseCondition());
        vo.setStatus(order.getStatus());
        vo.setStatusName(Dict.gridOrderStatusName(order.getStatus()));
        vo.setStatusColor(Dict.gridOrderStatusColor(order.getStatus()));
        vo.setDutyOperator(order.getDutyOperator());
        vo.setConfirmedBy(order.getConfirmedBy());
        vo.setActivatedLocal(TimeText.local(order.getActivatedAt(), zone));
        vo.setPausedLocal(TimeText.local(order.getPausedAt(), zone));
        vo.setResumedLocal(TimeText.local(order.getResumedAt(), zone));
        vo.setEndedLocal(TimeText.local(order.getEndedAt(), zone));
        vo.setCancelledLocal(TimeText.local(order.getCancelledAt(), zone));
        vo.setReleaseResult(order.getReleaseResult());
        vo.setReleaseOperator(order.getReleaseOperator());
        vo.setSupersedesOrderId(order.getSupersedesOrderId());
        if (order.getSupersedesOrderId() != null) {
            vo.setSupersedesOrderNo(orderRepository.findById(order.getSupersedesOrderId())
                    .map(GridLimitOrder::getOrderNo).orElse(null));
        }
        vo.setNote(order.getNote());
        List<Long> ids = selectedTurbineIds(order);
        vo.setTurbineIds(ids);
        List<Turbine> turbines = selectedTurbines(order);
        vo.setTurbineText(turbines.stream().map(Turbine::getUnitCode).collect(Collectors.joining(" / ")));
        List<GridLimitAllocation> allocations = allocationRepository.findByOrderIdOrderByIdAsc(order.getId());
        Map<Long, Turbine> turbineMap = turbines.stream().collect(Collectors.toMap(Turbine::getId, t -> t, (a, b) -> a));
        BigDecimal total = BigDecimal.ZERO;
        int runnable = 0;
        int excluded = 0;
        for (GridLimitAllocation a : allocations) {
            if (Boolean.TRUE.equals(a.getRunnable())) {
                runnable++;
                total = total.add(a.getLimitKw());
            } else {
                excluded++;
            }
            vo.getAllocations().add(toAllocationVO(a, turbineMap.get(a.getTurbineId())));
        }
        vo.setRunnableCount(runnable);
        vo.setExcludedCount(excluded);
        vo.setAllocatedTotalKw(total);
        vo.setRunnableCapacityKw(turbines.stream().filter(t -> Dict.isGridRunnableTurbine(t.getStatus()))
                .map(Turbine::getRatedPowerKw).reduce(BigDecimal.ZERO, BigDecimal::add));
        vo.setAllocationWithinTarget(total.compareTo(order.getTargetLimitKw()) <= 0);
        if (allocations.isEmpty() && "PLANNED".equals(order.getStatus())) {
            vo.getBlockers().add("尚未确认执行前机位分摊");
        }
        if (total.compareTo(order.getTargetLimitKw()) > 0) {
            vo.getBlockers().add("分摊合计超过场站目标");
        }
        vo.setLogs(logRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(l -> toLogVO(l, zone)).collect(Collectors.toList()));
        vo.setOverlaps(buildOverlaps(order));
        return vo;
    }

    private List<GridLimitOverlapVO> buildOverlaps(GridLimitOrder order) {
        List<GridLimitOverlapVO> out = new ArrayList<>();
        List<GridLimitOrder> others = orderRepository.findByFarmIdOrderByStartTimeDescIdDesc(order.getFarmId()).stream()
                .filter(o -> !Objects.equals(o.getId(), order.getId()))
                .filter(o -> !"ENDED".equals(o.getStatus()) && !"CANCELLED".equals(o.getStatus()))
                .filter(o -> overlaps(o, order)).toList();
        Set<Long> a = new HashSet<>(selectedTurbineIds(order));
        for (GridLimitOrder o : others) {
            Set<Long> b = new HashSet<>(selectedTurbineIds(o));
            GridLimitOverlapVO vo = new GridLimitOverlapVO();
            vo.setOrderId(o.getId()); vo.setOrderNo(o.getOrderNo());
            vo.setStatus(o.getStatus()); vo.setStatusName(Dict.gridOrderStatusName(o.getStatus()));
            vo.setPriority(o.getPriority()); vo.setOtherTargetKw(o.getTargetLimitKw());
            boolean covers = a.containsAll(b);
            boolean covered = b.containsAll(a);
            vo.setCoverage(covers && covered ? "范围相同" : covers ? "新令覆盖旧令" : covered ? "旧令覆盖新令" : "部分重叠");
            vo.setRelation(Dict.GRID_OVERLAP_COEXIST.equals(order.getOverlapMode()) ? "COEXIST" : "SUPERSEDE");
            vo.setRelationName(Dict.gridOverlapModeName(vo.getRelation()));
            vo.setEffect("执行时重叠机位取更严格上限；较宽松指令不会解除较严格旧令");
            out.add(vo);
        }
        return out;
    }

    private GridLimitAllocationVO toAllocationVO(GridLimitAllocation a, Turbine turbine) {
        GridLimitAllocationVO vo = new GridLimitAllocationVO();
        vo.setId(a.getId()); vo.setOrderId(a.getOrderId()); vo.setTurbineId(a.getTurbineId());
        vo.setUnitCode(a.getUnitCode());
        if (turbine != null) {
            vo.setUnitName(turbine.getUnitName()); vo.setModel(turbine.getModel());
            vo.setLatestPowerKw(turbine.getLastPowerKw());
        }
        vo.setRatedPowerKw(a.getRatedPowerKw()); vo.setLimitKw(a.getLimitKw());
        vo.setRunnable(a.getRunnable()); vo.setTurbineStatus(a.getTurbineStatus());
        vo.setTurbineStatusName(Dict.turbineStatusName(a.getTurbineStatus()));
        vo.setAllocationState(a.getAllocationState());
        vo.setAllocationStateName("EXCLUDED".equals(a.getAllocationState()) ? "不占额度" : "已分摊");
        vo.setConfirmOperator(a.getConfirmOperator());
        vo.setConfirmedLocal(TimeText.local(a.getConfirmedAt(), a.getConfirmedZone()));
        vo.setNote(a.getNote());
        vo.setLatestExceeded(Boolean.TRUE.equals(a.getRunnable()) && turbine != null && turbine.getLastPowerKw() != null
                && a.getLimitKw() != null
                && turbine.getLastPowerKw().compareTo(a.getLimitKw().add(Dict.gridTolerance(a.getLimitKw()))) > 0);
        return vo;
    }

    private GridLimitLogVO toLogVO(GridLimitLog l, String zone) {
        GridLimitLogVO vo = new GridLimitLogVO();
        vo.setId(l.getId()); vo.setOrderId(l.getOrderId()); vo.setAction(l.getAction());
        vo.setActionName(actionName(l.getAction()));
        vo.setFromStatus(l.getFromStatus()); vo.setFromStatusName(Dict.gridOrderStatusName(l.getFromStatus()));
        vo.setToStatus(l.getToStatus()); vo.setToStatusName(Dict.gridOrderStatusName(l.getToStatus()));
        vo.setOperator(l.getOperator()); vo.setDetail(l.getDetail());
        vo.setCreatedAt(TimeText.iso(l.getCreatedAt())); vo.setCreatedTz(l.getCreatedZone());
        vo.setCreatedLocal(TimeText.local(l.getCreatedAt(), TimeText.pickZone(l.getCreatedZone(), zone)));
        return vo;
    }

    private String actionName(String action) {
        return switch (action) {
            case "CREATE" -> "创建";
            case "UPDATE" -> "修改";
            case "ALLOCATE" -> "确认分摊";
            case "REALLOCATE" -> "刷新分摊";
            case "START" -> "开始执行";
            case "PAUSE" -> "暂停";
            case "RESUME" -> "恢复";
            case "END" -> "结束";
            case "CANCEL" -> "取消";
            case "SUPERSEDED" -> "被替代";
            case "DEVIATION_HANDLE" -> "偏差处置";
            case "DEVIATION_REVIEW" -> "偏差复核";
            case "DEVIATION_RELEASE" -> "偏差解除";
            default -> action;
        };
    }

    private GridLimitDeviationVO toDeviationVO(GridLimitDeviation d) {
        GridLimitDeviationVO vo = new GridLimitDeviationVO();
        WindFarm farm = farmRepository.findById(d.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        vo.setId(d.getId()); vo.setFarmId(d.getFarmId()); vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setGoverningOrderId(d.getGoverningOrderId()); vo.setOrderNo(d.getOrderNo());
        vo.setRunLogId(d.getRunLogId()); vo.setTurbineId(d.getTurbineId()); vo.setUnitCode(d.getUnitCode());
        vo.setScope(d.getScope()); vo.setScopeName("FARM".equals(d.getScope()) ? "全场" : "机位");
        vo.setRecordTime(TimeText.iso(d.getRecordTime())); vo.setRecordTz(d.getRecordZone());
        vo.setRecordLocal(TimeText.local(d.getRecordTime(), TimeText.pickZone(d.getRecordZone(), zone)));
        vo.setReportTime(TimeText.iso(d.getReportTime())); vo.setReportTz(d.getReportZone());
        vo.setReportLocal(TimeText.local(d.getReportTime(), TimeText.pickZone(d.getReportZone(), zone)));
        vo.setActualKw(d.getActualKw()); vo.setLimitKw(d.getLimitKw()); vo.setExceededKw(d.getExceededKw());
        vo.setDeviationType(d.getDeviationType()); vo.setDeviationTypeName(Dict.gridDeviationTypeName(d.getDeviationType()));
        vo.setStatus(d.getStatus()); vo.setStatusName(Dict.gridDeviationStatusName(d.getStatus()));
        vo.setRevisionNo(d.getRevisionNo()); vo.setLatestFlag(d.getLatestFlag());
        vo.setSupersedesDeviationId(d.getSupersedesDeviationId());
        vo.setRevisionReason(d.getRevisionReason());
        vo.setOriginalDeviationType(d.getOriginalDeviationType());
        vo.setOriginalDeviationTypeName(Dict.gridDeviationTypeName(d.getOriginalDeviationType()));
        vo.setOriginalActualKw(d.getOriginalActualKw()); vo.setOriginalLimitKw(d.getOriginalLimitKw());
        vo.setHandleNote(d.getHandleNote()); vo.setReviewNote(d.getReviewNote()); vo.setReleaseNote(d.getReleaseNote());
        vo.setHandler(d.getHandler()); vo.setReviewer(d.getReviewer()); vo.setReleaser(d.getReleaser());
        vo.setHandledLocal(TimeText.local(d.getHandledAt(), TimeText.pickZone(d.getHandledZone(), zone)));
        vo.setReviewedLocal(TimeText.local(d.getReviewedAt(), TimeText.pickZone(d.getReviewedZone(), zone)));
        vo.setReleasedLocal(TimeText.local(d.getReleasedAt(), TimeText.pickZone(d.getReleasedZone(), zone)));
        vo.setCreatedAt(TimeText.iso(d.getCreatedAt()));
        return vo;
    }

    private ZoneId zone(WindFarm farm) {
        return Dict.zoneOf(farm == null ? null : farm.getTimeZone());
    }

    private ZoneId zone(String zoneText) {
        return Dict.zoneOf(zoneText);
    }

    private String generateOrderNo(ZonedDateTime now) {
        return "GL-" + now.getYear() + String.format("%02d%02d-%04d",
                now.getMonthValue(), now.getDayOfMonth(),
                Math.floorMod((int) (now.toEpochSecond() * 1000L + System.nanoTime() % 1_000_000L), 10000));
    }

    private static class CalculatedAllocation {
        private BigDecimal runnableCapacity = BigDecimal.ZERO;
        private BigDecimal total = BigDecimal.ZERO;
        private int excluded;
        private final List<GridLimitAllocation> rows = new ArrayList<>();
        private final List<String> blockers = new ArrayList<>();
    }

    public record EffectiveLimit(GridLimitOrder order, BigDecimal limitKw) {
    }

    private record LimitContext(GridLimitOrder order, BigDecimal limit, Set<Long> turbineIds) {
        LimitContext(GridLimitOrder order, BigDecimal limit) {
            this(order, limit, null);
        }
    }
}
