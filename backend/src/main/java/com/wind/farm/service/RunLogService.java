package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.RunLog;
import com.wind.farm.domain.SafetyTicket;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.dto.RunLogRequest;
import com.wind.farm.dto.Views.RunLogVO;
import com.wind.farm.repository.RunLogRepository;
import com.wind.farm.repository.SafetyTicketRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行日志：同一机位同一记录时刻重复登记拦截；风速不得为负；
 * 有功功率不得超过额定功率；日发电量 > 0 且不得超过 额定功率 × 24h；
 * 故障机位不允许登记发电读数。
 *
 * <p>记录时刻 / 上报时刻是 ZonedDateTime，Hibernate 按 {@code hibernate.jdbc.time_zone}
 * 归一化存进 DATETIME；该记录所属哪个时区另存 record_time_tz / report_time_tz 两列（ZoneId 文本），
 * 登记 / 修改时按所属风场的时区写入，展示本地时间优先取它。
 */
@Service
public class RunLogService {

    private final RunLogRepository runLogRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;
    private final SafetyTicketRepository ticketRepository;
    private final GridLimitService gridLimitService;

    public RunLogService(RunLogRepository runLogRepository, TurbineRepository turbineRepository,
            WindFarmRepository farmRepository, SafetyTicketRepository ticketRepository,
            @Lazy GridLimitService gridLimitService) {
        this.runLogRepository = runLogRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
        this.ticketRepository = ticketRepository;
        this.gridLimitService = gridLimitService;
    }

    public List<RunLog> list(Long turbineId, Long farmId) {
        if (turbineId != null) {
            return runLogRepository.findByTurbineIdOrderByRecordTimeDesc(turbineId);
        }
        if (farmId != null) {
            return runLogRepository.findByFarmIdOrderByRecordTimeDesc(farmId);
        }
        return runLogRepository.findAllByOrderByRecordTimeDesc();
    }

    public List<RunLogVO> listVO(Long turbineId, Long farmId) {
        List<RunLogVO> result = new ArrayList<>();
        for (RunLog log : list(turbineId, farmId)) {
            result.add(toVO(log));
        }
        return result;
    }

    public RunLog get(Long id) {
        return runLogRepository.findById(id).orElseThrow(() -> new BizException("运行日志不存在：" + id));
    }

    @Transactional
    public RunLog create(RunLogRequest request) {
        if (request.getTurbineId() == null) {
            throw new BizException("请选择机位");
        }
        Turbine turbine = turbineRepository.findById(request.getTurbineId())
                .orElseThrow(() -> new BizException("机位不存在：" + request.getTurbineId()));
        WindFarm farm = farmRepository.findById(turbine.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();

        // 规则：故障机位不允许登记发电读数
        if (turbine.isFault()) {
            throw new BizException("机位 " + turbine.getUnitCode() + " 处于故障状态，不允许登记发电读数，请先转检修处理");
        }
        // 规则：机位存在未恢复且已动手的安全隔离票（确认中 / 已隔离 / 复电中）时禁止登记发电读数，
        //       避免检修中的机位仍被登记出力 / 发电量；草稿票尚未动手，不拦截
        SafetyTicket lockTicket = ticketRepository.findByTurbineIdAndActiveSlot(turbine.getId(), 1L).orElse(null);
        if (lockTicket != null && Dict.isTurbineLockedByTicket(lockTicket.getStatus())) {
            throw new BizException("机位 " + turbine.getUnitCode() + " 的隔离票 " + lockTicket.getTicketNo()
                    + " 处于「" + Dict.ticketStatusName(lockTicket.getStatus())
                    + "」，安全措施未恢复，禁止登记发电读数；请完成隔离复电恢复后再登记");
        }

        ZonedDateTime recordTime = request.getRecordTime() == null
                ? ZonedDateTime.now(Dict.zoneOf(zone))
                : request.getRecordTime();
        ZonedDateTime reportTime = request.getReportTime() == null
                ? ZonedDateTime.now(Dict.zoneOf(zone))
                : request.getReportTime();

        // 规则：同一机位同一记录时刻不允许重复登记
        for (RunLog exist : runLogRepository.findByTurbineIdOrderByRecordTimeDesc(turbine.getId())) {
            if (exist.getRecordTime() != null && exist.getRecordTime().isEqual(recordTime)) {
                throw new BizException("机位 " + turbine.getUnitCode() + " 在 "
                        + TimeText.local(recordTime, zone) + " 已登记过运行日志，不允许重复登记");
            }
        }

        BigDecimal windSpeed = nullSafe(request.getWindSpeedMs());
        BigDecimal activePower = nullSafe(request.getActivePowerKw());
        BigDecimal dailyEnergy = nullSafe(request.getDailyEnergyKwh());

        // 规则：风速不得为负
        if (request.getWindSpeedMs() == null) {
            throw new BizException("风速不能为空");
        }
        if (windSpeed.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("风速不得为负：" + windSpeed + " m/s");
        }
        if (windSpeed.compareTo(Dict.MAX_WIND_SPEED_MS) > 0) {
            throw new BizException("风速不得超过 " + Dict.MAX_WIND_SPEED_MS + " m/s：" + windSpeed);
        }

        // 规则：有功功率不得超过额定功率
        if (request.getActivePowerKw() == null) {
            throw new BizException("有功功率不能为空");
        }
        if (activePower.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("有功功率不得为负：" + activePower + " kW");
        }
        if (turbine.getRatedPowerKw() != null && activePower.compareTo(turbine.getRatedPowerKw()) > 0) {
            throw new BizException("有功功率 " + activePower + " kW 超过机位额定功率 "
                    + turbine.getRatedPowerKw() + " kW");
        }

        // 规则：日发电量 > 0 且不得超过理论上限（额定功率 × 24h）
        if (request.getDailyEnergyKwh() == null) {
            throw new BizException("日发电量不能为空");
        }
        BigDecimal limit = Dict.dailyEnergyLimit(turbine.getRatedPowerKw());
        if (dailyEnergy.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("日发电量必须大于 0");
        }
        if (limit.compareTo(BigDecimal.ZERO) > 0 && dailyEnergy.compareTo(limit) > 0) {
            throw new BizException("日发电量 " + dailyEnergy + " kWh 超过理论上限 " + limit
                    + " kWh（额定功率 " + turbine.getRatedPowerKw() + " kW × 24h）");
        }

        RunLog log = new RunLog();
        log.setTurbineId(turbine.getId());
        log.setFarmId(turbine.getFarmId());
        log.setUnitCode(turbine.getUnitCode());
        log.setRecordTime(recordTime);
        log.setRecordZone(Dict.zoneOf(zone).getId());
        log.setReportTime(reportTime);
        log.setReportZone(Dict.zoneOf(zone).getId());
        log.setWindSpeedMs(windSpeed);
        log.setActivePowerKw(activePower);
        log.setDailyEnergyKwh(dailyEnergy);
        log.setWindDirection(request.getWindDirection());
        log.setTemperatureC(request.getTemperatureC());
        log.setOperator(request.getOperator());
        log.setNote(request.getNote());

        RunLog saved = runLogRepository.save(log);
        // 回写机位的最近功率 / 今日发电量 / 累计发电量
        turbine.setLastPowerKw(activePower);
        turbine.setTodayEnergyKwh(dailyEnergy);
        turbine.setTotalEnergyKwh(nullSafe(turbine.getTotalEnergyKwh()).add(dailyEnergy));
        turbineRepository.save(turbine);
        gridLimitService.evaluateLog(saved, "READING", request.getOperator());
        return saved;
    }

    @Transactional
    public RunLog update(Long id, RunLogRequest request) {
        RunLog log = get(id);
        Turbine turbine = turbineRepository.findById(log.getTurbineId()).orElse(null);
        WindFarm farm = turbine == null ? null : farmRepository.findById(turbine.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();

        if (request.getRecordTime() != null) {
            for (RunLog exist : runLogRepository.findByTurbineIdOrderByRecordTimeDesc(log.getTurbineId())) {
                if (!exist.getId().equals(id) && exist.getRecordTime() != null
                        && exist.getRecordTime().isEqual(request.getRecordTime())) {
                    throw new BizException("机位 " + (turbine == null ? "" : turbine.getUnitCode()) + " 在 "
                            + TimeText.local(request.getRecordTime(), zone) + " 已登记过运行日志，不允许重复登记");
                }
            }
        }
        if (request.getWindSpeedMs() != null) {
            if (request.getWindSpeedMs().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("风速不得为负：" + request.getWindSpeedMs() + " m/s");
            }
            if (request.getWindSpeedMs().compareTo(Dict.MAX_WIND_SPEED_MS) > 0) {
                throw new BizException("风速不得超过 " + Dict.MAX_WIND_SPEED_MS + " m/s：" + request.getWindSpeedMs());
            }
        }
        if (request.getActivePowerKw() != null && turbine != null && turbine.getRatedPowerKw() != null
                && request.getActivePowerKw().compareTo(turbine.getRatedPowerKw()) > 0) {
            throw new BizException("有功功率 " + request.getActivePowerKw() + " kW 超过机位额定功率 "
                    + turbine.getRatedPowerKw() + " kW");
        }
        if (request.getDailyEnergyKwh() != null) {
            if (request.getDailyEnergyKwh().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("日发电量必须大于 0");
            }
            BigDecimal limit = Dict.dailyEnergyLimit(turbine == null ? null : turbine.getRatedPowerKw());
            if (limit.compareTo(BigDecimal.ZERO) > 0 && request.getDailyEnergyKwh().compareTo(limit) > 0) {
                throw new BizException("日发电量 " + request.getDailyEnergyKwh() + " kWh 超过理论上限 " + limit + " kWh");
            }
        }

        ifNotNull(request.getRecordTime(), log::setRecordTime);
        ifNotNull(request.getReportTime(), log::setReportTime);
        if (request.getRecordTime() != null) {
            log.setRecordZone(Dict.zoneOf(zone).getId());
        }
        if (request.getReportTime() != null) {
            log.setReportZone(Dict.zoneOf(zone).getId());
        }
        ifNotNull(request.getWindSpeedMs(), log::setWindSpeedMs);
        ifNotNull(request.getActivePowerKw(), log::setActivePowerKw);
        ifNotNull(request.getDailyEnergyKwh(), log::setDailyEnergyKwh);
        ifNotNull(request.getWindDirection(), log::setWindDirection);
        ifNotNull(request.getTemperatureC(), log::setTemperatureC);
        ifNotNull(request.getOperator(), log::setOperator);
        ifNotNull(request.getNote(), log::setNote);
        RunLog saved = runLogRepository.save(log);
        String trigger = request.getActivePowerKw() != null || request.getRecordTime() != null
                || request.getReportTime() != null
                ? "CORRECTION" : "MANUAL_RECHECK";
        gridLimitService.evaluateLog(saved, trigger, request.getOperator());
        return saved;
    }

    @Transactional
    public void remove(Long id) {
        RunLog log = get(id);
        gridLimitService.onLogDeleted(log, null);
        runLogRepository.delete(log);
    }

    public RunLogVO toVO(RunLog log) {
        RunLogVO vo = new RunLogVO();
        vo.setId(log.getId());
        vo.setTurbineId(log.getTurbineId());
        vo.setFarmId(log.getFarmId());
        WindFarm farm = log.getFarmId() == null ? null : farmRepository.findById(log.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setTimeZone(zone);
        vo.setTimeZoneName(Dict.timeZoneName(zone));
        vo.setUnitCode(log.getUnitCode());
        vo.setRecordTime(TimeText.iso(log.getRecordTime()));
        vo.setRecordTz(log.getRecordZone());
        vo.setRecordLocal(TimeText.local(log.getRecordTime(), TimeText.pickZone(log.getRecordZone(), zone)));
        vo.setReportTime(TimeText.iso(log.getReportTime()));
        vo.setReportTz(log.getReportZone());
        vo.setReportLocal(TimeText.local(log.getReportTime(), TimeText.pickZone(log.getReportZone(), zone)));
        vo.setWindSpeedMs(log.getWindSpeedMs());
        vo.setActivePowerKw(log.getActivePowerKw());
        vo.setDailyEnergyKwh(log.getDailyEnergyKwh());
        vo.setWindDirection(log.getWindDirection());
        vo.setTemperatureC(log.getTemperatureC());
        vo.setOperator(log.getOperator());
        vo.setNote(log.getNote());
        Turbine turbine = log.getTurbineId() == null ? null : turbineRepository.findById(log.getTurbineId()).orElse(null);
        if (turbine != null && turbine.getRatedPowerKw() != null
                && turbine.getRatedPowerKw().compareTo(BigDecimal.ZERO) > 0 && log.getActivePowerKw() != null) {
            vo.setLoadRate(Math.min(1D,
                    log.getActivePowerKw().doubleValue() / turbine.getRatedPowerKw().doubleValue()));
        } else {
            vo.setLoadRate(0D);
        }
        return vo;
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
