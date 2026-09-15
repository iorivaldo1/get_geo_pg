package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.pojo.Dmal;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.DmalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/DmalController")
@CrossOrigin
public class DmalController {

    @Autowired
    private DmalService dmalService;

    @GetMapping("/nearest_dmal")
    public Result<List<Dmal>> getNearestDmal(
            @RequestParam("lng") double lng,
            @RequestParam("lat") double lat,
            @RequestParam("distance") int distance) {
        try {
            List<Dmal> list = dmalService.getNearestDmal(lng, lat, distance);
            return Result.ok(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("查询失败: " + e.getMessage());
        }
    }
}
