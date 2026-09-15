package com.qskj.get_geo_pg.service.impl;

import com.qskj.get_geo_pg.mapper.DmaaMapper;
import com.qskj.get_geo_pg.pojo.Dmaa;
import com.qskj.get_geo_pg.service.DmaaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DmaaServiceImpl implements DmaaService {

    @Autowired
    private DmaaMapper dmaaMapper;

    @Override
    public void saveDmaa(Dmaa dmaa) {
        dmaaMapper.insertWithGeometry(dmaa);
    }

    @Override
    public List<Dmaa> findByManageScope(String manageScope) {
        return dmaaMapper.findByManageScope(manageScope);
    }

    @Override
    public List<Dmaa> findAll() {
        return dmaaMapper.findAll();
    }
}
