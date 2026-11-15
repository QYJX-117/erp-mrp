package com.example.erpmrp.controller;

import com.example.erpmrp.entity.*;
import com.example.erpmrp.mapper.*;
import com.example.erpmrp.service.ExcelExportService;
import com.example.erpmrp.service.MrpCalculatorService;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class MrpController {

    @Autowired private MaterialMapper materialMapper;
    @Autowired private BomMapper bomMapper;
    @Autowired private MpsMapper mpsMapper;
    @Autowired private MrpResultMapper mrpResultMapper;
    @Autowired private MrpCalculatorService mrpCalculator;
    @Autowired private ExcelExportService excelExport;

    // 物料 CRUD
    @GetMapping("/materials")
    public List<Material> listMaterials() {
        return materialMapper.selectList(null);
    }
    
    // BOM CRUD
    @GetMapping("/boms")
    public List<Bom> listBoms() {
        return bomMapper.selectList(null);
    }
    
    // MPS CRUD
    @GetMapping("/mps")
    public List<Mps> listMps() {
        return mpsMapper.selectList(null);
    }
    
    // 执行 MRP 运算
    @PostMapping("/run-mrp")
    public String runMrp() {
        mrpCalculator.calculateAndSave();
        return "MRP运算完成";
    }
    
    // 获取 MRP 结果
    @GetMapping("/results")
    public List<MrpResult> getResults(@RequestParam(required = false) String materialCode) {
        if (materialCode != null && !materialCode.isEmpty()) {
            return mrpResultMapper.selectList(new QueryWrapper<MrpResult>().eq("material_code", materialCode));
        }
        return mrpResultMapper.selectList(null);
    }
    
    // 导出 Excel
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws IOException {
        excelExport.exportMrpResult(response);
    }

    // MRP矩阵视图数据
    @GetMapping("/mrp-matrix")
    public Map<String, Object> getMrpMatrix() {
        return mrpCalculator.getMatrixData();
    }

    // 获取单个物料MRP明细（含每期中间变量）
    @GetMapping("/mrp-detail/{materialCode}")
    public Map<String, Object> getMrpDetail(@PathVariable String materialCode) {
        return mrpCalculator.getMaterialDetail(materialCode);
    }
 // 在 MrpController 类中添加
    @Autowired
    private UserMapper userMapper;

    // 登录方法改为数据库查询
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");
        Map<String, Object> result = new HashMap<>();
        
        // 根据用户名查询用户
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username));
        if (user != null && user.getPassword().equals(password)) {
            result.put("success", true);
            result.put("message", "登录成功");
            result.put("username", user.getUsername());
            result.put("role", user.getRole());
        } else {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
        }
        return result;
    }

    // 获取当前用户（基于简单逻辑，实际可用session，这里返回固定admin，可以扩展）
    @GetMapping("/current-user")
    public Map<String, Object> getCurrentUser() {
        Map<String, Object> user = new HashMap<>();
        // 实际可从session获取，为简单返回固定值
        user.put("username", "admin");
        user.put("roles", "管理员");
        return user;
    }
 // 新增或更新物料
    @PostMapping("/materials")
    public String saveMaterial(@RequestBody Material material) {
        // MyBatis-Plus 的 saveOrUpdate 需要自己实现，或者用 insert/update 判断
        Material existing = materialMapper.selectById(material.getMaterialCode());
        if (existing == null) {
            materialMapper.insert(material);
        } else {
            materialMapper.updateById(material);
        }
        return "success";
    }

    // 删除物料
    @DeleteMapping("/materials/{code}")
    public String deleteMaterial(@PathVariable String code) {
        materialMapper.deleteById(code);
        return "success";
    }
    @PostMapping("/boms")
    public String saveBom(@RequestBody Bom bom) {
        if (bom.getId() == null) {
            bomMapper.insert(bom);
        } else {
            bomMapper.updateById(bom);
        }
        return "success";
    }

    @DeleteMapping("/boms/{id}")
    public String deleteBom(@PathVariable Integer id) {
        bomMapper.deleteById(id);
        return "success";
    }
    @PostMapping("/mps")
    public String saveMps(@RequestBody Mps mps) {
        if (mps.getId() == null) {
            mpsMapper.insert(mps);
        } else {
            mpsMapper.updateById(mps);
        }
        return "success";
    }

    @DeleteMapping("/mps/{id}")
    public String deleteMps(@PathVariable Integer id) {
        mpsMapper.deleteById(id);
        return "success";
    }
 // 导出物料数据
    @GetMapping("/export-materials")
    public void exportMaterials(HttpServletResponse response) throws IOException {
        List<Material> list = materialMapper.selectList(null);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = URLEncoder.encode("物料主数据", "UTF-8");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".xlsx");
        EasyExcel.write(response.getOutputStream(), Material.class).sheet("物料").doWrite(list);
    }

    // 导出 BOM 数据
    @GetMapping("/export-boms")
    public void exportBoms(HttpServletResponse response) throws IOException {
        List<Bom> list = bomMapper.selectList(null);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = URLEncoder.encode("BOM结构", "UTF-8");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".xlsx");
        EasyExcel.write(response.getOutputStream(), Bom.class).sheet("BOM").doWrite(list);
    }

    // 导出 MPS 数据
    @GetMapping("/export-mps")
    public void exportMps(HttpServletResponse response) throws IOException {
        List<Mps> list = mpsMapper.selectList(null);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = URLEncoder.encode("主生产计划", "UTF-8");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".xlsx");
        EasyExcel.write(response.getOutputStream(), Mps.class).sheet("MPS").doWrite(list);
    }
 
}