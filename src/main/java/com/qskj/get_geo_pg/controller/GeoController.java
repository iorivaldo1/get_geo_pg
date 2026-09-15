package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.mapper.GeoMapper;
import com.qskj.get_geo_pg.pojo.Bridge;
import com.qskj.get_geo_pg.pojo.River;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/geo")
@CrossOrigin
public class GeoController {

    @Autowired
    private GeoMapper geoMapper;

    @GetMapping("/gist_index")
    public ResponseEntity<?> getGistIndexFile() {
        try {
            String dataDir = null;
            String relPath = null;
            try {
                dataDir = geoMapper.getPgDataDirectory();
                relPath = geoMapper.getYaRiverGistIndexFilePath();
            } catch (Exception e) {
                // ignore
            }

            byte[] bytes = null;
            try {
                bytes = geoMapper.getYaRiverGistIndexBytes();
            } catch (Exception ex) {
                ex.printStackTrace();
                Map<String, Object> err = new HashMap<>();
                err.put("code", 500);
                err.put("msg", "PostgreSQL 读取 GiST 索引二进制流失败: " + ex.getMessage());
                err.put("dataDir", dataDir);
                err.put("relPath", relPath);
                return new ResponseEntity<>(err, HttpStatus.INTERNAL_SERVER_ERROR);
            }

            if (bytes == null || bytes.length == 0) {
                Map<String, Object> err = new HashMap<>();
                err.put("code", 404);
                err.put("msg", "未能在 PostgreSQL 数据库中查找到 ya_data.rivers 表的 GiST 索引。请确认生产库是否已创建 GiST 索引 (CREATE INDEX idx_ya_river_geom ON ya_data.rivers USING gist(geom);)");
                err.put("dataDir", dataDir);
                err.put("relPath", relPath);
                return new ResponseEntity<>(err, HttpStatus.NOT_FOUND);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            if (relPath != null) {
                headers.add("X-PG-Index-RelPath", relPath);
            }
            if (dataDir != null) {
                headers.add("X-PG-Data-Dir", dataDir);
            }

            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            Map<String, Object> err = new HashMap<>();
            err.put("code", 500);
            err.put("msg", "服务器异常: " + e.getMessage());
            return new ResponseEntity<>(err, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/ya_river_by_tid/{blkid}/{posid}")
    public Map<String, Object> getYaRiverByTid(@PathVariable int blkid, @PathVariable int posid) {
        Map<String, Object> response = new HashMap<>();
        try {
            River river = geoMapper.getYaRiverByTid(blkid, posid);
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", river);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "error: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }


    @GetMapping("/ya_rivers_bbox")
    public Map<String, Object> getYaRiversBbox(
            @org.springframework.web.bind.annotation.RequestParam("minLng") double minLng,
            @org.springframework.web.bind.annotation.RequestParam("minLat") double minLat,
            @org.springframework.web.bind.annotation.RequestParam("maxLng") double maxLng,
            @org.springframework.web.bind.annotation.RequestParam("maxLat") double maxLat) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<River> rivers = geoMapper.getYaRiverByBbox(minLng, minLat, maxLng, maxLat);
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", rivers);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "error: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    @GetMapping("/ya_bridges")
    public Map<String, Object> getYaBridges() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Bridge> bridges = geoMapper.getYaBridges();
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", bridges);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "error: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    @GetMapping("/ya_river_route")
    public Map<String, Object> getRiverRoute(double lng, double lat) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<River> rivers = geoMapper.getRiverRoute(lng, lat);
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", rivers);
        } catch (Exception e) {
            response.put("code", 500);
            response.put("msg", "error: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }
}
