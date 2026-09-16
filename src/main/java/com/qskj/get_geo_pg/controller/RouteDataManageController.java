package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.pojo.RouteRequest;
import com.qskj.get_geo_pg.pojo.RouteRoadNamesRequest;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import com.qskj.get_geo_pg.pojo.RouteResponse;
import com.qskj.get_geo_pg.service.RouteService;
import com.qskj.get_geo_pg.service.BinaryGraphExporter;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 路网数据资产管理与构建控制器 (PostGIS 数据库端路网生命周期管理)
 * 
 * RouteDataManageController
 */

@RestController
@RequestMapping("/geo/route/data-manage")
@CrossOrigin
public class RouteDataManageController {

    @Autowired
    private RouteService routeService;

    @Autowired
    private com.qskj.get_geo_pg.service.ShpRoadBuildService shpRoadBuildService;

    @Autowired
    private com.qskj.get_geo_pg.service.XzqRoadBuildService xzqRoadBuildService;

    @Autowired
    private BinaryGraphExporter binaryGraphExporter;

    /**
     * 导出指定路网的二进制图文件 (.pgrb)
     */
    @GetMapping(value = "/graph/binary", produces = "application/octet-stream")
    public ResponseEntity<byte[]> exportBinaryGraph(
            @RequestParam(value = "networkId", required = false) String networkId) {
        try {
            byte[] binary = binaryGraphExporter.exportNetwork(networkId);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Disposition", "attachment; filename=graph.pgrb")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .body(binary);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 上传 SHP 文件夹构建新路网 (包含数据合规校验与 pgRouting 自动拓扑)
     */
    @PostMapping("/upload-shp-folder")
    public Map<String, Object> uploadShpFolder(
            @RequestParam("files") org.springframework.web.multipart.MultipartFile[] files,
            @RequestParam("networkId") String networkId,
            @RequestParam(value = "networkName", required = false) String networkName,
            @RequestParam(value = "encoding", defaultValue = "GBK") String encoding) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> buildResult = shpRoadBuildService.buildRoadNetworkFromFolder(files, networkId, networkName,
                    encoding);
            response.put("code", 200);
            response.put("msg", "构建成功");
            response.put("data", buildResult);
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", "SHP 校验未通过: " + e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "构建路网计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 按市级构建 2D 平面拓扑路网 (快捷别名端点)
     */
    @PostMapping("/build-city")
    public Map<String, Object> buildCityNetwork(
            @RequestParam("featureId") String featureId,
            @RequestParam("networkId") String networkId,
            @RequestParam(value = "networkName", required = false) String networkName) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = xzqRoadBuildService.buildCityNetwork(featureId, networkId, networkName);
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
            response.put("msg", "按市级构建路网拓扑计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 按市级构建 3D 立体分层拓扑路网 (快捷别名端点)
     */
    @PostMapping(value = {"/build-city-with-level", "/build-city-3d"})
    public Map<String, Object> buildCityNetworkWithLevel(
            @RequestParam("featureId") String featureId,
            @RequestParam("networkId") String networkId,
            @RequestParam(value = "networkName", required = false) String networkName) {
        Map<String, Object> response = new HashMap<>();
        try {
            Map<String, Object> result = xzqRoadBuildService.buildCityNetworkWithLevel(featureId, networkId, networkName);
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
            response.put("msg", "按市级构建3D立体路网拓扑计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 删除指定路网及其物理表和配置记录
     */
    @RequestMapping(value = "/delete", method = { RequestMethod.POST, RequestMethod.DELETE })
    public Map<String, Object> deleteNetwork(@RequestParam("networkId") String networkId) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = routeService.deleteRoadNetwork(networkId);
            if (success) {
                response.put("code", 200);
                response.put("msg", "路网【" + networkId + "】及其物理表已安全删除");
                response.put("data", null);
            } else {
                response.put("code", 400);
                response.put("msg", "删除失败：未找到对应的路网记录");
                response.put("data", null);
            }
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "删除路网过程发生异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 更新指定路网的显示名称
     */
    @PostMapping("/update-name")
    public Map<String, Object> updateNetworkName(
            @RequestParam("networkId") String networkId,
            @RequestParam("name") String name) {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean success = routeService.updateNetworkName(networkId, name);
            if (success) {
                response.put("code", 200);
                response.put("msg", "路网名称已成功更新为【" + name + "】");
                response.put("data", null);
            } else {
                response.put("code", 400);
                response.put("msg", "更新失败：未找到对应的路网记录");
                response.put("data", null);
            }
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
            response.put("data", null);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "更新路网名称异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 获取数据库中已配置启用的路网数据列表 (支持按 mode 过滤: 2d / 3d / shp / all)
     */
    @GetMapping("/networks")
    public Map<String, Object> getNetworks(
            @RequestParam(value = "mode", required = false, defaultValue = "all") String mode) {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", routeService.getAllNetworks(mode));
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取路网配置列表异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 获取指定路网表的路网背景 GeoJSON 特征要素
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
            Object geoJsonFeatures = routeService.getRoadRange(networkId, minLng, minLat, maxLng, maxLat);
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
            response.put("msg", "获取路网要素异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 在 graphs 库中计算最优化路径
     */
    @PostMapping("/plan")
    public Map<String, Object> planRoute(@RequestBody RouteRequest request) {
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

            // 执行精确点对点路径规划（最邻近弧段 4 节点组合最优计算 + 两端精准裁剪）
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

            RouteResponse data = new RouteResponse();
            data.setStartNode(((Number) resultMap.get("startNode")).longValue());
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
            response.put("msg", "路径规划服务端计算异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    /**
     * 获取前端 A* 规划路径沿途经过的道路名称与分段指引数据
     */
    @PostMapping("/road-names")
    public Map<String, Object> getRoadNamesAlongRoute(@RequestBody RouteRoadNamesRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (request == null) {
                response.put("code", 400);
                response.put("msg", "请求参数不能为空");
                response.put("data", null);
                return response;
            }

            List<List<Double>> coords = request.getCoordinates();
            if ((coords == null || coords.size() < 2) && request.getGeometry() != null) {
                coords = extractCoordinatesFromGeometry(request.getGeometry());
            }

            if (coords == null || coords.size() < 2) {
                response.put("code", 400);
                response.put("msg", "路线坐标点数量不足 (至少需要2个点)");
                response.put("data", null);
                return response;
            }

            Map<String, Object> data = routeService.getRoadNamesAlongRoute(request.getNetworkId(), coords);
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", data);
        } catch (Exception e) {
            e.printStackTrace();
            response.put("code", 500);
            response.put("msg", "获取沿途道路名称异常: " + e.getMessage());
            response.put("data", null);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<List<Double>> extractCoordinatesFromGeometry(Object geomObj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.valueToTree(geomObj);
            if (node.has("coordinates")) {
                com.fasterxml.jackson.databind.JsonNode coordsNode = node.get("coordinates");
                List<List<Double>> list = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode ptNode : coordsNode) {
                    if (ptNode.isArray() && ptNode.size() >= 2) {
                        list.add(Arrays.asList(ptNode.get(0).asDouble(), ptNode.get(1).asDouble()));
                    }
                }
                return list;
            }
        } catch (Exception ignored) {
        }
        return null;
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
