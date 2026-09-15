package com.qskj.get_geo_pg.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("hhgl.dmaa")
public class Dmaa {
    @TableId(type = IdType.AUTO)
    private Integer id;

    private String name;
    private String company;

    @TableField("setDate")
    private String setDate;

    private String duty;

    @TableField(exist = false)
    private String geometry;

    private String manageScope;
}
