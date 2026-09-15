package com.qskj.get_geo_pg.service.impl;

import com.qskj.get_geo_pg.mapper.DmalMapper;
import com.qskj.get_geo_pg.pojo.Dmal;
import com.qskj.get_geo_pg.service.DmalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DmalServiceImpl implements DmalService {
    @Autowired
    private DmalMapper dmalMapper;

    @Override
    public List<Dmal> getNearestDmal(double lng, double lat, int distance) {
        return dmalMapper.getNearestDmal(lng, lat, distance);
    }
}
