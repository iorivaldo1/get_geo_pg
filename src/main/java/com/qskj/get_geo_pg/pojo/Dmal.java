package com.qskj.get_geo_pg.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Dmal {
    private Integer id;
    private String name;
    private String geometry;
}
