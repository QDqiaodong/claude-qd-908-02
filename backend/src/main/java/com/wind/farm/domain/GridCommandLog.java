package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

/** 限功率指令只追加操作轨迹。 */
@Entity
@Table(name = "grid_command_log")
@Getter
@Setter
public class GridCommandLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false)
    private Long commandId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "detail", length = 1024)
    private String detail;

    @TimeZoneStorage(TimeZoneStorageType.NORMALIZE)
    @Column(name = "created_at")
    private ZonedDateTime createdAt;

    @Column(name = "created_at_tz", length = 32)
    private String createdZone;
}
