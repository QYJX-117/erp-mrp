package com.example.erpmrp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@TableName("t_material")
public class Material {
    @TableId
    private String materialCode;
    private String materialName;
    private Integer initStock;
    private Integer safetyStock;
    private Integer allocated;
    private String lotRule;
    private Integer lotSize;
    
    @JsonProperty("leadTime")
    private Integer leadTime;
}