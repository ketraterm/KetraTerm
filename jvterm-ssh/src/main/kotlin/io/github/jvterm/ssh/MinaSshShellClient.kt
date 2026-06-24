/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jvterm.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.PtyChannelConfiguration
import java.io.IOException
import java.io.OutputStream
import java.util.*
import java.util.concurrent.TimeUnit

internal object MinaSshShellClient : SshShellClient {
    override fun open(
        options: SshOptions,
        output: SshShellOutput,
    ): SshShell {
        val client = SshClient.setUpDefaultClient()
        client.serverKeyVerifier = options.hostKeyPolicy.createVerifier()
        client.start()

        var session: ClientSession? = null
        var shell: ChannelShell? = null
        try {
            session =
                client
                    .connect(options.username, options.host, options.port)
                    .verify(options.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                    .clientSession
            configureAuthentication(session, options.authentication)
            val auth = session.auth().verify(options.authTimeoutMillis, TimeUnit.MILLISECONDS)
            if (!auth.isSuccess) {
                throw IOException("SSH authentication failed for ${options.username}@${options.host}:${options.port}")
            }

            shell = openShell(session, options, output)
            return MinaSshShell(client, session, shell)
        } catch (exception: IOException) {
            shell?.close(false)
            session?.close(false)
            client.stop()
            throw exception
        } catch (exception: RuntimeException) {
            shell?.close(false)
            session?.close(false)
            client.stop()
            throw exception
        }
    }

    private fun configureAuthentication(
        session: ClientSession,
        authentication: List<SshAuthentication>,
    ) {
        for (credential in authentication) {
            when (credential) {
                is SshAuthentication.Password -> session.addPasswordIdentity(credential.password)
                is SshAuthentication.PublicKey -> session.addPublicKeyIdentity(credential.keyPair)
            }
        }
    }

    private fun openShell(
        session: ClientSession,
        options: SshOptions,
        output: SshShellOutput,
    ): ChannelShell {
        val config =
            PtyChannelConfiguration().apply {
                ptyType = options.terminalType
                ptyColumns = options.columns
                ptyLines = options.rows
            }
        val shell = session.createShellChannel(config, options.environment)
        val stream = ShellOutputStream(output)
        shell.setOut(stream)
        shell.setErr(stream)
        shell.setRedirectErrorStream(true)
        shell.open().verify(options.openTimeoutMillis, TimeUnit.MILLISECONDS)
        return shell
    }

    private class MinaSshShell(
        private val client: SshClient,
        private val session: ClientSession,
        private val shell: ChannelShell,
    ) : SshShell {
        private val input = shell.invertedIn

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            input.write(bytes, offset, length)
            input.flush()
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            shell.sendWindowChange(columns, rows)
        }

        override fun waitForClosed(): Int? {
            shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0L)
            return shell.exitStatus
        }

        override fun close() {
            try {
                shell.close(false)
            } finally {
                try {
                    session.close(false)
                } finally {
                    client.stop()
                }
            }
        }
    }

    private class ShellOutputStream(
        private val output: SshShellOutput,
    ) : OutputStream() {
        private val singleByte = ByteArray(1)

        override fun write(value: Int) {
            singleByte[0] = value.toByte()
            output.emit(singleByte, 0, 1)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            output.emit(bytes, offset, length)
        }
    }
}
