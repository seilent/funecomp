package net.seilent.funecomp.sim

import net.seilent.funecomp.proto.AppAbort
import net.seilent.funecomp.proto.AppBeginReply
import net.seilent.funecomp.proto.AppState
import net.seilent.funecomp.proto.Groups
import net.seilent.funecomp.proto.Node
import net.seilent.funecomp.proto.Req
import net.seilent.funecomp.proto.SvcInfo
import net.seilent.funecomp.proto.SvcKey
import net.seilent.funecomp.proto.gen.Enums
import net.seilent.funecomp.proto.gen.Err
import net.seilent.funecomp.proto.gen.Msg
import net.seilent.funecomp.proto.gen.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class AppSimTest {
    private class Peer(override val phone: String = "phone") : Out {
        val got = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
        var dropped: String? = null

        override fun push(type: Int, payload: ByteArray) {
            got += type to payload
        }

        override fun drop(reason: String) {
            dropped = reason
        }

        fun states() = got.filter { it.first == Msg.APP_STATE }.map { AppState.from(Node.decode("APP_STATE", it.second)) }

        fun aborts() = got.filter { it.first == Msg.APP_ABORT }.map { AppAbort.from(Node.decode("APP_ABORT", it.second)) }
    }

    private var t = 1_000L
    private val bytes = ByteArray(40_000) { (it * 7 + 3).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
    private val size = bytes.size.toLong()

    private fun device(vararg faults: String, replyMs: Long = Proto.svcReplyMs.toLong(), services: List<SvcInfo> = emptyList()) =
        Device(Options(Board.of("v1"), Faults(faults.toList()), appReplyMs = replyMs, services = services), Library.synthetic()) { t }

    private fun link(d: Device, p: Peer, features: Long = Groups.all) = p.also {
        d.linked(it, features)
        d.greet(it)
    }

    private fun begin(d: Device, p: Peer, digest: ByteArray = sha, id: String = "paddle") = d.request(Msg.APP_BEGIN, Node.decode("APP_BEGIN", Req.appBegin(id, 3, size, digest)), p)

    private fun reply(a: Answer) = AppBeginReply.from(Node.decode("APP_BEGIN.reply", (a as? Answer.Ok ?: throw AssertionError("failed: ${(a as Answer.Fail).code} ${a.text}")).payload))

    private fun send(d: Device, p: Peer, from: Long, to: Long = size) {
        var at = from
        while (at < to) {
            val end = minOf(to, at + Proto.updChunk)
            d.appData(Node.decode("APP_DATA", Req.appData(at, bytes.copyOfRange(at.toInt(), end.toInt()))), p)
            at = end
        }
    }

    private fun end(d: Device, p: Peer, digest: ByteArray = sha) = d.request(Msg.APP_END, Node.decode("APP_END", Req.appEnd(digest)), p).also { replied(it, p) }

    private fun replied(a: Answer, p: Peer) {
        when (a) {
            is Answer.Ok -> a.then?.invoke(p)
            is Answer.Fail -> a.then?.invoke(p)
            is Answer.Later -> {}
        }
    }

    private fun fail(a: Answer) = (a as Answer.Fail).let { it.code to it.text }

    private fun await(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!cond()) {
            if (System.nanoTime() > until) throw AssertionError("timed out: $what")
            Thread.sleep(5)
        }
    }

    private fun push(d: Device, p: Peer): Long {
        val r = reply(begin(d, p))
        send(d, p, r.offset)
        assertTrue(end(d, p) is Answer.Ok)
        return r.stage!!
    }

    @Test
    fun aBeginNeedsTheAppsBit() {
        val d = device()
        assertEquals(Err.unknown to "unknown", fail(begin(d, link(d, Peer(), Groups.all and Groups.mask("appPkg").inv()))))
        val r = reply(begin(d, link(d, Peer("other"))))
        assertEquals(0L, r.offset)
        assertEquals(1L, r.stage)
        assertEquals(0L, Faults(emptyList()).also { Options(Board.of("v1"), it, minor = 16) }.features and Groups.mask("appPkg"))
    }

    @Test
    fun eachAcceptedBeginGetsANewStageAndTheResultCarriesIt() {
        val d = device()
        val p = link(d, Peer())
        val first = push(d, p)
        await("first state") { p.states().size == 1 }
        assertEquals(AppState("paddle", 3, Enums.AppResult.installed, "installed", first), p.states().single())
        val second = reply(begin(d, p, ByteArray(32) { 1 })).stage!!
        assertTrue(second > first)
    }

    @Test
    fun aRestartRestartsTheSerialAndLosesTheAsk() {
        val d = device("app-restart")
        val p = link(d, Peer())
        assertEquals(1L, reply(begin(d, p)).stage)
        send(d, p, 0)
        val a = d.request(Msg.APP_END, Node.decode("APP_END", Req.appEnd(sha)), p) as Answer.Ok
        assertEquals(Err.busy to "", fail(begin(d, p)))
        a.then!!.invoke(p)
        await("restart") { !d.apps.asking }
        assertEquals(1L, reply(begin(d, p)).stage)
        assertTrue(p.states().isEmpty())
    }

    @Test
    fun aDeferredBeginDropsEarlyDataAndAnswersOpeningPastItsDeadline() {
        val d = device("app-open-ms=100")
        val p = link(d, Peer())
        val a = begin(d, p) as Answer.Later
        send(d, p, 0, Proto.updChunk.toLong())
        assertEquals(0L, d.apps.partSize)
        assertEquals(0L, reply(d.resolve(a)).offset)
        val late = device("app-open-ms=5000", replyMs = 3000)
        val q = link(late, Peer())
        val b = begin(late, q) as Answer.Later
        assertEquals(1000L, b.ms)
        assertEquals(Err.busy to "opening", fail(late.resolve(b)))
    }

    @Test
    fun theSameClaimResumesAndAnotherClaimTruncates() {
        val d = device()
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0, 20_000)
        d.unlinked(p)
        val again = link(d, Peer())
        val r = reply(begin(d, again))
        assertEquals(d.apps.partSize, r.offset)
        assertTrue(r.offset > 0)
        d.unlinked(again)
        val other = link(d, Peer())
        assertEquals(0L, reply(begin(d, other, ByteArray(32) { 9 })).offset)
    }

    @Test
    fun aSecondBeginFromTheOwnerClosesAndBeginsAnswerBusyUntilTheClose() {
        val d = device("app-drop-ms=200")
        val p = link(d, Peer())
        reply(begin(d, p))
        assertEquals(Err.busy, fail(begin(d, p)).first)
        assertEquals(Err.busy, fail(begin(d, p)).first)
        t += 300
        assertEquals(0L, reply(begin(d, p)).offset)
        assertEquals(Err.busy, fail(begin(d, link(d, Peer("other")))).first)
    }

    @Test
    fun anAbortLeavesThePartAndAnEndAfterItAnswersNoStage() {
        for (code in listOf("offsetMismatch", "busy", "noCard", "noSpace", "writeError", "badValue")) {
            val d = device("app-abort-at=${Proto.updChunk}@$code")
            val p = link(d, Peer())
            reply(begin(d, p))
            send(d, p, 0)
            val a = p.aborts().single()
            assertEquals(code, Proto.updChunk.toLong(), a.offset)
            assertEquals(code, Enums.AbortCode.names.entries.first { it.value == code }.key, a.code)
            assertEquals(code, Proto.updChunk.toLong(), d.apps.partSize)
            assertEquals(code, Err.badValue to "no stage", fail(end(d, p)))
        }
    }

    @Test
    fun theScriptedBeginsAnswerBusyStopStepAndZero() {
        val d = device("app-begins=busy/step/stop/zero+stop/ok")
        val p = link(d, Peer())
        val chunk = Proto.updChunk.toLong()
        assertEquals(Err.busy to "", fail(begin(d, p)))
        assertEquals(0L, reply(begin(d, p)).offset)
        send(d, p, 0)
        assertEquals(chunk, d.apps.partSize)
        assertEquals(chunk, reply(begin(d, p)).offset)
        send(d, p, chunk)
        assertEquals(chunk, d.apps.partSize)
        assertEquals(0L, reply(begin(d, p)).offset)
        send(d, p, 0)
        assertEquals(0L, d.apps.partSize)
        assertEquals(listOf(chunk, chunk, 0L), p.aborts().map { it.offset })
        assertTrue(p.aborts().all { it.code == Enums.AbortCode.offsetMismatch })
        send(d, p, reply(begin(d, p)).offset)
        assertTrue(end(d, p) is Answer.Ok)
        val e = device("app-begins=step@busy")
        val q = link(e, Peer())
        reply(begin(e, q))
        send(e, q, 0)
        assertEquals(listOf(chunk to Enums.AbortCode.busy), q.aborts().map { it.offset to it.code })
    }

    @Test
    fun theScriptedCardStepsAbortTheLastPieceAndAnswerTheEndNoCard() {
        val d = device("app-begins=last@noCard/endcard")
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0)
        val last = size - size % Proto.updChunk
        assertEquals(listOf(last), p.aborts().map { it.offset })
        assertEquals(Enums.AbortCode.noCard, p.aborts().single().code)
        assertEquals(last, reply(begin(d, p)).offset)
        send(d, p, last)
        assertEquals(Err.noCard to "no card", fail(end(d, p)))
        assertEquals(size, reply(begin(d, p)).offset)
        assertTrue(end(d, p) is Answer.Ok)
    }

    @Test
    fun aLateAbortGoesOutAtTheEnd() {
        val d = device("app-abort-at=${size - 1}@noCard", "app-abort-late")
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0)
        assertTrue(p.aborts().isEmpty())
        assertEquals(Err.badValue to "no stage", fail(end(d, p)))
        assertEquals(listOf(size - size % Proto.updChunk to Enums.AbortCode.noCard), p.aborts().map { it.offset to it.code })
    }

    @Test
    fun anEndBusyAnswersBusyOnceAndKeepsTheWholePart() {
        val d = device("app-end-busy")
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0)
        assertEquals(Err.busy to "", fail(end(d, p)))
        assertEquals(size, reply(begin(d, p)).offset)
        assertTrue(end(d, p) is Answer.Ok)
    }

    @Test
    fun endErrorsAndAFailedCheckInOnePass() {
        val d = device()
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0)
        assertEquals(Err.badValue to "sha", fail(end(d, p, ByteArray(32))))
        val short = device()
        val q = link(short, Peer())
        reply(begin(short, q))
        send(short, q, 0, 16_000)
        assertEquals(Err.badValue to "offset", fail(end(short, q)))
        for ((code, word) in listOf(Err.noSpace to "no room", Err.noCard to "no card", Err.badSignature to "signature", Err.refused to "layout")) {
            val e = device("app-end-fail=$code:$word")
            val r = link(e, Peer())
            val s = reply(begin(e, r)).stage
            send(e, r, 0)
            assertEquals(code to word, fail(end(e, r)))
            if (code == Err.noCard) assertTrue(r.states().isEmpty())
            else {
                await(word) { r.states().isNotEmpty() }
                assertEquals(AppState("paddle", 3, Enums.AppResult.refused, word, s), r.states().single())
                assertEquals(0L, reply(begin(e, r)).offset)
            }
        }
        val two = device("app-end-fail=2:sha")
        val w = link(two, Peer())
        reply(begin(two, w))
        send(two, w, 0)
        assertEquals(Err.badValue to "sha", fail(end(two, w)))
        assertEquals(size, reply(begin(two, w)).offset)
        assertTrue(w.states().isEmpty())
    }

    @Test
    fun aPartHashMismatchIsRefusedAndASyncEndErrorKeepsTheStage() {
        val d = device()
        val p = link(d, Peer())
        reply(begin(d, p))
        var at = 0L
        while (at < size) {
            val n = minOf(size, at + Proto.updChunk)
            d.appData(Node.decode("APP_DATA", Req.appData(at, ByteArray((n - at).toInt()) { 1 })), p)
            at = n
        }
        assertEquals(Err.badValue to "", fail(end(d, p, ByteArray(31))))
        assertEquals(Err.refused to "transport", fail(end(d, p)))
        await("refused") { p.states().isNotEmpty() }
        assertEquals(Enums.AppResult.refused to "transport", p.states().single().let { it.state to it.text })
        assertEquals(0L, reply(begin(d, p)).offset)
        val k = device()
        val q = link(k, Peer())
        reply(begin(k, q))
        send(k, q, 0)
        assertEquals(Err.badValue to "sha", fail(end(k, q, ByteArray(32))))
        assertTrue(end(k, q) is Answer.Ok)
        assertEquals(Err.unknown to "unknown", fail(d.request(Msg.APP_END, Node.decode("APP_END", Req.appEnd(sha)), link(d, Peer("old"), Groups.all and Groups.mask("appPkg").inv()))))
    }

    @Test
    fun theClaimChecksFollowTheDevice() {
        val d = device()
        val p = link(d, Peer())
        assertEquals(Err.badValue to "claim", fail(begin(d, p, id = "phone")))
        assertEquals(Err.badValue to "", fail(d.request(Msg.APP_BEGIN, Node.decode("APP_BEGIN", Req.appBegin("paddle", 0, size, sha)), p)))
        assertEquals(Err.tooBig to "size", fail(d.request(Msg.APP_BEGIN, Node.decode("APP_BEGIN", Req.appBegin("paddle", 3, APP_BUNDLE_MAX + 1, sha)), p)))
        val small = Device(Options(Board.of("v1"), Faults(emptyList()), cardFreeMiB = 8), Library.synthetic()) { t }
        assertEquals(Err.noSpace to "card full", fail(begin(small, link(small, Peer()))))
        reply(begin(d, p))
        d.appData(Node.decode("APP_DATA", Req.appData(0, bytes.copyOf(Proto.updChunk))), p)
        d.appData(Node.decode("APP_DATA", Req.appData(Proto.updChunk.toLong(), bytes.copyOf(Proto.updChunk))), p)
        d.appData(Node.decode("APP_DATA", Req.appData(2L * Proto.updChunk, bytes.copyOf(Proto.updChunk))), p)
        assertEquals(listOf(2L * Proto.updChunk to Enums.AbortCode.offsetMismatch), p.aborts().map { it.offset to it.code })
    }

    @Test
    fun aResultUnlinksThePartAndAPendingUpdateKeepsIt() {
        val d = device()
        val p = link(d, Peer())
        push(d, p)
        await("installed") { p.states().isNotEmpty() }
        assertEquals(0L, reply(begin(d, p)).offset)
        val q = device("app-pending")
        val r = link(q, Peer())
        push(q, r)
        await("pending") { r.states().isNotEmpty() }
        assertEquals(size, reply(begin(q, r)).offset)
    }

    @Test
    fun aCheckPastTheDeadlineAnswersCheckingThenTheResult() {
        val d = device("app-check-ms=300", replyMs = 2100)
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0)
        val a = end(d, p) as Answer.Later
        assertEquals(Err.busy to "checking", fail(d.resolve(a)))
        await("result after checking") { p.states().isNotEmpty() }
        assertEquals(Enums.AppResult.installed, p.states().single().state)
    }

    @Test
    fun theAskAnswers() {
        val want = mapOf(
            "app-ask=yes" to (Enums.AppResult.installed to "installed"),
            "app-ask=no" to (Enums.AppResult.declined to "declined"),
            "app-ask=expire" to (Enums.AppResult.declined to "expired"),
            "app-pending" to (Enums.AppResult.updatePending to "update pending"),
        )
        for ((fault, outcome) in want) {
            val d = device(fault)
            val p = link(d, Peer())
            push(d, p)
            await(fault) { p.states().isNotEmpty() }
            assertEquals(fault, outcome, p.states().single().let { it.state to it.text })
        }
    }

    @Test
    fun aResultGoesToTheBeginningPhoneAtItsNextConnectOnly() {
        val d = device("app-ask-ms=200")
        val a = link(d, Peer("a"))
        val stage = push(d, a)
        d.unlinked(a)
        val b = link(d, Peer("b"))
        await("kept for a") { d.apps.kept("a") == 1 }
        assertTrue(b.states().isEmpty())
        val back = link(d, Peer("a"))
        await("kept result") { back.states().isNotEmpty() }
        assertEquals(stage, back.states().single().stage)
        assertTrue(b.states().isEmpty())
    }

    @Test
    fun theResultGoesOutOnlyAfterTheEndReply() {
        val d = device()
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0)
        val a = d.request(Msg.APP_END, Node.decode("APP_END", Req.appEnd(sha)), p) as Answer.Ok
        val runs = d.apps.linkedRuns
        link(d, Peer("z"))
        await("a later run") { d.apps.linkedRuns > runs }
        assertTrue(p.states().isEmpty())
        assertEquals(Err.busy, fail(begin(d, link(d, Peer("y")))).first)
        a.then!!.invoke(p)
        await("result") { p.states().isNotEmpty() }
    }

    @Test
    fun aKeptResultGoesOutAfterTheGreetAndNotAtTheLink() {
        val d = device("app-ask-ms=200")
        val a = link(d, Peer("a"))
        val stage = push(d, a)
        d.unlinked(a)
        await("kept for a") { d.apps.kept("a") == 1 }
        val b = Peer("a")
        d.linked(b, Groups.all)
        val runs = d.apps.linkedRuns
        link(d, Peer("z"))
        await("a later run") { d.apps.linkedRuns > runs }
        assertTrue(b.states().isEmpty())
        assertEquals(1, d.apps.kept("a"))
        d.greet(b)
        await("kept result") { b.states().isNotEmpty() }
        assertEquals(stage, b.states().single().stage)
    }

    @Test
    fun aLostResultIsGoneAndARedeliveredOneComesAgain() {
        val lost = device("app-state-lost")
        val p = link(lost, Peer())
        push(lost, p)
        await("dropped") { p.dropped != null }
        lost.unlinked(p)
        val runs = lost.apps.linkedRuns
        val again = link(lost, Peer())
        await("linked run") { lost.apps.linkedRuns > runs }
        assertTrue(p.states().isEmpty() && again.states().isEmpty())
        val re = device("app-redeliver")
        val q = link(re, Peer())
        val stage = push(re, q)
        await("delivered") { q.states().isNotEmpty() }
        re.unlinked(q)
        val next = link(re, Peer())
        await("redelivered") { next.states().isNotEmpty() }
        assertEquals(listOf(stage), next.states().map { it.stage })
    }

    @Test
    fun aLostEndClosesWithACompletePart() {
        val d = device("app-end-lost")
        val p = link(d, Peer())
        reply(begin(d, p))
        send(d, p, 0)
        assertTrue(end(d, p) is Answer.Later)
        assertEquals("app end lost", p.dropped)
        d.unlinked(p)
        val again = link(d, Peer())
        assertEquals(size, reply(begin(d, again)).offset)
        assertTrue(end(d, again) is Answer.Ok)
        await("result") { again.states().isNotEmpty() }
    }

    @Test
    fun keysForAnAbsentIdAnswerNotInstalled() {
        val d = device(services = listOf(SvcInfo("tidal", "TIDAL", listOf("client_id"), true)))
        val f = d.request(Msg.SVC_KEYS, Node.decode("SVC_KEYS", Req.svcKeys("nope", listOf(SvcKey("client_id", "x")))), link(d, Peer())) as Answer.Fail
        assertEquals(Err.notFound to "not installed", f.code to f.text)
    }
}
