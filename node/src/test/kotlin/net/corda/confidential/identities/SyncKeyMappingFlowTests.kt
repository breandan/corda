package net.corda.confidential.identities

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueAndPaymentFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.CHARLIE_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.FINANCE_CORDAPPS
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncKeyMappingFlowTests {
    private lateinit var mockNet: InternalMockNetwork
    private lateinit var aliceNode: TestStartedNode
    private lateinit var bobNode: TestStartedNode
    private lateinit var charlieNode: TestStartedNode
    private lateinit var alice: Party
    private lateinit var bob: Party
    private lateinit var charlie: Party
    private lateinit var notary: Party

    @Before
    fun before() {
        mockNet = InternalMockNetwork(
                cordappsForAllNodes = FINANCE_CORDAPPS,
                networkSendManuallyPumped = false,
                threadPerNode = true)

        aliceNode = mockNet.createPartyNode(ALICE_NAME)
        bobNode = mockNet.createPartyNode(BOB_NAME)
        charlieNode = mockNet.createPartyNode(CHARLIE_NAME)
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()
        charlie = charlieNode.info.singleIdentity()
        notary = mockNet.defaultNotaryIdentity

        mockNet.startNodes()

        aliceNode.registerInitiatedFlow(SyncKeyMappingResponse::class.java)
        bobNode.registerInitiatedFlow(SyncKeyMappingResponse::class.java)
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `sync the key mapping between two parties in a transaction`() {
        // Alice issues then pays some cash to a new confidential identity that Bob doesn't know about
        val anonymous = true
        val ref = OpaqueBytes.of(0x01)
        val issueFlow = aliceNode.services.startFlow(CashIssueAndPaymentFlow(1000.DOLLARS, ref, alice, anonymous, notary)).resultFuture
        val issueTx = issueFlow.getOrThrow().stx
        val confidentialIdentity = issueTx.tx.outputs.map { it.data }.filterIsInstance<Cash.State>().single().owner
        assertNull(bobNode.database.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })

        // Run the flow to sync up the identities
        aliceNode.services.startFlow(SyncKeyMappingInitiator(bob, issueTx.tx)).resultFuture.getOrThrow()
        val expected = aliceNode.database.transaction {
            aliceNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        val actual = bobNode.database.transaction {
            bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `don't offer other's identities confidential identities`() {
        // Set up values we'll need
        val aliceNode = mockNet.createPartyNode(ALICE_NAME)
        val bobNode = mockNet.createPartyNode(BOB_NAME)
        val charlieNode = mockNet.createPartyNode(CHARLIE_NAME)
        val alice: Party = aliceNode.info.singleIdentity()
        val bob: Party = bobNode.info.singleIdentity()
        val charlie: Party = charlieNode.info.singleIdentity()
        val notary = mockNet.defaultNotaryIdentity
        bobNode.registerInitiatedFlow(SyncKeyMappingResponse::class.java)

        // Charlie issues then pays some cash to a new confidential identity
        val anonymous = true
        val ref = OpaqueBytes.of(0x01)
        val issueFlow = charlieNode.services.startFlow(CashIssueAndPaymentFlow(1000.DOLLARS, ref, charlie, anonymous, notary))
        val issueTx = issueFlow.resultFuture.getOrThrow().stx
        val confidentialIdentity = issueTx.tx.outputs.map { it.data }.filterIsInstance<Cash.State>().single().owner
        val confidentialIdentCert = charlieNode.services.identityService.certificateFromKey(confidentialIdentity.owningKey)!!

        // Manually inject this identity into Alice's database so the node could leak it, but we prove won't
        aliceNode.database.transaction { aliceNode.services.identityService.verifyAndRegisterIdentity(confidentialIdentCert) }
        assertNotNull(aliceNode.database.transaction { aliceNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })

        // Generate a payment from Charlie to Alice, including the confidential state
        val payTx = charlieNode.services.startFlow(CashPaymentFlow(1000.DOLLARS, alice, anonymous)).resultFuture.getOrThrow().stx

        // Run the flow to sync up the identities, and confirm Charlie's confidential identity doesn't leak
        assertNull(bobNode.database.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })
        aliceNode.services.startFlow(SyncKeyMappingInitiator(bob, payTx.tx)).resultFuture.getOrThrow()
        assertNull(bobNode.database.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })
    }
}

@InitiatingFlow
private class SyncKeyMappingInitiator(private val otherParty: Party, private val tx: WireTransaction) : FlowLogic<Boolean>() {
    @Suspendable
    override fun call() : Boolean {
        val session = initiateFlow(otherParty)
        subFlow(SyncKeyMappingFlow(session, tx))
        return session.receive<Boolean>().unwrap{ it }
    }
}

@InitiatedBy(SyncKeyMappingInitiator::class)
private class SyncKeyMappingResponse(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(SyncKeyMappingFlowHandler(otherSession))
        otherSession.send(true)
    }
}