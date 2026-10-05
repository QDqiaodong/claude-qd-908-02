package com.wind.farm.repository;

import com.wind.farm.domain.GridLimitDeviation;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridLimitDeviationRepository extends JpaRepository<GridLimitDeviation, Long> {
    List<GridLimitDeviation> findByFarmIdOrderByRecordTimeDescIdDesc(Long farmId);
    List<GridLimitDeviation> findByRunLogIdOrderByIdDesc(Long runLogId);
    List<GridLimitDeviation> findByGoverningOrderIdOrderByRecordTimeDescIdDesc(Long orderId);
    List<GridLimitDeviation> findByFarmIdAndRecordTimeOrderByIdDesc(Long farmId, ZonedDateTime recordTime);
    Optional<GridLimitDeviation> findFirstByFarmIdAndScopeAndRecordTimeAndLatestFlagTrueOrderByRevisionNoDesc(
            Long farmId, String scope, ZonedDateTime recordTime);
    Optional<GridLimitDeviation> findFirstByFarmIdAndScopeAndTurbineIdAndRecordTimeAndLatestFlagTrueOrderByRevisionNoDesc(
            Long farmId, String scope, Long turbineId, ZonedDateTime recordTime);
}
