package com.wind.farm.repository;

import com.wind.farm.domain.GridCommandLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GridCommandLogRepository extends JpaRepository<GridCommandLog, Long> {

    List<GridCommandLog> findByCommandIdOrderByIdAsc(Long commandId);
}
