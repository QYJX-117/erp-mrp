# ERP-MRP · 物料需求计划演示系统

一个可直接运行的 MRP（Material Requirements Planning）教学演示系统：维护物料、BOM 与主生产计划，一键展开多级物料需求，输出按周期排列的订货计划，并支持 Excel 导出。

<p>
  <img alt="Java" src="https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white" />
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.1.5-6DB33F?logo=springboot&logoColor=white" />
  <img alt="MyBatis-Plus" src="https://img.shields.io/badge/MyBatis--Plus-3.5.5-1E90FF" />
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-blue" />
</p>

---

## 功能

| 模块 | 说明 |
|---|---|
| **物料主数据** | 物料编码、名称、期初库存、安全库存、已分配量、批量规则（FOQ）、批量、提前期 |
| **BOM 管理** | 父件、子件、单位用量；支持多级 BOM |
| **主生产计划 MPS** | 按物料 + 周期录入毛需求 |
| **MRP 运算** | 自动推导低位码，逐层计算净需求、计划产出、计划投入与下达周期 |
| **结果视图** | 矩阵视图、单物料逐期明细（含中间变量） |
| **导出** | MRP 结果、物料、BOM、MPS 均可导出 Excel |
| **登录** | 数据库用户校验；演示环境为明文密码 |

### MRP 计算逻辑

```text
① 低位码：从 BOM 图推导每个物料的最深层级，父件先算、子件后算
② 毛需求：MPS 需求 + Σ(父件计划投入 × 单位用量)
③ 净需求：max(0, 毛需求 + 安全库存 - 可用库存)
④ 计划产出：FOQ 时向上取整到批量倍数，否则等于净需求
⑤ 下达周期：需求周期 - 提前期，小于 1 时取 1
⑥ 把计划产出按下达周期传递给子件，继续下一层
```

代码位于 `service/MrpCalculatorService.java`，主要方法：

- `computeLowLevelCodes()`：从 BOM 图计算低位码，不依赖手工维护的 `level` 字段；
- `calculateAndSave()`：清空上次结果 → 逐层展开 → 保存 `t_mrp_result`；
- `getMatrixData()` / `getMaterialDetail()`：供前端矩阵视图和明细视图使用。

---

## 技术栈

| 层级 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.1.5、MyBatis-Plus 3.5.5 |
| 数据库 | MySQL 8 |
| 导出 | EasyExcel 3.3.2 |
| 前端 | 原生 HTML + Vue 3（CDN）+ Element Plus，由 Spring Boot 直接托管 |

## 目录结构

```text
erp-mrp/
├── src/main/java/com/example/erpmrp/
│   ├── controller/MrpController.java      # 全部 REST 接口
│   ├── entity/                            # Bom、Material、Mps、MrpResult、User
│   ├── mapper/                            # MyBatis-Plus Mapper
│   └── service/
│       ├── MrpCalculatorService.java      # MRP 核心计算
│       └── ExcelExportService.java        # Excel 导出
├── src/main/resources/
│   ├── application.properties
│   └── static/index.html                  # 单页管理界面
├── sql/schema.sql                         # 5 张表
├── sql/seed.sql                           # 演示物料/BOM/MPS/账号
└── pom.xml
```

---

## 快速开始

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE erp_mrp DEFAULT CHARACTER SET utf8mb4;"
mysql -uroot -p erp_mrp < sql/schema.sql
mysql -uroot -p erp_mrp < sql/seed.sql
```

### 2. 启动

```bash
export DB_USER=root
export DB_PASSWORD=your_password        # PowerShell: $env:DB_PASSWORD="..."

./mvnw spring-boot:run
```

浏览器打开 `http://localhost:8080`，演示账号 **admin / 123456**。

> 需要 JDK 17+；首次运行 `mvnw` 会自动下载 Maven。

### 3. 体验流程

1. 在「物料」页确认或新增物料（含安全库存、批量规则、提前期）；
2. 在「BOM」页维护父子件与单位用量；
3. 在「MPS」页录入某物料的周期毛需求；
4. 点「运行 MRP」，查看矩阵与单物料明细；
5. 点「导出」下载 Excel。

---

## 主要接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/login` | 登录 |
| GET/POST/DELETE | `/api/materials` | 物料查询、保存、删除 |
| GET/POST/DELETE | `/api/boms` | BOM 查询、保存、删除 |
| GET/POST/DELETE | `/api/mps` | MPS 查询、保存、删除 |
| POST | `/api/run-mrp` | 执行 MRP 运算 |
| GET | `/api/results` | MRP 结果（可按物料筛选） |
| GET | `/api/mrp-matrix` | 矩阵视图数据 |
| GET | `/api/mrp-detail/{materialCode}` | 单物料逐期明细 |
| GET | `/api/export` | 导出 MRP 结果 |
| GET | `/api/export-materials` | 导出物料主数据 |
| GET | `/api/export-boms` | 导出 BOM |
| GET | `/api/export-mps` | 导出 MPS |

## 数据表

| 表 | 说明 |
|---|---|
| `t_material` | 物料主数据 |
| `t_bom` | BOM 父子件关系 |
| `t_mps` | 主生产计划 |
| `t_mrp_result` | MRP 运算结果 |
| `t_user` | 登录用户 |

## 说明

- 本项目用于课程演示，登录密码为明文存储，未接入 Spring Security；生产环境请改为 BCrypt 并增加权限控制。
- `sql/seed.sql` 为演示数据，不包含真实企业数据。

## License

[Apache License 2.0](LICENSE)
