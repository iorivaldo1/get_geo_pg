package com.qskj.get_geo_pg.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName(value = "excalidraw_json", schema = "excalidraw")
public class ExcalidrawJson {

    @TableId(type = IdType.AUTO)
    private Integer id;
    
    private String boardName;
    
    private String elements;
    
    private String appState;
    
    private String files;
    
    private Date createdAt;
    
    private Date updatedAt;
    
    private String dataStructuresType;
}
