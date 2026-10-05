package com.wind.farm.repository;

import com.wind.farm.domain.GridPowerCommand;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface GridPowerCommandRepository extends JpaRepository<GridPowerCommand, Long> {

    boolean existsByCommandNo(String commandNo);

    boolean existsByCommandNoAndIdNot(String commandNo, Long id);

    List<GridPowerCommand> findByFarmIdOrderByStartTimeDescIdDesc(Long farmId);

    List<GridPowerCommand> findByFarmIdAndStatusInOrderByStartTimeAscIdAsc(Long farmId, List<String> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GridPowerCommand c where c.id = :id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    Optional<GridPowerCommand> findByIdForUpdate(@Param("id") Long id);
}
