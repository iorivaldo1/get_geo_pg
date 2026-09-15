package com.qskj.get_geo_pg.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qskj.get_geo_pg.mapper.DmaaDictMapper;
import com.qskj.get_geo_pg.pojo.DmaaDict;
import com.qskj.get_geo_pg.service.DmaaDictService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DmaaDictServiceImpl implements DmaaDictService {

    @Autowired
    private DmaaDictMapper dmaaDictMapper;

    @Override
    public List<DmaaDict> findByType(String dictType) {
        QueryWrapper<DmaaDict> wrapper = new QueryWrapper<>();
        wrapper.eq("dict_type", dictType);
        return dmaaDictMapper.selectList(wrapper);
    }

    @Override
    public List<DmaaDict> findAll() {
        return dmaaDictMapper.selectList(null);
    }
}
