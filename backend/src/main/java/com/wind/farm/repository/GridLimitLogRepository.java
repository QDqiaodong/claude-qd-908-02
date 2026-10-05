package com.wind.farm.repository;

import com.wind.farm.domain.GridLimitLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridLimitLogRepository extends JpaRepository<GridLimitLog, Long> {
    List<GridLimitLog> findByOrderIdOrderByIdAsc(Long orderId);
}
