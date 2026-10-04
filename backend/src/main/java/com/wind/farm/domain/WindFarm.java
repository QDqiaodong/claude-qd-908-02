package com.wind.farm.domain;

import com.wind.farm.common.Dict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.Getter;
import lombok.Setter;

/**
 * 风场：陆上风电场的场站单元，场站本地时区记录在 time_zone 上，
 * 其下机位的运行日志、检修工单都按这个时区归一化存储与还原。
 */
@Entity
@Table(name = "wind_farm")
@Getter
@Setter
public class WindFarm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 风场编号：全场唯一，如 WF-HB-01 */
    @Column(name = "farm_code", nullable = false, unique = true, length = 32)
    private String farmCode;

    /** 风场名称，如 张北单晶河风场 */
    @Column(name = "farm_name", length = 64)
    private String farmName;

    /** 所属区域：华北 / 西北 / 东北 / 西南 */
    @Column(name = "region", length = 32)
    private String region;

    /** 省份 */
    @Column(name = "province", length = 32)
    private String province;

    /** 场站本地时区：Asia/Shanghai（东八区）/ Asia/Urumqi（东六区）等，必填 */
    @Column(name = "time_zone", nullable = false, length = 32)
    private String timeZone;

    /** 装机容量（MW） */
    @Column(name = "installed_capacity_mw", precision = 10, scale = 2)
    private BigDecimal installedCapacityMw;

    /** 机位数量（由机位增删维护） */
    @Column(name = "turbine_count")
    private Integer turbineCount;

    /** 并网日期 */
    @Column(name = "grid_date")
    private LocalDate gridDate;

    /** 场站负责人 */
    @Column(name = "owner", length = 32)
    private String owner;

    /** 状态：ACTIVE 在运 / MAINTAIN 检修 / STOPPED 停运 */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 128)
    private String note;

    @Transient
    public ZoneId getZone() {
        return Dict.zoneOf(timeZone);
    }

    @Transient
    public String getTimeZoneName() {
        return Dict.timeZoneName(timeZone);
    }

    /** 时区偏移文本，如 +08:00 */
    @Transient
    public String getOffsetText() {
        return Dict.zoneOffsetText(timeZone);
    }

    @Transient
    public String getStatusName() {
        return Dict.farmStatusName(status);
    }

    @Transient
    public boolean isRunning() {
        return "ACTIVE".equals(status);
    }
}
