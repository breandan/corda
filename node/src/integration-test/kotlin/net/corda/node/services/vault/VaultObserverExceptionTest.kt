package net.corda.node.services.vault

import co.paralleluniverse.strands.concurrent.Semaphore
import com.r3.dbfailure.workflows.CreateStateFlow
import com.r3.dbfailure.workflows.CreateStateFlow.Initiator
import com.r3.dbfailure.workflows.CreateStateFlow.errorTargetsToNum
import net.corda.core.CordaRuntimeException
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.node.services.Permissions
import net.corda.node.services.statemachine.StaffedFlowHospital
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import net.corda.testing.node.internal.findCordapp
import org.junit.After
import org.junit.Assert
import org.junit.Test
import rx.exceptions.OnErrorNotImplementedException
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeoutException
import javax.persistence.PersistenceException
import kotlin.test.assertFailsWith

class VaultObserverExceptionTest {
    companion object {

        val log = contextLogger()

        private fun testCordapps() = listOf(
                findCordapp("com.r3.dbfailure.contracts"),
                findCordapp("com.r3.dbfailure.workflows"),
                findCordapp("com.r3.dbfailure.schemas"))
    }

    @After
    fun tearDown() {
        StaffedFlowHospital.DatabaseEndocrinologist.customConditions.clear()
        StaffedFlowHospital.onFlowKeptForOvernightObservation.clear()
        StaffedFlowHospital.onFlowAdmitted.clear()
    }

    /**
     * Causing an SqlException via a syntax error in a vault observer will be wrapped within a HospitalizeFlowException
     * causes the flow to hit SedationNurse in the FlowHospital and being kept for overnight observation
     */
    @Test
    fun unhandledSqlExceptionFromVaultObserverGetsHospitalised() { // TODO: consolidate this
        val testStaffFuture = openFuture<List<String>>().toCompletableFuture()

        StaffedFlowHospital.onFlowKeptForOvernightObservation.add {_, staff ->
            testStaffFuture.complete(staff) // get all staff members that will give an overnight observation diagnosis for this flow
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            aliceNode.rpc.startFlow(
                    ::Initiator,
                    "Syntax Error in Custom SQL",
                    CreateStateFlow.errorTargetsToNum(CreateStateFlow.ErrorTarget.ServiceSqlSyntaxError)
            ).returnValue.then { testStaffFuture.complete(listOf()) }
            val staff = testStaffFuture.getOrThrow(30.seconds)

            // flow should have been given an overnight observation diagnosis by the SedationNurse
            Assert.assertTrue(staff.isNotEmpty() && staff.any { it.contains("SedationNurse") })
        }
    }

    /**
     * Throwing a random (non-SQL related) exception from a vault observer causes the flow to be
     * aborted when unhandled in user code
     */
    @Test
    fun otherExceptionsFromVaultObserverBringFlowDown() { // TODO: this will now go to hospital; behaviour changed; test needs to change/ consolidate
        // this test used to assert the suppression of exceptions by the triggering flow (other than SQLException and PersistenceException)
        // changed into asserting the same exception getting hospitalised
        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            assertFailsWith(CordaRuntimeException::class, "Toys out of pram") {
                aliceNode.rpc.startFlow(
                        ::Initiator,
                        "InvalidParameterException",
                        CreateStateFlow.errorTargetsToNum(CreateStateFlow.ErrorTarget.ServiceThrowInvalidParameter)
                ).returnValue.getOrThrow(30.seconds)
            }
        }
    }

    /**
     * InvalidParameterException thrown from a vault observer can not be suppressible in the flow that triggered the observer
     * The flow will be hospitalized. The exception will bring the rx.Observer down.
     */
    @Test
    fun invalidParameterExceptionFromVaultObserverGetsKeptForObservation() { // TODO: consolidate this
        // this test used to assert the suppression of exceptions by the triggering flow (other than SQLException and PersistenceException)
        // changed into asserting the same exception getting hospitalised
        var observation = 0
        val waitUntilHospitalised = Semaphore(0)
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            ++observation
            waitUntilHospitalised.release()
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            aliceNode.rpc.startFlow(::Initiator, "InvalidParameterException", CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceThrowInvalidParameter,
                    CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            waitUntilHospitalised.acquire() // wait here until flow gets hospitalised
        }

        Assert.assertEquals(1, observation)
    }

    /**
     * If the state we are trying to persist triggers a persistence exception, the flow hospital will retry the flow
     * and keep it in for observation if errors persist.
     */
    @Test
    fun persistenceExceptionOnCommitGetsRetriedAndThenGetsKeptForObservation() {
        var admitted = 0
        var observation = 0
        StaffedFlowHospital.onFlowAdmitted.add {
            ++admitted
        }
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            ++observation
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            assertFailsWith<TimeoutException> {
                aliceNode.rpc.startFlow(::Initiator, "EntityManager", errorTargetsToNum(CreateStateFlow.ErrorTarget.TxInvalidState))
                        .returnValue.getOrThrow(Duration.of(30, ChronoUnit.SECONDS))
            }
        }
        Assert.assertTrue("Exception from service has not been to Hospital", admitted > 0)
        Assert.assertEquals(1, observation)
    }

    /**
     * If we have a state causing a database error lined up for persistence, calling jdbConnection() in
     * the vault observer will trigger a flush that throws.
     * Trying to catch and suppress that exception in the flow around the code triggering the vault observer
     * does not change the outcome - the first exception in the service will bring the service down and will
     * be caught by the flow, but the state machine will error the flow anyway as Corda code threw.
     */
    @Test
    fun persistenceExceptionOnFlushInVaultObserverCannotBeSuppressedInFlow() {
        var counter = 0
        StaffedFlowHospital.DatabaseEndocrinologist.customConditions.add {
            when (it) {
                is OnErrorNotImplementedException -> Assert.fail("OnErrorNotImplementedException should be unwrapped") // TODO: remove this line
                is PersistenceException -> {
                    ++counter
                    log.info("Got a PersistentException in the flow hospital count = $counter")
                }
            }
            false
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            val flowHandle = aliceNode.rpc.startFlow(
                    ::Initiator,
                    "EntityManager",
                    CreateStateFlow.errorTargetsToNum(
                            CreateStateFlow.ErrorTarget.ServiceValidUpdate,
                            CreateStateFlow.ErrorTarget.TxInvalidState,
                            CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            val flowResult = flowHandle.returnValue
            assertFailsWith<TimeoutException>("PersistenceException") { flowResult.getOrThrow(30.seconds) }
            Assert.assertTrue("Flow has not been to hospital", counter > 0)
        }
    }

    /**
     * If we have a state causing a persistence exception lined up for persistence, calling jdbConnection() in
     * the vault observer will trigger a flush that throws.
     * Trying to catch and suppress that exception inside the service does protect the service, but the new
     * interceptor will fail the flow anyway. The flow will be kept in for observation if errors persist.
     */
    @Test
    fun persistenceExceptionOnFlushInVaultObserverCannotBeSuppressedInService() { //TODO: this never reaches flush to fail there, but rather it fails on persist
        var counter = 0
        StaffedFlowHospital.DatabaseEndocrinologist.customConditions.add {
            when (it) {
                is OnErrorNotImplementedException -> Assert.fail("OnErrorNotImplementedException should be unwrapped") // TODO: remove this line
                is PersistenceException -> {
                    ++counter
                    log.info("Got a PersistentException in the flow hospital count = $counter")
                }
            }
            false
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            val flowHandle = aliceNode.rpc.startFlow(
                    ::Initiator, "EntityManager",
                    CreateStateFlow.errorTargetsToNum(
                            CreateStateFlow.ErrorTarget.ServiceValidUpdate,
                            CreateStateFlow.ErrorTarget.TxInvalidState,
                            CreateStateFlow.ErrorTarget.ServiceSwallowErrors))
            val flowResult = flowHandle.returnValue
            assertFailsWith<TimeoutException>("PersistenceException") { flowResult.getOrThrow(30.seconds) }
            Assert.assertTrue("Flow has not been to hospital", counter > 0)
        }
    }

    /**
     * User code throwing a syntax error in a raw vault observer will break the recordTransaction call,
     * therefore handling it in flow code is no good, and the error will be passed to the flow hospital via the
     * interceptor.
     */
    @Test
    fun syntaxErrorInUserCodeInServiceCannotBeSuppressedInFlow() { // TODO: this will apply for all exceptions; all exceptions thrown will get wrapped in HospitalizeFlowException; HospitalizeFlowException will not be suppressible
        val testControlFuture = openFuture<Boolean>()
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { _, _ ->
            log.info("Flow has been kept for overnight observation")
            testControlFuture.set(true)
        }

        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            val flowHandle = aliceNode.rpc.startFlow(::Initiator, "EntityManager", CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceSqlSyntaxError,
                    CreateStateFlow.ErrorTarget.FlowSwallowErrors))
            val flowResult = flowHandle.returnValue
            flowResult.then {
                log.info("Flow has finished")
                testControlFuture.set(false)
            }
            Assert.assertTrue("Flow has not been kept in hospital", testControlFuture.getOrThrow(30.seconds))
        }
    }

    /**
     * User code throwing a syntax error and catching suppressing that within the observer code is fine
     * and should not have any impact on the rest of the flow
     */
    @Test
    fun syntaxErrorInUserCodeInServiceCanBeSuppressedInService() {
        driver(DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = testCordapps())) {
            val aliceUser = User("user", "foo", setOf(Permissions.all()))
            val aliceNode = startNode(providedName = ALICE_NAME, rpcUsers = listOf(aliceUser)).getOrThrow()
            val flowHandle = aliceNode.rpc.startFlow(::Initiator, "EntityManager", CreateStateFlow.errorTargetsToNum(
                    CreateStateFlow.ErrorTarget.ServiceSqlSyntaxError,
                    CreateStateFlow.ErrorTarget.ServiceSwallowErrors))
            val flowResult = flowHandle.returnValue
            flowResult.getOrThrow(30.seconds)
        }
    }
}