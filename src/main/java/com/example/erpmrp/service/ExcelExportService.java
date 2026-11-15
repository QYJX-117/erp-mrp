package com.example.erpmrp.service;

import com.alibaba.excel.EasyExcel;
import com.example.erpmrp.entity.MrpResult;
import com.example.erpmrp.mapper.MrpResultMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;

@Service
public class ExcelExportService {
    @Autowired
    private MrpResultMapper mrpResultMapper;

    public void exportMrpResult(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String fileName = URLEncoder.encode("MRP运算结果", "UTF-8");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".xlsx");
        
        List<MrpResult> list = mrpResultMapper.selectList(null);
        EasyExcel.write(response.getOutputStream(), MrpResult.class).sheet("订货计划").doWrite(list);
    }
}