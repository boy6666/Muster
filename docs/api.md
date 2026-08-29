# Muster · 点将台 API 清单

所有响应为 JSON。业务错误统一信封 `{"code":"<ERROR_CODE>","message":"<中文说明>"[,"data":{...}]}`，HTTP 状态随 code 映射。

认证方式：除标注「匿名」外，均需请求头 `Authorization: Bearer <token>`。

## 认证 `/api/auth`（匿名）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | `{username,password}` → `{token}` |
| GET | `/api/auth/me` | 当前管理员信息 |
| PUT | `/api/auth/password` | `{oldPassword,newPassword}`，新密码 ≥6 位 |

## 活动 `/api/activity`（需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/activity` | 当前活动（无活动返回空体） |
| POST | `/api/activity` | 创建； `{name,startTime,endTime,groupSizeLimit}`；上一场未归档 → 409 `ARCHIVE_REQUIRED` |
| PUT | `/api/activity` | 修改；仅未开始时可改时间，名称/人数上限随时可改 |
| POST | `/api/activity/end` | 手动结束（ENDED 后再调 → 409 `CONFLICT`） |
| DELETE | `/api/activity` | 删除；未导出归档包 → 409 `ARCHIVE_REQUIRED`；级联删除花名册/分组 |
| GET | `/api/activity/form-url` | 二维码内容 `{url}`（form-base-url + /form/{qrToken}） |
| POST | `/api/activity/export/archive` | 三 sheet 归档包（已参加/未参加/分组明细）xlsx，并锁定活动 |

时间格式：ISO `yyyy-MM-ddTHH:mm:ss`。

## 花名册 `/api/roster`（需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/roster/import` | multipart `file` xlsx（姓名/手机号/部门），跳过全空行；文件内重复手机号 → 400 带行号 |
| GET | `/api/roster?keyword=&page=&size=` | 姓名/手机号/部门模糊搜索，分页 |
| POST | `/api/roster` | `{name,phone,department}` 单个添加（手机号 `^1[3-9]\d{9}$`，活动内唯一） |
| DELETE | `/api/roster/{id}` | 删除（已入组成员一并移除） |
| GET | `/api/roster/template` | 下载花名册模板 xlsx |

## 报名表单 `/api/form/{qrToken}`（匿名，扫码进入）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/form/{token}` | 活动信息 `{name,startTime,endTime,groupSizeLimit,windowStatus}` |
| GET | `/api/form/{token}/person?phone=` | 手机号自动回显 `{name,phone,department}`；**仅完整 11 位手机号精确查询**（部分输入 → 400） |
| POST | `/api/form/{token}/teams` | 组长提交 `{memberPhoneList:[...]}` → TeamDetail；一人一组，冲突 → 409 带 `data` 冲突明细；超上限允许（`overLimit:true`） |
| GET | `/api/form/{token}/teams/{teamId}` | 查看本组 |
| PUT | `/api/form/{token}/teams/{teamId}` | 组长改组（仅 ACTIVE）→ 状态回 PENDING、清驳回理由 |

## 组管理 `/api/teams`（需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/teams?status=&page=&size=` | 分页列表 `{total,records}`，含 `overLimit` 标记 |
| GET | `/api/teams/{id}` | 组详情 |
| PUT | `/api/teams/{id}/review` | `{action:"PASS"|"REJECT", reason?}`；REJECT 必填理由；不受活动窗口限制 |
| PUT | `/api/teams/{id}/members` | 管理员改组 `{memberPhoneList}` → 状态 CONFIRMED |

组状态：`PENDING`（待审核）/ `CONFIRMED`（通过）/ `REJECTED`（驳回）。提交即算已参加，驳回不回落。

## 统计与导出（需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/stats` | `{total,joined,notJoined,teamCount,pendingTeamCount}` |
| GET | `/api/stats/export?type=JOINED\|MISSING` | 单表 xlsx（已参加 5 列 / 未参加 3 列） |

## 审计与流水（需登录）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/audit/logs?action=&page=&size=` | 管理操作审计日志（建/改/删/结束/归档活动、花名册导入增删、管理员改组、审核），按 id 倒序分页 |
| GET | `/api/teams/{id}/events` | 组生命周期流水，时间正序：`SUBMITTED`/`EDITED_BY_LEADER`/`EDITED_BY_ADMIN`/`PASSED`/`REJECTED`（驳回带理由） |

## WebSocket `/ws/stats`（匿名握手但需校验）

`ws://<host>/ws/stats?token=<JWT>`。握手成功即推送当前统计帧；任何提交/改组/审核事件后推送新帧。帧格式同 `/api/stats`。无效 token → 握手 401 拒绝。

## 错误码

| code | HTTP | 场景 |
|---|---|---|
| VALIDATION | 400 | 参数/格式/部分手机号查询 |
| UNAUTHORIZED | 401 | 未登录 |
| AUTH_FAILED | 401 | 账号或密码错误 |
| FORBIDDEN | 403 | — |
| NOT_FOUND | 404 | 无活动/无效二维码 |
| PERSON_NOT_FOUND | 404 | 手机号不在花名册 |
| CONFLICT | 409 | 已结束重复操作/一人多组（data 含冲突明细） |
| ARCHIVE_REQUIRED | 409 | 未归档先建/删 |
| WINDOW_CLOSED | 409 | 活动未开始或已结束时的编辑类操作 |
| PHONE_DUPLICATE | 400 | 花名册手机号重复 |
| INTERNAL | 500 | 未知错误（已记入服务端日志） |
