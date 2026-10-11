export type Role = 'SYSTEM_ADMIN' | 'RESOURCE_ADMIN' | 'STUDENT'

export interface Actor {
    userId: string;
    sessionId: string;
    username: string;
    displayName: string;
    status: string;
    authVersion: number;
    roles: Role[]
}

export interface Tokens {
    accessToken: string;
    refreshToken: string;
    expiresIn: number
}

export interface Page<T> {
    items: T[];
    total: number;
    page: number;
    size: number
}

export interface User {
    id: string;
    username: string;
    displayName: string;
    status: string;
    authVersion: number;
    roles: Role[]
}

export interface Group {
    id: string;
    name: string;
    status: string;
    memberUserIds: string[]
}

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

export interface ImportBatch {
    batchId: string;
    fileHash: string;
    status: string;
    summary: { sourceRows: number; newRows: number; updateRows: number; errorRows: number };
    errors: { rowNumber: number; fieldName: string; code: string; message: string }[];
    fieldMapping: Record<string, string>;
    requiresConfirmation: boolean
}
