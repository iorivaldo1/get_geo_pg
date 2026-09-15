package com.qskj.get_geo_pg.service;

import com.qskj.get_geo_pg.pojo.Dmaa;
import java.util.List;

public interface DmaaService {
    void saveDmaa(Dmaa dmaa);
    List<Dmaa> findByManageScope(String manageScope);
    List<Dmaa> findAll();
}
