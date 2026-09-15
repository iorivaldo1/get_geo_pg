package com.qskj.get_geo_pg.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qskj.get_geo_pg.mapper.HhglUserMapper;
import com.qskj.get_geo_pg.pojo.HhglUser;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping({"/hhgl_user", "/user"})
@CrossOrigin
public class HhglUserController {

    @Autowired
    private HhglUserMapper hhglUserMapper;

    @Autowired
    private JwtService jwtService;

    @PostMapping("/login")
    @ResponseBody
    @CrossOrigin
    public Result<Map<String, String>> login(@RequestBody Map<String, String> param) {
        String username = param.get("username");
        String password = param.get("password");

        try {
            LambdaQueryWrapper<HhglUser> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(HhglUser::getUsername, username);
            HhglUser user = hhglUserMapper.selectOne(wrapper);

            if (user == null || !password.equals(user.getPassword())) {
                System.out.println("NG - login failed");
                return Result.fail("用户名或密码错误");
            }

            String token = jwtService.generateToken(
                    user.getUsername(),
                    user.getAuthority() != null ? user.getAuthority() : "reader",
                    user.getRole() != null ? user.getRole() : "guest",
                    "hhgl");
            System.out.println("登录成功");

            Map<String, String> userInfo = new HashMap<>();
            userInfo.put("token", token);
            userInfo.put("userName", user.getUsername());
            if (user.getManageScope() != null) {
                userInfo.put("manageScope", user.getManageScope());
            }
            if (user.getAuthority() != null) {
                userInfo.put("authority", user.getAuthority());
            }
            return Result.ok(userInfo);
        } catch (Exception e) {
            System.out.println("Login failed with exception: " + e.getMessage());
            e.printStackTrace();
            return Result.fail("服务器内部错误: " + e.getMessage());
        }
    }
}
