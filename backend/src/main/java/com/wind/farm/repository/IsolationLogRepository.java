package com.wind.farm.repository;

import com.wind.farm.domain.IsolationLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IsolationLogRepository extends JpaRepository<IsolationLog, Long> {

    /** 完整变更记录：按 id（即写入先后）正序 */
    List<IsolationLog> findByTicketIdOrderByIdAsc(Long ticketId);

    List<IsolationLog> findByTurbineIdOrderByIdDesc(Long turbineId);
}
