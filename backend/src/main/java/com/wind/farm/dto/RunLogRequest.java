package com.wind.farm.dto;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import lombok.Data;

/** 运行日志新建 / 更新请求：记录时刻与上报时刻都带时区偏移 */
@Data
public class RunLogRequest {

    /** 机位 id */
    private Long turbineId;

    /** 记录时刻（带偏移，如 2026-09-28T08:00:00+08:00） */
    private ZonedDateTime recordTime;

    /** 上报时刻 */
    private ZonedDateTime reportTime;

    /** 风速（m/s），不得为负 */
    private BigDecimal windSpeedMs;

    /** 有功功率（kW），不得超过额定功率 */
    private BigDecimal activePowerKw;

    /** 当日累计发电量（kWh） */
    private BigDecimal dailyEnergyKwh;

    /** 风向 */
    private String windDirection;

    /** 机舱温度（℃） */
    private BigDecimal temperatureC;

    /** 值班员 */
    private String operator;

    private String note;
}
