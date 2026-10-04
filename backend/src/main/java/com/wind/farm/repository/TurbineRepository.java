package com.wind.farm.repository;

import com.wind.farm.domain.Turbine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TurbineRepository extends JpaRepository<Turbine, Long> {

    List<Turbine> findAllByOrderByFarmIdAscUnitCodeAsc();

    List<Turbine> findByFarmIdOrderByUnitCodeAsc(Long farmId);

    boolean existsByFarmIdAndUnitCode(Long farmId, String unitCode);

    long countByFarmId(Long farmId);

    long countByStatus(String status);

    long countByFarmIdAndStatus(Long farmId, String status);
}
