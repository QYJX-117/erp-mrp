package com.example.erpmrp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.erpmrp.entity.*;
import com.example.erpmrp.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MrpCalculatorService {

    private static final Logger log = LoggerFactory.getLogger(MrpCalculatorService.class);

    @Autowired
    private MaterialMapper materialMapper;
    @Autowired
    private BomMapper bomMapper;
    @Autowired
    private MpsMapper mpsMapper;
    @Autowired
    private MrpResultMapper mrpResultMapper;

    // ========== 动态计算总周期数 ==========
    // 移除固定常量 MAX_PERIOD=8，改为每次运算时动态计算
    private int computeTotalPeriods() {
        List<Mps> mpsList = mpsMapper.selectList(null);
        int maxMpsPeriod = mpsList.stream()
                .mapToInt(Mps::getPeriod).max().orElse(0);

        List<Material> materials = materialMapper.selectList(null);
        int maxLeadTime = materials.stream()
                .mapToInt(m -> m.getLeadTime() != null ? m.getLeadTime() : 0).max().orElse(0);

        // 总周期数 = MAX(最大MPS周期, 最大提前期) + 5 缓冲区，至少 8
        int total = Math.max(maxMpsPeriod, maxLeadTime) + 5;
        return Math.max(total, 8);
    }

    // ========== 低位码自动计算（从BOM图推导，不依赖用户输入的bom.level） ==========
    // 返回: level → [物料编码列表]，按层级从低到高排列
    private Map<Integer, List<String>> computeLowLevelCodes(List<Material> materials, List<Bom> boms) {
        // 构建父子关系图
        Map<String, Set<String>> parentChildren = new HashMap<>(); // 父→子
        Map<String, Set<String>> childParents = new HashMap<>();   // 子→父
        for (Bom bom : boms) {
            parentChildren.computeIfAbsent(bom.getParentCode(), k -> new HashSet<>()).add(bom.getChildCode());
            childParents.computeIfAbsent(bom.getChildCode(), k -> new HashSet<>()).add(bom.getParentCode());
        }

        // 物料名→物料映射
        Map<String, Material> materialMap = new HashMap<>();
        for (Material m : materials) materialMap.put(m.getMaterialCode(), m);

        // LLC分配: 没有父件的物料为0级（产成品/独立需求件）
        Map<String, Integer> llc = new HashMap<>();
        Queue<String> queue = new LinkedList<>();
        for (Material m : materials) {
            if (!childParents.containsKey(m.getMaterialCode())) {
                llc.put(m.getMaterialCode(), 0);
                queue.add(m.getMaterialCode());
            }
        }
        // 如果所有物料都有父件（环形结构），将所有物料初始化为0
        if (llc.isEmpty()) {
            for (Material m : materials) {
                llc.put(m.getMaterialCode(), 0);
                queue.add(m.getMaterialCode());
            }
        }

        // BFS逐层传播，取最长路径（max父件LLC+1）
        while (!queue.isEmpty()) {
            String parent = queue.poll();
            int parentLevel = llc.get(parent);
            Set<String> children = parentChildren.get(parent);
            if (children == null) continue;
            for (String child : children) {
                int candidateLevel = parentLevel + 1;
                Integer existing = llc.get(child);
                if (existing == null || candidateLevel > existing) {
                    llc.put(child, candidateLevel);
                    queue.add(child); // 重新传播以确保下游也更新
                }
            }
        }

        // 未覆盖的物料默认0级
        for (Material m : materials) {
            llc.putIfAbsent(m.getMaterialCode(), 0);
        }

        // 按层级分组
        int maxLevel = llc.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<Integer, List<String>> levelMaterials = new HashMap<>();
        for (Map.Entry<String, Integer> entry : llc.entrySet()) {
            levelMaterials.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        log.info("低位码自动计算完成，最大层级={}, 分组={}", maxLevel, levelMaterials);
        return levelMaterials;
    }

    @Transactional
    public void calculateAndSave() {
        int totalPeriods = computeTotalPeriods();
        log.info("MRP运算开始，动态总周期数 = {}", totalPeriods);

        // 1. 清空上次结果
        mrpResultMapper.delete(new QueryWrapper<>());

        // 2. 读取物料信息
        List<Material> materials = materialMapper.selectList(null);
        Map<String, Material> materialMap = new HashMap<>();
        for (Material m : materials) materialMap.put(m.getMaterialCode(), m);

        // 3. 读取 BOM，建立父→子列表
        List<Bom> boms = bomMapper.selectList(null);
        Map<String, List<Bom>> parentBomMap = new HashMap<>();
        for (Bom bom : boms) {
            parentBomMap.computeIfAbsent(bom.getParentCode(), k -> new ArrayList<>()).add(bom);
        }

        // 4. 低位码自动计算（从BOM图推导，不依赖用户输入的bom.level）
        Map<Integer, List<String>> levelMaterials = computeLowLevelCodes(materials, boms);

        // 5. 毛需求容器（物料 → 周期 → 毛需求），数组长度 = totalPeriods + 1
        Map<String, int[]> grossReqMap = new HashMap<>();
        for (Material m : materials) {
            grossReqMap.put(m.getMaterialCode(), new int[totalPeriods + 1]);
        }

        // 6. 载入 MPS，动态上限改为 totalPeriods
        List<Mps> mpsList = mpsMapper.selectList(null);
        for (Mps mps : mpsList) {
            int[] arr = grossReqMap.get(mps.getMaterialCode());
            if (arr != null && mps.getPeriod() >= 1) {
                int period = Math.min(mps.getPeriod(), totalPeriods); // 超过则截断
                arr[period] += mps.getGrossReq();
            }
        }

        // 存储最终订货计划
        List<MrpResult> orderPlan = new ArrayList<>();

        // 7. 逐层计算
        List<Integer> levels = new ArrayList<>(levelMaterials.keySet());
        Collections.sort(levels);
        for (int lvl : levels) {
            List<String> codes = levelMaterials.get(lvl);
            for (String code : codes) {
                Material mat = materialMap.get(code);
                if (mat == null) continue;

                int[] gross = grossReqMap.get(code);
                int lt = mat.getLeadTime() != null ? mat.getLeadTime() : 0;
                int initStock = mat.getInitStock() != null ? mat.getInitStock() : 0;
                int allocated = mat.getAllocated() != null ? mat.getAllocated() : 0;
                int safetyStock = mat.getSafetyStock() != null ? mat.getSafetyStock() : 0;
                String lotRule = mat.getLotRule();
                int lotSize = mat.getLotSize() != null ? mat.getLotSize() : 1;

                int available = initStock - allocated;
                int[] plannedOut = new int[totalPeriods + 1];
                int[] plannedIn = new int[totalPeriods + 1];
                int[] releasePeriod = new int[totalPeriods + 1];

                for (int t = 1; t <= totalPeriods; t++) {
                    int gr = gross[t];
                    int net = Math.max(0, gr + safetyStock - available);
                    if (net > 0) {
                        int po;
                        if ("FOQ".equals(lotRule) && lotSize > 0) {
                            po = (int) Math.ceil((double) net / lotSize) * lotSize;
                        } else {
                            po = net;
                        }
                        plannedOut[t] = po;
                        plannedIn[t] = po;

                        // 提前期偏移：下达周期 = t - lt
                        int rel = t - lt;
                        if (rel < 1) {
                            rel = 1;  // 小于1则设为1
                        } else if (rel > totalPeriods) {
                            log.warn("物料 {} 第{}周期下达周期计算为{}，超过总周期{}，截断为{}",
                                    code, t, rel, totalPeriods, totalPeriods);
                            rel = totalPeriods;  // 超过总周期则截断
                        }
                        releasePeriod[t] = rel;
                        available = available + po - gr;

                        MrpResult order = new MrpResult();
                        order.setMaterialCode(code);
                        order.setPeriod(rel);
                        order.setGrossReq(gr);
                        order.setNetReq(net);
                        order.setPlannedOut(po);
                        order.setPlannedIn(po);
                        order.setReleasePeriod(t);
                        orderPlan.add(order);
                    } else {
                        available = available - gr;
                    }
                }

                // 8. 将本物料的计划投入量传递给子件
                if (parentBomMap.containsKey(code)) {
                    for (Bom childBom : parentBomMap.get(code)) {
                        String childCode = childBom.getChildCode();
                        int qty = childBom.getQty();
                        int[] childGross = grossReqMap.get(childCode);
                        if (childGross == null) continue;
                        for (int t = 1; t <= totalPeriods; t++) {
                            if (plannedIn[t] > 0) {
                                int demandPeriod = releasePeriod[t];
                                childGross[demandPeriod] += plannedIn[t] * qty;
                            }
                        }
                    }
                }
            }
        }

        // 9. 保存订货计划
        for (MrpResult order : orderPlan) {
            mrpResultMapper.insert(order);
        }
        log.info("MRP运算完成，共生成 {} 条订货计划", orderPlan.size());
    }

    // ========== 矩阵视图数据 ==========
    // 返回前端矩阵视图所需的 JSON 数据结构
    public Map<String, Object> getMatrixData() {
        int totalPeriods = computeTotalPeriods();
        log.info("生成MRP矩阵数据，总周期数 = {}", totalPeriods);

        // 读取物料
        List<Material> materials = materialMapper.selectList(null);
        Map<String, Material> materialMap = new HashMap<>();
        for (Material m : materials) materialMap.put(m.getMaterialCode(), m);

        // 读取 BOM
        List<Bom> boms = bomMapper.selectList(null);
        Map<String, List<Bom>> parentBomMap = new HashMap<>();
        for (Bom bom : boms) {
            parentBomMap.computeIfAbsent(bom.getParentCode(), k -> new ArrayList<>()).add(bom);
        }

        // 低位码自动计算
        Map<Integer, List<String>> levelMaterials = computeLowLevelCodes(materials, boms);

        // 毛需求
        Map<String, int[]> grossReqMap = new HashMap<>();
        for (Material m : materials) {
            grossReqMap.put(m.getMaterialCode(), new int[totalPeriods + 1]);
        }

        // 载入 MPS
        List<Mps> mpsList = mpsMapper.selectList(null);
        for (Mps mps : mpsList) {
            int[] arr = grossReqMap.get(mps.getMaterialCode());
            if (arr != null && mps.getPeriod() >= 1) {
                int period = Math.min(mps.getPeriod(), totalPeriods);
                arr[period] += mps.getGrossReq();
            }
        }

        // 矩阵数据容器：物料 → { "gross":[], "net":[], "plannedIn":[], "endInv":[] }
        Map<String, Map<String, int[]>> matrixData = new LinkedHashMap<>();

        // 逐层计算并收集数据
        List<Integer> levels = new ArrayList<>(levelMaterials.keySet());
        Collections.sort(levels);
        for (int lvl : levels) {
            List<String> codes = levelMaterials.get(lvl);
            if (codes == null) continue;
            for (String code : codes) {
                Material mat = materialMap.get(code);
                if (mat == null) continue;

                int[] gross = grossReqMap.get(code);
                int lt = mat.getLeadTime() != null ? mat.getLeadTime() : 0;
                int initStock = mat.getInitStock() != null ? mat.getInitStock() : 0;
                int allocated = mat.getAllocated() != null ? mat.getAllocated() : 0;
                int safetyStock = mat.getSafetyStock() != null ? mat.getSafetyStock() : 0;
                String lotRule = mat.getLotRule();
                int lotSize = mat.getLotSize() != null ? mat.getLotSize() : 1;

                int available = initStock - allocated;
                int[] net = new int[totalPeriods + 1];
                int[] plannedIn = new int[totalPeriods + 1];
                int[] endInv = new int[totalPeriods + 1];
                int[] releasePeriod = new int[totalPeriods + 1];

                for (int t = 1; t <= totalPeriods; t++) {
                    int gr = gross[t];
                    int netReq = Math.max(0, gr + safetyStock - available);
                    net[t] = netReq;
                    if (netReq > 0) {
                        int po;
                        if ("FOQ".equals(lotRule) && lotSize > 0) {
                            po = (int) Math.ceil((double) netReq / lotSize) * lotSize;
                        } else {
                            po = netReq;
                        }
                        plannedIn[t] = po;
                        int rel = t - lt;
                        if (rel < 1) {
                            rel = 1;
                        } else if (rel > totalPeriods) {
                            rel = totalPeriods;
                        }
                        releasePeriod[t] = rel;
                        available = available + po - gr;
                    } else {
                        available = available - gr;
                    }
                    endInv[t] = available;
                }

                // 保存该物料的矩阵行数据
                Map<String, int[]> row = new LinkedHashMap<>();
                row.put("gross", gross);
                row.put("net", net);
                row.put("plannedIn", plannedIn);
                row.put("endInv", endInv);
                matrixData.put(code, row);

                // 传递给子件
                if (parentBomMap.containsKey(code)) {
                    for (Bom childBom : parentBomMap.get(code)) {
                        String childCode = childBom.getChildCode();
                        int qty = childBom.getQty();
                        int[] childGross = grossReqMap.get(childCode);
                        if (childGross == null) continue;
                        for (int t = 1; t <= totalPeriods; t++) {
                            if (plannedIn[t] > 0) {
                                int demandPeriod = releasePeriod[t];
                                childGross[demandPeriod] += plannedIn[t] * qty;
                            }
                        }
                    }
                }
            }
        }

        // 构建返回 JSON：物料列表、周期列表、每个物料的各数组（下标1..totalPeriods）
        List<String> materialList = new ArrayList<>();
        List<Integer> periodList = new ArrayList<>();
        for (int t = 1; t <= totalPeriods; t++) periodList.add(t);

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, List<Integer>>> data = new LinkedHashMap<>();

        // 按低位码排序物料列表
        List<Integer> sortedLevels = new ArrayList<>(levelMaterials.keySet());
        Collections.sort(sortedLevels);
        for (int lvl : sortedLevels) {
            List<String> codes = levelMaterials.get(lvl);
            if (codes == null) continue;
            for (String code : codes) {
                materialList.add(code);
                Map<String, int[]> row = matrixData.get(code);
                if (row == null) continue;

                Map<String, List<Integer>> cell = new LinkedHashMap<>();
                for (String key : new String[]{"gross", "net", "plannedIn", "endInv"}) {
                    int[] arr = row.get(key);
                    List<Integer> list = new ArrayList<>();
                    for (int t = 1; t <= totalPeriods; t++) {
                        list.add(arr[t]);
                    }
                    cell.put(key, list);
                }
                data.put(code, cell);
            }
        }

        result.put("materials", materialList);
        result.put("periods", periodList);
        result.put("data", data);
        result.put("totalPeriods", totalPeriods);
        return result;
    }

    // ========== 获取单个物料MRP明细（含每期中间变量） ==========
    public Map<String, Object> getMaterialDetail(String materialCode) {
        int totalPeriods = computeTotalPeriods();

        List<Material> materials = materialMapper.selectList(null);
        Map<String, Material> materialMap = new HashMap<>();
        for (Material m : materials) materialMap.put(m.getMaterialCode(), m);

        Material targetMat = materialMap.get(materialCode);
        if (targetMat == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "物料不存在: " + materialCode);
            return error;
        }

        // BOM父→子映射
        List<Bom> boms = bomMapper.selectList(null);
        Map<String, List<Bom>> parentBomMap = new HashMap<>();
        for (Bom bom : boms) {
            parentBomMap.computeIfAbsent(bom.getParentCode(), k -> new ArrayList<>()).add(bom);
        }

        // 低位码自动计算（从BOM图推导）
        Map<Integer, List<String>> levelMaterials = computeLowLevelCodes(materials, boms);

        // 毛需求初始化
        Map<String, int[]> grossReqMap = new HashMap<>();
        for (Material m : materials) {
            grossReqMap.put(m.getMaterialCode(), new int[totalPeriods + 1]);
        }
        List<Mps> mpsList = mpsMapper.selectList(null);
        for (Mps mps : mpsList) {
            int[] arr = grossReqMap.get(mps.getMaterialCode());
            if (arr != null && mps.getPeriod() >= 1) {
                int period = Math.min(mps.getPeriod(), totalPeriods);
                arr[period] += mps.getGrossReq();
            }
        }

        // 所有物料的明细数据容器
        Map<String, List<Map<String, Object>>> allDetails = new LinkedHashMap<>();

        // 逐层MRP计算
        List<Integer> levels = new ArrayList<>(levelMaterials.keySet());
        Collections.sort(levels);
        for (int lvl : levels) {
            List<String> codes = levelMaterials.get(lvl);
            if (codes == null) continue;
            for (String code : codes) {
                Material mat = materialMap.get(code);
                if (mat == null) continue;

                int[] gross = grossReqMap.get(code);
                int lt = mat.getLeadTime() != null ? mat.getLeadTime() : 0;
                int initStock = mat.getInitStock() != null ? mat.getInitStock() : 0;
                int allocated = mat.getAllocated() != null ? mat.getAllocated() : 0;
                int safetyStock = mat.getSafetyStock() != null ? mat.getSafetyStock() : 0;
                String lotRule = mat.getLotRule();
                int lotSize = mat.getLotSize() != null ? mat.getLotSize() : 1;

                int available = initStock - allocated; // 期初可用库存
                int[] plannedOut = new int[totalPeriods + 1];
                int[] plannedIn = new int[totalPeriods + 1];
                int[] releasePeriod = new int[totalPeriods + 1];
                List<Map<String, Object>> details = new ArrayList<>();

                for (int t = 1; t <= totalPeriods; t++) {
                    int beginInv = available; // 记录当期期初库存
                    int gr = gross[t];
                    // 净需求 = MAX(0, 毛需求 + 安全库存 - 期初可用库存 - 计划接收量)
                    // 计划接收量(scheduledReceipts)本题目恒为0，故省略
                    int net = Math.max(0, gr + safetyStock - available);
                    int po = 0;
                    int rel = 0;

                    if (net > 0) {
                        // 批量规则：FOQ向上取整，LFL直等于净需求
                        if ("FOQ".equals(lotRule) && lotSize > 0) {
                            po = (int) Math.ceil((double) net / lotSize) * lotSize;
                        } else {
                            po = net;
                        }
                        plannedOut[t] = po;
                        plannedIn[t] = po;
                        // 提前期偏移：下达周期 = 当期 - 提前期，边界保护
                        rel = t - lt;
                        if (rel < 1) rel = 1;
                        else if (rel > totalPeriods) rel = totalPeriods;
                        releasePeriod[t] = rel;
                        available = available + po - gr; // 库存滚动
                    } else {
                        available = available - gr;
                    }

                    Map<String, Object> pd = new LinkedHashMap<>();
                    pd.put("period", t);
                    pd.put("grossReq", gr);
                    pd.put("scheduledReceipts", 0);
                    pd.put("beginInventory", beginInv);
                    pd.put("safetyStock", safetyStock);
                    pd.put("netReq", net);
                    pd.put("plannedOrderReceipts", po);
                    pd.put("plannedOrderRelease", po);
                    pd.put("releasePeriod", net > 0 ? rel : null);
                    details.add(pd);
                }
                allDetails.put(code, details);

                // 子件需求传递：父件计划投入量 × BOM用量 → 子件毛需求
                if (parentBomMap.containsKey(code)) {
                    for (Bom childBom : parentBomMap.get(code)) {
                        String childCode = childBom.getChildCode();
                        int qty = childBom.getQty();
                        int[] childGross = grossReqMap.get(childCode);
                        if (childGross == null) continue;
                        for (int t = 1; t <= totalPeriods; t++) {
                            if (plannedIn[t] > 0) {
                                int demandPeriod = releasePeriod[t];
                                childGross[demandPeriod] += plannedIn[t] * qty;
                            }
                        }
                    }
                }
            }
        }

        // 构建返回值
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialCode", materialCode);
        result.put("materialName", targetMat.getMaterialName());
        result.put("totalPeriods", totalPeriods);
        List<Integer> periods = new ArrayList<>();
        for (int t = 1; t <= totalPeriods; t++) periods.add(t);
        result.put("periods", periods);
        result.put("details", allDetails.getOrDefault(materialCode, new ArrayList<>()));
        return result;
    }
}
