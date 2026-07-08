package com.littleone.dailycutreport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    @Test fun encryptedBackupRoundTripsAndRejectsWrongPassword() {
        val plain = "private local report".toByteArray()
        val encrypted = BackupCrypto.encrypt(plain, "correct horse".toCharArray())
        assertArrayEquals(plain, BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()))
        assertThrows(Throwable::class.java) { BackupCrypto.decrypt(encrypted, "wrong password".toCharArray()) }
    }

    @Test fun authenticatedBackupRejectsTampering() {
        val encrypted = BackupCrypto.encrypt("payload".toByteArray(), "correct horse".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Throwable::class.java) { BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()) }
    }
}
