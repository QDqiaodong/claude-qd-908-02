package com.wind.farm.repository;

import com.wind.farm.domain.GridLimitAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridLimitAllocationRepository extends JpaRepository<GridLimitAllocation, Long> {
    List<GridLimitAllocation> findByOrderIdOrderByIdAsc(Long orderId);
    List<GridLimitAllocation> findByTurbineIdAndOrderIdIn(Long turbineId, List<Long> orderIds);
}
