package com.wind.farm.repository;

import com.wind.farm.domain.IsolationTemplate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IsolationTemplateRepository extends JpaRepository<IsolationTemplate, Long> {

    List<IsolationTemplate> findByEnabledTrueOrderByMaintTypeAscPointSeqAsc();

    List<IsolationTemplate> findByMaintTypeAndEnabledTrueOrderByPointSeqAsc(String maintType);

    List<IsolationTemplate> findByMaintTypeOrderByPointSeqAsc(String maintType);

    boolean existsByMaintTypeAndPointSeq(String maintType, Integer pointSeq);
}
