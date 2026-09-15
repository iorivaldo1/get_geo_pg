package com.qskj.get_geo_pg.pojo;

import lombok.Data;

@Data
public class RouteRequest {
    private String networkId;
    private Double startLng;
    private Double startLat;
    private Double endLng;
    private Double endLat;
    private Boolean directed = true;
}
