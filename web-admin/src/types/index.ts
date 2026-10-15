/**
 * 与后端 JSON 响应对应的前端契约。
 * 数据库 ID 使用字符串，防止大整数精度丢失；可空来源字段保留 null，不用零或空字符串伪造事实。
 */
export type Role = 'SYSTEM_ADMIN' | 'RESOURCE_ADMIN' | 'STUDENT' | 'TEACHER'

// 身份服务确认的操作者，包含设备会话标识和实时角色；不能由前端表单自行构造为可信身份。
export interface Actor {
    userId: string;
    sessionId: string;
    username: string;
    displayName: string;
    status: string;
    authVersion: number;
    roles: Role[]
}

// 登录或刷新成功后的凭据，仅供会话仓库在内存中使用。expiresIn 单位为秒。
export interface Tokens {
    accessToken: string;
    refreshToken: string;
    expiresIn: number
}

// 分页响应中的 total 为服务端统计值，items 仅表示当前页，不能用当前页长度替代总数。
export interface Page<T> {
    items: T[];
    total: number;
    page: number;
    size: number
}

// 管理员可见的账号摘要，不包含密码原文或数据库密码摘要。
export interface User {
    id: string;
    username: string;
    displayName: string;
    status: string;
    authVersion: number;
    roles: Role[]
}

// 审批组及成员 ID；成员必须由后端核验管理角色，前端下拉筛选只是辅助。
export interface Group {
    id: string;
    name: string;
    status: string;
    memberUserIds: string[]
}

// 资源目录模型：REAL 表示真实来源，DEMO 用于隔离测试资料，与 dev/prod 部署环境无关。
// availability 和 coverageStatus 在没有时段查询时可能为 UNKNOWN，不能据此自行推导空闲。
export interface Resource {
    id: string;
    sourceSystem: string;
    sourceId: string;
    resourceType: 'VENUE' | 'EQUIPMENT';
    name: string;
    campus: string | null;
    building: string | null;
    floorNo: string | null;
    category: string | null;
    subcategory: string | null;
    capacity: number | null;
    examCapacity: number | null;
    area: number | null;
    sourceBorrowable: 'YES' | 'NO' | 'UNKNOWN';
    platformStatus: 'DRAFT' | 'OPEN' | 'CLOSED' | 'RETIRED';
    dataMode: 'REAL' | 'DEMO';
    approvalGroupId: string | null;
    remark: string | null;
    features: Record<string, string>;
    assetCode: string | null;
    equipmentType: string | null;
    conditionStatus: string | null;
    version: number;
    availability: string;
    coverageStatus: string
}

// 导入预览或确认结果。sourceRows 为实际文件行数，errors 提供源行号和稳定错误码。
export interface ImportBatch {
    batchId: string;
    fileHash: string;
    status: string;
    summary: { sourceRows: number; newRows: number; updateRows: number; errorRows: number };
    errors: { rowNumber: number; fieldName: string; code: string; message: string }[];
    fieldMapping: Record<string, string>;
    requiresConfirmation: boolean
}

/** 申请状态来自服务端；allowedActions 仅辅助显示，动作仍由服务器核对当前权限和版本。 */
export type ApplicationStatus =
    'PENDING_APPROVAL'
    | 'APPROVED'
    | 'CONFLICT_REVIEW'
    | 'COMPLETED'
    | 'REJECTED'
    | 'EXPIRED'
    | 'CANCELLED'

export interface ApplicationSummary {
    applicationId: string;
    activityName: string;
    status: ApplicationStatus;
    version: number;
    participantCount: number;
    startAt: string;
    endAt: string;
    holdExpiresAt: string | null;
    dataMode: string;
    approvalGroupId: string;
    policyVersion: number;
    lastReason: string | null;
    updatedAt: string;
    allowedActions: string[]
}

export interface ApplicationDetail extends ApplicationSummary {
    description: string | null;
    applicant: { userId: string; username: string; displayName: string };
    resourceIds: string[];
    resources: {
        id: string;
        name: string;
        resourceType: string;
        campus: string | null;
        equipmentType: string | null
    }[];
    approvalRecords: {
        operatorId: string | null;
        decision: string;
        reason: string | null;
        fromVersion: number;
        createdAt: string
    }[]
}

/** 候补确认产生待审批申请；CONFIRMED 不等于预约审批通过。 */
export type WaitlistStatus = 'WAITING' | 'OFFERED' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED'

export interface WaitlistSummary {
    waitlistId: string;
    groupId: string;
    activityName: string;
    participantCount: number;
    status: WaitlistStatus;
    version: number;
    dataMode: string;
    startAt: string;
    endAt: string;
    expiresAt: string;
    offerExpiresAt: string | null;
    applicationId: string | null;
    lastReason: string | null;
    updatedAt: string;
    queuePosition: number | null;
    allowedActions: string[]
}

export interface WaitlistDetail extends WaitlistSummary {
    description: string | null;
    resourceIds: string[];
    requiredFeatures: string[];
    resources: { id: string; name: string; resourceType: string }[]
}

export interface Notice {
    notificationId: string;
    title: string;
    body: string;
    kind: string;
    objectType: 'APPLICATION' | 'WAITLIST';
    objectId: string;
    aggregateVersion: number;
    occurredAt: string;
    createdAt: string;
    readAt: string | null
}

export interface EventDelivery {
    id: string;
    eventId: string;
    eventType: string;
    objectType: string;
    objectId: string;
    aggregateVersion: number;
    status: 'PENDING' | 'SENDING' | 'SENT' | 'FAILED';
    attempts: number;
    version: number;
    lastErrorCode: string | null;
    nextAttemptAt: string;
    publishedAt: string | null;
    deadLetteredAt: string | null
}
