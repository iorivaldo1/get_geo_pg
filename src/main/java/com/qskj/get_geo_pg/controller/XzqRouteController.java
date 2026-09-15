package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.service.XzqRoadBuildService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/geo/route/xzq")
@CrossOrigin
public class XzqRouteController {

    @Autowired
    private XzqRoadBuildService xzqRoadBuildService;

    @Autowired
    private com.qskj.get_geo_pg.service.RouteService routeService;

    @Autowired
    private com.qskj.get_geo_pg.service.BinaryGraphExporter binaryGraphExporter;

    /**
     * 导出行政区路网为二进制图文件 (.pgrb)，供前端下载后存入 IndexedDB 本地高速规划
     */
    @GetMapping(value = "/graph/binary", produces = "application/octet-stream")
    public org.springframework.http.ResponseEntity<byte[]> exportBinaryGraph(
            @RequestParam(value = "networkId", required = false) String networkId) {
        try {
            byte[] binary = binaryGraphExporter.exportNetwork(networkId);
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Disposition", "attachment; filename=graph.pgrb")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .body(binary);
        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(500).build();
        }
    }

    /**
     * 1. 动态获取当前数据库 sc_xzq 模式下的所有有效行政级别列表
     */
    @GetMapping("/levels")
    public Map<String, Object> getAvailableXzqLevels() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<Map<String, Object>> levels = xzqRoadBuildService.getAvailableXzqLevels();
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", levels);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取行政级别配置列表失败: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 2. 仅获取行政区划名称与ID列表 (轻量极速，不查询 geom)
     */
    @GetMapping("/list")
    public Map<String, Object> getXzqList(
            @RequestParam(value = "level", defaultValue = "town") String level,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "pageSize", required = false, defaultValue = "100") int pageSize,
            @RequestParam(value = "keyword", required = false) String keyword) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object info = xzqRoadBuildService.getXzqListInfo(level, page, pageSize, keyword);
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", info);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取行政区划列表失败: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 3. 点击列表要素时，按需获取单个要素的 bbox 与 GeoJSON (geom) 几何图形
     */
    @GetMapping("/detail")
    public Map<String, Object> getXzqDetail(
            @RequestParam("level") String level,
            @RequestParam("featureId") String featureId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> detail = xzqRoadBuildService.getXzqDetail(level, featureId);
            if (detail != null) {
                response.put("code", 200);
                response.put("msg", "success");
                response.put("data", detail);
            } else {
                response.put("code", 404);
                response.put("msg", "未找到对应的行政区划要素");
                response.put("data", null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取行政区划要素详情异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 4. 获取任意指定路网的边界 GeoJSON 与包围盒
     */
    @GetMapping("/boundary")
    public Map<String, Object> getNetworkBoundary(@RequestParam("networkId") String networkId) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> detail = xzqRoadBuildService.getNetworkBoundary(networkId);
            if (detail != null && detail.get("geojson") != null) {
                response.put("code", 200);
                response.put("msg", "success");
                response.put("data", detail);
            } else {
                response.put("code", 404);
                response.put("msg", "未找到对应的路网边界图形");
                response.put("data", null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取路网边界图形异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 5. 根据行政区划要素与 OSM 路网相交构建拓扑路网
     */
    @PostMapping("/build")
    public Map<String, Object> buildNetworkFromXzq(
            @RequestParam("level") String level,
            @RequestParam("featureId") String featureId,
            @RequestParam("networkId") String networkId,
            @RequestParam(value = "networkName", required = false) String networkName) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = xzqRoadBuildService.buildNetworkFromXzq(level, featureId, networkId,
                    networkName);
            response.put("code", 200);
            response.put("msg", "构建成功");
            response.put("data", result);
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "构建路网拓扑计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 5.1 根据行政区划要素与 OSM 路网相交构建 3D 立体分层拓扑路网 (Layer-Aware Noding, 写入 3d_road 模式)
     */
    @PostMapping(value = {"/build-with-level", "/build-3d"})
    public Map<String, Object> buildNetworkWithLevelFromXzq(
            @RequestParam("level") String level,
            @RequestParam("featureId") String featureId,
            @RequestParam("networkId") String networkId,
            @RequestParam(value = "networkName", required = false) String networkName) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = xzqRoadBuildService.buildNetworkWithLevelFromXzq(level, featureId, networkId,
                    networkName);
            response.put("code", 200);
            response.put("msg", "构建成功");
            response.put("data", result);
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "构建3D立体路网拓扑计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 5.2 根据市级要素与 OSM 路网相交构建 2D 拓扑路网 (快捷专用接口)
     */
    @PostMapping("/build-city")
    public Map<String, Object> buildCityNetwork(
            @RequestParam("featureId") String featureId,
            @RequestParam("networkId") String networkId,
            @RequestParam(value = "networkName", required = false) String networkName) {
        return buildNetworkFromXzq("city", featureId, networkId, networkName);
    }

    /**
     * 5.3 根据市级要素与 OSM 路网相交构建 3D 立体分层拓扑路网 (Layer-Aware Noding, 快捷专用接口)
     */
    @PostMapping(value = {"/build-city-with-level", "/build-city-3d"})
    public Map<String, Object> buildCityNetworkWithLevel(
            @RequestParam("featureId") String featureId,
            @RequestParam("networkId") String networkId,
            @RequestParam(value = "networkName", required = false) String networkName) {
        return buildNetworkWithLevelFromXzq("city", featureId, networkId, networkName);
    }

    /**
     * 6. 获取行政区划构建的所有有效路网列表 (XZQ 模式)
     */
    @GetMapping("/networks")
    public Map<String, Object> getNetworks(
            @RequestParam(value = "mode", required = false, defaultValue = "all") String mode) {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", xzqRoadBuildService.getAllNetworks(mode));
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取行政区路网配置列表异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 7. 删除指定行政区路网及其物理表和配置记录
     */
    @RequestMapping(value = "/delete", method = { RequestMethod.POST, RequestMethod.DELETE })
    public Map<String, Object> deleteNetwork(@RequestParam("networkId") String networkId) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = xzqRoadBuildService.deleteRoadNetwork(networkId);
            if (success) {
                response.put("code", 200);
                response.put("msg", "行政区路网【" + networkId + "】及其物理表已安全删除");
                response.put("data", null);
            } else {
                response.put("code", 400);
                response.put("msg", "删除失败：未找到对应的行政区路网记录");
                response.put("data", null);
            }
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "删除行政区路网过程发生异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 8. 更新指定行政区路网的显示名称
     */
    @PostMapping("/update-name")
    public Map<String, Object> updateNetworkName(
            @RequestParam("networkId") String networkId,
            @RequestParam("name") String name) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = xzqRoadBuildService.updateNetworkName(networkId, name);
            if (success) {
                response.put("code", 200);
                response.put("msg", "行政区路网名称已成功更新为【" + name + "】");
                response.put("data", null);
            } else {
                response.put("code", 400);
                response.put("msg", "更新失败：未找到对应的行政区路网记录");
                response.put("data", null);
            }
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "更新行政区路网名称异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 9. 获取指定行政区路网表的背景 GeoJSON 特征要素
     */
    @RequestMapping(value = "/range", method = { RequestMethod.GET, RequestMethod.POST })
    public Map<String, Object> getRouteRange(
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "minLng", required = false) Double minLng,
            @RequestParam(value = "minLat", required = false) Double minLat,
            @RequestParam(value = "maxLng", required = false) Double maxLng,
            @RequestParam(value = "maxLat", required = false) Double maxLat) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object geoJsonFeatures = xzqRoadBuildService.getRoadRange(networkId, minLng, minLat, maxLng, maxLat);
            if (geoJsonFeatures == null) {
                response.put("code", 404);
                response.put("msg", "未从 graphs 库检索到有效要素");
                response.put("data", null);
            } else {
                response.put("code", 200);
                response.put("msg", "success");
                response.put("data", geoJsonFeatures);
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取行政区路网要素异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 10. 行政区划点对点路径规划端点 (对齐 RouteController 的 4-Combo 单行道精确算法)
     */
    @PostMapping("/plan")
    public Map<String, Object> planRoute(@RequestBody com.qskj.get_geo_pg.pojo.RouteRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (request == null || request.getStartLng() == null || request.getStartLat() == null
                    || request.getEndLng() == null || request.getEndLat() == null) {
                response.put("code", 400);
                response.put("msg", "请求参数错误：起点或终点坐标不能为空");
                response.put("data", null);
                return response;
            }

            boolean isDirected = request.getDirected() != null ? request.getDirected() : true;

            Map<String, Object> resultMap = routeService.computeRoute(
                    request.getNetworkId(),
                    request.getStartLng(), request.getStartLat(),
                    request.getEndLng(), request.getEndLat(),
                    isDirected);

            if (resultMap == null || resultMap.get("route_geojson") == null) {
                response.put("code", 404);
                response.put("msg", "起点与终点之间未查找到可达的连通路径");
                response.put("data", null);
                return response;
            }

            Number totalDistNum = (Number) resultMap.get("total_distance");
            double totalDistance = totalDistNum != null ? totalDistNum.doubleValue() : 0.0;
            String geoJsonStr = (String) resultMap.get("route_geojson");

            if (totalDistance <= 0 || geoJsonStr == null || geoJsonStr.trim().isEmpty()) {
                response.put("code", 404);
                response.put("msg", "无法找到可连通路径 (总里程为 0)");
                response.put("data", null);
                return response;
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Object geometryObj = mapper.readTree(geoJsonStr);

            com.qskj.get_geo_pg.pojo.RouteResponse data = new com.qskj.get_geo_pg.pojo.RouteResponse();
            if (resultMap.get("startNode") != null)
                data.setStartNode(((Number) resultMap.get("startNode")).longValue());
            if (resultMap.get("endNode") != null)
                data.setEndNode(((Number) resultMap.get("endNode")).longValue());
            data.setTotalDistance(totalDistance);
            data.setUnit("meters");
            data.setGeometry(geometryObj);

            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", data);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "行政区划路径规划服务端计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 11. 接收前端提交的多边形 (Polygon GeoJSON) 与名称 (name)，与 mdb1.osm.sc_road 相交并存入 mdb1.temp.temp_polygon_roads，
     * 并在 graphs 数据库中构建 3D 分层拓扑路网，注册到 sys_road_network_by_xzq
     */
    @RequestMapping(value = {"/polygon/build", "/build-polygon"}, method = {RequestMethod.POST, RequestMethod.GET})
    public Map<String, Object> buildNetworkFromPolygon(
            @RequestParam(value = "polygon", required = false) String polygon,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "networkName", required = false) String networkName,
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (body != null) {
                if (polygon == null && body.containsKey("polygon")) {
                    Object pObj = body.get("polygon");
                    polygon = (pObj instanceof String) ? (String) pObj : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(pObj);
                }
                if (name == null && body.containsKey("name")) {
                    name = (String) body.get("name");
                }
                if (networkName == null && body.containsKey("networkName")) {
                    networkName = (String) body.get("networkName");
                }
                if (networkId == null && body.containsKey("networkId")) {
                    networkId = (String) body.get("networkId");
                }
            }

            String finalName = (name != null && !name.trim().isEmpty()) ? name : networkName;
            Map<String, Object> result = xzqRoadBuildService.buildNetworkFromPolygon(polygon, finalName, networkId);
            response.put("code", 200);
            response.put("msg", "多边形路网构建成功");
            response.put("data", result);
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "构建多边形路网拓扑计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

}
