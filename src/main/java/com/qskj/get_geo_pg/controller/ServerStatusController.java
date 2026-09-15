package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.pojo.ServerStatusDto;
import com.qskj.get_geo_pg.service.ServerStatusService;
import com.qskj.get_geo_pg.result.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/server")
public class ServerStatusController {

    @Autowired
    private ServerStatusService serverStatusService;

    @GetMapping("/status")
    public Result<ServerStatusDto> getStatus() {
        try {
            ServerStatusDto status = serverStatusService.getServerStatus();
            return Result.ok(status);
        } catch (Throwable t) {
            t.printStackTrace();
            return Result.fail("获取状态异常: " + t.getMessage());
        }
    }
}
