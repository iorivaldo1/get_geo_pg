package com.qskj.get_geo_pg.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Bridge {
    private Integer gid;
    private String bridgeName;
    private String geometry;
}
