package com.wind.farm.repository;

import com.wind.farm.domain.GridLimitTurbine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridLimitTurbineRepository extends JpaRepository<GridLimitTurbine, Long> {
    List<GridLimitTurbine> findByOrderIdOrderByIdAsc(Long orderId);
    void deleteByOrderId(Long orderId);
}
