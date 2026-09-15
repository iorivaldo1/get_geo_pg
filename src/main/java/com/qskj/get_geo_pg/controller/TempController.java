package com.qskj.get_geo_pg.controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
@RestController
public class TempController {
    @Autowired
    JdbcTemplate jdbcTemplate;
    @GetMapping("/test_columns")
    public List<Map<String, Object>> test() {
        return jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'hhgl' AND table_name = 'dmal'");
    }
}
