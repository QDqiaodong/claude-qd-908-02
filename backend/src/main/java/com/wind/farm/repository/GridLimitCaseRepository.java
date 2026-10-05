package com.wind.farm.repository;

import com.wind.farm.domain.GridLimitCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridLimitCaseRepository extends JpaRepository<GridLimitCase, Long> {

    List<GridLimitCase> findByFarmIdOrderByObservedTimeDescIdDesc(Long farmId);

    List<GridLimitCase> findByCommandIdOrderByObservedTimeDescIdDesc(Long commandId);

    Optional<GridLimitCase> findFirstByCommandIdAndScopeAndTurbineIdAndStatusNotOrderByIdDesc(
            Long commandId, String scope, Long turbineId, String status);

    Optional<GridLimitCase> findFirstByCommandIdAndScopeAndTurbineIdIsNullAndStatusNotOrderByIdDesc(
            Long commandId, String scope, String status);

    Optional<GridLimitCase> findFirstByCommandIdAndScopeAndTurbineIdOrderByIdDesc(
            Long commandId, String scope, Long turbineId);

    Optional<GridLimitCase> findFirstByCommandIdAndScopeAndTurbineIdIsNullOrderByIdDesc(
            Long commandId, String scope);
}
