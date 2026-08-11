package com.agentforge.prreview.security;

/**
 * Shared idempotency boundary for GitHub webhook delivery IDs.
 */
public interface WebhookDeliveryStore {

    /**
     * Atomically records a delivery ID.
     *
     * @return true only when the delivery ID was not already present
     */
    boolean recordIfNew(String deliveryId);

    /**
     * Releases a reservation after processing fails so GitHub can redeliver it.
     */
    void release(String deliveryId);
}
