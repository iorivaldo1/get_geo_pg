-- 创建 excalidraw schema (如果不存在)
CREATE SCHEMA IF NOT EXISTS excalidraw;

-- 1. 创建 Excalidraw 用户表
CREATE TABLE IF NOT EXISTS excalidraw.excalidraw_user (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) DEFAULT 'admin',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. 创建 Excalidraw 左侧导航菜单表
CREATE TABLE IF NOT EXISTS excalidraw.excalidraw_nav (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    path VARCHAR(100) NOT NULL UNIQUE,
    data_structures_type VARCHAR(100) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 3. 初始默认管理员账号 (密码: aa00aa)
INSERT INTO excalidraw.excalidraw_user (username, password, role)
VALUES ('admin', 'aa00aa', 'admin')
ON CONFLICT (username) DO NOTHING;

-- 4. 初始 4 个静态导航数据
INSERT INTO excalidraw.excalidraw_nav (name, path, data_structures_type, sort_order)
VALUES 
    ('POSTGRES_R树', '/postgres-rtree', 'postgres-rtree', 1),
    ('QGIS_四叉树', '/qgis-quadtree', 'qgis-quadtree', 2),
    ('GEOSERVER_四叉树', '/geoserver-quadtree', 'geoserver-quadtree', 3),
    ('ESRI_KD树', '/esri-kdtree', 'esri-kdtree', 4)
ON CONFLICT (path) DO NOTHING;
