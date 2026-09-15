package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.pojo.DmaaDict;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.DmaaDictService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dmaaDict")
@CrossOrigin
public class DmaaDictController {

    @Autowired
    private DmaaDictService dmaaDictService;

    @GetMapping("/listAll")
    public Result<Map<String, List<DmaaDict>>> listAllGrouped() {
        try {
            List<DmaaDict> all = dmaaDictService.findAll();
            Map<String, List<DmaaDict>> grouped = all.stream()
                    .collect(Collectors.groupingBy(DmaaDict::getDictType));
            return Result.ok(grouped);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("查询失败: " + e.getMessage());
        }
    }
}
