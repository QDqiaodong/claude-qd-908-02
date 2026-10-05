package com.wind.farm.repository;

import com.wind.farm.domain.GridLimitEvent;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridLimitEventRepository extends JpaRepository<GridLimitEvent, Long> {

    List<GridLimitEvent> findByFarmIdOrderByObservedTimeDescIdDesc(Long farmId);

    List<GridLimitEvent> findByCommandIdOrderByObservedTimeDescIdDesc(Long commandId);

    List<GridLimitEvent> findByRunLogIdOrderByIdDesc(Long runLogId);

    Optional<GridLimitEvent> findFirstByEventKeyAndStatusNotOrderByIdDesc(String eventKey, String status);

    List<GridLimitEvent> findByTurbineIdAndObservedTimeBetweenOrderByObservedTimeAscIdAsc(
            Long turbineId, ZonedDateTime start, ZonedDateTime end);

    List<GridLimitEvent> findByStatusInOrderByObservedTimeDescIdDesc(List<String> statuses);
}
