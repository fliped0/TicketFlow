# V2 接口设计草案（尚未实现）

2026-10-06，设计版本0.1。真实OpenAPI仍为0.5.0、27个HTTP操作；本文件不表示路由已部署。

完整契约、状态与错误语义见[异步协议第3节](../03_V2异步抢票协议.md#3-接口草案与边界)。新增计划为异步提交、本人结果查询和管理员场次模式配置，金额和身份不由客户端输入。

```http
POST /api/v1/purchase-requests
Authorization: Bearer <token>
Idempotency-Key: <16-64 ASCII characters>
Content-Type: application/json

{"tierId":"123","quantity":1}
```

设计响应示例，不是实测：

```json
{
  "code":"OK",
  "message":"已受理",
  "data":{
    "requestId":"00000000-0000-4000-8000-000000000001",
    "state":"ACCEPTED",
    "acceptedAt":"2026-10-06T08:00:00Z",
    "createDeadline":"2026-10-06T08:00:30Z",
    "orderId":null,
    "failureCode":null,
    "currentOrderStatus":null
  },
  "traceId":"example",
  "replayed":false
}
```

202+Location仅在数据库可靠提交后返回。GET为200持久状态、404本人不可见；202非终态原键重放，200已受理终态POST重放，受理前业务拒绝保持原HTTP拒绝结果。提交未知503必须原键恢复。限流429不持久化为该键的业务失败。ASYNC场次旧同步入口409，不改变原成功201契约。

批次9将为真实路由加入正式OpenAPI；批次6只检查草案与数据库/状态协议一致性。
