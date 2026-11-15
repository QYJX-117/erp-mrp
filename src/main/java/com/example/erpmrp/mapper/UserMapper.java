package com.example.erpmrp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.erpmrp.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}