package com.example.erpmrp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_bom")
public class Bom {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String parentCode;
    private String childCode;
    private Integer qty;
    private Integer level;
}