package com.scifsidekick.cleanroom.util

/**
 * Refuses outbound texts to well-known premium-rate and satellite number ranges, so a mistaken or
 * compromised allowlisted sender can't run up charges. A safety net, not a guarantee: no prefix
 * list covers every country's premium ranges. Emergency numbers and short codes never reach it
 * because a destination must already be a 7-15 digit E.164 number.
 */
object PremiumNumbers {
    // Digits after the "+". Sources: national numbering plans and ITU country-code assignments.
    private val prefixes =
        listOf(
            "1900", "1976", // US/Canada 900 and 976
            "449", "4470", // UK 09xx premium, 070 personal numbers
            "49900", "49137", // Germany premium and mass-traffic
            "3389", // France 089x
            "34803", "34806", "34807", "34905", // Spain
            "3989", "39144", "39166", // Italy
            "61190", // Australia 190x
            "31900", "31906", "31909", // Netherlands
            "3290", // Belgium
            "41900", "41901", "41906", // Switzerland
            "43900", "43901", "43930", "43931", // Austria
            "979", // ITU international premium rate
            "870", "871", "872", "873", "874", "881", "882", "883", // satellite and international networks
        )

    fun isPremiumRate(e164: String): Boolean {
        val digits = e164.removePrefix("+")
        return prefixes.any(digits::startsWith)
    }

    /** A reason string if [e164] must not be texted, else null. */
    fun rejectionReason(e164: String): String? =
        if (isPremiumRate(e164)) "Destination $e164 is in a premium-rate or satellite number range; the safety policy blocks it" else null
}
