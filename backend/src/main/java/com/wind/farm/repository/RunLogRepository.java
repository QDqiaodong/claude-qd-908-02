package com.wind.farm.repository;

import com.wind.farm.domain.RunLog;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunLogRepository extends JpaRepository<RunLog, Long> {

    List<RunLog> findAllByOrderByRecordTimeDesc();

    List<RunLog> findByTurbineIdOrderByRecordTimeDesc(Long turbineId);

    List<RunLog> findByFarmIdOrderByRecordTimeDesc(Long farmId);

    List<RunLog> findByFarmIdAndRecordTimeBetweenOrderByRecordTimeAscUnitCodeAsc(
            Long farmId, ZonedDateTime start, ZonedDateTime end);

    List<RunLog> findByTurbineIdAndRecordTimeBetweenOrderByRecordTimeAsc(
            Long turbineId, ZonedDateTime start, ZonedDateTime end);

    long countByTurbineId(Long turbineId);

    long countByFarmId(Long farmId);
}
