package com.qskj.get_geo_pg.pojo;

import lombok.Data;
import java.util.List;

@Data
public class RouteRoadNamesRequest {
    private String networkId;
    private List<List<Double>> coordinates;
    private Object geometry;
}