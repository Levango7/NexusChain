/**
 * NexusChain Payment Orchestration SDK
 * TypeScript/JavaScript client for the Gateway orchestration API.
 */

export interface PaymentRequest {
  amount: number;
  currency: string;
  description?: string;
  merchant_id?: string;
  routing?: {
    strategy?: 'priority' | 'weight' | 'cost' | 'explicit';
    preferred_connector?: string;
  };
  notify_url?: string;
  metadata?: Record<string, string>;
}

export interface Payment {
  id: string;
  status: 'CREATED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'CANCELLED' | 'REFUNDED';
  amount: number;
  currency: string;
  description?: string;
  connector?: string;
  connector_payment_id?: string;
  transaction_hash?: string;
  routing_strategy?: string;
  created_at?: string;
  confirmed_at?: string;
  expires_at?: string;
}

export interface Connector {
  id: string;
  type: string;
  display_name: string;
  active: boolean;
  fee_bps: number;
  currencies: string[];
}

export interface ConnectorHealth {
  connectorId: string;
  healthy: boolean;
  message: string;
  checkedAt: string;
  latencyMs: number;
}

export interface RoutingRule {
  id: string;
  name: string;
  conditions: Record<string, string>;
  strategy: string;
  connectors: string[];
  priority: number;
}

export interface PaymentListResponse {
  data: Payment[];
  total: number;
  page: number;
  limit: number;
}

export class NexusPayClient {
  private baseUrl: string;
  private apiKey: string;

  constructor(baseUrl: string, apiKey: string = '') {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.apiKey = apiKey;
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.apiKey) headers['Authorization'] = `Bearer ${this.apiKey}`;

    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });

    if (!res.ok) {
      const err = await res.text().catch(() => '');
      throw new NexusApiError(res.status, `Request failed: ${res.status} ${res.statusText}`, err);
    }
    return res.json() as Promise<T>;
  }

  // === Payments ===

  async createPayment(req: PaymentRequest): Promise<Payment> {
    return this.request<Payment>('POST', '/api/v1/payments', req);
  }

  async getPayment(paymentId: string): Promise<Payment> {
    return this.request<Payment>('GET', `/api/v1/payments/${paymentId}`);
  }

  async listPayments(opts?: { merchantId?: number; status?: string; page?: number; limit?: number }): Promise<PaymentListResponse> {
    const params = new URLSearchParams();
    if (opts?.merchantId) params.set('merchantId', String(opts.merchantId));
    if (opts?.status) params.set('status', opts.status);
    if (opts?.page !== undefined) params.set('page', String(opts.page));
    if (opts?.limit) params.set('limit', String(opts.limit));
    const qs = params.toString();
    return this.request<PaymentListResponse>('GET', `/api/v1/payments${qs ? '?' + qs : ''}`);
  }

  async refreshPayment(paymentId: string): Promise<Payment> {
    return this.request<Payment>('POST', `/api/v1/payments/${paymentId}/refresh`);
  }

  // === Connectors ===

  async listConnectors(): Promise<Connector[]> {
    return this.request<Connector[]>('GET', '/api/v1/payments/connectors');
  }

  async getConnectorHealth(connectorId: string): Promise<ConnectorHealth> {
    return this.request<ConnectorHealth>('GET', `/api/v1/payments/connectors/${connectorId}/health`);
  }

  // === Routing Rules ===

  async listRoutingRules(): Promise<RoutingRule[]> {
    return this.request<RoutingRule[]>('GET', '/api/v1/payments/routing-rules');
  }

  async addRoutingRule(rule: RoutingRule): Promise<RoutingRule> {
    return this.request<RoutingRule>('POST', '/api/v1/payments/routing-rules', rule);
  }

  async deleteRoutingRule(ruleId: string): Promise<void> {
    await this.request<unknown>('DELETE', `/api/v1/payments/routing-rules/${ruleId}`);
  }

  // === Convenience ===

  /** Create a payment and wait until it reaches a terminal state. */
  async createPaymentAndWait(req: PaymentRequest, pollIntervalMs = 2000, timeoutMs = 60000): Promise<Payment> {
    const payment = await this.createPayment(req);
    if (payment.status === 'SUCCEEDED' || payment.status === 'FAILED') return payment;

    const deadline = Date.now() + timeoutMs;
    let current = payment;
    while (Date.now() < deadline) {
      await new Promise(r => setTimeout(r, pollIntervalMs));
      current = await this.refreshPayment(payment.id);
      if (current.status === 'SUCCEEDED' || current.status === 'FAILED' || current.status === 'EXPIRED') {
        return current;
      }
    }
    return current;
  }
}

export class NexusApiError extends Error {
  constructor(public status: number, message: string, public body?: string) {
    super(message);
    this.name = 'NexusApiError';
  }
}

export default NexusPayClient;