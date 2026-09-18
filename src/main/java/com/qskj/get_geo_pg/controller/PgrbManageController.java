package com.qskj.get_geo_pg.controller;

import com.qskj.get_geo_pg.pojo.PgrbFileManage;
import com.qskj.get_geo_pg.pojo.PgrbRoutePath;
import com.qskj.get_geo_pg.service.BinaryGraphExporter;
import com.qskj.get_geo_pg.service.PgrbRouterService;
import com.qskj.get_geo_pg.service.PgrbStorageService;
import com.qskj.get_geo_pg.service.XzqRoadBuildService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PGRB 二进制路网存储与管控控制器 (支持 PGBB 边界与 PGRP 路径双二进制协议)
 */
@Slf4j
@CrossOrigin
@RestController
@RequestMapping("/geo/route/pgrb")
public class PgrbManageController {

    @Autowired
    private PgrbStorageService pgrbStorageService;

    @Autowired
    private XzqRoadBuildService xzqRoadBuildService;

    @Autowired
    private BinaryGraphExporter binaryGraphExporter;

    @Autowired
    private PgrbRouterService pgrbRouterService;

    /**
     * 1. 构建行政区 3D 拓扑路网并直接在服务端落盘生成 .pgrb 文件
     */
    @RequestMapping(value = "/build-and-save", method = {RequestMethod.POST, RequestMethod.GET})
    public Map<String, Object> buildAndSave(
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "featureId", required = false) String featureId,
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "networkName", required = false) String networkName,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (level == null) level = request.getParameter("level");
            if (featureId == null) featureId = request.getParameter("featureId");
            if (networkId == null) networkId = request.getParameter("networkId");
            if (networkName == null) networkName = request.getParameter("networkName");

            if (level == null || featureId == null || networkId == null) {
                response.put("code", 400);
                response.put("msg", "参数缺失：level, featureId 与 networkId 为必填项！");
                return response;
            }

            if (networkName == null || networkName.trim().isEmpty()) {
                networkName = networkId;
            }

            log.info("[PGRB Manage] 步骤一：开始构建 3D 立体分层路网拓扑: networkId={}, level={}, featureId={}", networkId, level, featureId);
            Map<String, Object> buildResult = xzqRoadBuildService.buildNetworkWithLevelFromXzq(level, featureId, networkId, networkName);

            log.info("[PGRB Manage] 步骤二：提取全量 CSR 拓扑并编译为 PGRB 二进制数据: {}", networkId);
            byte[] binaryData = binaryGraphExporter.exportNetwork(networkId);

            log.info("[PGRB Manage] 步骤三：保存 PGRB 文件到服务器磁盘并同步数据库: {}", networkId);
            PgrbFileManage fileRecord = pgrbStorageService.savePgrbFile(networkId, networkName, level, binaryData);

            // 清理对应路网旧内存缓存
            pgrbRouterService.evictCache(networkId);

            Map<String, Object> data = new HashMap<>();
            data.put("networkId", fileRecord.getNetworkId());
            data.put("networkName", fileRecord.getNetworkName());
            data.put("level", fileRecord.getLevel());
            data.put("fileName", fileRecord.getFileName());
            data.put("filePath", fileRecord.getFilePath());
            data.put("fileSize", fileRecord.getFileSize());
            data.put("fileSizeFmt", fileRecord.getFileSizeFmt());
            data.put("nodeCount", fileRecord.getNodeCount());
            data.put("edgeCount", fileRecord.getEdgeCount());
            data.put("pointCount", fileRecord.getPointCount());
            data.put("boundaryPointCount", fileRecord.getBoundaryPointCount());
            data.put("centerLng", fileRecord.getCenterLng());
            data.put("centerLat", fileRecord.getCenterLat());
            data.put("buildTime", fileRecord.getBuildTime());
            data.put("msg", "✅ 服务端路网拓扑构建并已成功生成 .pgrb 文件落盘！(" + fileRecord.getFileSizeFmt() + ")");

            response.put("code", 200);
            response.put("msg", "路网构建与二进制落盘成功");
            response.put("data", data);
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("msg", e.getMessage());
        } catch (Exception e) {
            log.error("[PGRB Manage] 构建路网与保存二进制异常: {}", e.getMessage(), e);
            response.put("code", 500);
            response.put("msg", "服务端构建与落盘异常: " + e.getMessage());
        }
        return response;
    }

    /**
     * 2. 查询服务器已管理的所有 PGRB 路网文件列表
     */
    @GetMapping("/list")
    public Map<String, Object> getPgrbList(
            @RequestParam(value = "level", required = false, defaultValue = "all") String level,
            @RequestParam(value = "keyword", required = false) String keyword) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<PgrbFileManage> list = pgrbStorageService.getPgrbFileList(level, keyword);
            response.put("code", 200);
            response.put("msg", "success");
            response.put("data", list);
        } catch (Exception e) {
            log.error("[PGRB Manage] 获取路网文件列表失败: {}", e.getMessage(), e);
            response.put("code", 500);
            response.put("msg", "获取路网文件列表失败: " + e.getMessage());
        }
        return response;
    }

    /**
     * 3. 极速读取/下载指定路网的 PGRB 二进制流（保留向后兼容）
     */
    @GetMapping(value = "/file", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getPgrbFile(@RequestParam("networkId") String networkId) {
        try {
            byte[] binaryData = pgrbStorageService.getPgrbFileBytes(networkId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", networkId + ".pgrb");
            headers.setContentLength(binaryData.length);
            headers.setCacheControl("no-cache, no-store, must-revalidate");
            return new ResponseEntity<>(binaryData, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("[PGRB Manage] 读取 PGRB 文件异常 [{}]: {}", networkId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 4. 重新编译指定路网的 PGRB 二进制文件
     */
    @RequestMapping(value = "/recompile", method = {RequestMethod.POST, RequestMethod.GET})
    public Map<String, Object> recompilePgrb(
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "level", required = false) String level,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (networkId == null) networkId = request.getParameter("networkId");
            if (level == null) level = request.getParameter("level");
            if (networkId == null || networkId.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "networkId 不能为空！");
                return response;
            }

            PgrbFileManage updated = pgrbStorageService.recompilePgrb(networkId, level);
            // 联动清除内存图缓存，保证下次算路读取最新拓扑
            pgrbRouterService.evictCache(networkId);

            response.put("code", 200);
            response.put("msg", "重新编译成功");
            response.put("data", updated);
        } catch (Exception e) {
            log.error("[PGRB Manage] 重新编译失败: {}", e.getMessage(), e);
            response.put("code", 500);
            response.put("msg", "重新编译失败: " + e.getMessage());
        }
        return response;
    }

    /**
     * 5. 删除指定的路网文件（支持可选级联清理数据库拓扑表）
     */
    @RequestMapping(value = "/delete", method = {RequestMethod.POST, RequestMethod.GET, RequestMethod.DELETE})
    public Map<String, Object> deletePgrb(
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "deleteDbTable", required = false, defaultValue = "false") boolean deleteDbTable,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (networkId == null) networkId = request.getParameter("networkId");
            String delParam = request.getParameter("deleteDbTable");
            if (delParam != null) {
                deleteDbTable = Boolean.parseBoolean(delParam);
            }

            if (networkId == null || networkId.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "networkId 不能为空！");
                return response;
            }

            boolean ok = pgrbStorageService.deletePgrbFile(networkId, deleteDbTable);
            if (ok) {
                pgrbRouterService.evictCache(networkId);
                response.put("code", 200);
                response.put("msg", "删除路网文件成功" + (deleteDbTable ? " (已级联清理物理拓扑表)" : ""));
            } else {
                response.put("code", 500);
                response.put("msg", "删除路网文件失败");
            }
        } catch (Exception e) {
            log.error("[PGRB Manage] 删除路网失败: {}", e.getMessage(), e);
            response.put("code", 500);
            response.put("msg", "删除异常: " + e.getMessage());
        }
        return response;
    }

    /**
     * 6. 修改路网显示名称
     */
    @RequestMapping(value = "/update-name", method = {RequestMethod.POST, RequestMethod.GET})
    public Map<String, Object> updateNetworkName(
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "networkName", required = false) String networkName,
            HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (networkId == null) networkId = request.getParameter("networkId");
            if (networkName == null) networkName = request.getParameter("networkName");

            if (networkId == null || networkName == null || networkName.trim().isEmpty()) {
                response.put("code", 400);
                response.put("msg", "networkId 和 networkName 不能为空！");
                return response;
            }

            boolean ok = pgrbStorageService.updateNetworkName(networkId, networkName.trim());
            if (ok) {
                response.put("code", 200);
                response.put("msg", "修改路网名称成功");
            } else {
                response.put("code", 500);
                response.put("msg", "修改路网名称失败");
            }
        } catch (Exception e) {
            log.error("[PGRB Manage] 修改名称失败: {}", e.getMessage(), e);
            response.put("code", 500);
            response.put("msg", "修改名称异常: " + e.getMessage());
        }
        return response;
    }

    // =========================================================================
    // 核心安全算路 API：杜绝全量路网二进制外泄，仅下发轻量元数据、PGBB边界二进制与PGRP路径折线
    // =========================================================================

    /**
     * 7. 获取路网轻量元数据 (包含基础属性与 BBOX 包围盒)
     */
    @GetMapping("/meta")
    public Map<String, Object> getNetworkMeta(@RequestParam("networkId") String networkId) {
        return pgrbRouterService.getNetworkMeta(networkId);
    }

    /**
     * 8. 获取边界紧凑二进制流 (PGBB 协议规范，仅约 30KB，大幅优于 150KB 的 GeoJSON 文本)
     */
    @GetMapping(value = "/boundary-binary", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getBoundaryBinary(@RequestParam("networkId") String networkId) {
        byte[] bytes = pgrbRouterService.getBoundaryBinary(networkId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("attachment", networkId + ".pgbb");
        headers.setContentLength(bytes.length);
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /**
     * 9. 服务端内存高速算路 (二进制 PGRP 协议，仅约 2.5KB，微秒级解析)
     */
    @RequestMapping(value = "/plan-binary", method = {RequestMethod.POST, RequestMethod.GET}, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> planRouteBinary(
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "startLng", required = false) Double startLng,
            @RequestParam(value = "startLat", required = false) Double startLat,
            @RequestParam(value = "endLng", required = false) Double endLng,
            @RequestParam(value = "endLat", required = false) Double endLat,
            @RequestParam(value = "directed", required = false, defaultValue = "true") Boolean directed,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        if (body != null) {
            if (networkId == null && body.containsKey("networkId")) networkId = String.valueOf(body.get("networkId"));
            if (startLng == null && body.containsKey("startLng")) startLng = Double.parseDouble(String.valueOf(body.get("startLng")));
            if (startLat == null && body.containsKey("startLat")) startLat = Double.parseDouble(String.valueOf(body.get("startLat")));
            if (endLng == null && body.containsKey("endLng")) endLng = Double.parseDouble(String.valueOf(body.get("endLng")));
            if (endLat == null && body.containsKey("endLat")) endLat = Double.parseDouble(String.valueOf(body.get("endLat")));
            if (body.containsKey("directed")) directed = Boolean.parseBoolean(String.valueOf(body.get("directed")));
        }

        if (networkId == null) networkId = request.getParameter("networkId");
        if (startLng == null && request.getParameter("startLng") != null) startLng = Double.parseDouble(request.getParameter("startLng"));
        if (startLat == null && request.getParameter("startLat") != null) startLat = Double.parseDouble(request.getParameter("startLat"));
        if (endLng == null && request.getParameter("endLng") != null) endLng = Double.parseDouble(request.getParameter("endLng"));
        if (endLat == null && request.getParameter("endLat") != null) endLat = Double.parseDouble(request.getParameter("endLat"));
        if (request.getParameter("directed") != null) directed = Boolean.parseBoolean(request.getParameter("directed"));

        if (networkId == null || startLng == null || startLat == null || endLng == null || endLat == null) {
            PgrbRoutePath errPath = PgrbRoutePath.notFound("缺少必填参数: networkId, startLng, startLat, endLng, endLat");
            byte[] errBytes = errPath.toBinary();
            return ResponseEntity.badRequest().body(errBytes);
        }

        PgrbRoutePath path = pgrbRouterService.planSingleRoute(networkId, startLng, startLat, endLng, endLat, directed != null && directed);
        byte[] bytes = path.toBinary();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(bytes.length);
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /**
     * 10. 服务端内存高速算路 (JSON 格式降级兼容接口)
     */
    @RequestMapping(value = "/plan", method = {RequestMethod.POST, RequestMethod.GET})
    public Map<String, Object> planRoute(
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "startLng", required = false) Double startLng,
            @RequestParam(value = "startLat", required = false) Double startLat,
            @RequestParam(value = "endLng", required = false) Double endLng,
            @RequestParam(value = "endLat", required = false) Double endLat,
            @RequestParam(value = "directed", required = false, defaultValue = "true") Boolean directed,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        if (body != null) {
            if (networkId == null && body.containsKey("networkId")) networkId = String.valueOf(body.get("networkId"));
            if (startLng == null && body.containsKey("startLng")) startLng = Double.parseDouble(String.valueOf(body.get("startLng")));
            if (startLat == null && body.containsKey("startLat")) startLat = Double.parseDouble(String.valueOf(body.get("startLat")));
            if (endLng == null && body.containsKey("endLng")) endLng = Double.parseDouble(String.valueOf(body.get("endLng")));
            if (endLat == null && body.containsKey("endLat")) endLat = Double.parseDouble(String.valueOf(body.get("endLat")));
            if (body.containsKey("directed")) directed = Boolean.parseBoolean(String.valueOf(body.get("directed")));
        }

        if (networkId == null) networkId = request.getParameter("networkId");
        if (startLng == null && request.getParameter("startLng") != null) startLng = Double.parseDouble(request.getParameter("startLng"));
        if (startLat == null && request.getParameter("startLat") != null) startLat = Double.parseDouble(request.getParameter("startLat"));
        if (endLng == null && request.getParameter("endLng") != null) endLng = Double.parseDouble(request.getParameter("endLng"));
        if (endLat == null && request.getParameter("endLat") != null) endLat = Double.parseDouble(request.getParameter("endLat"));
        if (request.getParameter("directed") != null) directed = Boolean.parseBoolean(request.getParameter("directed"));

        if (networkId == null || startLng == null || startLat == null || endLng == null || endLat == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("message", "缺少必填参数: networkId, startLng, startLat, endLng, endLat");
            return err;
        }

        PgrbRoutePath path = pgrbRouterService.planSingleRoute(networkId, startLng, startLat, endLng, endLat, directed != null && directed);
        return path.toResponseMap();
    }

    /**
     * 11. 服务端批量路径规划 (1对N 或 N对1)
     */
    @PostMapping("/plan-batch")
    public Map<String, Object> planBatchRoutes(@RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("networkId") || !body.containsKey("mode")) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("message", "缺少必填参数: networkId, mode, center, points");
            return err;
        }

        String networkId = String.valueOf(body.get("networkId"));
        String mode = String.valueOf(body.get("mode")); // '1_to_n' | 'n_to_1'
        boolean directed = !body.containsKey("directed") || Boolean.parseBoolean(String.valueOf(body.get("directed")));

        Map<String, Object> center = (Map<String, Object>) body.get("center");
        double centerLng = Double.parseDouble(String.valueOf(center.get("lng")));
        double centerLat = Double.parseDouble(String.valueOf(center.get("lat")));

        List<Map<String, Object>> points = (List<Map<String, Object>>) body.get("points");

        return pgrbRouterService.planBatchRoutes(networkId, mode, centerLng, centerLat, points, directed);
    }

    /**
     * 12. A* 寻径动画接口 (支持优先队列堆数据同步/分步动画渲染)
     */
    @RequestMapping(value = {"/route_animate", "/route-animate"}, method = {RequestMethod.POST, RequestMethod.GET})
    public Map<String, Object> routeAnimate(
            @RequestParam(value = "networkId", required = false) String networkId,
            @RequestParam(value = "startLng", required = false) Double startLng,
            @RequestParam(value = "startLat", required = false) Double startLat,
            @RequestParam(value = "endLng", required = false) Double endLng,
            @RequestParam(value = "endLat", required = false) Double endLat,
            @RequestParam(value = "directed", required = false, defaultValue = "true") Boolean directed,
            @RequestParam(value = "maxSteps", required = false, defaultValue = "0") Integer maxSteps,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        if (body != null) {
            if (networkId == null && body.containsKey("networkId")) networkId = String.valueOf(body.get("networkId"));
            if (startLng == null && body.containsKey("startLng")) startLng = Double.parseDouble(String.valueOf(body.get("startLng")));
            if (startLat == null && body.containsKey("startLat")) startLat = Double.parseDouble(String.valueOf(body.get("startLat")));
            if (endLng == null && body.containsKey("endLng")) endLng = Double.parseDouble(String.valueOf(body.get("endLng")));
            if (endLat == null && body.containsKey("endLat")) endLat = Double.parseDouble(String.valueOf(body.get("endLat")));
            if (body.containsKey("directed")) directed = Boolean.parseBoolean(String.valueOf(body.get("directed")));
            if (body.containsKey("maxSteps")) maxSteps = Integer.parseInt(String.valueOf(body.get("maxSteps")));
        }

        if (networkId == null) networkId = request.getParameter("networkId");
        if (startLng == null && request.getParameter("startLng") != null) startLng = Double.parseDouble(request.getParameter("startLng"));
        if (startLat == null && request.getParameter("startLat") != null) startLat = Double.parseDouble(request.getParameter("startLat"));
        if (endLng == null && request.getParameter("endLng") != null) endLng = Double.parseDouble(request.getParameter("endLng"));
        if (endLat == null && request.getParameter("endLat") != null) endLat = Double.parseDouble(request.getParameter("endLat"));
        if (request.getParameter("directed") != null) directed = Boolean.parseBoolean(request.getParameter("directed"));
        if (request.getParameter("maxSteps") != null) maxSteps = Integer.parseInt(request.getParameter("maxSteps"));

        if (networkId == null || startLng == null || startLat == null || endLng == null || endLat == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "缺少必填参数: networkId, startLng, startLat, endLng, endLat");
            return err;
        }

        // 校验 xzq 行政级别：市级路网规模庞大，动画演进数据量过大，禁止调用动画分析
        String netLevel = pgrbStorageService.getNetworkLevel(networkId);
        boolean isCity = "city".equalsIgnoreCase(netLevel)
                || (netLevel != null && (netLevel.contains("市") || netLevel.contains("city")))
                || (networkId != null && (networkId.startsWith("xzq_city_") || networkId.contains("_city_")));
        if (isCity) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 400);
            err.put("msg", "市级行政区路网规模庞大、动画演进数据量超限，暂不支持 A* 动画演进分析，请切换至区县级或更小范围路网！");
            return err;
        }

        return pgrbRouterService.planRouteAnimate(networkId, startLng, startLat, endLng, endLat, directed != null && directed, maxSteps);
    }
}

