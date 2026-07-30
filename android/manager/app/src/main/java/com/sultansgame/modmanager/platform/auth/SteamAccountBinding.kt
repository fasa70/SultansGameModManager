package com.sultansgame.modmanager.platform.auth

import java.security.MessageDigest

/** Stable, non-reversible task binding for an authenticated Steam account. */
fun steamAccountBindingHash(steamId: Long): String = MessageDigest.getInstance("SHA-256")
    .digest(steamId.toString().toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
