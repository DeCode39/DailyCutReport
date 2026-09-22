package com.littleone.dailycutreport

/** Expiry hides catalog availability only. Products, log snapshots and cart references survive. */
const val TEMPORARY_MEAL_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000

fun ProductEntity.isAvailableAt(now: Long): Boolean = expiresAtEpochMs?.let { now < it } ?: true

fun ProductEntity.asOneTimeCopy(now: Long = System.currentTimeMillis()): ProductEntity = copy(
    productId = java.util.UUID.randomUUID().toString(), barcode = null,
    expiresAtEpochMs = now + TEMPORARY_MEAL_LIFETIME_MS,
    includeInPlanner = false, alwaysIncludeInPlanner = false, favorite = false,
    createdAt = now, updatedAt = now
)
