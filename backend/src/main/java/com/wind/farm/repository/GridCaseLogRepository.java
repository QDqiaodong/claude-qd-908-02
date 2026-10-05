package com.wind.farm.repository;

import com.wind.farm.domain.GridCaseLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridCaseLogRepository extends JpaRepository<GridCaseLog, Long> {

    List<GridCaseLog> findByCaseIdOrderByIdAsc(Long caseId);
}
