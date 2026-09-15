package com.qskj.get_geo_pg.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qskj.get_geo_pg.pojo.ExcalidrawNav;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.ExcalidrawNavService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/excalidraw/nav")
@CrossOrigin
public class ExcalidrawNavController {

    @Autowired
    private ExcalidrawNavService excalidrawNavService;

    // 获取所有导航菜单列表 (按 sortOrder 正序)
    @GetMapping("/list")
    public Result<List<ExcalidrawNav>> list() {
        LambdaQueryWrapper<ExcalidrawNav> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(ExcalidrawNav::getSortOrder);
        List<ExcalidrawNav> navList = excalidrawNavService.list(wrapper);
        return Result.ok(navList);
    }

    // 新增导航菜单
    @PostMapping("/add")
    public Result<Boolean> add(@RequestBody ExcalidrawNav nav) {
        if (nav.getName() == null || nav.getName().trim().isEmpty()) {
            return Result.fail("菜单名称不能为空");
        }
        if (nav.getPath() == null || nav.getPath().trim().isEmpty()) {
            return Result.fail("路由路径不能为空");
        }
        if (nav.getDataStructuresType() == null || nav.getDataStructuresType().trim().isEmpty()) {
            return Result.fail("数据结构分类标识不能为空");
        }

        // 校验 path 唯一性
        LambdaQueryWrapper<ExcalidrawNav> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExcalidrawNav::getPath, nav.getPath());
        if (excalidrawNavService.count(wrapper) > 0) {
            return Result.fail("该路由路径已存在");
        }

        if (nav.getSortOrder() == null) {
            nav.setSortOrder(0);
        }
        nav.setCreatedAt(new Date());
        nav.setUpdatedAt(new Date());

        boolean success = excalidrawNavService.save(nav);
        return success ? Result.ok(true) : Result.fail("添加导航菜单失败");
    }

    // 修改导航菜单
    @PutMapping("/update")
    public Result<Boolean> update(@RequestBody ExcalidrawNav nav) {
        if (nav.getId() == null) {
            return Result.fail("ID不能为空");
        }
        nav.setUpdatedAt(new Date());
        boolean success = excalidrawNavService.updateById(nav);
        return success ? Result.ok(true) : Result.fail("更新导航菜单失败");
    }

    // 删除导航菜单
    @DeleteMapping("/delete/{id}")
    public Result<Boolean> delete(@PathVariable("id") Integer id) {
        boolean success = excalidrawNavService.removeById(id);
        return success ? Result.ok(true) : Result.fail("删除导航菜单失败");
    }
}
