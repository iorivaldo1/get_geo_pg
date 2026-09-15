package com.qskj.get_geo_pg.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName(value = "excalidraw_user", schema = "excalidraw")
public class ExcalidrawUser {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String username;

    private String password;

    private String role;

    private Date createdAt;
}
