package com.wind.farm.repository;

import com.wind.farm.domain.PartReplacement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartReplacementRepository extends JpaRepository<PartReplacement, Long> {

    List<PartReplacement> findAllByOrderByReplaceTimeDesc();

    List<PartReplacement> findByTurbineIdOrderByReplaceTimeDesc(Long turbineId);

    List<PartReplacement> findByPartIdOrderByReplaceTimeDesc(Long partId);

    /** 幂等：同一次更换由客户端令牌唯一确定，只允许一条有效履历 */
    Optional<PartReplacement> findByClientToken(String clientToken);

    /**
     * 旧客户端不带令牌时的自然键短窗兜底：同备件 / 同机位 / 同工单 / 同数量 / 同登记人，
     * 在短时间内重复提交视为同一次更换的网络重试，不重复扣减、不重复落履历。
     * 任一字段不同都不算重试，避免把正常的连续登记误并。
     */
    @Query("select r from PartReplacement r where r.partId = :partId "
            + "and ((r.turbineId is null and :turbineId is null) or r.turbineId = :turbineId) "
            + "and ((r.workOrderId is null and :workOrderId is null) or r.workOrderId = :workOrderId) "
            + "and r.qty = :qty "
            + "and ((r.operator is null and :operator is null) or r.operator = :operator) "
            + "and r.replaceTime >= :since order by r.replaceTime desc")
    List<PartReplacement> findRecentNatural(@Param("partId") Long partId,
            @Param("turbineId") Long turbineId,
            @Param("workOrderId") Long workOrderId,
            @Param("qty") Integer qty,
            @Param("operator") String operator,
            @Param("since") LocalDateTime since);
}
