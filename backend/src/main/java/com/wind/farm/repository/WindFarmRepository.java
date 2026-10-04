package com.wind.farm.repository;

import com.wind.farm.domain.WindFarm;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WindFarmRepository extends JpaRepository<WindFarm, Long> {

    boolean existsByFarmCode(String farmCode);

    List<WindFarm> findAllByOrderByFarmCodeAsc();

    List<WindFarm> findByStatusOrderByFarmCodeAsc(String status);
}
