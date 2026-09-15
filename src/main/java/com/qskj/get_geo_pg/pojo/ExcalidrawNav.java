package com.qskj.get_geo_pg.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName(value = "excalidraw_nav", schema = "excalidraw")
public class ExcalidrawNav {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String name;

    private String path;

    private String dataStructuresType;

    private Integer sortOrder;

    private Date createdAt;

    private Date updatedAt;
}
