package com.wind.farm.service;

import com.wind.farm.common.BizException;
import com.wind.farm.common.Dict;
import com.wind.farm.common.TimeText;
import com.wind.farm.domain.IsolationLog;
import com.wind.farm.domain.IsolationPoint;
import com.wind.farm.domain.IsolationTemplate;
import com.wind.farm.domain.SafetyTicket;
import com.wind.farm.domain.Turbine;
import com.wind.farm.domain.WindFarm;
import com.wind.farm.domain.WorkOrder;
import com.wind.farm.dto.IsolationConfirmRequest;
import com.wind.farm.dto.IsolationPointRequest;
import com.wind.farm.dto.IsolationTemplateRequest;
import com.wind.farm.dto.TicketCreateRequest;
import com.wind.farm.dto.Views.IsolationLogVO;
import com.wind.farm.dto.Views.IsolationPointVO;
import com.wind.farm.dto.Views.SafetyTicketVO;
import com.wind.farm.dto.Views.TemplateVO;
import com.wind.farm.dto.Views.TicketBriefVO;
import com.wind.farm.repository.IsolationLogRepository;
import com.wind.farm.repository.IsolationPointRepository;
import com.wind.farm.repository.IsolationTemplateRepository;
import com.wind.farm.repository.SafetyTicketRepository;
import com.wind.farm.repository.TurbineRepository;
import com.wind.farm.repository.WindFarmRepository;
import com.wind.farm.repository.WorkOrderRepository;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 安全隔离票闭环核心服务。
 *
 * <p>状态机：DRAFT 草稿 → CONFIRMING 确认中 → ISOLATED 已隔离 → RESTORING 复电中 → RESTORED 已恢复，
 * 严格单向、不可跳步；隔离按 point_seq 升序逐条双人确认，复电严格按 point_seq 降序逐项解除。
 *
 * <p>模板与票的取舍：建票时按检修类型把启用模板点深拷贝成票内快照（记 template_id 与版本号），
 * 模板后续调整不改任何已创建的票；差异检查把建票后新增的模板点作为「待处理风险」显式抛出，
 * 责任人必须逐条「补入本票」或「留痕忽略」后才能开始 / 完成隔离，新版本仅作提示、不覆盖历史确认。
 *
 * <p>幂等与并发：票用乐观锁、机位活动占位用数据库部分唯一索引兜底、隔离点确认 / 复电用带前置
 * 条件的 UPDATE；两人同时确认、重复点击、网络重试都只会产生一次有效推进。每个隔离点操作是独立
 * 事务，部分失败时已完成步骤保留，阻塞原因里写清剩余动作。
 */
@Service
public class SafetyTicketService {

    private final SafetyTicketRepository ticketRepository;
    private final IsolationPointRepository pointRepository;
    private final IsolationLogRepository logRepository;
    private final IsolationTemplateRepository templateRepository;
    private final WorkOrderRepository orderRepository;
    private final TurbineRepository turbineRepository;
    private final WindFarmRepository farmRepository;

    public SafetyTicketService(SafetyTicketRepository ticketRepository, IsolationPointRepository pointRepository,
            IsolationLogRepository logRepository, IsolationTemplateRepository templateRepository,
            WorkOrderRepository orderRepository, TurbineRepository turbineRepository,
            WindFarmRepository farmRepository) {
        this.ticketRepository = ticketRepository;
        this.pointRepository = pointRepository;
        this.logRepository = logRepository;
        this.templateRepository = templateRepository;
        this.orderRepository = orderRepository;
        this.turbineRepository = turbineRepository;
        this.farmRepository = farmRepository;
    }

    // ================= 模板维护 =================

    public List<IsolationTemplate> listTemplates(String maintType) {
        if (StringUtils.hasText(maintType)) {
            return templateRepository.findByMaintTypeOrderByPointSeqAsc(maintType);
        }
        return templateRepository.findAll(org.springframework.data.domain.Sort.by("maintType", "pointSeq"));
    }

    @Transactional
    public IsolationTemplate createTemplate(IsolationTemplateRequest request) {
        validateTemplateFields(request, true);
        if (templateRepository.existsByMaintTypeAndPointSeq(request.getMaintType(), request.getPointSeq())) {
            throw new BizException("检修类型 " + Dict.maintTypeName(request.getMaintType())
                    + " 已存在序号 " + request.getPointSeq() + " 的模板点");
        }
        IsolationTemplate tpl = new IsolationTemplate();
        tpl.setMaintType(request.getMaintType());
        tpl.setPointSeq(request.getPointSeq());
        tpl.setPointName(request.getPointName());
        tpl.setPointType(request.getPointType());
        tpl.setStandard(request.getStandard());
        tpl.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
        tpl.setVersion(1);
        tpl.setNote(request.getNote());
        return templateRepository.save(tpl);
    }

    /**
     * 修改模板点：只影响之后新建的票。隔离点名称 / 类型 / 标准等安全内容发生变化时版本 +1，
     * 已创建的票仍持旧快照，差异检查会提示「模板有新版本」，由责任人决定是否采纳，历史不可变。
     */
    @Transactional
    public IsolationTemplate updateTemplate(Long id, IsolationTemplateRequest request) {
        IsolationTemplate tpl = getTemplate(id);
        validateTemplateFields(request, false);
        if (request.getPointSeq() != null && !request.getPointSeq().equals(tpl.getPointSeq())
                && templateRepository.existsByMaintTypeAndPointSeq(tpl.getMaintType(), request.getPointSeq())) {
            throw new BizException("该检修类型下已存在序号 " + request.getPointSeq() + " 的模板点");
        }
        boolean safetyContentChanged =
                !same(request.getPointName(), tpl.getPointName())
                        || !same(request.getPointType(), tpl.getPointType())
                        || !same(request.getStandard(), tpl.getStandard());
        if (request.getPointSeq() != null) {
            tpl.setPointSeq(request.getPointSeq());
        }
        if (request.getPointName() != null) {
            tpl.setPointName(request.getPointName());
        }
        if (request.getPointType() != null) {
            tpl.setPointType(request.getPointType());
        }
        if (request.getStandard() != null) {
            tpl.setStandard(request.getStandard());
        }
        if (request.getEnabled() != null) {
            tpl.setEnabled(request.getEnabled());
        }
        if (request.getNote() != null) {
            tpl.setNote(request.getNote());
        }
        if (safetyContentChanged) {
            tpl.setVersion((tpl.getVersion() == null ? 1 : tpl.getVersion()) + 1);
        }
        return templateRepository.save(tpl);
    }

    @Transactional
    public void removeTemplate(Long id) {
        IsolationTemplate tpl = getTemplate(id);
        templateRepository.delete(tpl);
    }

    private void validateTemplateFields(IsolationTemplateRequest request, boolean creating) {
        if (creating || request.getMaintType() != null) {
            if (!Dict.isValidMaintType(request.getMaintType())) {
                throw new BizException("检修类型非法：" + request.getMaintType());
            }
        }
        if (creating || request.getPointSeq() != null) {
            if (request.getPointSeq() == null || request.getPointSeq() <= 0) {
                throw new BizException("隔离顺序必须为正整数");
            }
        }
        if (creating || request.getPointName() != null) {
            if (!StringUtils.hasText(request.getPointName())) {
                throw new BizException("隔离点名称不能为空");
            }
        }
        if (request.getPointType() != null && !Dict.isValidIsolationType(request.getPointType())) {
            throw new BizException("隔离手段非法：" + request.getPointType());
        }
    }

    private IsolationTemplate getTemplate(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new BizException("隔离点模板不存在：" + id));
    }

    // ================= 隔离票生命周期 =================

    @Transactional
    public SafetyTicket createTicket(TicketCreateRequest request) {
        if (request.getWorkOrderId() == null) {
            throw new BizException("请选择隔离票所属工单");
        }
        WorkOrder order = orderRepository.findById(request.getWorkOrderId())
                .orElseThrow(() -> new BizException("检修工单不存在：" + request.getWorkOrderId()));
        if (!StringUtils.hasText(request.getOwner())) {
            throw new BizException("请填写隔离责任人（工作负责人）");
        }
        if (ticketRepository.existsByWorkOrderId(order.getId())) {
            throw new BizException("工单 " + order.getOrderNo() + " 已存在隔离票，一张工单只能有一张隔离票");
        }
        // 同一机位只能有一张尚未恢复的票：先查再插，唯一索引 uk_ticket_active_turbine 并发兜底
        Optional<SafetyTicket> active = ticketRepository.findByTurbineIdAndActiveSlot(order.getTurbineId(), 1L);
        if (active.isPresent()) {
            throw new BizException("机位 " + order.getUnitCode() + " 已有一张尚未恢复的隔离票 "
                    + active.get().getTicketNo() + "，必须恢复后才能再开新票");
        }
        if ("CLOSED".equals(order.getStatus())) {
            throw new BizException("工单 " + order.getOrderNo() + " 已闭环，不能再开隔离票");
        }

        SafetyTicket ticket = new SafetyTicket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setWorkOrderId(order.getId());
        ticket.setOrderNo(order.getOrderNo());
        ticket.setTurbineId(order.getTurbineId());
        ticket.setFarmId(order.getFarmId());
        ticket.setUnitCode(order.getUnitCode());
        ticket.setMaintType(order.getMaintType());
        ticket.setStatus("DRAFT");
        ticket.setActiveSlot(1L);
        ticket.setOwner(request.getOwner());
        ticket.setNote(request.getNote());
        ZonedDateTime now = ZonedDateTime.now(zoneOf(order.getFarmId()));
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticket.setTemplateAckFp("");

        // 深拷贝模板快照
        List<IsolationTemplate> tpls = templateRepository
                .findByMaintTypeAndEnabledTrueOrderByPointSeqAsc(order.getMaintType());
        int maxVersion = 1;
        try {
            ticket = ticketRepository.save(ticket);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("机位 " + order.getUnitCode() + " 已有一张尚未恢复的隔离票，请刷新后重试");
        }
        List<IsolationPoint> points = new ArrayList<>();
        for (IsolationTemplate tpl : tpls) {
            IsolationPoint p = new IsolationPoint();
            p.setTicketId(ticket.getId());
            p.setPointSeq(tpl.getPointSeq());
            p.setPointName(tpl.getPointName());
            p.setPointType(tpl.getPointType());
            p.setStandard(tpl.getStandard());
            p.setSource("TEMPLATE");
            p.setTemplateId(tpl.getId());
            p.setSnapshotVersion(tpl.getVersion());
            p.setIsolated(false);
            p.setValidFlag(true);
            p.setRestored(false);
            points.add(p);
            maxVersion = Math.max(maxVersion, tpl.getVersion() == null ? 1 : tpl.getVersion());
        }
        pointRepository.saveAll(points);
        ticket.setTemplateVersion(maxVersion);
        ticket.setTemplateAckFp(currentFingerprint(order.getMaintType()));
        saveTicket(ticket);
        writeLog(ticket, null, "CREATE", null, "DRAFT", request.getOwner(),
                "创建隔离票，按「" + Dict.maintTypeName(order.getMaintType()) + "」模板带入 "
                        + points.size() + " 个隔离点（模板快照，后续模板调整不影响本票）");
        return ticket;
    }

    @Transactional
    public void deleteTicket(Long id) {
        SafetyTicket ticket = getTicket(id);
        if (!"DRAFT".equals(ticket.getStatus())) {
            throw new BizException("隔离票 " + ticket.getTicketNo() + " 已开始隔离确认，不能删除（作废须走完恢复流程留痕）");
        }
        pointRepository.deleteAll(pointRepository.findByTicketIdOrderByPointSeqAsc(id));
        logRepository.deleteAll(logRepository.findByTicketIdOrderByIdAsc(id));
        ticketRepository.delete(ticket);
    }

    /** 开始隔离确认：草稿 → 确认中；机位若仍在并网，自动转检修（隔离动作开始，不能再登记发电读数） */
    @Transactional
    public SafetyTicket startConfirm(Long id) {
        SafetyTicket ticket = getTicket(id);
        if (!"DRAFT".equals(ticket.getStatus())) {
            return ticket; // 幂等：重复点击 / 网络重试直接返回当前状态
        }
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(id);
        if (points.isEmpty()) {
            throw new BizException("隔离票还没有任何隔离点，请从模板补入或人工补充后再开始确认");
        }
        assertNoPendingTemplateRisk(ticket, points, "开始隔离确认");

        ticket.setStatus("CONFIRMING");
        ticket.setUpdatedAt(ZonedDateTime.now(zoneOf(ticket.getFarmId())));
        saveTicket(ticket);
        // 联动机位：并网中自动转检修；故障 / 停运保持原状，仅被隔离锁锁定
        Turbine turbine = turbineRepository.findById(ticket.getTurbineId()).orElse(null);
        boolean wasOnline = turbine != null && "ONLINE".equals(turbine.getStatus());
        if (wasOnline) {
            turbine.setStatus("MAINTAIN");
            turbine.setLastPowerKw(java.math.BigDecimal.ZERO);
            turbineRepository.save(turbine);
        }
        writeLog(ticket, null, "START_CONFIRM", "DRAFT", "CONFIRMING", ticket.getOwner(),
                "开始安全隔离确认，执行停机、停电、验电、机械锁定等隔离措施"
                        + (wasOnline ? "，机位由并网转入检修状态"
                                : "，机位维持「" + (turbine == null ? "—"
                                        : Dict.turbineStatusName(turbine.getStatus())) + "」并施加隔离锁定"));
        return ticket;
    }

    /**
     * 单个隔离点的隔离确认（独立事务，互不影响）：双人（操作人 / 监护人不得为同一人）。
     * 走条件 UPDATE，重复点击 / 两人同时确认 / 网络重试只生效一次；已完成步骤天然保留。
     */
    @Transactional
    public IsolationPoint confirmPoint(Long pointId, IsolationConfirmRequest request) {
        IsolationPoint point = getPoint(pointId);
        SafetyTicket ticket = getTicket(point.getTicketId());
        if (!"CONFIRMING".equals(ticket.getStatus())) {
            throw new BizException("隔离票当前为「" + Dict.ticketStatusName(ticket.getStatus())
                    + "」，只有确认中才能逐项确认隔离点");
        }
        if (!StringUtils.hasText(request.getOperator()) || !StringUtils.hasText(request.getVerifier())) {
            throw new BizException("隔离确认必须填写操作人和监护人（双人确认）");
        }
        if (request.getOperator().trim().equals(request.getVerifier().trim())) {
            throw new BizException("操作人与监护人不能为同一人，隔离确认必须双人执行");
        }
        ZoneId zone = zoneOf(ticket.getFarmId());
        int rows = pointRepository.confirmIsolation(pointId, ticket.getId(),
                request.getOperator().trim(), request.getVerifier().trim(),
                ZonedDateTime.now(zone), zone.getId());
        if (rows == 0) {
            // 幂等：已经被确认（且有效）——回显既有确认人，不产生第二条记录
            IsolationPoint now = getPoint(pointId);
            if (Boolean.TRUE.equals(now.getIsolated()) && Boolean.TRUE.equals(now.getValidFlag())) {
                throw new BizException("隔离点「" + now.getPointName() + "」已由 " + now.getIsolateOperator()
                        + " / 监护人 " + now.getIsolateVerifier() + " 确认，请勿重复提交");
            }
            throw new BizException("隔离点「" + now.getPointName() + "」当前状态已变化，请刷新后重试");
        }
        point = getPoint(pointId);
        writeLog(ticket, point.getId(), "ISOLATE_POINT", null, null, request.getOperator(),
                point.getPointSeq() + ". " + point.getPointName() + " 已隔离确认（监护人："
                        + request.getVerifier() + "）");
        return point;
    }

    /**
     * 复查发现隔离失效（挂锁脱落 / 验电异常等）：该点必须重新双人确认。
     * 票在已隔离 / 复电中时整体退回确认中，暂停现场作业（工单退回待派）、验收结论作废；
     * 复电中已解除的点一律重新置为未复电、需要重新双人确认——保证再次隔离后复电仍能严格按逆序执行，
     * 机位绝不允许在措施不完整时提前进入可并网状态。
     */
    @Transactional
    public IsolationPoint markInvalid(Long pointId, String operator, String reason) {
        IsolationPoint point = getPoint(pointId);
        SafetyTicket ticket = getTicket(point.getTicketId());
        if ("DRAFT".equals(ticket.getStatus()) || "RESTORED".equals(ticket.getStatus())) {
            throw new BizException("隔离票为「" + Dict.ticketStatusName(ticket.getStatus()) + "」，不能标记隔离失效");
        }
        int rows = pointRepository.markInvalid(pointId, ticket.getId());
        if (rows == 0) {
            throw new BizException("隔离点「" + point.getPointName() + "」未确认或已被标记失效，请勿重复操作");
        }
        point = getPoint(pointId);
        String who = StringUtils.hasText(operator) ? operator : ticket.getOwner();
        writeLog(ticket, point.getId(), "POINT_INVALID", null, null, who,
                point.getPointSeq() + ". " + point.getPointName() + " 隔离被判定失效："
                        + (StringUtils.hasText(reason) ? reason : "复查异常") + "，必须重新双人确认");
        // 已隔离 / 复电中发现失效：退回确认中，并暂停现场作业（工单退回待派），验收结论作废
        if ("ISOLATED".equals(ticket.getStatus()) || "RESTORING".equals(ticket.getStatus())) {
            String from = ticket.getStatus();
            // 复电中：此前已按逆序解除的点全部重新隔离；同时把本次失效点一并置为待重新确认。
            // 注意 point 实体是条件 UPDATE 之前读取的，validFlag 仍可能是内存旧值 true，
            // 因此用「已复电 或 即本次失效点 或 明确失效」三个条件并集判断。
            int reopened = 0;
            if ("RESTORING".equals(ticket.getStatus())) {
                for (IsolationPoint p : pointRepository.findByTicketIdOrderByPointSeqAsc(ticket.getId())) {
                    boolean needReconfirm = Boolean.TRUE.equals(p.getRestored())
                            || p.getId().equals(pointId)
                            || Boolean.FALSE.equals(p.getValidFlag());
                    if (needReconfirm) {
                        p.setIsolated(false);
                        p.setValidFlag(true);
                        p.setIsolateOperator(null);
                        p.setIsolateVerifier(null);
                        p.setIsolateTime(null);
                        p.setIsolateZone(null);
                        p.setRestored(false);
                        p.setRestoreOperator(null);
                        p.setRestoreTime(null);
                        p.setRestoreZone(null);
                        pointRepository.save(p);
                        reopened++;
                    }
                }
            }
            ticket.setStatus("CONFIRMING");
            ticket.setIsolatedTime(null);
            ticket.setIsolatedZone(null);
            ticket.setUpdatedAt(ZonedDateTime.now(zoneOf(ticket.getFarmId())));
            saveTicket(ticket);
            writeLog(ticket, null, "REOPEN_CONFIRM", from, "CONFIRMING", ticket.getOwner(),
                    "因隔离点失效，隔离票退回确认中，现场作业必须暂停"
                            + (reopened > 0 ? "；复电中已解除的 " + reopened + " 个点已全部重新隔离，须逐项重新双人确认" : "")
                            + "，重新确认全部隔离措施后方可再隔离");
            orderRepository.findById(ticket.getWorkOrderId()).ifPresent(order -> {
                if ("WORKING".equals(order.getStatus()) || "CHECKING".equals(order.getStatus())) {
                    order.setStatus("PENDING");
                    order.setAcceptResult(null);
                    order.setAcceptPerson(null);
                    order.setAcceptTime(null);
                    order.setAcceptZone(null);
                    orderRepository.save(order);
                }
            });
        }
        return point;
    }

    /** 全部隔离点确认完成：确认中 → 已隔离（隔离生效，工单才允许进入作业中） */
    @Transactional
    public SafetyTicket completeIsolation(Long id) {
        SafetyTicket ticket = getTicket(id);
        if ("ISOLATED".equals(ticket.getStatus()) || "RESTORING".equals(ticket.getStatus())
                || "RESTORED".equals(ticket.getStatus())) {
            return ticket; // 幂等
        }
        if (!"CONFIRMING".equals(ticket.getStatus())) {
            throw new BizException("只有确认中的隔离票才能完成隔离，" + Dict.ticketFlowHint(ticket.getStatus()));
        }
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(id);
        List<String> blockers = isolationBlockers(ticket, points);
        if (!blockers.isEmpty()) {
            throw new BizException("还不能完成隔离：\n" + String.join("\n", blockers));
        }
        ZoneId zone = zoneOf(ticket.getFarmId());
        ticket.setStatus("ISOLATED");
        ticket.setIsolatedTime(ZonedDateTime.now(zone));
        ticket.setIsolatedZone(zone.getId());
        ticket.setUpdatedAt(ZonedDateTime.now(zone));
        saveTicket(ticket);
        writeLog(ticket, null, "ISOLATED", "CONFIRMING", "ISOLATED", ticket.getOwner(),
                "全部 " + points.size() + " 个隔离点双人确认完成，隔离正式生效，机位禁止并网，工单可进入作业中");
        return ticket;
    }

    /** 开始复电：已隔离 → 复电中。前置：工单已验收合格（未验收 / 验收驳回一律阻止） */
    @Transactional
    public SafetyTicket startRestore(Long id) {
        SafetyTicket ticket = getTicket(id);
        if ("RESTORING".equals(ticket.getStatus()) || "RESTORED".equals(ticket.getStatus())) {
            return ticket; // 幂等
        }
        if (!"ISOLATED".equals(ticket.getStatus())) {
            throw new BizException("只有已隔离的票才能开始复电，" + Dict.ticketFlowHint(ticket.getStatus()));
        }
        WorkOrder order = orderRepository.findById(ticket.getWorkOrderId())
                .orElseThrow(() -> new BizException("关联工单不存在"));
        if (!"PASS".equals(order.getAcceptResult())) {
            throw new BizException("工单 " + order.getOrderNo() + " 尚未验收合格，不允许开始复电；"
                    + "请先在工单上登记验收结论（验收合格）");
        }
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(id);
        for (IsolationPoint p : points) {
            if (!Boolean.TRUE.equals(p.getIsolated()) || !Boolean.TRUE.equals(p.getValidFlag())) {
                throw new BizException("隔离点「" + p.getPointName() + "」未处于有效隔离状态，不能开始复电");
            }
        }
        ticket.setStatus("RESTORING");
        ticket.setUpdatedAt(ZonedDateTime.now(zoneOf(ticket.getFarmId())));
        saveTicket(ticket);
        writeLog(ticket, null, "START_RESTORE", "ISOLATED", "RESTORING", ticket.getOwner(),
                "工单已由 " + order.getAcceptPerson() + " 验收合格，开始按隔离顺序的逆序逐项复电解除");
        return ticket;
    }

    /**
     * 单个隔离点复电解除（独立事务）：严格按隔离顺序逆序，只能解除当前最高序号的未复电点，跳项阻止。
     * 条件 UPDATE 保证重复点击 / 并发重试只生效一次，已解除的步骤保留。
     */
    @Transactional
    public IsolationPoint restorePoint(Long pointId, IsolationConfirmRequest request) {
        IsolationPoint point = getPoint(pointId);
        SafetyTicket ticket = getTicket(point.getTicketId());
        if (!"RESTORING".equals(ticket.getStatus())) {
            throw new BizException("隔离票当前为「" + Dict.ticketStatusName(ticket.getStatus())
                    + "」，只有复电中才能逐项解除隔离");
        }
        if (!StringUtils.hasText(request.getOperator())) {
            throw new BizException("请填写复电确认人");
        }
        // 已解除的点重复提交：幂等提示优先（网络重试 / 双击），不产生第二次推进
        if (Boolean.TRUE.equals(point.getRestored())) {
            throw new BizException("隔离点「" + point.getPointName() + "」已由 " + point.getRestoreOperator()
                    + " 解除，请勿重复提交");
        }
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(ticket.getId());
        // 逆序校验：找到尚未复电的最高序号点，它是唯一当前可解除的点
        IsolationPoint expected = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (!Boolean.TRUE.equals(points.get(i).getRestored())) {
                expected = points.get(i);
                break;
            }
        }
        if (expected == null) {
            throw new BizException("全部隔离点均已解除，请直接完成恢复");
        }
        if (!expected.getId().equals(pointId)) {
            throw new BizException("复电必须按隔离顺序的逆序逐项解除：当前应先解除第 " + expected.getPointSeq()
                    + " 项「" + expected.getPointName() + "」，不能跳过它解除第 " + point.getPointSeq() + " 项");
        }
        if (!Boolean.TRUE.equals(point.getValidFlag())) {
            throw new BizException("隔离点「" + point.getPointName() + "」确认已失效，须重新隔离确认后才能解除");
        }
        ZoneId zone = zoneOf(ticket.getFarmId());
        int rows = pointRepository.confirmRestore(pointId, ticket.getId(), request.getOperator().trim(),
                ZonedDateTime.now(zone), zone.getId());
        if (rows == 0) {
            IsolationPoint now = getPoint(pointId);
            if (Boolean.TRUE.equals(now.getRestored())) {
                throw new BizException("隔离点「" + now.getPointName() + "」已由 " + now.getRestoreOperator()
                        + " 解除，请勿重复提交");
            }
            throw new BizException("隔离点「" + now.getPointName() + "」状态已变化，请刷新后重试");
        }
        point = getPoint(pointId);
        writeLog(ticket, point.getId(), "RESTORE_POINT", null, null, request.getOperator(),
                "按逆序解除第 " + point.getPointSeq() + " 项「" + point.getPointName() + "」");
        return point;
    }

    /** 全部逆序解除完成：复电中 → 已恢复。机位不自动并网，须人工核对后手动恢复并网 */
    @Transactional
    public SafetyTicket completeRestore(Long id) {
        SafetyTicket ticket = getTicket(id);
        if ("RESTORED".equals(ticket.getStatus())) {
            return ticket; // 幂等
        }
        if (!"RESTORING".equals(ticket.getStatus())) {
            throw new BizException("只有复电中的票才能完成恢复，" + Dict.ticketFlowHint(ticket.getStatus()));
        }
        WorkOrder order = orderRepository.findById(ticket.getWorkOrderId()).orElse(null);
        if (order == null || !"PASS".equals(order.getAcceptResult())) {
            throw new BizException("工单尚未验收合格，不能完成最终恢复");
        }
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(id);
        List<IsolationPoint> notRestored = points.stream()
                .filter(p -> !Boolean.TRUE.equals(p.getRestored())).collect(Collectors.toList());
        if (!notRestored.isEmpty()) {
            String remain = notRestored.stream()
                    .map(p -> "第 " + p.getPointSeq() + " 项「" + p.getPointName() + "」")
                    .collect(Collectors.joining("、"));
            throw new BizException("还有 " + notRestored.size() + " 个隔离点未按逆序解除（" + remain
                    + "），全部解除后才能完成恢复；机位仍保持禁止并网");
        }
        boolean anyInvalid = points.stream().anyMatch(p -> !Boolean.TRUE.equals(p.getValidFlag()));
        if (anyInvalid) {
            throw new BizException("存在失效的隔离确认，须重新确认并解除后才能完成恢复");
        }
        ZoneId zone = zoneOf(ticket.getFarmId());
        ticket.setStatus("RESTORED");
        ticket.setActiveSlot(null); // 释放机位活动占位；此后机位才允许人工恢复并网
        ticket.setRestoredTime(ZonedDateTime.now(zone));
        ticket.setRestoredZone(zone.getId());
        ticket.setUpdatedAt(ZonedDateTime.now(zone));
        saveTicket(ticket);
        writeLog(ticket, null, "RESTORED", "RESTORING", "RESTORED", ticket.getOwner(),
                "全部隔离点按逆序解除完毕，隔离恢复完成；机位经人工核对后方可恢复并网");
        return ticket;
    }

    // ================= 票内隔离点维护 =================

    /** 人工补充隔离点（针对本次工单）：草稿可任意补；确认中只允许追加（不改变已确认顺序的语义） */
    @Transactional
    public IsolationPoint addPoint(IsolationPointRequest request) {
        if (request.getTicketId() == null) {
            throw new BizException("缺少隔离票 id");
        }
        SafetyTicket ticket = getTicket(request.getTicketId());
        if ("RESTORING".equals(ticket.getStatus()) || "RESTORED".equals(ticket.getStatus())) {
            throw new BizException("隔离票已进入复电 / 已恢复，不能再补充隔离点");
        }
        if (!StringUtils.hasText(request.getPointName())) {
            throw new BizException("隔离点名称不能为空");
        }
        if (request.getPointType() != null && !Dict.isValidIsolationType(request.getPointType())) {
            throw new BizException("隔离手段非法：" + request.getPointType());
        }
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(ticket.getId());
        int seq = request.getPointSeq() == null
                ? points.stream().mapToInt(IsolationPoint::getPointSeq).max().orElse(0) + 1
                : request.getPointSeq();
        if (points.stream().anyMatch(p -> p.getPointSeq().equals(seq))) {
            throw new BizException("序号 " + seq + " 已存在，请换一个隔离顺序");
        }
        // 已开始确认后追加的点排在队尾之外还必须显式按序确认；禁止插到未确认点中间打乱逆序复电
        if (request.getPointSeq() != null && "CONFIRMING".equals(ticket.getStatus())
                && points.stream().anyMatch(p -> p.getPointSeq() > seq && Boolean.TRUE.equals(p.getIsolated()))) {
            throw new BizException("确认中补充的隔离点只能追加到队尾，不能排在已确认点之前");
        }
        IsolationPoint p = new IsolationPoint();
        p.setTicketId(ticket.getId());
        p.setPointSeq(seq);
        p.setPointName(request.getPointName());
        p.setPointType(request.getPointType());
        p.setStandard(request.getStandard());
        p.setSource("MANUAL");
        p.setSnapshotVersion(0);
        p.setIsolated(false);
        p.setValidFlag(true);
        p.setRestored(false);
        p.setNote(request.getNote());
        IsolationPoint saved = pointRepository.save(p);
        writeLog(ticket, saved.getId(), "ADD_POINT", null, null, ticket.getOwner(),
                "针对本次工单人工补充隔离点：" + seq + ". " + saved.getPointName());
        return saved;
    }

    @Transactional
    public IsolationPoint updatePoint(Long id, IsolationPointRequest request) {
        IsolationPoint p = getPoint(id);
        SafetyTicket ticket = getTicket(p.getTicketId());
        if (!"DRAFT".equals(ticket.getStatus())) {
            throw new BizException("隔离票已开始确认，票内隔离点内容不可修改（确认内容须可追溯）；"
                    + "如措施不足请追加新隔离点");
        }
        if (request.getPointName() != null) {
            if (!StringUtils.hasText(request.getPointName())) {
                throw new BizException("隔离点名称不能为空");
            }
            p.setPointName(request.getPointName());
        }
        if (request.getPointType() != null) {
            if (!Dict.isValidIsolationType(request.getPointType())) {
                throw new BizException("隔离手段非法：" + request.getPointType());
            }
            p.setPointType(request.getPointType());
        }
        if (request.getStandard() != null) {
            p.setStandard(request.getStandard());
        }
        if (request.getNote() != null) {
            p.setNote(request.getNote());
        }
        return pointRepository.save(p);
    }

    @Transactional
    public void removePoint(Long id) {
        IsolationPoint p = getPoint(id);
        SafetyTicket ticket = getTicket(p.getTicketId());
        if (!"DRAFT".equals(ticket.getStatus())) {
            throw new BizException("隔离票已开始确认，隔离点不能删除（历史确认须可追溯）");
        }
        pointRepository.delete(p);
    }

    // ================= 模板差异处理（新增风险不被旧数据掩盖） =================

    /**
     * 处理建票后模板新增的风险点：
     * IMPORT 把全部新增模板点补入本票并重新确认；IGNORE 由责任人逐条留痕忽略（记录处理人与时间）。
     * 之后若模板再次新增点，会重新出现在待处理列表里——一次忽略不能掩盖未来的新风险。
     */
    @Transactional
    public SafetyTicket acknowledgeTemplateRisk(Long ticketId, String action, String operator) {
        SafetyTicket ticket = getTicket(ticketId);
        if ("RESTORING".equals(ticket.getStatus()) || "RESTORED".equals(ticket.getStatus())) {
            throw new BizException("隔离票已进入复电 / 已恢复，模板差异只作存档提示");
        }
        if (!"IMPORT".equals(action) && !"IGNORE".equals(action)) {
            throw new BizException("处理方式只能为 IMPORT（补入本票）或 IGNORE（留痕忽略）");
        }
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(ticketId);
        List<IsolationTemplate> pending = pendingNewTemplates(ticket, points);
        if (pending.isEmpty()) {
            throw new BizException("当前没有待处理的模板新增风险点");
        }
        String who = StringUtils.hasText(operator) ? operator : ticket.getOwner();
        if ("IMPORT".equals(action)) {
            int maxSeq = points.stream().mapToInt(IsolationPoint::getPointSeq).max().orElse(0);
            List<IsolationPoint> added = new ArrayList<>();
            for (IsolationTemplate tpl : pending) {
                IsolationPoint p = new IsolationPoint();
                p.setTicketId(ticket.getId());
                p.setPointSeq(++maxSeq);
                p.setPointName(tpl.getPointName());
                p.setPointType(tpl.getPointType());
                p.setStandard(tpl.getStandard());
                p.setSource("TEMPLATE");
                p.setTemplateId(tpl.getId());
                p.setSnapshotVersion(tpl.getVersion());
                p.setIsolated(false);
                p.setValidFlag(true);
                p.setRestored(false);
                added.add(pointRepository.save(p));
            }
            ticket.setTemplateAckFp(currentFingerprint(ticket.getMaintType()));
            saveTicket(ticket);
            for (IsolationPoint p : added) {
                writeLog(ticket, p.getId(), "TPL_IMPORT", null, null, who,
                        "模板新增风险点已补入本票（追加到队尾，须按序双人确认）："
                                + p.getPointSeq() + ". " + p.getPointName());
            }
        } else {
            ticket.setTemplateAckFp(currentFingerprint(ticket.getMaintType()));
            saveTicket(ticket);
            String names = pending.stream()
                    .map(t -> t.getPointSeq() + ". " + t.getPointName()).collect(Collectors.joining("、"));
            writeLog(ticket, null, "TPL_ACK", null, null, who,
                    "责任人对模板新增风险点书面确认本次不适用、留痕忽略：" + names
                            + "（若模板后续再新增点，仍会重新提示）");
        }
        return ticket;
    }

    // ================= 查询与 VO =================

    public SafetyTicket getTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new BizException("安全隔离票不存在：" + id));
    }

    public SafetyTicket getByOrder(Long workOrderId) {
        return ticketRepository.findByWorkOrderId(workOrderId).orElse(null);
    }

    /** 机位当前尚未恢复的票（无则 null） */
    public SafetyTicket activeTicketOfTurbine(Long turbineId) {
        return ticketRepository.findByTurbineIdAndActiveSlot(turbineId, 1L).orElse(null);
    }

    public List<SafetyTicket> list(Long turbineId, Long farmId) {
        if (turbineId != null) {
            return ticketRepository.findByTurbineIdOrderByTicketNoAsc(turbineId);
        }
        if (farmId != null) {
            return ticketRepository.findByFarmIdOrderByTicketNoAsc(farmId);
        }
        return ticketRepository.findAllByOrderByTicketNoAsc();
    }

    public SafetyTicketVO getVO(Long id) {
        return toVO(getTicket(id));
    }

    public SafetyTicketVO toVO(SafetyTicket ticket) {
        SafetyTicketVO vo = new SafetyTicketVO();
        vo.setId(ticket.getId());
        vo.setTicketNo(ticket.getTicketNo());
        vo.setWorkOrderId(ticket.getWorkOrderId());
        vo.setOrderNo(ticket.getOrderNo());
        vo.setTurbineId(ticket.getTurbineId());
        vo.setFarmId(ticket.getFarmId());
        WindFarm farm = farmRepository.findById(ticket.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        vo.setFarmName(farm == null ? null : farm.getFarmName());
        vo.setUnitCode(ticket.getUnitCode());
        vo.setMaintType(ticket.getMaintType());
        vo.setMaintTypeName(Dict.maintTypeName(ticket.getMaintType()));
        vo.setStatus(ticket.getStatus());
        vo.setStatusName(Dict.ticketStatusName(ticket.getStatus()));
        vo.setStatusColor(Dict.ticketStatusColor(ticket.getStatus()));
        String next = Dict.nextTicketStatus(ticket.getStatus());
        vo.setNextStatus(next);
        vo.setNextStatusName(next == null ? null : Dict.ticketStatusName(next));
        vo.setOwner(ticket.getOwner());
        vo.setTemplateVersion(ticket.getTemplateVersion());
        vo.setIsolatedTime(TimeText.iso(ticket.getIsolatedTime()));
        vo.setIsolatedTz(ticket.getIsolatedZone());
        vo.setIsolatedLocal(TimeText.local(ticket.getIsolatedTime(),
                TimeText.pickZone(ticket.getIsolatedZone(), zone)));
        vo.setRestoredTime(TimeText.iso(ticket.getRestoredTime()));
        vo.setRestoredTz(ticket.getRestoredZone());
        vo.setRestoredLocal(TimeText.local(ticket.getRestoredTime(),
                TimeText.pickZone(ticket.getRestoredZone(), zone)));
        vo.setNote(ticket.getNote());

        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(ticket.getId());
        java.util.Map<Long, IsolationTemplate> tplMap = templateRepository.findAll().stream()
                .collect(Collectors.toMap(IsolationTemplate::getId, t -> t, (a, b) -> a));
        int isolated = 0;
        int restored = 0;
        int invalid = 0;
        // 当前可复电点（严格逆序下的最高序号未复电点）
        Long canRestoreId = null;
        if ("RESTORING".equals(ticket.getStatus())) {
            for (int i = points.size() - 1; i >= 0; i--) {
                if (!Boolean.TRUE.equals(points.get(i).getRestored())) {
                    canRestoreId = points.get(i).getId();
                    break;
                }
            }
        }
        for (IsolationPoint p : points) {
            IsolationPointVO pvo = toPointVO(p, zone);
            pvo.setCanRestoreNow(canRestoreId != null && canRestoreId.equals(p.getId()));
            IsolationTemplate tpl = tplMap.get(p.getTemplateId());
            if (tpl != null && Boolean.TRUE.equals(tpl.getEnabled())
                    && tpl.getVersion() != null && p.getSnapshotVersion() != null
                    && tpl.getVersion() > p.getSnapshotVersion()) {
                pvo.setTemplateChanged(true);
                pvo.setTemplateLatestVersion(tpl.getVersion());
            }
            vo.getPoints().add(pvo);
            if (Boolean.TRUE.equals(p.getIsolated()) && Boolean.TRUE.equals(p.getValidFlag())) {
                isolated++;
            }
            if (Boolean.TRUE.equals(p.getRestored())) {
                restored++;
            }
            if (Boolean.FALSE.equals(p.getValidFlag())) {
                invalid++;
            }
        }
        vo.setPointTotal(points.size());
        vo.setIsolatedCount(isolated);
        vo.setRestoredCount(restored);
        vo.setInvalidCount(invalid);

        List<IsolationTemplate> pendingNew = pendingNewTemplates(ticket, points);
        for (IsolationTemplate tpl : pendingNew) {
            vo.getNewTemplatePoints().add(toTemplateVO(tpl));
        }
        int changed = 0;
        for (IsolationPoint p : points) {
            IsolationTemplate tpl = tplMap.get(p.getTemplateId());
            if (tpl != null && Boolean.TRUE.equals(tpl.getEnabled())
                    && tpl.getVersion() != null && p.getSnapshotVersion() != null
                    && tpl.getVersion() > p.getSnapshotVersion()) {
                changed++;
            }
        }
        vo.setChangedTemplateCount(changed);

        WorkOrder order = orderRepository.findById(ticket.getWorkOrderId()).orElse(null);
        if (order != null) {
            vo.setOrderTitle(order.getTitle());
            vo.setOrderStatus(order.getStatus());
            vo.setOrderStatusName(Dict.workOrderStatusName(order.getStatus()));
            vo.setAcceptResult(order.getAcceptResult());
            vo.setAcceptResultName(Dict.acceptResultName(order.getAcceptResult()));
            vo.setAcceptPerson(order.getAcceptPerson());
        }

        vo.setBlockers(currentBlockers(ticket, points, order, pendingNew));

        List<IsolationLogVO> logs = new ArrayList<>();
        for (IsolationLog log : logRepository.findByTicketIdOrderByIdAsc(ticket.getId())) {
            logs.add(toLogVO(log, zone));
        }
        vo.setLogs(logs);
        return vo;
    }

    public TicketBriefVO briefOf(SafetyTicket ticket) {
        if (ticket == null) {
            return null;
        }
        TicketBriefVO vo = new TicketBriefVO();
        vo.setId(ticket.getId());
        vo.setTicketNo(ticket.getTicketNo());
        vo.setWorkOrderId(ticket.getWorkOrderId());
        vo.setOrderNo(ticket.getOrderNo());
        vo.setTurbineId(ticket.getTurbineId());
        vo.setUnitCode(ticket.getUnitCode());
        vo.setMaintType(ticket.getMaintType());
        vo.setStatus(ticket.getStatus());
        vo.setStatusName(Dict.ticketStatusName(ticket.getStatus()));
        vo.setStatusColor(Dict.ticketStatusColor(ticket.getStatus()));
        String next = Dict.nextTicketStatus(ticket.getStatus());
        vo.setNextStatus(next);
        vo.setNextStatusName(next == null ? null : Dict.ticketStatusName(next));
        vo.setOwner(ticket.getOwner());
        WindFarm farm = farmRepository.findById(ticket.getFarmId()).orElse(null);
        String zone = farm == null ? null : farm.getTimeZone();
        vo.setIsolatedLocal(TimeText.local(ticket.getIsolatedTime(),
                TimeText.pickZone(ticket.getIsolatedZone(), zone)));
        vo.setRestoredLocal(TimeText.local(ticket.getRestoredTime(),
                TimeText.pickZone(ticket.getRestoredZone(), zone)));
        List<IsolationPoint> points = pointRepository.findByTicketIdOrderByPointSeqAsc(ticket.getId());
        int isolated = 0;
        int restored = 0;
        int invalid = 0;
        for (IsolationPoint p : points) {
            if (Boolean.TRUE.equals(p.getIsolated()) && Boolean.TRUE.equals(p.getValidFlag())) {
                isolated++;
            }
            if (Boolean.TRUE.equals(p.getRestored())) {
                restored++;
            }
            if (Boolean.FALSE.equals(p.getValidFlag())) {
                invalid++;
            }
        }
        vo.setPointTotal(points.size());
        vo.setIsolatedCount(isolated);
        vo.setRestoredCount(restored);
        vo.setInvalidCount(invalid);
        List<IsolationTemplate> pending = pendingNewTemplates(ticket, points);
        vo.setTemplatePendingCount(pending.size());
        WorkOrder order = orderRepository.findById(ticket.getWorkOrderId()).orElse(null);
        vo.setBlockers(currentBlockers(ticket, points, order, pending));
        return vo;
    }

    public TemplateVO toTemplateVO(IsolationTemplate tpl) {
        TemplateVO vo = new TemplateVO();
        vo.setId(tpl.getId());
        vo.setMaintType(tpl.getMaintType());
        vo.setMaintTypeName(Dict.maintTypeName(tpl.getMaintType()));
        vo.setPointSeq(tpl.getPointSeq());
        vo.setPointName(tpl.getPointName());
        vo.setPointType(tpl.getPointType());
        vo.setPointTypeName(Dict.isolationTypeName(tpl.getPointType()));
        vo.setStandard(tpl.getStandard());
        vo.setEnabled(tpl.getEnabled());
        vo.setVersion(tpl.getVersion());
        vo.setNote(tpl.getNote());
        return vo;
    }

    public IsolationPointVO toPointVO(IsolationPoint p, String fallbackZone) {
        IsolationPointVO vo = new IsolationPointVO();
        vo.setId(p.getId());
        vo.setTicketId(p.getTicketId());
        vo.setPointSeq(p.getPointSeq());
        vo.setPointName(p.getPointName());
        vo.setPointType(p.getPointType());
        vo.setPointTypeName(Dict.isolationTypeName(p.getPointType()));
        vo.setStandard(p.getStandard());
        vo.setSource(p.getSource());
        vo.setSourceName("MANUAL".equals(p.getSource()) ? "本次补充" : "模板快照");
        vo.setSnapshotVersion(p.getSnapshotVersion());
        vo.setIsolated(p.getIsolated());
        vo.setIsolateOperator(p.getIsolateOperator());
        vo.setIsolateVerifier(p.getIsolateVerifier());
        vo.setIsolateTime(TimeText.iso(p.getIsolateTime()));
        vo.setIsolateTz(p.getIsolateZone());
        vo.setIsolateLocal(TimeText.local(p.getIsolateTime(),
                TimeText.pickZone(p.getIsolateZone(), fallbackZone)));
        vo.setValidFlag(p.getValidFlag());
        vo.setRestored(p.getRestored());
        vo.setRestoreOperator(p.getRestoreOperator());
        vo.setRestoreTime(TimeText.iso(p.getRestoreTime()));
        vo.setRestoreTz(p.getRestoreZone());
        vo.setRestoreLocal(TimeText.local(p.getRestoreTime(),
                TimeText.pickZone(p.getRestoreZone(), fallbackZone)));
        vo.setNote(p.getNote());
        vo.setTemplateChanged(false);
        return vo;
    }

    public IsolationLogVO toLogVO(IsolationLog log, String fallbackZone) {
        IsolationLogVO vo = new IsolationLogVO();
        vo.setId(log.getId());
        vo.setTicketId(log.getTicketId());
        vo.setPointId(log.getPointId());
        vo.setAction(log.getAction());
        vo.setActionName(logActionName(log.getAction()));
        vo.setFromStatus(log.getFromStatus());
        vo.setFromStatusName(Dict.ticketStatusName(log.getFromStatus()));
        vo.setToStatus(log.getToStatus());
        vo.setToStatusName(Dict.ticketStatusName(log.getToStatus()));
        vo.setOperator(log.getOperator());
        vo.setDetail(log.getDetail());
        vo.setCreatedAt(TimeText.iso(log.getCreatedAt()));
        vo.setCreatedTz(log.getCreatedZone());
        vo.setCreatedLocal(TimeText.local(log.getCreatedAt(),
                TimeText.pickZone(log.getCreatedZone(), fallbackZone)));
        return vo;
    }

    // ================= 规则与阻塞原因 =================

    /** 完成隔离还差什么（给前端按钮旁 / 错误提示直接展示） */
    private List<String> isolationBlockers(SafetyTicket ticket, List<IsolationPoint> points) {
        List<String> blockers = new ArrayList<>();
        if (points.isEmpty()) {
            blockers.add("票内还没有隔离点，请从模板补入或人工补充");
        }
        for (IsolationPoint p : points) {
            if (!Boolean.TRUE.equals(p.getIsolated())) {
                blockers.add("第 " + p.getPointSeq() + " 项「" + p.getPointName() + "」尚未完成双人隔离确认");
            } else if (!Boolean.TRUE.equals(p.getValidFlag())) {
                blockers.add("第 " + p.getPointSeq() + " 项「" + p.getPointName() + "」隔离已失效，必须重新双人确认");
            }
        }
        assertNoPendingTemplateRisk(ticket, points, blockers, "完成隔离");
        return blockers;
    }

    /** 当前状态下推进 / 复电 / 恢复还差什么 */
    private List<String> currentBlockers(SafetyTicket ticket, List<IsolationPoint> points,
            WorkOrder order, List<IsolationTemplate> pendingNew) {
        List<String> blockers = new ArrayList<>();
        switch (ticket.getStatus()) {
            case "DRAFT":
                if (points.isEmpty()) {
                    blockers.add("票内还没有隔离点");
                }
                if (!pendingNew.isEmpty()) {
                    blockers.add("模板新增了 " + pendingNew.size()
                            + " 个风险点，开始确认前须「补入本票」或由责任人「留痕忽略」");
                }
                blockers.add("待办：责任人开始隔离确认（机位将转入检修）");
                break;
            case "CONFIRMING":
                blockers.addAll(isolationBlockers(ticket, points));
                if (blockers.stream().noneMatch(s -> s.contains("尚未完成双人隔离确认"))) {
                    blockers.add("全部确认后执行「完成隔离」，工单方可进入作业中");
                }
                break;
            case "ISOLATED":
                if (order == null) {
                    blockers.add("关联工单缺失");
                } else if (!"PASS".equals(order.getAcceptResult())) {
                    blockers.add("工单尚未验收合格：作业完成提交验收并由验收人登记「验收合格」后才能开始复电");
                } else {
                    blockers.add("已验收合格，可开始按逆序复电");
                }
                break;
            case "RESTORING": {
                if (order != null && !"PASS".equals(order.getAcceptResult())) {
                    blockers.add("工单验收结论失效，不能继续复电");
                }
                List<IsolationPoint> remain = new ArrayList<>();
                for (IsolationPoint p : points) {
                    if (!Boolean.TRUE.equals(p.getRestored())) {
                        remain.add(p);
                    }
                    if (Boolean.FALSE.equals(p.getValidFlag())) {
                        blockers.add("第 " + p.getPointSeq() + " 项隔离确认失效，须重新确认");
                    }
                }
                if (!remain.isEmpty()) {
                    IsolationPoint next = remain.get(remain.size() - 1);
                    blockers.add("复电待办：按逆序下一项应解除第 " + next.getPointSeq()
                            + " 项「" + next.getPointName() + "」（共剩 " + remain.size() + " 项，禁止跳项）");
                } else {
                    blockers.add("全部隔离点已解除，执行「完成恢复」后机位方可人工并网");
                }
                break;
            }
            case "RESTORED":
                blockers.add("隔离已恢复；如检修全部结束，可将机位人工恢复并网并闭环工单");
                break;
            default:
                break;
        }
        return blockers;
    }

    private void assertNoPendingTemplateRisk(SafetyTicket ticket, List<IsolationPoint> points, String action) {
        List<String> blockers = new ArrayList<>();
        assertNoPendingTemplateRisk(ticket, points, blockers, action);
        if (!blockers.isEmpty()) {
            throw new BizException(String.join("\n", blockers));
        }
    }

    private void assertNoPendingTemplateRisk(SafetyTicket ticket, List<IsolationPoint> points,
            List<String> blockers, String action) {
        List<IsolationTemplate> pending = pendingNewTemplates(ticket, points);
        if (!pending.isEmpty()) {
            String names = pending.stream()
                    .map(t -> "第 " + t.getPointSeq() + " 项「" + t.getPointName() + "」")
                    .collect(Collectors.joining("、"));
            blockers.add("模板在建票后新增了 " + pending.size() + " 个风险点（" + names + "），"
                    + action + "前必须「补入本票」逐项确认，或由责任人「留痕忽略」，不得被旧快照静默掩盖");
        }
    }

    /** 建票后模板新增、且尚未在本票处理（补入或留痕）的启用模板点 */
    private List<IsolationTemplate> pendingNewTemplates(SafetyTicket ticket, List<IsolationPoint> points) {
        Set<Long> linked = new HashSet<>();
        for (IsolationPoint p : points) {
            if (p.getTemplateId() != null) {
                linked.add(p.getTemplateId());
            }
        }
        Set<String> ack = parseFingerprint(ticket.getTemplateAckFp());
        List<IsolationTemplate> result = new ArrayList<>();
        for (IsolationTemplate tpl : templateRepository
                .findByMaintTypeAndEnabledTrueOrderByPointSeqAsc(ticket.getMaintType())) {
            if (linked.contains(tpl.getId())) {
                continue;
            }
            if (!ack.contains(tpl.getId() + ":" + tpl.getVersion())) {
                result.add(tpl);
            }
        }
        return result;
    }

    private String currentFingerprint(String maintType) {
        return templateRepository.findByMaintTypeAndEnabledTrueOrderByPointSeqAsc(maintType).stream()
                .map(t -> t.getId() + ":" + t.getVersion())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private Set<String> parseFingerprint(String fp) {
        Set<String> set = new HashSet<>();
        if (!StringUtils.hasText(fp)) {
            return set;
        }
        for (String part : fp.split(",")) {
            if (StringUtils.hasText(part)) {
                set.add(part.trim());
            }
        }
        return set;
    }

    private IsolationPoint getPoint(Long id) {
        return pointRepository.findById(id)
                .orElseThrow(() -> new BizException("隔离点不存在：" + id));
    }

    /** 票号 SI-yyyy-####：取当年最大序号 +1 */
    private String generateTicketNo() {
        int year = java.time.Year.now(zoneOf(null)).getValue();
        String prefix = "SI-" + year + "-";
        int maxSeq = ticketRepository.findAll().stream()
                .map(SafetyTicket::getTicketNo)
                .filter(no -> no != null && no.startsWith(prefix))
                .mapToInt(no -> {
                    try {
                        return Integer.parseInt(no.substring(prefix.length()));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .max().orElse(0);
        for (int delta = 1; delta <= 10; delta++) {
            String candidate = prefix + String.format("%04d", maxSeq + delta);
            if (!ticketRepository.existsByTicketNo(candidate)) {
                return candidate;
            }
        }
        throw new BizException("隔离票编号生成失败，请稍后重试");
    }

    /** 追加一条变更流水（只增不改，完整可追溯） */
    private void writeLog(SafetyTicket ticket, Long pointId, String action,
            String fromStatus, String toStatus, String operator, String detail) {
        IsolationLog log = new IsolationLog();
        log.setTicketId(ticket.getId());
        log.setPointId(pointId);
        log.setWorkOrderId(ticket.getWorkOrderId());
        log.setTurbineId(ticket.getTurbineId());
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperator(operator);
        log.setDetail(detail);
        ZoneId zone = zoneOf(ticket.getFarmId());
        log.setCreatedAt(ZonedDateTime.now(zone));
        log.setCreatedZone(zone.getId());
        logRepository.save(log);
    }

    private String logActionName(String action) {
        if (action == null) {
            return "";
        }
        switch (action) {
            case "CREATE": return "建票";
            case "START_CONFIRM": return "开始确认";
            case "ISOLATE_POINT": return "隔离确认";
            case "POINT_INVALID": return "隔离失效";
            case "RE_CONFIRM": return "重新确认";
            case "REOPEN_CONFIRM": return "退回确认";
            case "ISOLATED": return "隔离生效";
            case "START_RESTORE": return "开始复电";
            case "RESTORE_POINT": return "复电解除";
            case "RESTORED": return "恢复完成";
            case "ADD_POINT": return "补充隔离点";
            case "TPL_IMPORT": return "模板新点补入";
            case "TPL_ACK": return "模板差异留痕";
            default: return action;
        }
    }

    private ZoneId zoneOf(Long farmId) {
        WindFarm farm = farmId == null ? null : farmRepository.findById(farmId).orElse(null);
        return Dict.zoneOf(farm == null ? null : farm.getTimeZone());
    }

    /** 保存票并把 updated_at 向前推；@Version 乐观锁在并发保存时由 JPA 抛出冲突异常 */
    private SafetyTicket saveTicket(SafetyTicket ticket) {
        if (ticket.getUpdatedAt() == null) {
            ticket.setUpdatedAt(ZonedDateTime.now(zoneOf(ticket.getFarmId())));
        }
        return ticketRepository.save(ticket);
    }

    private static boolean same(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }
}
