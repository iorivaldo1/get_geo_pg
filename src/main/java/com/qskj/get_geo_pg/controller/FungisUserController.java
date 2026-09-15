package com.qskj.get_geo_pg.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qskj.get_geo_pg.mapper.FungisUserMapper;
import com.qskj.get_geo_pg.pojo.FungisUser;
import com.qskj.get_geo_pg.result.Result;
import com.qskj.get_geo_pg.service.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/fungis_user")
@CrossOrigin
public class FungisUserController {

    @Autowired
    private FungisUserMapper fungisUserMapper;

    @Autowired
    private JwtService jwtService;

    @PostMapping("/login")
    @ResponseBody
    public Result<Map<String, String>> login(@RequestBody Map<String, String> param) {
        String username = param.get("username");
        String password = param.get("password");

        try {
            LambdaQueryWrapper<FungisUser> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(FungisUser::getUsername, username);
            FungisUser user = fungisUserMapper.selectOne(wrapper);

            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            if (user == null || !encoder.matches(password, user.getPassword())) {
                System.out.println("NG - fungis_user login failed");
                return Result.fail("用户名或密码错误");
            }

            String authority = user.getAuthority() != null ? user.getAuthority() : "reader";
            String role = user.getRole() != null ? user.getRole() : "guest";

            String token = jwtService.generateToken(user.getUsername(), authority, role, "fungis");
            System.out.println("fungis_user 登录成功: " + user.getUsername() + ", authority: " + authority);

            Map<String, String> userInfo = new HashMap<>();
            userInfo.put("token", token);
            userInfo.put("userName", user.getUsername());
            userInfo.put("authority", authority);
            if (user.getManageScope() != null) {
                userInfo.put("manageScope", user.getManageScope());
            }
            return Result.ok(userInfo);
        } catch (Exception e) {
            System.out.println("fungis_user Login failed with exception: " + e.getMessage());
            e.printStackTrace();
            return Result.fail("服务器内部错误: " + e.getMessage());
        }
    }
}
