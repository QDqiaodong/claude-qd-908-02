package com.wind.farm.repository;

import com.wind.farm.domain.SparePart;
import com.wind.farm.dto.PartCategoryDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SparePartRepository extends JpaRepository<SparePart, Long> {

    boolean existsByPartCode(String partCode);

    List<SparePart> findAllByOrderByPartCodeAsc();

    List<SparePart> findByCategoryOrderByPartCodeAsc(String category);

    /**
     * 并发安全的库存条件扣减：只在「数据库当前库存 ≥ 本次数量」时更新成功。
     * 两人同时领用 / 网络重试时，后到的一笔影响行数为 0，库存永不为负；
     * 扣减与更换履历写入在同一事务，履历失败则整体回滚，不存在局部写入。
     * 条件 UPDATE 在 InnoDB 上对命中行加排他写锁，同备件并发登记被数据库串行化裁决。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SparePart p set p.stockQty = p.stockQty - :qty "
            + "where p.id = :partId and p.stockQty >= :qty")
    int deductStock(@Param("partId") Long partId, @Param("qty") int qty);

    /** 步进器入库：无条件增加（正数），只用于 + 方向 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SparePart p set p.stockQty = p.stockQty + :qty where p.id = :partId")
    int increaseStock(@Param("partId") Long partId, @Param("qty") int qty);

    /** 分类库存分布：JPQL select new，DTO 必须有参数个数匹配的显式构造器 */
    @Query("select new com.wind.farm.dto.PartCategoryDTO(p.category, count(p), "
            + "sum(p.stockQty), "
            + "sum(case when p.stockQty < p.safetyStock then 1 else 0 end)) "
            + "from SparePart p group by p.category")
    List<PartCategoryDTO> statByCategory();
}
