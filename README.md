# 黑马点评（hm-dianping）

一个基于 Spring Boot + MyBatis-Plus + Redis 的本地生活服务平台后端，提供商铺浏览、优惠券、探店博客、点赞、关注等核心业务，并以 Redis 为核心实现登录鉴权、热点数据缓存、秒杀下单等典型高并发场景。

> 本项目为教学实践项目，重点演示 Redis 在实际业务中的落地用法（缓存、令牌、分布式锁、GEO、点赞集合等），部分功能仍处于未完成状态，详见 [待完成功能](#待完成功能)。

---

## 功能特性

### 已实现

- **登录鉴权**：手机号 + 验证码登录，验证码与登录令牌统一存储于 Redis，采用双拦截器 + ThreadLocal 实现无状态会话。
- **商铺模块**：按 id 查询商铺、按类型/名称分页查询，商铺详情、商铺类型列表均接入 Redis 缓存并设置过期时间。
- **探店博客**：发布探店博文、点赞、查询我的博客、查询热门博客（按点赞数排序并回填作者信息）。
- **优惠券模块**：新增普通券、新增秒杀券（事务保证优惠券与秒杀库存一致），按商铺查询优惠券列表。
- **文件上传**：博客图片上传与删除，按哈希分目录存储。

### 待完成功能

- 用户登出（`/user/logout`）。
- 秒杀下单（`/voucher-order/seckill/{id}` 目前仅返回"功能未完成"）。
- 关注/取关及关注流推送（`FollowController` 为空）。
- 博客评论（`BlogCommentsController` 为空）。
- 商铺更新后的缓存一致性处理（`ShopServiceImpl#updateShop` 为 TODO）。

---

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 基础框架 | Spring Boot 2.3.12、Spring MVC |
| 持久层 | MyBatis-Plus 3.4.3、MySQL 8（mysql-connector-java 8.0.23） |
| 缓存/中间件 | Redis、Spring Data Redis（Lettuce 连接池 + commons-pool2） |
| 工具库 | Hutool 5.7.17、Lombok |
| 构建 | Maven、spring-boot-maven-plugin |

---

## 快速开始

### 1. 环境要求

- JDK 8 及以上（`pom.xml` 中 `java.version` 为 1.8）
- Maven 3.6+
- MySQL 8.x
- Redis 6.x

### 2. 准备数据库

在 MySQL 中创建数据库 `hmdp`，并导入项目所需的数据表（`tb_user`、`tb_shop`、`tb_blog`、`tb_voucher` 等，见 [数据模型](#数据模型)）。

### 3. 启动 Redis

确保本地 Redis 正常运行（默认 `127.0.0.1:6379`）。

### 4. 修改配置

项目通过 Spring Profile 切换环境，默认激活 `dev`（见 `application.yaml`），实际配置位于 `src/main/resources/application-dev.yaml`。请按本地环境修改数据库账号密码等信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: root
    password: your_password
  redis:
    host: 127.0.0.1
    port: 6379
```

> 提示：仓库中的 `application-dev.yaml` 含真实本地账号密码，请勿提交到公共仓库；可参考 `application-template.yaml` 作为模板。
>
> 注意：图片上传目录在 `SystemConstants.IMAGE_UPLOAD_DIR` 中硬编码为 Nginx 静态资源路径，部署前需按实际环境修改。

### 5. 启动项目

```bash
# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

启动成功后，服务监听 `http://127.0.0.1:8081`。

---

## 项目结构

```
src/main/java/com/hmdp
├── HmDianPingApplication.java   # 启动类（@MapperScan 扫描 mapper）
├── config                        # 配置类
│   ├── MvcConfig.java            # 注册登录拦截器 / Token 刷新拦截器
│   ├── MybatisConfig.java        # MyBatis-Plus 分页插件
│   └── WebExceptionAdvice.java   # 全局异常处理
├── controller                    # 接口层
├── dto                           # 传输对象（Result、UserDTO、LoginFormDTO、ScrollResult）
├── entity                        # 数据库实体（对应 tb_ 前缀表）
├── mapper                        # MyBatis-Plus Mapper 接口
├── service                       # 业务接口
│   └── impl                      # 业务实现
└── utils                         # 工具类
    ├── LoginInterceptor.java         # 登录校验拦截器
    ├── RefreshTokenInterceptor.java  # Token 刷新 + 用户上下文拦截器
    ├── UserHolder.java               # ThreadLocal 用户上下文
    ├── RedisConstants.java           # Redis Key 与 TTL 常量
    ├── RedisData.java                # 逻辑过期封装（预留）
    ├── SystemConstants.java          # 系统常量
    ├── RegexPatterns.java            # 正则常量
    ├── RegexUtils.java               # 正则校验工具
    └── PasswordEncoder.java          # 加盐 MD5 密码加密
```

---

## 数据模型

| 表名 | 说明 |
| --- | --- |
| `tb_user` | 用户表（手机号、昵称、头像） |
| `tb_user_info` | 用户详情（城市、介绍、粉丝/关注数等） |
| `tb_shop` | 商铺表（名称、类型、地址、评分、销量等） |
| `tb_shop_type` | 商铺类型表 |
| `tb_blog` | 探店博客表（关联用户与商铺） |
| `tb_blog_comments` | 博客评论表 |
| `tb_follow` | 用户关注关系表 |
| `tb_voucher` | 优惠券表 |
| `tb_seckill_voucher` | 秒杀优惠券表（与优惠券一对一，含库存与生效时间） |
| `tb_voucher_order` | 优惠券订单表 |

---

## 接口文档

所有接口统一返回 `Result` 结构：`{ "success": true, "errorMsg": null, "data": {}, "total": null }`。

### 用户 `/user`

| 方法 | 路径 | 说明 | 是否需登录 |
| --- | --- | --- | --- |
| POST | `/user/code?phone=` | 发送手机验证码 | 否 |
| POST | `/user/login` | 登录，Body 为 `{phone, code}`，成功返回 token | 否 |
| POST | `/user/logout` | 登出（待完成） | 是 |
| GET | `/user/me` | 获取当前登录用户 | 是 |
| GET | `/user/info/{id}` | 查询用户详情 | 是 |

> 登录成功后需在后续请求头中携带 `authorization: <token>`。

### 商铺 `/shop`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/shop/{id}` | 根据 id 查询商铺（走缓存） |
| POST | `/shop` | 新增商铺 |
| PUT | `/shop` | 更新商铺 |
| GET | `/shop/of/type?typeId=&current=` | 按类型分页查询（走缓存） |
| GET | `/shop/of/name?name=&current=` | 按名称分页查询 |

### 商铺类型 `/shop-type`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/shop-type/list` | 查询商铺类型列表（走缓存） |

### 博客 `/blog`

| 方法 | 路径 | 说明 | 是否需登录 |
| --- | --- | --- | --- |
| POST | `/blog` | 发布探店博文 | 是 |
| PUT | `/blog/like/{id}` | 点赞博客 | 是 |
| GET | `/blog/of/me?current=` | 查询我的博客 | 是 |
| GET | `/blog/hot?current=` | 查询热门博客 | 否 |

### 优惠券 `/voucher`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/voucher` | 新增普通优惠券 |
| POST | `/voucher/seckill` | 新增秒杀优惠券 |
| GET | `/voucher/list/{shopId}` | 查询店铺优惠券列表 |

### 秒杀订单 `/voucher-order`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单（待完成） |

### 上传 `/upload`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/upload/blog` | 上传博客图片（form-data，字段名 `file`） |
| GET | `/upload/blog/delete?name=` | 删除博客图片 |

---

## 核心实现说明

### 登录鉴权（Redis + ThreadLocal）

采用登录令牌方案替代传统 Session，链路如下：

1. `POST /user/code` 生成 6 位验证码，存入 Redis（`login:code:{phone}`，TTL 2 分钟），日志输出模拟发送。
2. `POST /user/login` 校验验证码；若手机号未注册则自动创建用户（昵称前缀 `user_`）；生成 UUID token，将用户信息以 Hash 存入 Redis（`login:token:{token}`，TTL 36000 分钟），返回 token。
3. `RefreshTokenInterceptor`（`order=0`，拦截 `/**`）：读取请求头 `authorization`，从 Redis 取出用户写入 `UserHolder`，并刷新 token 有效期。
4. `LoginInterceptor`（`order=1`）：从 `UserHolder` 判断是否登录，未登录返回 401。
5. 请求结束时在 `afterCompletion` 中清理 ThreadLocal，避免内存泄漏与线程复用问题。

`MvcConfig` 中的放行路径：`/user/code`、`/user/login`、`/blog/hot`、`/shop/**`、`/shop-type/**`、`/voucher/**`。

### 商铺缓存

- 查询以"先查 Redis，未命中再查数据库并回写"的方式实现，使用 `StrUtil.isNotBlank` 判断命中，并对热点数据续期。
- 缓存 Key 统一定义在 `RedisConstants`（如 `cache:shop:{id}`、`cache:shop:type:list`），TTL 30 分钟。

### 秒杀券

`VoucherServiceImpl#addSeckillVoucher` 通过 `@Transactional` 保证 `tb_voucher` 与 `tb_seckill_voucher` 写入的原子性。

---
