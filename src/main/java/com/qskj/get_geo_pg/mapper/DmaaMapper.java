package com.qskj.get_geo_pg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qskj.get_geo_pg.pojo.Dmaa;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DmaaMapper extends BaseMapper<Dmaa> {
    
    @Insert("INSERT INTO hhgl.dmaa (name, company, \"setDate\", duty, manage_scope, geometry) " +
            "VALUES (#{name}, #{company}, #{setDate}, #{duty}, #{manageScope}, ST_GeomFromText(#{geometry}))")
    int insertWithGeometry(Dmaa dmaa);

    @org.apache.ibatis.annotations.Select("SELECT id, name, company, \"setDate\", duty, manage_scope as manageScope, ST_AsText(geometry) as geometry FROM hhgl.dmaa WHERE manage_scope = #{manageScope}")
    java.util.List<Dmaa> findByManageScope(String manageScope);

    @org.apache.ibatis.annotations.Select("SELECT id, name, company, \"setDate\", duty, manage_scope as manageScope, ST_AsText(geometry) as geometry FROM hhgl.dmaa")
    java.util.List<Dmaa> findAll();
}
