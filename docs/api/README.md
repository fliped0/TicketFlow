# TicketFlow 接口文档

版本 0.1.0，基地址 `http://localhost:8080`。本批仅实现用户认证与健康检查；其余接口仍属 [详细设计](../03_详细设计.md) 的规划。

[OpenAPI 3.1 JSON](openapi.json) 可导入 Apifox / Postman。当前不提供在线 Swagger UI。

## 1. 通用协议

JSON 请求及响应，ID 使用十进制字符串。业务响应包含 code、message、data、traceId、replayed；本批 replayed 恒为 false。响应头 X-Trace-Id 与响应 traceId 一致，健康检查使用 Actuator 原生响应。

账号为 4—32 位 ASCII 字母、数字或下划线，注册和登录均转为小写。密码为 8—64 个 Unicode 码点，不删除空格、不截断。请求包含未知字段（如 role）返回 400。

JWT 使用 RS256，有效期 1800 秒，验证签名、issuer、audience、exp、nbf，容差 30 秒。受保护接口携带 `Authorization: Bearer <accessToken>`。每次请求检查账号启用状态和当前角色。不提供刷新令牌、注销或修改密码接口。

## 2. 已实现接口

| 方法 | 路径 | 认证 | 成功结果 |
| --- | --- | --- | --- |
| POST | /api/v1/auth/register | 无 | 201，userId、username、role=USER |
| POST | /api/v1/auth/login | 无 | 200，accessToken、tokenType、expiresIn |
| GET | /api/v1/users/me | Bearer | 200，userId、username、role |
| GET | /actuator/health | 无 | 200，status=UP，无组件详情 |

### 2.1 注册

```http
POST /api/v1/auth/register
Content-Type: application/json

{"username":"example_user","password":"ExampleOnly_123"}
```

```json
{
  "code": "OK",
  "message": "成功",
  "data": {"userId": "1", "username": "example_user", "role": "USER"},
  "traceId": "00000000-0000-4000-8000-000000000000",
  "replayed": false
}
```

示例账号不会预置到数据库。重复账号（包括仅大小写不同）返回 409 / USERNAME_EXISTS。

### 2.2 登录

请求体与注册相同。成功响应 data：

```json
{"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":1800}
```

账号不存在、密码错误和禁用账号统一返回 401 / BAD_CREDENTIALS。

### 2.3 当前用户

```http
GET /api/v1/users/me
Authorization: Bearer <JWT>
```

成功 data 与注册一致。JWT 无效、缺少令牌或账号已禁用返回 401 / UNAUTHENTICATED。

## 3. 错误码

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 参数或未知 JSON 字段不合法 |
| 401 | BAD_CREDENTIALS | 登录失败 |
| 401 | UNAUTHENTICATED | 受保护接口认证失败 |
| 403 | FORBIDDEN | 普通用户不能访问管理员路径；管理业务接口尚未实现 |
| 409 | USERNAME_EXISTS | 账号已存在 |
| 503 | TEMPORARILY_UNAVAILABLE | 数据访问暂不可用 |
| 500 | INTERNAL_ERROR | 内部错误 |

错误响应不返回 SQL、堆栈、密码哈希或他人资料。

## 4. 更新规则

每个模块完成时同步修改代码、OpenAPI、示例和测试报告，通过测试后提交并推送。未实现接口不在本规范中承诺可调用。

## 5. 实现结构记录

2026-09-29 调整为统一 controller/service/mapper/model 分层，请求对象为 CredentialsDTO，返回对象为 UserVO、TokenVO。URL、JSON 字段及成功响应状态保持 0.1.0 契约；当前用户查询由 Service 统一校验账号状态。
