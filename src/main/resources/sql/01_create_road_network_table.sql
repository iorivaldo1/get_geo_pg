-- 1. 上传 SHP 模式路网配置 (upload_shp_road)
CREATE SCHEMA IF NOT EXISTS upload_shp_road;

CREATE TABLE IF NOT EXISTS upload_shp_road.sys_road_network (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    road_table VARCHAR(128) NOT NULL,
    noded_table VARCHAR(128) NOT NULL,
    center_lng NUMERIC(10, 6) DEFAULT 104.114000,
    center_lat NUMERIC(10, 6) DEFAULT 30.632000,
    default_zoom INT DEFAULT 15,
    sort_order INT DEFAULT 10,
    status INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE upload_shp_road.sys_road_network IS '上传 SHP 模式多路网配置表';
COMMENT ON COLUMN upload_shp_road.sys_road_network.id IS '路网唯一标识Code';
COMMENT ON COLUMN upload_shp_road.sys_road_network.name IS '路网前端显示名称';
COMMENT ON COLUMN upload_shp_road.sys_road_network.road_table IS '背景矢量要素表名';
COMMENT ON COLUMN upload_shp_road.sys_road_network.noded_table IS '拓扑网络断弧边表名';

-- 2. 行政区划相交建图路网配置 (xzq_road)
CREATE SCHEMA IF NOT EXISTS xzq_road;

CREATE TABLE IF NOT EXISTS xzq_road.sys_road_network_by_xzq (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    road_table VARCHAR(128) NOT NULL,
    noded_table VARCHAR(128) NOT NULL,
    center_lng NUMERIC(10, 6) DEFAULT 104.114000,
    center_lat NUMERIC(10, 6) DEFAULT 30.632000,
    default_zoom INT DEFAULT 15,
    sort_order INT DEFAULT 10,
    status INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE xzq_road.sys_road_network_by_xzq IS '行政区划相交多路网配置表';
COMMENT ON COLUMN xzq_road.sys_road_network_by_xzq.id IS '路网唯一标识Code';
COMMENT ON COLUMN xzq_road.sys_road_network_by_xzq.name IS '路网前端显示名称';
COMMENT ON COLUMN xzq_road.sys_road_network_by_xzq.road_table IS '背景矢量要素表名';
COMMENT ON COLUMN xzq_road.sys_road_network_by_xzq.noded_table IS '拓扑网络断弧边表名';
