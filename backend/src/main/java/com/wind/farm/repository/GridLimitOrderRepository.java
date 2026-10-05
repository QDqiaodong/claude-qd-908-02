package com.wind.farm.repository;

import com.wind.farm.domain.GridLimitOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GridLimitOrderRepository extends JpaRepository<GridLimitOrder, Long> {

    List<GridLimitOrder> findByFarmIdOrderByStartTimeDescIdDesc(Long farmId);

    List<GridLimitOrder> findByStatusOrderByPriorityAscIdAsc(String status);

    Optional<GridLimitOrder> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from GridLimitOrder o where o.id = :id")
    Optional<GridLimitOrder> lockById(@Param("id") Long id);
}
