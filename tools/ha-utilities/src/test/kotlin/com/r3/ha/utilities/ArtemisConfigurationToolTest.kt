package com.r3.ha.utilities

import net.corda.core.internal.div
import net.corda.core.internal.exists
import net.corda.core.internal.readText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
import kotlin.test.assertTrue

class ArtemisConfigurationToolTest {

    @Rule
    @JvmField
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val tool = ArtemisConfigurationTool()

    @Test
    fun `test generate`() {

        val workingDirectory = tempFolder.root.toPath() / "etc"

        CommandLine.populateCommand(tool, "--path", workingDirectory.toString(),
                "--user", "CN=artemis, O=Corda, L=London, C=GB",
                "--ha", "MASTER",
                "--acceptor-address", "fbantesting-notary:11005",
                "--keystore", "artemis.jks",
                "--keystore-password", "artemisStorePass",
                "--truststore", "artemis-truststore.jks",
                "--truststore-password", "artemisTrustpass",
                "--connectors", "fbantesting-notary:11005,fbantesting-zoo:11005")

        tool.runProgram()

        with(workingDirectory / "broker.xml") {
            assertTrue(exists())
            val allLines = readText()
            assertTrue(allLines) {
                allLines.contains("<acceptor name=\"artemis\">tcp://fbantesting-notary:11005?tcpSendBufferSize=1048576;tcpReceiveBufferSize=1048576;protocols=CORE,AMQP;useEpoll=true;amqpCredits=1000;amqpLowCredits=300;sslEnabled=true;keyStorePath=artemis.jks;keyStorePassword=artemisStorePass;trustStorePath=artemis-truststore.jks;trustStorePassword=artemisTrustpass;needClientAuth=true;enabledCipherSuites=TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256;enabledProtocols=TLSv1.2</acceptor>")
            }
        }

        with(workingDirectory / "artemis-roles.properties") {
            assertTrue(exists())
            val allLines = readText()
            assertTrue(allLines) {
                allLines.contains("Client=ArtemisUser")
            }
        }

        with(workingDirectory / "artemis-users.properties") {
            assertTrue(exists())
            val allLines = readText()
            assertTrue(allLines) {
                allLines.contains("ArtemisUser=CN=artemis, O=Corda, L=London, C=GB")
            }
        }
    }
}