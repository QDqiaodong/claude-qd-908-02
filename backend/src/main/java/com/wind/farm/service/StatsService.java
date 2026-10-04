package com.wind.farm.service;

import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.RunLog;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.FarmEnergyDTO;
import com.wind.farm.dto.PartCategoryDTO;
import com.wind.farm.dto.PeriodEnergyDTO;
import com.wind.farm.dto.TurbineEnergyDTO;
import com.wind.farm.dto.WorkOrderStatusDTO;
import com.wind.farm.repository.PartReplacementRepository;
import com.wind.farm.repository.RunLogRepository;
import com.wind.farm.repository.SafetyTicketRepository;
import com.wind.farm.repository.SparePartRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 发电统计：日 / 月发电量、等效利用小时，按风场 / 机位两种口径聚合。
 *
 * <p>统计全部落到 run_log 的真实种子数据上：先把日志按「机位 + 场站本地日期」去重
 * （同一天多条读数取当日累计发电量的最大值），再按日 / 月 / 风场 / 机位汇总。
 * 场站本地日期用记录自己的 record_time_tz（取不到再退回 wind_farm.time_zone）换算，
 * 这也是多风场跨时区归一化的意义所在。
 */
@Service
public class StatsService {

    private final WindFarmRepository farmRepository;
    private final TurbineRepository turbineRepository;
    private final RunLogRepository runLogRepository;
    private final WorkOrderRepository orderRepository;
    private final SparePartRepository partRepository;
    private final PartReplacementRepository replacementRepository;
    private final SafetyTicketRepository ticketRepository;

    public StatsService(WindFarmRepository farmRepository, TurbineRepository turbineRepository,
            RunLogRepository runLogRepository, WorkOrderRepository orderRepository,
            SparePartRepository partRepository, PartReplacementRepository replacementRepository,
            SafetyTicketRepository ticketRepository) {
        this.farmRepository = farmRepository;
        this.turbineRepository = turbineRepository;
        this.runLogRepository = runLogRepository;
        this.orderRepository = orderRepository;
        this.partRepository = partRepository;
        this.replacementRepository = replacementRepository;
        this.ticketRepository = ticketRepository;
    }

    // ---------------- 概览 ----------------

    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<WindFarm> farms = farmRepository.findAllByOrderByFarmCodeAsc();
        List<Turbine> turbines = turbineRepository.findAllByOrderByFarmIdAscUnitCodeAsc();
        BigDecimal today = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Turbine turbine : turbines) {
            today = today.add(nvl(turbine.getTodayEnergyKwh()));
            total = total.add(nvl(turbine.getTotalEnergyKwh()));
        }
        data.put("farmCount", farms.size());
        data.put("turbineCount", turbines.size());
        data.put("onlineCount", turbineRepository.countByStatus("ONLINE"));
        data.put("maintainCount", turbineRepository.countByStatus("MAINTAIN"));
        data.put("faultCount", turbineRepository.countByStatus("FAULT"));
        data.put("stoppedCount", turbineRepository.countByStatus("STOPPED"));
        data.put("runLogCount", runLogRepository.count());
        data.put("workOrderCount", orderRepository.count());
        data.put("workingOrderCount", orderRepository.countByStatus("WORKING"));
        data.put("sparePartCount", partRepository.count());
        data.put("replacementCount", replacementRepository.count());
        data.put("safetyTicketCount", ticketRepository.count());
        data.put("activeTicketCount", ticketRepository.count() - ticketRepository.countByStatus("RESTORED"));
        data.put("todayEnergyKwh", today);
        data.put("totalEnergyKwh", total);
        return data;
    }

    // ---------------- 日 / 月发电量 ----------------

    public List<PeriodEnergyDTO> daily(Long farmId) {
        return period(farmId, false);
    }

    public List<PeriodEnergyDTO> monthly(Long farmId) {
        return period(farmId, true);
    }

    private List<PeriodEnergyDTO> period(Long farmId, boolean byMonth) {
        Map<Long, Turbine> turbineMap = turbineMap();
        Map<Long, WindFarm> farmMap = farmMap();
        List<RunLog> logs = logs(farmId);

        // 机位 + 周期 → 当日累计发电量最大值（同一天多条读数取最大的那条）
        Map<String, BigDecimal> energyByUnit = new HashMap<>();
        // 周期 → 汇总
        Map<String, Agg> aggByPeriod = new TreeMap<>();
        Map<String, Set<Long>> turbinesByPeriod = new HashMap<>();

        for (RunLog log : logs) {
            Turbine turbine = turbineMap.get(log.getTurbineId());
            WindFarm farm = farmMap.get(log.getFarmId());
            String zone = TimeText.pickZone(log.getRecordZone(), farm == null ? null : farm.getTimeZone());
            String period = byMonth ? TimeText.localMonth(log.getRecordTime(), zone)
                    : TimeText.localDate(log.getRecordTime(), zone);
            if (period == null) {
                continue;
            }
            String unitKey = log.getTurbineId() + "|" + period;
            BigDecimal energy = nvl(log.getDailyEnergyKwh());
            BigDecimal prev = energyByUnit.get(unitKey);
            if (prev == null || energy.compareTo(prev) > 0) {
                energyByUnit.put(unitKey, energy);
            }
            Agg agg = aggByPeriod.computeIfAbsent(period, key -> new Agg());
            agg.powerSum = agg.powerSum.add(nvl(log.getActivePowerKw()));
            agg.logCount += 1;
            turbinesByPeriod.computeIfAbsent(period, key -> new HashSet<>()).add(log.getTurbineId());
        }

        // 用去重后的「机位 + 周期」发电量重新汇总
        Map<String, Agg> finalAgg = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : energyByUnit.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String period = parts[1];
            Agg agg = finalAgg.computeIfAbsent(period, key -> new Agg());
            agg.energy = agg.energy.add(entry.getValue());
        }
        for (Map.Entry<String, Agg> entry : aggByPeriod.entrySet()) {
            Agg target = finalAgg.get(entry.getKey());
            if (target != null) {
                target.powerSum = entry.getValue().powerSum;
                target.logCount = entry.getValue().logCount;
            }
        }

        List<PeriodEnergyDTO> result = new ArrayList<>();
        List<String> periods = new ArrayList<>(finalAgg.keySet());
        periods.sort(Comparator.reverseOrder());
        for (String period : periods) {
            Agg agg = finalAgg.get(period);
            BigDecimal rated = BigDecimal.ZERO;
            Set<Long> ids = turbinesByPeriod.get(period);
            if (ids != null) {
                for (Long id : ids) {
                    Turbine turbine = turbineMap.get(id);
                    if (turbine != null) {
                        rated = rated.add(nvl(turbine.getRatedPowerKw()));
                    }
                }
            }
            result.add(new PeriodEnergyDTO(
                    period,
                    byMonth ? period + " 月" : period,
                    scale(agg.energy),
                    hours(agg.energy, rated),
                    agg.logCount == 0 ? BigDecimal.ZERO : scale(agg.powerSum.divide(
                            BigDecimal.valueOf(agg.logCount), 2, RoundingMode.HALF_UP)),
                    (long) agg.logCount,
                    ids == null ? 0 : ids.size()));
        }
        return result;
    }

    // ---------------- 按风场 / 按机位 ----------------

    public List<FarmEnergyDTO> byFarm() {
        Map<Long, WindFarm> farmMap = farmMap();
        List<Turbine> turbines = turbineRepository.findAllByOrderByFarmIdAscUnitCodeAsc();
        Map<Long, Turbine> turbineMap = turbineMap();
        List<RunLog> logs = runLogRepository.findAllByOrderByRecordTimeDesc();

        Map<Long, Map<String, BigDecimal>> energyByFarmDay = new HashMap<>();
        Map<Long, Map<String, BigDecimal>> energyByFarmMonth = new HashMap<>();
        Map<Long, Long> logCountByFarm = new HashMap<>();
        // 机位 + 天 → 当日累计发电量（同一天多条读数取最大），按风场分组
        Map<Long, Map<String, BigDecimal>> unitDay = new HashMap<>();

        for (RunLog log : logs) {
            WindFarm farm = farmMap.get(log.getFarmId());
            String zone = TimeText.pickZone(log.getRecordZone(), farm == null ? null : farm.getTimeZone());
            String day = TimeText.localDate(log.getRecordTime(), zone);
            String month = TimeText.localMonth(log.getRecordTime(), zone);
            if (day == null) {
                continue;
            }
            String unitKey = log.getTurbineId() + "|" + day;
            BigDecimal energy = nvl(log.getDailyEnergyKwh());
            // 同一机位同一天取当日累计发电量最大值
            Map<String, BigDecimal> unitDayMap = unitDay.computeIfAbsent(log.getFarmId(), k -> new HashMap<>());
            BigDecimal prev = unitDayMap.get(unitKey);
            if (prev == null || energy.compareTo(prev) > 0) {
                unitDayMap.put(unitKey, energy);
            }
            logCountByFarm.merge(log.getFarmId(), 1L, Long::sum);
            energyByFarmMonth.computeIfAbsent(log.getFarmId(), k -> new HashMap<>())
                    .merge(month, energy, BigDecimal::add);
        }

        // 按「机位 + 天」去重后的结果再汇总到 天
        for (Map.Entry<Long, Map<String, BigDecimal>> entry : unitDay.entrySet()) {
            Long farmId = entry.getKey();
            for (Map.Entry<String, BigDecimal> unitEntry : entry.getValue().entrySet()) {
                String day = unitEntry.getKey().split("\\|")[1];
                energyByFarmDay.computeIfAbsent(farmId, k -> new HashMap<>())
                        .merge(day, unitEntry.getValue(), BigDecimal::add);
            }
        }

        List<FarmEnergyDTO> result = new ArrayList<>();
        for (WindFarm farm : farmMap.values()) {
            int turbineCount = 0;
            int onlineCount = 0;
            BigDecimal rated = BigDecimal.ZERO;
            for (Turbine turbine : turbines) {
                if (farm.getId().equals(turbine.getFarmId())) {
                    turbineCount += 1;
                    rated = rated.add(nvl(turbine.getRatedPowerKw()));
                    if ("ONLINE".equals(turbine.getStatus())) {
                        onlineCount += 1;
                    }
                }
            }
            Map<String, BigDecimal> dayMap = energyByFarmDay.get(farm.getId());
            Map<String, BigDecimal> monthMap = energyByFarmMonth.get(farm.getId());
            BigDecimal daily = dayMap == null || dayMap.isEmpty()
                    ? BigDecimal.ZERO
                    : dayMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(dayMap.size()), 2, RoundingMode.HALF_UP);
            BigDecimal monthly = monthMap == null || monthMap.isEmpty()
                    ? BigDecimal.ZERO
                    : monthMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalEnergy = dayMap == null
                    ? BigDecimal.ZERO
                    : dayMap.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            result.add(new FarmEnergyDTO(
                    farm.getId(),
                    farm.getFarmCode(),
                    farm.getFarmName(),
                    farm.getTimeZone(),
                    Dict.timeZoneName(farm.getTimeZone()),
                    Dict.zoneOffsetText(farm.getTimeZone()),
                    turbineCount,
                    onlineCount,
                    scale(rated),
                    scale(daily),
                    scale(monthly),
                    scale(totalEnergy),
                    hours(totalEnergy, rated),
                    logCountByFarm.getOrDefault(farm.getId(), 0L)));
        }
        result.sort(Comparator.comparing(FarmEnergyDTO::getFarmCode));
        return result;
    }

    public List<TurbineEnergyDTO> byTurbine(Long farmId) {
        Map<Long, WindFarm> farmMap = farmMap();
        Map<Long, Turbine> turbineMap = turbineMap();
        List<RunLog> logs = logs(farmId);

        Map<Long, Map<String, BigDecimal>> dayEnergy = new HashMap<>();
        Map<Long, Map<String, BigDecimal>> monthEnergy = new HashMap<>();
        Map<Long, BigDecimal> powerSum = new HashMap<>();
        Map<Long, Long> logCount = new HashMap<>();

        for (RunLog log : logs) {
            Turbine turbine = turbineMap.get(log.getTurbineId());
            WindFarm farm = farmMap.get(log.getFarmId());
            String zone = TimeText.pickZone(log.getRecordZone(), farm == null ? null : farm.getTimeZone());
            String day = TimeText.localDate(log.getRecordTime(), zone);
            String month = TimeText.localMonth(log.getRecordTime(), zone);
            if (day == null) {
                continue;
            }
            BigDecimal energy = nvl(log.getDailyEnergyKwh());
            Map<String, BigDecimal> days = dayEnergy.computeIfAbsent(log.getTurbineId(), k -> new HashMap<>());
            BigDecimal prev = days.get(day);
            if (prev == null || energy.compareTo(prev) > 0) {
                days.put(day, energy);
            }
            monthEnergy.computeIfAbsent(log.getTurbineId(), k -> new HashMap<>())
                    .merge(month, energy, BigDecimal::add);
            powerSum.merge(log.getTurbineId(), nvl(log.getActivePowerKw()), BigDecimal::add);
            logCount.merge(log.getTurbineId(), 1L, Long::sum);
        }

        List<TurbineEnergyDTO> result = new ArrayList<>();
        for (Turbine turbine : turbineMap.values()) {
            if (farmId != null && !farmId.equals(turbine.getFarmId())) {
                continue;
            }
            Map<String, BigDecimal> days = dayEnergy.get(turbine.getId());
            Map<String, BigDecimal> months = monthEnergy.get(turbine.getId());
            if (days == null) {
                days = new HashMap<>();
            }
            BigDecimal totalEnergy = days.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal latestDay = days.isEmpty()
                    ? BigDecimal.ZERO
                    : days.get(days.keySet().stream().max(Comparator.naturalOrder()).orElse(""));
            BigDecimal latestMonth = months == null || months.isEmpty()
                    ? BigDecimal.ZERO
                    : months.get(months.keySet().stream().max(Comparator.naturalOrder()).orElse(""));
            long count = logCount.getOrDefault(turbine.getId(), 0L);
            BigDecimal avgPower = count == 0
                    ? BigDecimal.ZERO
                    : powerSum.getOrDefault(turbine.getId(), BigDecimal.ZERO)
                            .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            WindFarm farm = farmMap.get(turbine.getFarmId());
            result.add(new TurbineEnergyDTO(
                    turbine.getId(),
                    turbine.getFarmId(),
                    farm == null ? null : farm.getFarmName(),
                    farm == null ? null : Dict.timeZoneName(farm.getTimeZone()),
                    turbine.getUnitCode(),
                    turbine.getUnitName(),
                    turbine.getModel(),
                    turbine.getStatus(),
                    turbine.getStatusName(),
                    turbine.getRatedPowerKw(),
                    scale(latestDay),
                    scale(latestMonth),
                    scale(totalEnergy),
                    avgPower,
                    hours(totalEnergy, turbine.getRatedPowerKw()),
                    count));
        }
        result.sort(Comparator.comparing(TurbineEnergyDTO::getUnitCode));
        return result;
    }

    // ---------------- 工单 / 备件分布 ----------------

    public List<WorkOrderStatusDTO> workOrderStatus() {
        List<WorkOrderStatusDTO> result = new ArrayList<>();
        for (WorkOrderStatusDTO dto : orderRepository.statByStatus()) {
            dto.setStatusName(Dict.workOrderStatusName(dto.getStatus()));
            result.add(dto);
        }
        return result;
    }

    public List<WorkOrderStatusDTO> maintTypeStat() {
        List<WorkOrderStatusDTO> result = new ArrayList<>();
        for (WorkOrderStatusDTO dto : orderRepository.statByMaintType()) {
            dto.setStatusName(Dict.maintTypeName(dto.getStatus()));
            result.add(dto);
        }
        return result;
    }

    public List<PartCategoryDTO> partCategory() {
        List<PartCategoryDTO> result = new ArrayList<>();
        for (PartCategoryDTO dto : partRepository.statByCategory()) {
            dto.setCategoryName(Dict.partCategoryName(dto.getCategory()));
            result.add(dto);
        }
        return result;
    }

    /** 词典：前端下拉框直接从这里取，避免前后端各写一份 */
    public Map<String, Object> dict() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timeZones", Dict.TIME_ZONES);
        data.put("farmStatuses", Dict.FARM_STATUSES);
        data.put("turbineStatuses", Dict.TURBINE_STATUSES);
        data.put("maintTypes", Dict.MAINT_TYPES);
        data.put("orderStatuses", Dict.ORDER_STATUSES);
        data.put("itemResults", Dict.ITEM_RESULTS);
        data.put("partCategories", Dict.PART_CATEGORIES);
        data.put("ticketStatuses", Dict.TICKET_STATUSES);
        data.put("isolationTypes", Dict.ISOLATION_TYPES);
        data.put("maxRatedPowerKw", Dict.MAX_RATED_POWER_KW);
        data.put("maxWindSpeedMs", Dict.MAX_WIND_SPEED_MS);
        return data;
    }

    // ---------------- 工具 ----------------

    private List<RunLog> logs(Long farmId) {
        if (farmId == null) {
            return runLogRepository.findAllByOrderByRecordTimeDesc();
        }
        return runLogRepository.findByFarmIdOrderByRecordTimeDesc(farmId);
    }

    private Map<Long, WindFarm> farmMap() {
        Map<Long, WindFarm> map = new LinkedHashMap<>();
        for (WindFarm farm : farmRepository.findAllByOrderByFarmCodeAsc()) {
            map.put(farm.getId(), farm);
        }
        return map;
    }

    private Map<Long, Turbine> turbineMap() {
        Map<Long, Turbine> map = new LinkedHashMap<>();
        for (Turbine turbine : turbineRepository.findAllByOrderByFarmIdAscUnitCodeAsc()) {
            map.put(turbine.getId(), turbine);
        }
        return map;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 等效利用小时 = 发电量 / 额定功率 */
    private static BigDecimal hours(BigDecimal energy, BigDecimal rated) {
        if (energy == null || rated == null || rated.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return energy.divide(rated, 2, RoundingMode.HALF_UP);
    }

    /** 汇总中间量 */
    private static class Agg {
        private BigDecimal energy = BigDecimal.ZERO;
        private BigDecimal powerSum = BigDecimal.ZERO;
        private int logCount = 0;
    }
}
