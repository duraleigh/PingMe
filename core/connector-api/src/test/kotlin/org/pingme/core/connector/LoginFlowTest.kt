// SPDX-License-Identifier: AGPL-3.0-or-later
package org.pingme.core.connector

import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LoginFlowTest {
    @Test
    fun answersReachTheStepThatAskedAndTheFlowEndsWithTheScript() =
        runTest {
            val flow =
                loginFlow {
                    val phone =
                        (
                            ask(
                                LoginStep.EnterText("phone", "Phone number", TextKind.PHONE_NUMBER, null, null),
                            ) as LoginResponse.Text
                        ).value
                    val code =
                        (
                            ask(
                                LoginStep.EnterText("code", "Code", TextKind.CODE, null, null),
                            ) as LoginResponse.Text
                        ).value
                    show(LoginStep.Done("done", credentialRef = "ref:$phone:$code", accountName = "Me"))
                }
            val seen =
                flow.steps
                    .onEach { step ->
                        when (step.id) {
                            // An answer for a step that is not being asked is ignored.
                            "phone" -> {
                                flow.respond("code", LoginResponse.Text("too early"))
                                flow.respond("phone", LoginResponse.Text("+1555"))
                            }

                            "code" -> {
                                flow.respond("code", LoginResponse.Text("123456"))
                            }
                        }
                    }.toList()
            assertEquals(listOf("phone", "code", "done"), seen.map { it.id })
            assertEquals("ref:+1555:123456", (seen.last() as LoginStep.Done).credentialRef)
        }
}
