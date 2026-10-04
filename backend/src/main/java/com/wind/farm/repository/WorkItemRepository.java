package com.wind.farm.repository;

import com.wind.farm.domain.WorkItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkItemRepository extends JpaRepository<WorkItem, Long> {

    List<WorkItem> findByWorkOrderIdOrderByItemSeqAsc(Long workOrderId);

    long countByWorkOrderId(Long workOrderId);
}
