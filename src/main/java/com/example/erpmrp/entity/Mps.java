package com.example.erpmrp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_mps")
public class Mps {
    @TableId(type = IdType.AUTO)
    private Integer id;
    private String materialCode;
    private Integer period;
    private Integer grossReq;
}