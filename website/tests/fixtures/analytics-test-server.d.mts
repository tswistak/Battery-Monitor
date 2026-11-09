export function startAnalyticsHarness(): Promise<{ url: string; close(): Promise<void> }>;
