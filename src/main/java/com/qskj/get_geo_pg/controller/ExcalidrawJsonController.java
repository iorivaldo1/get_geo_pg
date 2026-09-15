package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.pojo.ExcalidrawJson;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.ExcalidrawJsonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/excalidraw")
public class ExcalidrawJsonController {

    @Autowired
    private ExcalidrawJsonService excalidrawJsonService;

    // 获取所有记录列表
    @GetMapping("/list")
    public Result<List<ExcalidrawJson>> list() {
        return Result.ok(excalidrawJsonService.list());
    }

    // 根据ID查询单条记录
    @GetMapping("/get/{id}")
    public Result<ExcalidrawJson> getById(@PathVariable("id") Integer id) {
        ExcalidrawJson record = excalidrawJsonService.getById(id);
        if (record != null) {
            return Result.ok(record);
        }
        return Result.fail("未找到该记录");
    }

    // 根据画板名称(boardName)查询单条记录
    @GetMapping("/getByBoardName")
    public Result<ExcalidrawJson> getByBoardName(@RequestParam("boardName") String boardName) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExcalidrawJson> queryWrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        queryWrapper.eq("board_name", boardName);
        
        ExcalidrawJson record = excalidrawJsonService.getOne(queryWrapper);
        if (record != null) {
            return Result.ok(record);
        }
        return Result.fail("未找到该画板");
    }

    // 根据类别查询画板列表
    @GetMapping("/listByType")
    public Result<List<ExcalidrawJson>> listByType(@RequestParam("type") String type) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExcalidrawJson> queryWrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        queryWrapper.eq("data_structures_type", type);
        // 为了提高查询性能，列表接口只返回基础信息，不返回庞大的 JSON 字符串
        queryWrapper.select("id", "board_name", "data_structures_type", "created_at", "updated_at");
        queryWrapper.orderByAsc("board_name");
        
        List<ExcalidrawJson> list = excalidrawJsonService.list(queryWrapper);
        return Result.ok(list);
    }

    // 新增记录
    @PostMapping("/add")
    public Result<Boolean> add(@RequestBody ExcalidrawJson excalidrawJson) {
        excalidrawJson.setCreatedAt(new Date());
        excalidrawJson.setUpdatedAt(new Date());
        boolean success = excalidrawJsonService.save(excalidrawJson);
        return success ? Result.ok(true) : Result.fail("添加失败");
    }

    // 修改记录
    @PutMapping("/update")
    public Result<Boolean> update(@RequestBody ExcalidrawJson excalidrawJson) {
        if (excalidrawJson.getId() == null) {
            return Result.fail("ID不能为空");
        }
        excalidrawJson.setUpdatedAt(new Date());
        boolean success = excalidrawJsonService.updateById(excalidrawJson);
        return success ? Result.ok(true) : Result.fail("更新失败");
    }

    // 删除记录
    @DeleteMapping("/delete/{id}")
    public Result<Boolean> delete(@PathVariable("id") Integer id) {
        boolean success = excalidrawJsonService.removeById(id);
        return success ? Result.ok(true) : Result.fail("删除失败");
    }

    // 上传画板数据 (如果已存在则更新，不存在则插入)
    @PostMapping("/upload")
    public Result<Boolean> upload(@RequestBody ExcalidrawJson excalidrawJson) {
        if (excalidrawJson.getBoardName() == null || excalidrawJson.getBoardName().isEmpty()) {
            return Result.fail("画板名称(boardName)不能为空");
        }
        
        // 尝试查询数据库中是否已有该画板
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ExcalidrawJson> queryWrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        queryWrapper.eq("board_name", excalidrawJson.getBoardName());
        
        ExcalidrawJson existing = excalidrawJsonService.getOne(queryWrapper);
        
        if (existing != null) {
            // 更新
            excalidrawJson.setId(existing.getId());
            excalidrawJson.setUpdatedAt(new Date());
            boolean success = excalidrawJsonService.updateById(excalidrawJson);
            return success ? Result.ok(true) : Result.fail("上传更新失败");
        } else {
            // 新增
            excalidrawJson.setCreatedAt(new Date());
            excalidrawJson.setUpdatedAt(new Date());
            boolean success = excalidrawJsonService.save(excalidrawJson);
            return success ? Result.ok(true) : Result.fail("上传新增失败");
        }
    }
}
