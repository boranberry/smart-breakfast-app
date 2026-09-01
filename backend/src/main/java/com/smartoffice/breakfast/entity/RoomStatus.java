package com.smartoffice.breakfast.entity;

/**
 * Lifecycle of an ordering room.
 *
 * <pre>
 *   OPEN ──(admin closes / 60-min timer)──▶ CLOSED
 *        food is ordered and delivered; the paper receipt arrives
 *   CLOSED ──(admin enters receipt prices + delivery fee)──▶ PENDING_ADMIN_APPROVAL
 *        bill is split from the receipt prices, awaiting a final admin check
 *   PENDING_ADMIN_APPROVAL ──(admin approves)──▶ APPROVED_AND_CLOSED
 *        splits are locked in and the verified prices are written to the
 *        restaurant's menu for future rooms
 * </pre>
 */
public enum RoomStatus {

    /** Accepting orders. Prices entered here are guesses and carry no weight. */
    OPEN,

    /** No longer accepting orders; waiting for the paper receipt. */
    CLOSED,

    /** Receipt entered and bill split; awaiting the admin's final approval. */
    PENDING_ADMIN_APPROVAL,

    /** Approved: splits final, verified prices persisted to the menu. */
    APPROVED_AND_CLOSED;

    /** True once the room can no longer take new orders. */
    public boolean isTerminalForOrdering() {
        return this != OPEN;
    }
}
