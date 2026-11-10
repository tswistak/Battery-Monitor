export function startAnalyticsHarness(): Promise<{ url: string; close(): Promise<void> }>;
export function startAnalyticsReceiver(): Promise<{
  url: string;
  received: URLSearchParams[];
  completed: URLSearchParams[];
  close(): Promise<void>;
}>;
