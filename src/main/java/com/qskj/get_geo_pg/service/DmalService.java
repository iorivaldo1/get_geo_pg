package com.qskj.get_geo_pg.service;

import com.qskj.get_geo_pg.pojo.Dmal;
import java.util.List;

public interface DmalService {
    List<Dmal> getNearestDmal(double lng, double lat, int distance);
}
