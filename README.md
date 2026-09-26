# srv-nacos-cse-config-bridge

只读 Nacos Config 兼容层，后端使用 Huawei CSE KIE Config Center。

## CSE KIE 原生维度与桥接映射

CSE KIE 的 `app`、`service`、`environment` 不是 Nacos 字段的同义词，而是 CSE 自己的配置作用域：

```text
app         = 一个应用/应用组的名称，表示应用级公共配置
service     = 该应用中的微服务名称，表示服务级配置
environment = 部署环境或运行环境，例如 development、testing、production
version     = 可选的服务版本作用域
custom label= 自定义配置维度
```

以 CSE 原生客户端为例，它会分别查询并按优先级合并 app、service、version 和 custom label 维度；同名
配置项通常由更具体的维度覆盖更通用的维度。因此 `service` 并不是“配置分组”的通用别名，
`environment` 也不天然等价于 Nacos namespace。

本项目使用的是兼容映射，而不是声称两个系统的语义完全相同：

Nacos 配置的原生唯一身份是 `source + namespace(tenant) + group + dataId`。本桥接层将它映射为：

```text
KIE project                 = 一个桥接目标/配置域
KIE label app               = 桥接源标识（由 CSE_CONFIG_APP 配置）
KIE label environment       = Nacos namespace / tenant
KIE label service           = Nacos group
KIE custom label nacos-data-id = Nacos dataId
```

`CSE_CONFIG_APP` 不是 Nacos 客户端的业务 `AppName`，也不是固定的业务服务名；它用于隔离不同的
Nacos 源。连接同一个 Nacos 源的多个代理实例应使用相同的值；不同 Nacos 集群、租户体系或配置域
应使用不同的 `CSE_CONFIG_APP`，或者使用不同的 `CSE_PROJECT`，否则相同的
`namespace + group + dataId` 可能在 KIE 中发生覆盖/共用。默认值 `nacos-config-bridge` 只适合单一源的
部署，生产环境多源部署必须显式设置，例如 `CSE_CONFIG_APP=nacos-prod`、`CSE_CONFIG_APP=nacos-test`。

### 一个 CSE 对接多个 Nacos

推荐为每个 Nacos 源运行一个桥接实例；这些实例可以共用同一个 CSE KIE 和 project，但必须使用不同的
`CSE_CONFIG_APP`：

```text
Nacos-A -> bridge-A -> CSE KIE project=default, app=nacos-a
Nacos-B -> bridge-B -> CSE KIE project=default, app=nacos-b
```

例如：

```bash
# bridge-A
CSE_CONFIG_SERVER_ADDR=http://cse:30110
CSE_PROJECT=default
CSE_CONFIG_APP=nacos-a
# Nacos 客户端连接 http://bridge-a:8080

# bridge-B
CSE_CONFIG_SERVER_ADDR=http://cse:30110
CSE_PROJECT=default
CSE_CONFIG_APP=nacos-b
# Nacos 客户端连接 http://bridge-b:8080
```

然后分别对两个桥接实例执行迁移：`bridge-A` 的 `sourceServerAddr` 指向 Nacos-A，`bridge-B` 的
`sourceServerAddr` 指向 Nacos-B。两个 Nacos 即使存在相同的
`namespace + group + dataId`，也会因为 `app` 不同而在 KIE 中隔离。

不能让一个普通 Nacos 兼容端点仅凭 `dataId/group/namespace` 同时代理多个 Nacos 源：Nacos 客户端的
请求中没有“源 Nacos 标识”，如果两个源存在相同的配置身份，桥接层无法可靠判断应该读取哪一个。
因此需要使用不同的桥接地址（不同端口、域名或实例）。同一 Nacos 源的桥接副本则应使用相同的
`CSE_CONFIG_APP`，并可以通过负载均衡共享。

CSE 中的配置项使用 KIE 支持的实际类型。迁移时优先使用源 Nacos 保存的 `type`；只有显式迁移项没有提供
类型时，才按 `dataId` 后缀兜底：`properties`/`eproperties` 使用 `properties`，`yaml`/`yml` 使用 `yaml`，
`ini` 使用 `ini`，`json` 使用 `json`，`xml` 使用 `xml`，其他格式使用 `text`。
桥接读取时直接读取 KIE 文档原始 `value`，不会因为 KIE 类型解析成扁平键值，因此配置内容和换行格式保持不变。
读取请求始终携带 `app + environment + service + nacos-data-id` 四个标签并使用 `match=exact`，且返回后再次
校验文档标签和 `key == dataId`。因此不走 Java Chassis KIE 客户端的 app/environment/service 层级合并，
一个 Nacos dataId 只返回对应的一份原文，不会把多个 YAML/Properties/JSON 文档拼接在一起。

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

只要配置了 `CSE_SERVICE_CENTER_ADDR`，面板注册默认启用；未配置地址时不会创建客户端，也不会发起
Service Center 请求。可以显式关闭：

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

如果业务客户端不能发送 `servicePort`，可以显式指定兜底地址；默认值为 0，代理不会猜测端口：

```bash
export CSE_SERVICE_CENTER_INSTANCE_PORT=8080
# 可选；不设置时使用 gRPC 对端 IP
export CSE_SERVICE_CENTER_INSTANCE_HOST=10.0.0.12
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
`NACOS_MIGRATION_ENABLED=false` 关闭。它从源 Nacos 的 HTTP Config API 读取配置，优先使用 Nacos
保存的 `type` 写入 KIE；当显式迁移项没有提供 `type` 时，才按 dataId 后缀兜底。代理的普通 Nacos
发布/删除接口仍然是只读的。

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

省略 `configs` 时，接口会分页查询 `sourceNamespace` 下的全部配置并逐项迁移，同时保留源 Nacos
返回的配置类型。显式配置项也可以直接指定 Nacos 类型，例如
`{"dataId":"settings.conf","group":"DEFAULT_GROUP","type":"json"}`；不指定时才使用
`.yaml`、`.properties` 等 dataId 后缀作为兼容性兜底。`sourceNamespace` 省略或为空表示 `public`。

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

## 资源占用与 JDK 25

桥接服务的主要资源不是配置数据，而是 Nacos 长轮询连接、HTTP socket 和每个客户端连接的
监听状态。监听器使用 JDK 虚拟线程；相同的 `dataId/group/namespace` 在多个 Nacos 客户端中
监听时会共享一个 KIE long-poll，不再为每个客户端重复建立 KIE 长连接。当前 Actuator 还提供：

- `nacos.bridge.watch.active`：唯一的 KIE long-poll 数量；
- `nacos.bridge.watch.subscribers`：Nacos 监听订阅数量。

项目使用 JDK 25 运行，并启用了：

- 虚拟线程：Nacos HTTP 长轮询、gRPC 任务和 Spring MVC 请求；
- `-XX:+UseCompactObjectHeaders`：降低对象头的内存开销；
- Docker Compose 默认 `384m` 内存上限和 `-Xms32m -Xmx256m`，防止 JVM 按宿主机内存推导出数 GB 的最大堆；
- Docker 构建阶段使用 JDK 25 `AOTMode=record` 生成 `/app/app.aot`，运行阶段通过 `AOTMode=auto` 复用。

默认限制适合当前桥接服务的低到中等连接量；大量不同 `dataId` 或很大的配置内容应通过
`BRIDGE_MEMORY_LIMIT` 和 `JAVA_TOOL_OPTIONS` 调大，并用实际连接数压测后确定，而不是盲目
把堆设得更大。容器外运行时也应显式设置 `-Xmx` 和 cgroup 内存上限。


### JVM AOT Cache

Dockerfile 使用两阶段构建生成 JDK 25 JVM AOT Cache：构建阶段短暂启动同一个应用 JAR，记录类加载和链接信息；运行阶段使用相同的 JDK 基础镜像、JAR 路径和缓存。它仍然是 JVM 应用，不是 GraalVM Native Image，主要改善启动阶段，不能减少 KIE/Nacos 长轮询连接。

AOT Cache 与应用 JAR、JDK 版本和启动参数相关，修改 JAR 后必须重新构建镜像。若部署环境需要完全自定义 JVM 参数，可以覆盖 `JAVA_TOOL_OPTIONS`，但应保留：

```text
-XX:AOTMode=auto -XX:AOTCache=/app/app.aot
```

如果缓存与运行环境不匹配，JVM 会放弃不适用的缓存内容并继续使用普通类加载；如需排查，可临时移除 AOT 参数进行对比。
