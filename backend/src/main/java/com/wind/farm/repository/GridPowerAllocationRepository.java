package com.wind.farm.repository;

import com.wind.farm.domain.GridPowerAllocation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridPowerAllocationRepository extends JpaRepository<GridPowerAllocation, Long> {

    List<GridPowerAllocation> findByCommandIdOrderByUnitCodeAscIdAsc(Long commandId);

    Optional<GridPowerAllocation> findByCommandIdAndTurbineId(Long commandId, Long turbineId);
}
