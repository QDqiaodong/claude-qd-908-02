package com.wind.farm.repository;

import com.wind.farm.domain.IsolationPoint;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IsolationPointRepository extends JpaRepository<IsolationPoint, Long> {

    List<IsolationPoint> findByTicketIdOrderByPointSeqAsc(Long ticketId);

    long countByTicketId(Long ticketId);

    long countByTicketIdAndIsolatedTrueAndValidFlagTrue(Long ticketId);

    long countByTicketIdAndRestoredTrue(Long ticketId);

    /**
     * 隔离点确认的条件更新：只有「属于该票、尚未确认、隔离仍有效（或首次确认）」的点才能被置位。
     * 影响行数 0 即重复点击 / 已被他人确认 / 已失效——调用方据此幂等返回，不会产生第二次推进。
     */
    @Modifying
    @Query("update IsolationPoint p set p.isolated = true, p.isolateOperator = :operator, "
            + "p.isolateVerifier = :verifier, p.isolateTime = :time, p.isolateZone = :zone, p.validFlag = true "
            + "where p.id = :id and p.ticketId = :ticketId "
            + "and (p.isolated = false or p.validFlag = false)")
    int confirmIsolation(@Param("id") Long id, @Param("ticketId") Long ticketId,
            @Param("operator") String operator, @Param("verifier") String verifier,
            @Param("time") ZonedDateTime time, @Param("zone") String zone);

    /** 隔离失效：只能对已确认且当前仍有效的点操作，天然防重复失效 */
    @Modifying
    @Query("update IsolationPoint p set p.validFlag = false "
            + "where p.id = :id and p.ticketId = :ticketId and p.isolated = true and p.validFlag = true")
    int markInvalid(@Param("id") Long id, @Param("ticketId") Long ticketId);

    /**
     * 复电解除的条件更新：必须是「已隔离、仍有效、尚未复电」的点。
     * 顺序约束（必须是当前待复电的最高序号）由 service 先校验，这里只保证状态幂等。
     */
    @Modifying
    @Query("update IsolationPoint p set p.restored = true, p.restoreOperator = :operator, "
            + "p.restoreTime = :time, p.restoreZone = :zone "
            + "where p.id = :id and p.ticketId = :ticketId and p.isolated = true "
            + "and p.validFlag = true and p.restored = false")
    int confirmRestore(@Param("id") Long id, @Param("ticketId") Long ticketId,
            @Param("operator") String operator, @Param("time") ZonedDateTime time, @Param("zone") String zone);
}
