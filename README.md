# srv-nacos-cse-config-bridge

只读 Nacos Config 兼容层，后端使用 Huawei CSE KIE Config Center。

## CSE 映射

```text
app                         = nacos-config-bridge
environment                = Nacos namespace / tenant
service                    = Nacos group
custom label nacos-data-id = Nacos dataId
```

CSE 中的配置项必须使用 `text` 或 `string` 类型，以便返回原始 YAML/Properties 文本。

## 启动配置

```bash
export CSE_CONFIG_SERVER_ADDR=https://cse-config.example.com
export CSE_PROJECT=your-project
./mvnw spring-boot:run
```

原有服务只需把 Nacos 地址改为代理地址：

```bash
SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR=http://srv-nacos-cse-config-bridge:8080
```

当前 Nacos 兼容接口只实现读取和动态监听；通过 Nacos 兼容接口发布、删除会返回 `405`。
配置迁移是单独的、显式开启的导入接口，写入目标为 CSE KIE。

## CSE Service Center 面板注册

直接运行 Spring Boot 时该功能默认关闭，避免在没有 Service Center 地址的环境中发起注册请求；
关闭时不创建 Service Center 客户端，也不发起任何 Service Center 请求：

```bash
export CSE_SERVICE_CENTER_ENABLED=false
```

本仓库的 Docker Compose 自带本地 Service Center，因此 Compose 中默认启用，等价于：

```bash
export CSE_SERVICE_CENTER_ENABLED=true
```

启用后面板注册需要地址和项目；Compose 中代理访问 Service Center 的默认地址是
`http://cse:30100`：

```bash
export CSE_SERVICE_CENTER_ENABLED=true
export CSE_SERVICE_CENTER_ADDR=http://cse:30100
export CSE_SERVICE_CENTER_PROJECT=default
export CSE_SERVICE_CENTER_TENANT_NAME=default
export CSE_SERVICE_CENTER_APP_ID=default
export CSE_SERVICE_CENTER_ENVIRONMENT=development
```

CSE Service Center 的 `environment` 只接受 `development`、`testing`、`acceptance`、
`production`。Nacos namespace 通常是 UUID 或 `public`，不能直接发送，因此代理按下面规则映射：

```text
Nacos namespace 恰好是四个合法值之一 -> 使用该 Nacos namespace
其他 namespace（包括 public、UUID）   -> 使用 CSE_SERVICE_CENTER_ENVIRONMENT
Nacos namespace 为空                -> 使用 CSE_SERVICE_CENTER_ENVIRONMENT
CSE_SERVICE_CENTER_ENVIRONMENT 为空或非法 -> development
```

### 微服务名与实例标签

`AppName` 是 Nacos 客户端自带标签，用于决定 CSE 微服务名。缺少 `AppName` 时默认忽略该连接，
不会猜测服务名；只有显式配置 `CSE_SERVICE_CENTER_DEFAULT_SERVICE_NAME` 才启用兜底名称。

业务实例必须由客户端显式提供业务地址标签。推荐标准 Nacos 2.5.2 `ConfigService` 配置：

```properties
nacos.app.conn.labels=serviceHost=10.0.0.12,servicePort=8080,serviceProtocol=http
```

Nacos 2.5.2 会把 `nacos.app.conn.labels` 中的键统一加上 `app_` 前缀，因此代理实际收到的是：

```text
app_serviceHost
app_servicePort
app_serviceProtocol
```

代理同时兼容带 `app_` 和不带 `app_` 的三种标签。`serviceProtocol` 缺省为 `http`；
`serviceHost` 缺省使用 gRPC 连接的客户端远端 IP。三个值会组合成合法业务 endpoint，例如
`https://10.0.0.12:8443`。标签名可以通过以下变量修改：

```bash
export CSE_SERVICE_CENTER_SERVICE_NAME_LABEL=AppName
export CSE_SERVICE_CENTER_HOST_LABEL=serviceHost
export CSE_SERVICE_CENTER_PORT_LABEL=servicePort
export CSE_SERVICE_CENTER_PROTOCOL_LABEL=serviceProtocol
```

Nacos 2.5.2 标准标签收集器只允许标签键和值包含字母、数字、`_`、`-`、`.`，最长 128 个字符。
完整 endpoint（例如 `http://10.0.0.12:8080/health`）包含 `://`、`:`、`/`，标准客户端会在发送前丢弃，
所以默认不配置完整 endpoint 标签。可选的高级完整 URI 标签默认关闭；只有自定义 ConnectionSetup
客户端确实能发送该标签时，才通过 `CSE_SERVICE_CENTER_ENDPOINT_LABEL` 启用。

### 面板注册规则与空列表排查

- 没有业务端口标签时，只注册 CSE microservice，用于面板展示，不伪造实例。
- 有合法的业务主机和端口标签时，注册 microservice instance，按
  `CSE_SERVICE_CENTER_HEARTBEAT_INTERVAL_SECONDS` 维持心跳。
- 同一 `serviceId + endpoint` 的多个 gRPC 连接共享一个实例并做引用计数，最后一个连接关闭后注销。
- 代理绝不把 gRPC 连接的临时源端口当成业务端口。
- 可设置 `CSE_SERVICE_CENTER_INSTANCE_ENABLED=false`，此时即使提供标签也只显示微服务。
- CSE 注册仅用于面板展示；业务服务发现和流量路由仍完全基于 Kubernetes，本功能不改变 K8s Endpoints
  或 Service 路由。

面板仍为空时按下面顺序检查：

1. 确认代理环境里 `CSE_SERVICE_CENTER_ENABLED=true`、`CSE_SERVICE_CENTER_ADDR` 可从容器内访问。
2. 微服务列表需要连接完成 `ConnectionSetup`，并携带 `AppName`；否则显式设置
   `CSE_SERVICE_CENTER_DEFAULT_SERVICE_NAME`。
3. 实例列表必须额外携带合法的 `servicePort`（可选 `serviceHost`、`serviceProtocol`）。
   没有业务端口时代理只显示微服务，这是有意设计，不会把 gRPC 临时源端口伪装成业务实例。
4. 在代理日志中确认出现了 `Registered CSE microservice`；如果出现
   `Unable to mirror Nacos client`，根据异常修正 Service Center 地址、project 或 tenant 配置。

## 动态监听与 `autoRefreshed`

Nacos 2.x gRPC 的 `ConfigBatchListen` 现在立即返回，不再阻塞 KIE 的 29 秒长轮询。
代理会为未变化的 key 启动后台 KIE watch；KIE revision 或内容 MD5 变化后，通过同一条
BiStream 主动发送 `ConfigChangeNotifyRequest`。因此 Nacos 客户端和
`@NacosValue(autoRefreshed = true)` 使用的底层 listener 能立即收到变更，再重新读取 KIE
并刷新变量。

验证时可在客户端日志中寻找 `[server-push] config changed`。如果只配置了 HTTP 地址而客户端
没有连上代理的 `HTTP 端口 + 1000` gRPC 端口，动态通知不会工作；Compose 中对应关系是
`8080 -> 9080`。

## Docker Compose

先使用 Maven 生成 Spring Boot JAR，再启动 Nacos 2.5.2、CSE 2.1.5 和代理：

```bash
./mvnw -DskipTests clean package
docker compose up -d --build
```

Dockerfile 只把
`target/srv-nacos-cse-config-bridge-0.1.0-SNAPSHOT.jar` 复制进运行镜像。`.dockerignore`
允许这个 JAR 进入构建上下文，同时继续排除 `target/` 下的 class、测试报告和其他产物。

代理地址为 `http://localhost:8080`，代理 gRPC 地址为 `localhost:9080`，Nacos 地址为
`http://localhost:8848`。

端口关系如下：

```text
业务服务 HTTP -> srv-nacos-cse-config-bridge:8080 -> CSE KIE:30110
业务服务 gRPC -> srv-nacos-cse-config-bridge:9080 -> CSE KIE:30110
调试访问      -> localhost:8848       -> 原生 Nacos HTTP API/UI
原生 Nacos gRPC -> localhost:9848      -> 原生 Nacos Config
```

Nacos 客户端会按“HTTP 端口 + 1000”计算 Config gRPC 端口，所以代理的 `8080` 必须同时暴露
`9080`。原生 Nacos 的 `8848 -> 9848` 只用于对比测试和直接访问原 Nacos。
原生 Nacos 的 `9849` 是集群/server gRPC 端口，`7848` 是 Raft 端口；本项目不做代理集群，
不需要在代理上实现或暴露它们。

代理 gRPC 已支持 Nacos 2.x Config 的 server-check、connection setup、ConfigQuery 和
ConfigBatchListen。代理不保存配置内容；每次读取和动态监听都直接查询 KIE，KIE 不可用时不返回旧值。

## 对比测试程序

先在原生 Nacos 和 CSE 中准备同一个映射配置，然后执行：

```bash
./mvnw -Dtest=NacosConfigReadComparisonTest \
  -Dnacos.comparison.enabled=true \
  -Dnacos.source.addr=127.0.0.1:8848 \
  -Dnacos.proxy.addr=127.0.0.1:8080 \
  -Dnacos.data-id=application.yaml \
  -Dnacos.group=DEFAULT_GROUP test
```

这个测试使用 Nacos 2.5.2 `ConfigService` 分别连接原生 Nacos 和代理，比较两边返回的原始文本。
如果使用 namespace，再增加 `-Dnacos.namespace=<namespace>`。

## 配置迁移接口

直接运行 Spring Boot 时迁移接口默认关闭，启用时设置 `NACOS_MIGRATION_ENABLED=true`；
本仓库的 Docker Compose 为便于本地验证默认开启，可通过
`NACOS_MIGRATION_ENABLED=false` 关闭。它从源 Nacos 的 HTTP Config API 读取配置，向 KIE 创建
`text` 类型的 KV；代理的普通 Nacos 发布/删除接口仍然是只读的。

指定配置迁移：

```bash
curl -X POST http://localhost:8080/srv-nacos-cse-config-bridge/v1/migration/configs \
  -H 'Content-Type: application/json' \
  -d '{
    "sourceServerAddr": "http://nacos:8848",
    "sourceNamespace": "dev",
    "overwrite": false,
    "configs": [
      {"dataId": "application.yaml", "group": "DEFAULT_GROUP"}
    ]
  }'
```

省略 `configs` 时，接口会分页查询 `sourceNamespace` 下的全部配置并逐项迁移；
`sourceNamespace` 省略或为空表示 `public`。

先向源 Nacos 登录并取得短时 `accessToken`。它必须放在迁移 JSON 的顶层字段
`sourceAccessToken` 中；不要传用户名、`NACOS_AUTH_TOKEN` JWT 密钥或 `Bearer` 前缀：

```bash
ACCESS_TOKEN="$(curl -fsS -X POST http://localhost:8848/nacos/v1/auth/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'username=nacos&password=nacos' \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["accessToken"])')"

curl -X POST http://localhost:8080/srv-nacos-cse-config-bridge/v1/migration/configs \
  -H 'Content-Type: application/json' \
  -d "{
    \"sourceServerAddr\": \"http://nacos:8848\",
    \"sourceAccessToken\": \"$ACCESS_TOKEN\",
    \"overwrite\": false,
    \"allNamespaces\": true
  }"
```

`/nacos/v1/console/namespaces` 在当前配置下允许匿名访问，但 `/nacos/v1/cs/configs`
开启鉴权后不允许匿名访问。Nacos 返回 `user not found!` 通常表示实际请求中的
`accessToken` 为空，因此迁移接口必须传入登录响应里的 `accessToken`；传错值则会返回
`token invalid!`。

`allNamespaces=true` 会先查询源 Nacos 的所有命名空间，再分页查询并迁移每个命名空间的全部配置；
此时忽略 `sourceNamespace`，并且不能同时传入非空的 `configs`。

将 `overwrite` 设为 `true` 可按固定标签查找已有 KIE 项并更新；迁移结果会逐项返回
`SUCCESS`、`SKIPPED` 或 `FAILED`。

Compose 默认把 KIE 地址配置为 `http://cse:30110`，项目配置为 `default`。
如果 `ghcr.io/renfei/cse:2.1.5` 的 KIE 监听端口不是 `30110`，需要同步修改
`docker-compose.yml` 中的 `CSE_CONFIG_SERVER_ADDR`。

## 版本说明

项目使用 Spring Boot 3、Nacos client 2.5.2 的协议类和 `config-kie-client`，不依赖 Spring Cloud。
Java Chassis 版本由 `java-chassis-dependencies` 的 `java-chassis.version` 控制，当前为 3.3.0：

```bash
./mvnw test
```
