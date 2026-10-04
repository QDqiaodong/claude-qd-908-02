package com.wind.farm.repository;

import com.wind.farm.domain.SafetyTicket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyTicketRepository extends JpaRepository<SafetyTicket, Long> {

    boolean existsByTicketNo(String ticketNo);

    boolean existsByWorkOrderId(Long workOrderId);

    Optional<SafetyTicket> findByWorkOrderId(Long workOrderId);

    /** 同一机位尚未恢复（active_slot=1）的票：部分唯一索引 uk_ticket_active_turbine 兜底至多一张 */
    Optional<SafetyTicket> findByTurbineIdAndActiveSlot(Long turbineId, Long activeSlot);

    List<SafetyTicket> findByTurbineIdOrderByTicketNoAsc(Long turbineId);

    List<SafetyTicket> findByFarmIdOrderByTicketNoAsc(Long farmId);

    List<SafetyTicket> findAllByOrderByTicketNoAsc();

    long countByStatus(String status);
}
