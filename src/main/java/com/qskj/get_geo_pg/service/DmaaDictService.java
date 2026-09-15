package com.qskj.get_geo_pg.service;

import com.qskj.get_geo_pg.pojo.DmaaDict;
import java.util.List;

public interface DmaaDictService {
    List<DmaaDict> findByType(String dictType);
    List<DmaaDict> findAll();
}
