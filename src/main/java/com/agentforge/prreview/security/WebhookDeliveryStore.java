package com.agentforge.prreview.security;

/**
 * Shared replay-protection boundary for authenticated webhook payload keys.
 */
public interface WebhookDeliveryStore {

    /**
     * Atomically records a replay key derived from a signature-validated payload.
     *
     * @return true only when the delivery ID was not already present
     */
    boolean recordIfNew(String replayKey);

    /**
     * Releases a reservation after processing fails so GitHub can redeliver it.
     */
    void release(String replayKey);
}
