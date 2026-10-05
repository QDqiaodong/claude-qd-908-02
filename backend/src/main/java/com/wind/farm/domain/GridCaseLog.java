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

/** 偏差处置、复核和解除操作的只追加流水。 */
@Entity
@Table(name = "grid_case_log")
@Getter
@Setter
public class GridCaseLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

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
