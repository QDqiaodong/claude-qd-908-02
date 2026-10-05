package com.wind.farm.repository;

import com.wind.farm.domain.WindFarm;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WindFarmRepository extends JpaRepository<WindFarm, Long> {

    boolean existsByFarmCode(String farmCode);

    List<WindFarm> findAllByOrderByFarmCodeAsc();

    List<WindFarm> findByStatusOrderByFarmCodeAsc(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from WindFarm f where f.id = :id")
    Optional<WindFarm> lockById(@Param("id") Long id);
}
