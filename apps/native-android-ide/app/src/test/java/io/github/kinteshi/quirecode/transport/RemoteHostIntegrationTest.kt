package io.github.kinteshi.quirecode.transport

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Opt-in: pairs with a real TLS daemon. Start one with `AVF_TLS=1`, run
 * `avf-daemon pair --remote 127.0.0.1:PORT`, and pass the printed string as AVF_TEST_CONNECTION.
 */
class RemoteHostIntegrationTest {
    @Test
    fun pairsPinsAndConnectsToATlsHost() {
        val connection = System.getenv("AVF_TEST_CONNECTION")?.let(::parseConnectionString)
        assumeTrue("set AVF_TEST_CONNECTION to run", connection != null)
        val target = connection!!

        val wrongPin = GuestVfsClient()
        val mismatch = runCatching { wrongPin.pair(target.endpoint, target.code, "0".repeat(64)).get(10, TimeUnit.SECONDS) }
        assertTrue("a wrong fingerprint must be refused", (mismatch.exceptionOrNull() as? ExecutionException) != null)
        wrongPin.close()

        val client = GuestVfsClient()
        val token = client.pair(target.endpoint, target.code, target.fingerprint).get(10, TimeUnit.SECONDS)
        assertTrue(token.length == 64)
        client.connect(target.endpoint, token, target.fingerprint).get(10, TimeUnit.SECONDS)
        assertTrue(client.roots().get(10, TimeUnit.SECONDS).isNotEmpty())
        client.close()
    }
}
