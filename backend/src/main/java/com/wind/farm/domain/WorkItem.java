package com.wind.farm.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 检修作业项：一张工单下若干条必做项；提验收前所有作业项都必须有结果。
 */
@Entity
@Table(name = "work_item")
@Getter
@Setter
public class WorkItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属工单 id */
    @Column(name = "work_order_id", nullable = false)
    private Long workOrderId;

    /** 作业项序号 */
    @Column(name = "item_seq")
    private Integer itemSeq;

    /** 作业项名称 */
    @Column(name = "item_name", length = 64)
    private String itemName;

    /** 作业标准 */
    @Column(name = "standard", length = 128)
    private String standard;

    /** 结果：PASS 合格 / FAIL 不合格 / SKIP 免检，为空表示还没做 */
    @Column(name = "result", length = 16)
    private String result;

    /** 结果说明 */
    @Column(name = "result_note", length = 128)
    private String resultNote;

    /** 是否已确认（勾选清单） */
    @Column(name = "done")
    private Boolean done;

    @Column(name = "note", length = 128)
    private String note;
}
