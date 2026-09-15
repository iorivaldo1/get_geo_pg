package com.qskj.get_geo_pg.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qskj.get_geo_pg.pojo.ExcalidrawUser;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.ExcalidrawUserService;
import com.qskj.get_geo_pg.service.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/excalidraw/user")
@CrossOrigin
public class ExcalidrawUserController {

    @Autowired
    private ExcalidrawUserService excalidrawUserService;

    @Autowired
    private JwtService jwtService;

    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody Map<String, String> param) {
        String username = param.get("username");
        String password = param.get("password");

        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            return Result.fail("用户名或密码不能为空");
        }

        try {
            LambdaQueryWrapper<ExcalidrawUser> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ExcalidrawUser::getUsername, username);
            ExcalidrawUser user = excalidrawUserService.getOne(wrapper);

            if (user == null || !password.equals(user.getPassword())) {
                return Result.fail("用户名或密码错误");
            }

            String token = jwtService.generateToken(
                    user.getUsername(),
                    "admin",
                    user.getRole() != null ? user.getRole() : "admin",
                    "excalidraw");

            Map<String, String> userInfo = new HashMap<>();
            userInfo.put("token", token);
            userInfo.put("userName", user.getUsername());
            userInfo.put("role", user.getRole());

            return Result.ok(userInfo);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("服务器内部错误: " + e.getMessage());
        }
    }
}
