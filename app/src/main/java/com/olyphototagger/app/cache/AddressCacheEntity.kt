package com.olyphototagger.app.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One cached reverse-geocode result, keyed by [com.olyphototagger.app.geocode.bucketKey]
 * rather than exact coordinates — see that function's own doc for why. [address] is the
 * full Nominatim `display_name`; only ever inserted on a *successful* lookup (see
 * [com.olyphototagger.app.geocode.AddressResolver]) — a network failure is never cached,
 * so a later scan just retries rather than being permanently stuck with nothing.
 */
@Entity(tableName = "address_cache")
data class AddressCacheEntity(
    @PrimaryKey val bucketKey: String,
    val address: String
)
