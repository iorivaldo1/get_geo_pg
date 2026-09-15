package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.pojo.Dmaa;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.DmaaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dmaa")
@CrossOrigin 
public class DmaaController {

    @Autowired
    private DmaaService dmaaService;

    @PostMapping("/upload")
    public Result<String> upload(@RequestBody Dmaa dmaa) {
        try {
            dmaaService.saveDmaa(dmaa);
            return Result.ok("上传成功");
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/listByScope")
    public Result<java.util.List<Dmaa>> listByScope(@RequestParam("manageScope") String manageScope) {
        try {
            java.util.List<Dmaa> list = dmaaService.findByManageScope(manageScope);
            return Result.ok(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("查询失败: " + e.getMessage());
        }
    }

    @GetMapping("/listAll")
    public Result<java.util.List<Dmaa>> listAll() {
        try {
            java.util.List<Dmaa> list = dmaaService.findAll();
            return Result.ok(list);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("查询失败: " + e.getMessage());
        }
    }
}
