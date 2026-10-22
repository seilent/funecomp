package net.seilent.funecomp.proto.gen

enum class Base { U8, U16, U32, U64, I32, I64, BOOL, STR, BYTES, ENUM, RECORD }

class Field(val tag: Int, val name: String, val base: Base, val ref: String?, val list: Boolean)

class MsgInfo(val id: Int, val name: String, val dir: String, val push: Boolean)

object Pin {
    const val COMMIT = "b75bae10d79708eae3dfa1dc5f4c03572fb17c2b"
    const val PROTO_SHA256 = "852272ac80c4219155162e0460a888135c1881848d722b0482c0a23469621ebf"
    const val VECTORS_SHA256 = "e472f9b5238c827033463e3f4a133120dbf4223ef806dbfb39222cff5edcf48f"
    const val TOKENS_SHA256 = "97eb918b633c01dc6f8277ed650e6ec6c248fed024fdd9d1002039822e9a1217"
    const val TOKENSV1_SHA256 = "1ef8a4ce11176c493a78cd1fa1f24c7fc82844428c5cc7d62801f7eb753e1d1f"
}

object Proto {
    const val uuid = "6238ed73-a581-4335-9d8a-df1ecf688118"
    const val magic = 0x50434446
    const val major = 1
    const val minor = 15
    const val headerBytes = 6
    const val tlvHeaderBytes = 3
    const val maxPayload = 16384
    const val flagReply = 0x01
    const val flagError = 0x02
    const val flagWant = 0x04
    const val flagReserved = 0xf8
    const val helloTimeoutMs = 5000
    const val requestTimeoutMs = 10000
    const val keepaliveIdleMs = 30000
    const val keepaliveBusyMs = 5000
    const val busyCapMs = 60000
    const val deadIntervals = 3
    const val pageFillPct = 75
    const val stateIntervalMs = 10000
    const val pushGateMs = 1000
    const val playMaxTracks = 500
    const val imgMaxBytes = 262144
    const val imgDataReserve = 16
    const val imgEndReplyMs = 5000
    const val imgSrcMaxBytes = 4194304
    const val imgSrcMaxSide = 8192
    const val imgRefetchGapMs = 2000
    const val imgRefetchIdleMs = 10000
    const val imgRefetchTries = 3
    const val refuseCount = 5
    const val refuseWindowMs = 10000
    val backoffMs: List<Long> = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L)
    const val artChunk = 16320
    const val artMaxPx = 512
    const val artMinPx = 64
    const val lrcMaxBytes = 15360
    const val updChunk = 16320
    const val lyrLineMax = 4096
    const val searchMaxBytes = 256
    const val lyrOffsetMaxMs = 60000
    const val streamChunk = 16320
    const val streamWindowMax = 32
    const val streamStallMs = 60000
    const val phoneItemMaxBytes = 1024
    const val phoneListMs = 30000
}

object Msg {
    const val HELLO = 0x01
    const val PING = 0x02
    const val BYE = 0x03
    const val STATE = 0x10
    const val SCHEMA = 0x11
    const val SETTINGS = 0x12
    const val EQ = 0x13
    const val QUEUE = 0x14
    const val LIB = 0x15
    const val LIVE = 0x16
    const val TRANSPORT = 0x20
    const val VOLUME = 0x21
    const val SET = 0x22
    const val DRAFT = 0x23
    const val EQ_SET = 0x24
    const val PLAY = 0x25
    const val QUEUE_OP = 0x26
    const val LIB_GET = 0x27
    const val ACTION = 0x28
    const val ART_GET = 0x29
    const val LRC_PUT = 0x2a
    const val LRC_DEL = 0x2b
    const val UPD_BEGIN = 0x2c
    const val UPD_DATA = 0x2d
    const val UPD_END = 0x2e
    const val UPD_ABORT = 0x2f
    const val IMG_BEGIN = 0x30
    const val IMG_DATA = 0x32
    const val IMG_END = 0x33
    const val IMG_LIST = 0x34
    const val IMG_DEL = 0x35
    const val IMG_ABORT = 0x36
    const val EQ_PRESET = 0x37
    const val ART_BLOCK = 0x38
    const val LYR_GET = 0x39
    const val ART_PICK = 0x3a
    const val IMG_TAG = 0x3b
    const val LYR_OFFSET = 0x3c
    const val PLAYLOG_GET = 0x3d
    const val PLAYLOG_ACK = 0x3e
    const val STREAM_READ = 0x3f
    const val STREAM_CANCEL = 0x40
    const val PHONE_LIST = 0x41
    const val PHONE_ART = 0x42
    val info: Map<Int, MsgInfo> = mapOf(
        HELLO to MsgInfo(HELLO, "HELLO", "app", false),
        PING to MsgInfo(PING, "PING", "both", false),
        BYE to MsgInfo(BYE, "BYE", "dev", true),
        STATE to MsgInfo(STATE, "STATE", "dev", true),
        SCHEMA to MsgInfo(SCHEMA, "SCHEMA", "dev", true),
        SETTINGS to MsgInfo(SETTINGS, "SETTINGS", "dev", true),
        EQ to MsgInfo(EQ, "EQ", "dev", true),
        QUEUE to MsgInfo(QUEUE, "QUEUE", "dev", true),
        LIB to MsgInfo(LIB, "LIB", "dev", true),
        LIVE to MsgInfo(LIVE, "LIVE", "dev", true),
        TRANSPORT to MsgInfo(TRANSPORT, "TRANSPORT", "app", false),
        VOLUME to MsgInfo(VOLUME, "VOLUME", "app", false),
        SET to MsgInfo(SET, "SET", "app", false),
        DRAFT to MsgInfo(DRAFT, "DRAFT", "app", false),
        EQ_SET to MsgInfo(EQ_SET, "EQ_SET", "app", false),
        PLAY to MsgInfo(PLAY, "PLAY", "app", false),
        QUEUE_OP to MsgInfo(QUEUE_OP, "QUEUE_OP", "app", false),
        LIB_GET to MsgInfo(LIB_GET, "LIB_GET", "app", false),
        ACTION to MsgInfo(ACTION, "ACTION", "app", false),
        ART_GET to MsgInfo(ART_GET, "ART_GET", "app", false),
        LRC_PUT to MsgInfo(LRC_PUT, "LRC_PUT", "app", false),
        LRC_DEL to MsgInfo(LRC_DEL, "LRC_DEL", "app", false),
        UPD_BEGIN to MsgInfo(UPD_BEGIN, "UPD_BEGIN", "app", false),
        UPD_DATA to MsgInfo(UPD_DATA, "UPD_DATA", "app", true),
        UPD_END to MsgInfo(UPD_END, "UPD_END", "app", false),
        UPD_ABORT to MsgInfo(UPD_ABORT, "UPD_ABORT", "dev", true),
        IMG_BEGIN to MsgInfo(IMG_BEGIN, "IMG_BEGIN", "app", false),
        IMG_DATA to MsgInfo(IMG_DATA, "IMG_DATA", "app", true),
        IMG_END to MsgInfo(IMG_END, "IMG_END", "app", false),
        IMG_LIST to MsgInfo(IMG_LIST, "IMG_LIST", "app", false),
        IMG_DEL to MsgInfo(IMG_DEL, "IMG_DEL", "app", false),
        IMG_ABORT to MsgInfo(IMG_ABORT, "IMG_ABORT", "dev", true),
        EQ_PRESET to MsgInfo(EQ_PRESET, "EQ_PRESET", "app", false),
        ART_BLOCK to MsgInfo(ART_BLOCK, "ART_BLOCK", "dev", false),
        LYR_GET to MsgInfo(LYR_GET, "LYR_GET", "app", false),
        ART_PICK to MsgInfo(ART_PICK, "ART_PICK", "dev", false),
        IMG_TAG to MsgInfo(IMG_TAG, "IMG_TAG", "app", false),
        LYR_OFFSET to MsgInfo(LYR_OFFSET, "LYR_OFFSET", "app", false),
        PLAYLOG_GET to MsgInfo(PLAYLOG_GET, "PLAYLOG_GET", "app", false),
        PLAYLOG_ACK to MsgInfo(PLAYLOG_ACK, "PLAYLOG_ACK", "app", false),
        STREAM_READ to MsgInfo(STREAM_READ, "STREAM_READ", "dev", false),
        STREAM_CANCEL to MsgInfo(STREAM_CANCEL, "STREAM_CANCEL", "dev", true),
        PHONE_LIST to MsgInfo(PHONE_LIST, "PHONE_LIST", "app", false),
        PHONE_ART to MsgInfo(PHONE_ART, "PHONE_ART", "dev", false),
    )
    val byName: Map<String, Int> = info.values.associate { it.name to it.id }
}

object Err {
    const val unknown = 1
    const val badValue = 2
    const val busy = 3
    const val refused = 4
    const val tooBig = 5
    const val crc = 6
    const val staleRev = 7
    const val noLibrary = 8
    const val noSpace = 9
    const val notFound = 10
    const val readOnly = 11
    const val exists = 12
    const val cancelled = 13
    const val noCard = 14
    const val badSignature = 15
    val names: Map<Int, String> = mapOf(1 to "unknown", 2 to "badValue", 3 to "busy", 4 to "refused", 5 to "tooBig", 6 to "crc", 7 to "staleRev", 8 to "noLibrary", 9 to "noSpace", 10 to "notFound", 11 to "readOnly", 12 to "exists", 13 to "cancelled", 14 to "noCard", 15 to "badSignature")
}

object Feature {
    const val settings = 0
    const val eq = 1
    const val library = 2
    const val queue = 3
    const val image = 4
    const val art = 5
    const val lyrics = 6
    const val update = 7
    const val clockAuto = 8
    const val eqPresets = 9
    const val artBlock = 10
    const val lyricsGet = 11
    const val artPick = 12
    const val imgOrigin = 13
    const val stateClock = 14
    const val eqSlots = 15
    const val eqGraphic = 16
    const val eqPass = 17
    const val outputKinds = 18
    const val rowConfirm = 19
    const val search = 20
    const val lyricsOffset = 21
    const val receiver = 22
    const val playLog = 23
    const val genres = 24
    const val queueAdd = 25
    const val phoneFiles = 26
    const val updSig = 27
    const val imgSource = 28
    const val eqStages = 29
    const val liveRows = 30
    val names: Map<Int, String> = mapOf(0 to "settings", 1 to "eq", 2 to "library", 3 to "queue", 4 to "image", 5 to "art", 6 to "lyrics", 7 to "update", 8 to "clockAuto", 9 to "eqPresets", 10 to "artBlock", 11 to "lyricsGet", 12 to "artPick", 13 to "imgOrigin", 14 to "stateClock", 15 to "eqSlots", 16 to "eqGraphic", 17 to "eqPass", 18 to "outputKinds", 19 to "rowConfirm", 20 to "search", 21 to "lyricsOffset", 22 to "receiver", 23 to "playLog", 24 to "genres", 25 to "queueAdd", 26 to "phoneFiles", 27 to "updSig", 28 to "imgSource", 29 to "eqStages", 30 to "liveRows")
}

object Enums {
    object Source {
        const val local = 0
        const val phone = 1
        const val phoneFile = 2
        val names: Map<Int, String> = mapOf(0 to "local", 1 to "phone", 2 to "phoneFile")
    }
    object Output {
        const val wired = 0
        const val btHeadset = 1
        const val phoneReceiver = 2
        const val speaker = 3
        const val usbDac = 4
        val names: Map<Int, String> = mapOf(0 to "wired", 1 to "btHeadset", 2 to "phoneReceiver", 3 to "speaker", 4 to "usbDac")
    }
    object Repeat {
        const val off = 0
        const val all = 1
        const val one = 2
        val names: Map<Int, String> = mapOf(0 to "off", 1 to "all", 2 to "one")
    }
    object Card {
        const val out = 0
        const val error = 1
        const val busy = 2
        const val ready = 3
        val names: Map<Int, String> = mapOf(0 to "out", 1 to "error", 2 to "busy", 3 to "ready")
    }
    object ByeReason {
        const val version = 0
        const val replaced = 1
        const val refused = 2
        const val companionOff = 3
        const val shutdown = 4
        const val streaming = 5
        val names: Map<Int, String> = mapOf(0 to "version", 1 to "replaced", 2 to "refused", 3 to "companionOff", 4 to "shutdown", 5 to "streaming")
    }
    object RowKind {
        const val choice = 0
        const val steps = 1
        const val range = 2
        const val toggle = 3
        const val action = 4
        const val draft = 5
        const val info = 6
        val names: Map<Int, String> = mapOf(0 to "choice", 1 to "steps", 2 to "range", 3 to "toggle", 4 to "action", 5 to "draft", 6 to "info")
    }
    object RowFlag {
        const val readOnly = 0
        const val restart = 1
        const val boardOnly = 2
        const val confirm = 3
        val names: Map<Int, String> = mapOf(0 to "readOnly", 1 to "restart", 2 to "boardOnly", 3 to "confirm")
    }
    object EqOutput {
        const val wired = 0
        const val bt = 1
        val names: Map<Int, String> = mapOf(0 to "wired", 1 to "bt")
    }
    object BandType {
        const val off = 0
        const val peak = 1
        const val lowShelf = 2
        const val highShelf = 3
        const val bandPass = 4
        const val lowPass = 5
        const val highPass = 6
        const val allPass = 7
        val names: Map<Int, String> = mapOf(0 to "off", 1 to "peak", 2 to "lowShelf", 3 to "highShelf", 4 to "bandPass", 5 to "lowPass", 6 to "highPass", 7 to "allPass")
    }
    object EqMode {
        const val parametric = 0
        const val graphic = 1
        val names: Map<Int, String> = mapOf(0 to "parametric", 1 to "graphic")
    }
    object View {
        const val artists = 0
        const val albums = 1
        const val songs = 2
        const val artistAlbums = 3
        const val albumTracks = 4
        const val playlists = 5
        const val folders = 6
        const val favorites = 7
        const val searchArtists = 8
        const val searchAlbums = 9
        const val searchSongs = 10
        const val genres = 11
        val names: Map<Int, String> = mapOf(0 to "artists", 1 to "albums", 2 to "songs", 3 to "artistAlbums", 4 to "albumTracks", 5 to "playlists", 6 to "folders", 7 to "favorites", 8 to "searchArtists", 9 to "searchAlbums", 10 to "searchSongs", 11 to "genres")
    }
    object TransportCmd {
        const val toggle = 0
        const val play = 1
        const val pause = 2
        const val next = 3
        const val prev = 4
        const val seek = 5
        const val shuffle = 6
        const val repeat = 7
        val names: Map<Int, String> = mapOf(0 to "toggle", 1 to "play", 2 to "pause", 3 to "next", 4 to "prev", 5 to "seek", 6 to "shuffle", 7 to "repeat")
    }
    object DraftKind {
        const val usbMode = 0
        const val btCodec = 1
        const val sleep = 2
        const val clock = 3
        val names: Map<Int, String> = mapOf(0 to "usbMode", 1 to "btCodec", 2 to "sleep", 3 to "clock")
    }
    object BtRole {
        const val receiver = 0
        const val headphones = 1
        val names: Map<Int, String> = mapOf(0 to "receiver", 1 to "headphones")
    }
    object DraftStatus {
        const val done = 0
        const val pending = 1
        val names: Map<Int, String> = mapOf(0 to "done", 1 to "pending")
    }
    object QueueCmd {
        const val page = 0
        const val playAt = 1
        const val remove = 2
        const val move = 3
        const val playNext = 4
        const val clear = 5
        const val add = 6
        val names: Map<Int, String> = mapOf(0 to "page", 1 to "playAt", 2 to "remove", 3 to "move", 4 to "playNext", 5 to "clear", 6 to "add")
    }
    object ActionCmd {
        const val rescan = 0
        const val shuffleLibrary = 1
        const val favorite = 2
        val names: Map<Int, String> = mapOf(0 to "rescan", 1 to "shuffleLibrary", 2 to "favorite")
    }
    object ImgKind {
        const val artist = 0
        val names: Map<Int, String> = mapOf(0 to "artist")
    }
    object AbortCode {
        const val crc = 0
        const val badValue = 1
        const val noSpace = 2
        const val busy = 3
        const val offsetMismatch = 4
        const val superseded = 5
        const val writeError = 6
        const val noCard = 7
        val names: Map<Int, String> = mapOf(0 to "crc", 1 to "badValue", 2 to "noSpace", 3 to "busy", 4 to "offsetMismatch", 5 to "superseded", 6 to "writeError", 7 to "noCard")
    }
    object EqPresetOp {
        const val save = 0
        const val remove = 1
        const val rename = 2
        val names: Map<Int, String> = mapOf(0 to "save", 1 to "remove", 2 to "rename")
    }
    object BlockCmd {
        const val blockImage = 0
        const val wrongArtist = 1
        val names: Map<Int, String> = mapOf(0 to "blockImage", 1 to "wrongArtist")
    }
    object LyricsKind {
        const val synced = 0
        const val plain = 1
        val names: Map<Int, String> = mapOf(0 to "synced", 1 to "plain")
    }
    object ImgOrigin {
        const val library = 0
        const val phone = 1
        val names: Map<Int, String> = mapOf(0 to "library", 1 to "phone")
    }
    val byName: Map<String, Map<Int, String>> = mapOf(
        "SOURCE" to Source.names,
        "OUTPUT" to Output.names,
        "REPEAT" to Repeat.names,
        "CARD" to Card.names,
        "BYE_REASON" to ByeReason.names,
        "ROW_KIND" to RowKind.names,
        "ROW_FLAG" to RowFlag.names,
        "EQ_OUTPUT" to EqOutput.names,
        "BAND_TYPE" to BandType.names,
        "EQ_MODE" to EqMode.names,
        "VIEW" to View.names,
        "TRANSPORT_CMD" to TransportCmd.names,
        "DRAFT_KIND" to DraftKind.names,
        "BT_ROLE" to BtRole.names,
        "DRAFT_STATUS" to DraftStatus.names,
        "QUEUE_CMD" to QueueCmd.names,
        "ACTION_CMD" to ActionCmd.names,
        "IMG_KIND" to ImgKind.names,
        "ABORT_CODE" to AbortCode.names,
        "EQ_PRESET_OP" to EqPresetOp.names,
        "BLOCK_CMD" to BlockCmd.names,
        "LYRICS_KIND" to LyricsKind.names,
        "IMG_ORIGIN" to ImgOrigin.names,
    )
}

object Tags {
    object Hello {
        const val magic = 1
        const val major = 2
        const val minor = 3
        const val appVersion = 4
        const val features = 5
    }
    object HelloReply {
        const val magic = 1
        const val major = 2
        const val minor = 3
        const val board = 4
        const val version = 5
        const val screenW = 6
        const val screenH = 7
        const val backdropW = 8
        const val backdropH = 9
        const val maxPayload = 10
        const val idleMs = 11
        const val busyMs = 12
        const val features = 13
        const val libRev = 14
        const val settingsRev = 15
        const val snapRev = 16
        const val eqSlot = 17
        const val bandTypes = 18
        const val phoneMime = 19
        const val phoneMaxRate = 20
        const val phoneMaxBits = 21
    }
    object Bye {
        const val reason = 1
        const val major = 2
        const val minor = 3
    }
    object State {
        const val source = 1
        const val output = 2
        const val track = 3
        const val title = 4
        const val artist = 5
        const val artists = 6
        const val mbids = 7
        const val durationMs = 8
        const val positionMs = 9
        const val sampleMs = 10
        const val sentMs = 11
        const val playing = 12
        const val shuffle = 13
        const val repeat = 14
        const val volume = 15
        const val maxVolume = 16
        const val battery = 17
        const val queueRev = 18
        const val slot = 19
        const val slots = 20
        const val libRev = 21
        const val album = 22
        const val sleepLeftS = 23
        const val charging = 24
        const val cardFreeMiB = 25
        const val cardTotalMiB = 26
        const val volumePct = 27
        const val volumeStepPct = 28
        const val lyrics = 29
        const val maxVolumePct = 30
        const val favorite = 31
        const val snapRev = 32
        const val lyricsPlain = 33
        const val lyricsCrc = 34
        const val positionHeld = 35
        const val eqSlot = 36
        const val card = 37
        const val lyricsOffsetMs = 38
        const val receiver = 39
        const val playLogSeq = 40
        const val phoneName = 41
        const val phoneArtCrc = 42
        const val phoneTrackNo = 43
        const val phoneTracks = 44
        const val phoneFile = 45
        const val buffering = 46
        const val aheadMs = 47
        const val imgHold = 48
        const val updHold = 49
        const val eqEarSlot = 50
    }
    object Schema {
        const val pivot = 1
        const val offset = 2
        const val total = 3
        const val more = 4
        const val row = 5
        const val rev = 6
        const val pivotIndex = 7
        const val pivotCount = 8
    }
    object Settings {
        const val rev = 1
        const val text = 2
    }
    object Eq {
        const val output = 1
        const val key = 2
        const val profile = 3
        const val ints = 4
        const val offset = 5
        const val total = 6
        const val more = 7
        const val slot = 8
        const val mode = 9
        const val graphicKey = 10
        const val graphicName = 11
        const val gains = 12
        const val graphicOffset = 13
        const val graphicTotal = 14
        const val on = 15
        const val target = 16
        const val headset = 17
    }
    object Queue {
        const val rev = 1
        const val offset = 2
        const val total = 3
        const val more = 4
        const val item = 5
    }
    object Lib {
        const val libRev = 1
        const val view = 2
        const val parent = 3
        const val offset = 4
        const val total = 5
        const val more = 6
        const val row = 7
        const val snapRev = 8
    }
    object Live {
        const val rev = 1
        const val text = 2
    }
    object Transport {
        const val cmd = 1
        const val seekMs = 2
        const val on = 3
        const val repeat = 4
    }
    object Volume {
        const val set = 1
        const val step = 2
        const val percent = 3
    }
    object Set {
        const val text = 1
    }
    object SetReply {
        const val rev = 1
        const val kept = 2
        const val refused = 3
    }
    object Draft {
        const val kind = 1
        const val `value` = 2
        const val role = 3
        const val minutes = 4
        const val unixS = 5
        const val zoneS = 6
        const val automatic = 7
    }
    object DraftReply {
        const val status = 1
    }
    object EqSet {
        const val output = 1
        const val key = 2
        const val ints = 3
        const val slot = 4
        const val mode = 5
        const val graphicKey = 6
        const val gains = 7
        const val on = 8
        const val target = 9
    }
    object Play {
        const val view = 1
        const val parent = 2
        const val libRev = 3
        const val start = 4
        const val shuffle = 5
        const val track = 6
        const val snapRev = 7
        const val phoneList = 8
    }
    object QueueOp {
        const val cmd = 1
        const val rev = 2
        const val offset = 3
        const val count = 4
        const val slot = 5
        const val to = 6
        const val track = 7
        const val view = 8
        const val parent = 9
        const val libRev = 10
        const val snapRev = 11
        const val phoneList = 12
    }
    object QueueOpReply {
        const val added = 1
        const val rev = 2
    }
    object LibGet {
        const val view = 1
        const val parent = 2
        const val offset = 3
        const val count = 4
        const val libRev = 5
        const val snapRev = 6
    }
    object Action {
        const val cmd = 1
        const val track = 2
    }
    object ArtGet {
        const val track = 1
        const val px = 2
        const val offset = 3
        const val have = 4
    }
    object ArtGetReply {
        const val crc = 1
        const val total = 2
        const val w = 3
        const val h = 4
        const val offset = 5
        const val `data` = 6
    }
    object LrcPut {
        const val track = 1
        const val libRev = 2
        const val text = 3
        const val crc = 4
        const val snapRev = 5
        const val phoneFile = 6
    }
    object LrcPutReply {
        const val lyrics = 1
        const val lyricsPlain = 2
    }
    object LrcDel {
        const val track = 1
        const val phoneFile = 2
    }
    object LrcDelReply {
        const val lyrics = 1
        const val lyricsPlain = 2
    }
    object UpdBegin {
        const val board = 1
        const val size = 2
        const val md5 = 3
        const val sig = 4
    }
    object UpdBeginReply {
        const val offset = 1
    }
    object UpdData {
        const val offset = 1
        const val `data` = 2
    }
    object UpdEnd {
        const val md5 = 1
    }
    object UpdAbort {
        const val offset = 1
        const val code = 2
    }
    object ImgBegin {
        const val kind = 1
        const val name = 2
        const val mbid = 3
        const val total = 4
        const val crc = 5
        const val w = 6
        const val h = 7
        const val wrongArtist = 8
        const val source = 9
        const val credit = 10
        const val origin = 11
        const val focusX = 12
        const val focusY = 13
        const val full = 14
    }
    object ImgBeginReply {
        const val offset = 1
        const val crc = 2
    }
    object ImgData {
        const val offset = 1
        const val `data` = 2
    }
    object ImgEnd {
        const val crc = 1
    }
    object ImgList {
        const val offset = 1
        const val count = 2
    }
    object ImgListReply {
        const val offset = 1
        const val total = 2
        const val more = 3
        const val entry = 4
        const val bytes = 5
        const val cap = 6
        const val srcRoom = 7
    }
    object ImgDel {
        const val name = 1
    }
    object ImgAbort {
        const val name = 1
        const val offset = 2
        const val code = 3
    }
    object EqPreset {
        const val op = 1
        const val output = 2
        const val key = 3
        const val name = 4
        const val ints = 5
        const val replace = 6
        const val slot = 7
        const val mode = 8
        const val gains = 9
        const val target = 10
    }
    object EqPresetReply {
        const val key = 1
    }
    object ArtBlock {
        const val cmd = 1
        const val name = 2
        const val mbid = 3
        const val crc = 4
    }
    object LyrGet {
        const val track = 1
        const val offset = 2
        const val have = 3
        const val phoneFile = 4
    }
    object LyrGetReply {
        const val crc = 1
        const val kind = 2
        const val total = 3
        const val offset = 4
        const val more = 5
        const val line = 6
        const val offsetMs = 7
    }
    object ArtPick {
        const val name = 1
        const val mbid = 2
    }
    object ArtPickReply {
        const val shown = 1
    }
    object ImgTag {
        const val name = 1
        const val origin = 2
    }
    object LyrOffset {
        const val track = 1
        const val ms = 2
    }
    object LyrOffsetReply {
        const val ms = 1
    }
    object PlaylogGet {
        const val after = 1
        const val count = 2
    }
    object PlaylogGetReply {
        const val log = 1
        const val newest = 2
        const val more = 3
        const val entry = 4
        const val first = 5
    }
    object PlaylogAck {
        const val log = 1
        const val upTo = 2
    }
    object StreamRead {
        const val file = 1
        const val rev = 2
        const val offset = 3
        const val len = 4
        const val landed = 5
    }
    object StreamReadReply {
        const val offset = 1
        const val size = 2
        const val `data` = 3
    }
    object StreamCancel {
        const val seq = 1
    }
    object PhoneList {
        const val list = 1
        const val offset = 2
        const val total = 3
        const val item = 4
    }
    object PhoneArt {
        const val art = 1
        const val px = 2
        const val offset = 3
        const val have = 4
    }
    object PhoneArtReply {
        const val crc = 1
        const val total = 2
        const val w = 3
        const val h = 4
        const val offset = 5
        const val `data` = 6
    }
    object Error {
        const val code = 1
        const val text = 2
        const val refused = 3
    }
    object Row {
        const val key = 1
        const val label = 2
        const val pivot = 3
        const val kind = 4
        const val `value` = 5
        const val min = 6
        const val max = 7
        const val step = 8
        const val word = 9
        const val flag = 10
    }
    object EqName {
        const val key = 1
        const val name = 2
        const val preset = 3
    }
    object Profile {
        const val preamp10 = 1
        const val band = 2
    }
    object Band {
        const val type = 1
        const val grid = 2
        const val milliHz = 3
        const val gain10 = 4
        const val q100 = 5
    }
    object QueueItem {
        const val track = 1
        const val title = 2
        const val artists = 3
        const val mbids = 4
        const val album = 5
        const val durationMs = 6
        const val lyrics = 7
        const val lyricsPlain = 8
        const val phoneFile = 9
    }
    object LibRow {
        const val name = 1
        const val count = 2
        const val first = 3
        const val key = 4
        const val mbid = 5
        const val artist = 6
    }
    object ImgEntry {
        const val name = 1
        const val mbid = 2
        const val origin = 3
        const val full = 4
        const val shownS = 5
        const val crc = 6
        const val srcCrc = 7
    }
    object LyrLine {
        const val ms = 1
        const val text = 2
    }
    object EqSlot {
        const val key = 1
        const val label = 2
    }
    object Graphic {
        const val preamp10 = 1
        const val band = 2
    }
    object GraphicBand {
        const val grid = 1
        const val gain10 = 2
    }
    object PlayEntry {
        const val seq = 1
        const val startLocalS = 2
        const val playedMs = 3
        const val durationMs = 4
        const val track = 5
        const val title = 6
        const val artist = 7
        const val artists = 8
        const val mbids = 9
        const val album = 10
        const val albumArtist = 11
        const val trackNo = 12
        const val zoneS = 13
        const val liveSeen = 14
        const val phoneFile = 15
    }
    object PhoneItem {
        const val file = 1
        const val rev = 2
        const val size = 3
        const val mime = 4
        const val durationMs = 5
        const val title = 6
        const val artists = 7
        const val album = 8
        const val albumArtist = 9
        const val trackNo = 10
        const val discNo = 11
        const val art = 12
        const val rate = 19
        const val bits = 20
    }
    val scopes: Map<String, Map<Int, Field>> = mapOf(
        "HELLO" to mapOf(
            1 to Field(1, "magic", Base.U32, null, false),
            2 to Field(2, "major", Base.U16, null, false),
            3 to Field(3, "minor", Base.U16, null, false),
            4 to Field(4, "appVersion", Base.STR, null, false),
            5 to Field(5, "features", Base.U64, null, false),
        ),
        "HELLO.reply" to mapOf(
            1 to Field(1, "magic", Base.U32, null, false),
            2 to Field(2, "major", Base.U16, null, false),
            3 to Field(3, "minor", Base.U16, null, false),
            4 to Field(4, "board", Base.STR, null, false),
            5 to Field(5, "version", Base.STR, null, false),
            6 to Field(6, "screenW", Base.U16, null, false),
            7 to Field(7, "screenH", Base.U16, null, false),
            8 to Field(8, "backdropW", Base.U16, null, false),
            9 to Field(9, "backdropH", Base.U16, null, false),
            10 to Field(10, "maxPayload", Base.U32, null, false),
            11 to Field(11, "idleMs", Base.U32, null, false),
            12 to Field(12, "busyMs", Base.U32, null, false),
            13 to Field(13, "features", Base.U64, null, false),
            14 to Field(14, "libRev", Base.U32, null, false),
            15 to Field(15, "settingsRev", Base.U32, null, false),
            16 to Field(16, "snapRev", Base.U32, null, false),
            17 to Field(17, "eqSlot", Base.RECORD, "EqSlot", true),
            18 to Field(18, "bandTypes", Base.ENUM, "BAND_TYPE", true),
            19 to Field(19, "phoneMime", Base.STR, null, true),
            20 to Field(20, "phoneMaxRate", Base.U32, null, false),
            21 to Field(21, "phoneMaxBits", Base.U8, null, false),
        ),
        "BYE" to mapOf(
            1 to Field(1, "reason", Base.ENUM, "BYE_REASON", false),
            2 to Field(2, "major", Base.U16, null, false),
            3 to Field(3, "minor", Base.U16, null, false),
        ),
        "STATE" to mapOf(
            1 to Field(1, "source", Base.ENUM, "SOURCE", false),
            2 to Field(2, "output", Base.ENUM, "OUTPUT", false),
            3 to Field(3, "track", Base.U64, null, false),
            4 to Field(4, "title", Base.STR, null, false),
            5 to Field(5, "artist", Base.STR, null, false),
            6 to Field(6, "artists", Base.STR, null, true),
            7 to Field(7, "mbids", Base.STR, null, true),
            8 to Field(8, "durationMs", Base.U32, null, false),
            9 to Field(9, "positionMs", Base.U32, null, false),
            10 to Field(10, "sampleMs", Base.U64, null, false),
            11 to Field(11, "sentMs", Base.U64, null, false),
            12 to Field(12, "playing", Base.BOOL, null, false),
            13 to Field(13, "shuffle", Base.BOOL, null, false),
            14 to Field(14, "repeat", Base.ENUM, "REPEAT", false),
            15 to Field(15, "volume", Base.U16, null, false),
            16 to Field(16, "maxVolume", Base.U16, null, false),
            17 to Field(17, "battery", Base.U8, null, false),
            18 to Field(18, "queueRev", Base.U32, null, false),
            19 to Field(19, "slot", Base.U32, null, false),
            20 to Field(20, "slots", Base.U32, null, false),
            21 to Field(21, "libRev", Base.U32, null, false),
            22 to Field(22, "album", Base.STR, null, false),
            23 to Field(23, "sleepLeftS", Base.U32, null, false),
            24 to Field(24, "charging", Base.BOOL, null, false),
            25 to Field(25, "cardFreeMiB", Base.U32, null, false),
            26 to Field(26, "cardTotalMiB", Base.U32, null, false),
            27 to Field(27, "volumePct", Base.U8, null, false),
            28 to Field(28, "volumeStepPct", Base.U8, null, false),
            29 to Field(29, "lyrics", Base.BOOL, null, false),
            30 to Field(30, "maxVolumePct", Base.U8, null, false),
            31 to Field(31, "favorite", Base.BOOL, null, false),
            32 to Field(32, "snapRev", Base.U32, null, false),
            33 to Field(33, "lyricsPlain", Base.BOOL, null, false),
            34 to Field(34, "lyricsCrc", Base.U32, null, false),
            35 to Field(35, "positionHeld", Base.BOOL, null, false),
            36 to Field(36, "eqSlot", Base.STR, null, false),
            37 to Field(37, "card", Base.ENUM, "CARD", false),
            38 to Field(38, "lyricsOffsetMs", Base.I32, null, false),
            39 to Field(39, "receiver", Base.BOOL, null, false),
            40 to Field(40, "playLogSeq", Base.U32, null, false),
            41 to Field(41, "phoneName", Base.STR, null, false),
            42 to Field(42, "phoneArtCrc", Base.U32, null, false),
            43 to Field(43, "phoneTrackNo", Base.U32, null, false),
            44 to Field(44, "phoneTracks", Base.U32, null, false),
            45 to Field(45, "phoneFile", Base.U64, null, false),
            46 to Field(46, "buffering", Base.BOOL, null, false),
            47 to Field(47, "aheadMs", Base.U32, null, false),
            48 to Field(48, "imgHold", Base.BOOL, null, false),
            49 to Field(49, "updHold", Base.BOOL, null, false),
            50 to Field(50, "eqEarSlot", Base.STR, null, false),
        ),
        "SCHEMA" to mapOf(
            1 to Field(1, "pivot", Base.STR, null, false),
            2 to Field(2, "offset", Base.U32, null, false),
            3 to Field(3, "total", Base.U32, null, false),
            4 to Field(4, "more", Base.BOOL, null, false),
            5 to Field(5, "row", Base.RECORD, "Row", true),
            6 to Field(6, "rev", Base.U32, null, false),
            7 to Field(7, "pivotIndex", Base.U8, null, false),
            8 to Field(8, "pivotCount", Base.U8, null, false),
        ),
        "SETTINGS" to mapOf(
            1 to Field(1, "rev", Base.U32, null, false),
            2 to Field(2, "text", Base.STR, null, false),
        ),
        "EQ" to mapOf(
            1 to Field(1, "output", Base.ENUM, "EQ_OUTPUT", false),
            2 to Field(2, "key", Base.STR, null, false),
            3 to Field(3, "profile", Base.RECORD, "EqName", true),
            4 to Field(4, "ints", Base.RECORD, "Profile", false),
            5 to Field(5, "offset", Base.U32, null, false),
            6 to Field(6, "total", Base.U32, null, false),
            7 to Field(7, "more", Base.BOOL, null, false),
            8 to Field(8, "slot", Base.STR, null, false),
            9 to Field(9, "mode", Base.ENUM, "EQ_MODE", false),
            10 to Field(10, "graphicKey", Base.STR, null, false),
            11 to Field(11, "graphicName", Base.RECORD, "EqName", true),
            12 to Field(12, "gains", Base.RECORD, "Graphic", false),
            13 to Field(13, "graphicOffset", Base.U32, null, false),
            14 to Field(14, "graphicTotal", Base.U32, null, false),
            15 to Field(15, "on", Base.BOOL, null, false),
            16 to Field(16, "target", Base.U32, null, false),
            17 to Field(17, "headset", Base.STR, null, false),
        ),
        "QUEUE" to mapOf(
            1 to Field(1, "rev", Base.U32, null, false),
            2 to Field(2, "offset", Base.U32, null, false),
            3 to Field(3, "total", Base.U32, null, false),
            4 to Field(4, "more", Base.BOOL, null, false),
            5 to Field(5, "item", Base.RECORD, "QueueItem", true),
        ),
        "LIB" to mapOf(
            1 to Field(1, "libRev", Base.U32, null, false),
            2 to Field(2, "view", Base.ENUM, "VIEW", false),
            3 to Field(3, "parent", Base.STR, null, false),
            4 to Field(4, "offset", Base.U32, null, false),
            5 to Field(5, "total", Base.U32, null, false),
            6 to Field(6, "more", Base.BOOL, null, false),
            7 to Field(7, "row", Base.RECORD, "LibRow", true),
            8 to Field(8, "snapRev", Base.U32, null, false),
        ),
        "LIVE" to mapOf(
            1 to Field(1, "rev", Base.U32, null, false),
            2 to Field(2, "text", Base.STR, null, false),
        ),
        "TRANSPORT" to mapOf(
            1 to Field(1, "cmd", Base.ENUM, "TRANSPORT_CMD", false),
            2 to Field(2, "seekMs", Base.U32, null, false),
            3 to Field(3, "on", Base.BOOL, null, false),
            4 to Field(4, "repeat", Base.ENUM, "REPEAT", false),
        ),
        "VOLUME" to mapOf(
            1 to Field(1, "set", Base.U16, null, false),
            2 to Field(2, "step", Base.I32, null, false),
            3 to Field(3, "percent", Base.U8, null, false),
        ),
        "SET" to mapOf(
            1 to Field(1, "text", Base.STR, null, false),
        ),
        "SET.reply" to mapOf(
            1 to Field(1, "rev", Base.U32, null, false),
            2 to Field(2, "kept", Base.STR, null, true),
            3 to Field(3, "refused", Base.STR, null, true),
        ),
        "DRAFT" to mapOf(
            1 to Field(1, "kind", Base.ENUM, "DRAFT_KIND", false),
            2 to Field(2, "value", Base.STR, null, false),
            3 to Field(3, "role", Base.ENUM, "BT_ROLE", false),
            4 to Field(4, "minutes", Base.U32, null, false),
            5 to Field(5, "unixS", Base.I64, null, false),
            6 to Field(6, "zoneS", Base.I32, null, false),
            7 to Field(7, "automatic", Base.BOOL, null, false),
        ),
        "DRAFT.reply" to mapOf(
            1 to Field(1, "status", Base.ENUM, "DRAFT_STATUS", false),
        ),
        "EQ_SET" to mapOf(
            1 to Field(1, "output", Base.ENUM, "EQ_OUTPUT", false),
            2 to Field(2, "key", Base.STR, null, false),
            3 to Field(3, "ints", Base.RECORD, "Profile", false),
            4 to Field(4, "slot", Base.STR, null, false),
            5 to Field(5, "mode", Base.ENUM, "EQ_MODE", false),
            6 to Field(6, "graphicKey", Base.STR, null, false),
            7 to Field(7, "gains", Base.RECORD, "Graphic", false),
            8 to Field(8, "on", Base.BOOL, null, false),
            9 to Field(9, "target", Base.U32, null, false),
        ),
        "PLAY" to mapOf(
            1 to Field(1, "view", Base.ENUM, "VIEW", false),
            2 to Field(2, "parent", Base.STR, null, false),
            3 to Field(3, "libRev", Base.U32, null, false),
            4 to Field(4, "start", Base.U32, null, false),
            5 to Field(5, "shuffle", Base.BOOL, null, false),
            6 to Field(6, "track", Base.U64, null, true),
            7 to Field(7, "snapRev", Base.U32, null, false),
            8 to Field(8, "phoneList", Base.U32, null, false),
        ),
        "QUEUE_OP" to mapOf(
            1 to Field(1, "cmd", Base.ENUM, "QUEUE_CMD", false),
            2 to Field(2, "rev", Base.U32, null, false),
            3 to Field(3, "offset", Base.U32, null, false),
            4 to Field(4, "count", Base.U32, null, false),
            5 to Field(5, "slot", Base.U32, null, false),
            6 to Field(6, "to", Base.U32, null, false),
            7 to Field(7, "track", Base.U64, null, true),
            8 to Field(8, "view", Base.ENUM, "VIEW", false),
            9 to Field(9, "parent", Base.STR, null, false),
            10 to Field(10, "libRev", Base.U32, null, false),
            11 to Field(11, "snapRev", Base.U32, null, false),
            12 to Field(12, "phoneList", Base.U32, null, false),
        ),
        "QUEUE_OP.reply" to mapOf(
            1 to Field(1, "added", Base.U32, null, false),
            2 to Field(2, "rev", Base.U32, null, false),
        ),
        "LIB_GET" to mapOf(
            1 to Field(1, "view", Base.ENUM, "VIEW", false),
            2 to Field(2, "parent", Base.STR, null, false),
            3 to Field(3, "offset", Base.U32, null, false),
            4 to Field(4, "count", Base.U32, null, false),
            5 to Field(5, "libRev", Base.U32, null, false),
            6 to Field(6, "snapRev", Base.U32, null, false),
        ),
        "ACTION" to mapOf(
            1 to Field(1, "cmd", Base.ENUM, "ACTION_CMD", false),
            2 to Field(2, "track", Base.U64, null, false),
        ),
        "ART_GET" to mapOf(
            1 to Field(1, "track", Base.U64, null, false),
            2 to Field(2, "px", Base.U16, null, false),
            3 to Field(3, "offset", Base.U32, null, false),
            4 to Field(4, "have", Base.U32, null, false),
        ),
        "ART_GET.reply" to mapOf(
            1 to Field(1, "crc", Base.U32, null, false),
            2 to Field(2, "total", Base.U32, null, false),
            3 to Field(3, "w", Base.U16, null, false),
            4 to Field(4, "h", Base.U16, null, false),
            5 to Field(5, "offset", Base.U32, null, false),
            6 to Field(6, "data", Base.BYTES, null, false),
        ),
        "LRC_PUT" to mapOf(
            1 to Field(1, "track", Base.U64, null, false),
            2 to Field(2, "libRev", Base.U32, null, false),
            3 to Field(3, "text", Base.BYTES, null, false),
            4 to Field(4, "crc", Base.U32, null, false),
            5 to Field(5, "snapRev", Base.U32, null, false),
            6 to Field(6, "phoneFile", Base.U64, null, false),
        ),
        "LRC_PUT.reply" to mapOf(
            1 to Field(1, "lyrics", Base.BOOL, null, false),
            2 to Field(2, "lyricsPlain", Base.BOOL, null, false),
        ),
        "LRC_DEL" to mapOf(
            1 to Field(1, "track", Base.U64, null, false),
            2 to Field(2, "phoneFile", Base.U64, null, false),
        ),
        "LRC_DEL.reply" to mapOf(
            1 to Field(1, "lyrics", Base.BOOL, null, false),
            2 to Field(2, "lyricsPlain", Base.BOOL, null, false),
        ),
        "UPD_BEGIN" to mapOf(
            1 to Field(1, "board", Base.STR, null, false),
            2 to Field(2, "size", Base.U32, null, false),
            3 to Field(3, "md5", Base.STR, null, false),
            4 to Field(4, "sig", Base.BYTES, null, false),
        ),
        "UPD_BEGIN.reply" to mapOf(
            1 to Field(1, "offset", Base.U32, null, false),
        ),
        "UPD_DATA" to mapOf(
            1 to Field(1, "offset", Base.U32, null, false),
            2 to Field(2, "data", Base.BYTES, null, false),
        ),
        "UPD_END" to mapOf(
            1 to Field(1, "md5", Base.STR, null, false),
        ),
        "UPD_ABORT" to mapOf(
            1 to Field(1, "offset", Base.U32, null, false),
            2 to Field(2, "code", Base.ENUM, "ABORT_CODE", false),
        ),
        "IMG_BEGIN" to mapOf(
            1 to Field(1, "kind", Base.ENUM, "IMG_KIND", false),
            2 to Field(2, "name", Base.STR, null, false),
            3 to Field(3, "mbid", Base.STR, null, false),
            4 to Field(4, "total", Base.U32, null, false),
            5 to Field(5, "crc", Base.U32, null, false),
            6 to Field(6, "w", Base.U16, null, false),
            7 to Field(7, "h", Base.U16, null, false),
            8 to Field(8, "wrongArtist", Base.BOOL, null, false),
            9 to Field(9, "source", Base.STR, null, false),
            10 to Field(10, "credit", Base.STR, null, false),
            11 to Field(11, "origin", Base.ENUM, "IMG_ORIGIN", false),
            12 to Field(12, "focusX", Base.U16, null, false),
            13 to Field(13, "focusY", Base.U16, null, false),
            14 to Field(14, "full", Base.BOOL, null, false),
        ),
        "IMG_BEGIN.reply" to mapOf(
            1 to Field(1, "offset", Base.U32, null, false),
            2 to Field(2, "crc", Base.U32, null, false),
        ),
        "IMG_DATA" to mapOf(
            1 to Field(1, "offset", Base.U32, null, false),
            2 to Field(2, "data", Base.BYTES, null, false),
        ),
        "IMG_END" to mapOf(
            1 to Field(1, "crc", Base.U32, null, false),
        ),
        "IMG_LIST" to mapOf(
            1 to Field(1, "offset", Base.U32, null, false),
            2 to Field(2, "count", Base.U32, null, false),
        ),
        "IMG_LIST.reply" to mapOf(
            1 to Field(1, "offset", Base.U32, null, false),
            2 to Field(2, "total", Base.U32, null, false),
            3 to Field(3, "more", Base.BOOL, null, false),
            4 to Field(4, "entry", Base.RECORD, "ImgEntry", true),
            5 to Field(5, "bytes", Base.U64, null, false),
            6 to Field(6, "cap", Base.U64, null, false),
            7 to Field(7, "srcRoom", Base.U64, null, false),
        ),
        "IMG_DEL" to mapOf(
            1 to Field(1, "name", Base.STR, null, false),
        ),
        "IMG_ABORT" to mapOf(
            1 to Field(1, "name", Base.STR, null, false),
            2 to Field(2, "offset", Base.U32, null, false),
            3 to Field(3, "code", Base.ENUM, "ABORT_CODE", false),
        ),
        "EQ_PRESET" to mapOf(
            1 to Field(1, "op", Base.ENUM, "EQ_PRESET_OP", false),
            2 to Field(2, "output", Base.ENUM, "EQ_OUTPUT", false),
            3 to Field(3, "key", Base.STR, null, false),
            4 to Field(4, "name", Base.STR, null, false),
            5 to Field(5, "ints", Base.RECORD, "Profile", false),
            6 to Field(6, "replace", Base.BOOL, null, false),
            7 to Field(7, "slot", Base.STR, null, false),
            8 to Field(8, "mode", Base.ENUM, "EQ_MODE", false),
            9 to Field(9, "gains", Base.RECORD, "Graphic", false),
            10 to Field(10, "target", Base.U32, null, false),
        ),
        "EQ_PRESET.reply" to mapOf(
            1 to Field(1, "key", Base.STR, null, false),
        ),
        "ART_BLOCK" to mapOf(
            1 to Field(1, "cmd", Base.ENUM, "BLOCK_CMD", false),
            2 to Field(2, "name", Base.STR, null, false),
            3 to Field(3, "mbid", Base.STR, null, false),
            4 to Field(4, "crc", Base.U32, null, false),
        ),
        "LYR_GET" to mapOf(
            1 to Field(1, "track", Base.U64, null, false),
            2 to Field(2, "offset", Base.U32, null, false),
            3 to Field(3, "have", Base.U32, null, false),
            4 to Field(4, "phoneFile", Base.U64, null, false),
        ),
        "LYR_GET.reply" to mapOf(
            1 to Field(1, "crc", Base.U32, null, false),
            2 to Field(2, "kind", Base.ENUM, "LYRICS_KIND", false),
            3 to Field(3, "total", Base.U32, null, false),
            4 to Field(4, "offset", Base.U32, null, false),
            5 to Field(5, "more", Base.BOOL, null, false),
            6 to Field(6, "line", Base.RECORD, "LyrLine", true),
            7 to Field(7, "offsetMs", Base.I32, null, false),
        ),
        "ART_PICK" to mapOf(
            1 to Field(1, "name", Base.STR, null, false),
            2 to Field(2, "mbid", Base.STR, null, false),
        ),
        "ART_PICK.reply" to mapOf(
            1 to Field(1, "shown", Base.BOOL, null, false),
        ),
        "IMG_TAG" to mapOf(
            1 to Field(1, "name", Base.STR, null, false),
            2 to Field(2, "origin", Base.ENUM, "IMG_ORIGIN", false),
        ),
        "LYR_OFFSET" to mapOf(
            1 to Field(1, "track", Base.U64, null, false),
            2 to Field(2, "ms", Base.I32, null, false),
        ),
        "LYR_OFFSET.reply" to mapOf(
            1 to Field(1, "ms", Base.I32, null, false),
        ),
        "PLAYLOG_GET" to mapOf(
            1 to Field(1, "after", Base.U32, null, false),
            2 to Field(2, "count", Base.U32, null, false),
        ),
        "PLAYLOG_GET.reply" to mapOf(
            1 to Field(1, "log", Base.U64, null, false),
            2 to Field(2, "newest", Base.U32, null, false),
            3 to Field(3, "more", Base.BOOL, null, false),
            4 to Field(4, "entry", Base.RECORD, "PlayEntry", true),
            5 to Field(5, "first", Base.U32, null, false),
        ),
        "PLAYLOG_ACK" to mapOf(
            1 to Field(1, "log", Base.U64, null, false),
            2 to Field(2, "upTo", Base.U32, null, false),
        ),
        "STREAM_READ" to mapOf(
            1 to Field(1, "file", Base.U64, null, false),
            2 to Field(2, "rev", Base.U64, null, false),
            3 to Field(3, "offset", Base.U64, null, false),
            4 to Field(4, "len", Base.U32, null, false),
            5 to Field(5, "landed", Base.U64, null, false),
        ),
        "STREAM_READ.reply" to mapOf(
            1 to Field(1, "offset", Base.U64, null, false),
            2 to Field(2, "size", Base.U64, null, false),
            3 to Field(3, "data", Base.BYTES, null, false),
        ),
        "STREAM_CANCEL" to mapOf(
            1 to Field(1, "seq", Base.U16, null, true),
        ),
        "PHONE_LIST" to mapOf(
            1 to Field(1, "list", Base.U32, null, false),
            2 to Field(2, "offset", Base.U32, null, false),
            3 to Field(3, "total", Base.U32, null, false),
            4 to Field(4, "item", Base.RECORD, "PhoneItem", true),
        ),
        "PHONE_ART" to mapOf(
            1 to Field(1, "art", Base.STR, null, false),
            2 to Field(2, "px", Base.U16, null, false),
            3 to Field(3, "offset", Base.U32, null, false),
            4 to Field(4, "have", Base.U32, null, false),
        ),
        "PHONE_ART.reply" to mapOf(
            1 to Field(1, "crc", Base.U32, null, false),
            2 to Field(2, "total", Base.U32, null, false),
            3 to Field(3, "w", Base.U16, null, false),
            4 to Field(4, "h", Base.U16, null, false),
            5 to Field(5, "offset", Base.U32, null, false),
            6 to Field(6, "data", Base.BYTES, null, false),
        ),
        "error" to mapOf(
            1 to Field(1, "code", Base.U8, null, false),
            2 to Field(2, "text", Base.STR, null, false),
            3 to Field(3, "refused", Base.STR, null, true),
        ),
        "Row" to mapOf(
            1 to Field(1, "key", Base.STR, null, false),
            2 to Field(2, "label", Base.STR, null, false),
            3 to Field(3, "pivot", Base.STR, null, false),
            4 to Field(4, "kind", Base.ENUM, "ROW_KIND", false),
            5 to Field(5, "value", Base.STR, null, true),
            6 to Field(6, "min", Base.I32, null, false),
            7 to Field(7, "max", Base.I32, null, false),
            8 to Field(8, "step", Base.I32, null, false),
            9 to Field(9, "word", Base.STR, null, true),
            10 to Field(10, "flag", Base.ENUM, "ROW_FLAG", true),
        ),
        "EqName" to mapOf(
            1 to Field(1, "key", Base.STR, null, false),
            2 to Field(2, "name", Base.STR, null, false),
            3 to Field(3, "preset", Base.BOOL, null, false),
        ),
        "Profile" to mapOf(
            1 to Field(1, "preamp10", Base.I32, null, false),
            2 to Field(2, "band", Base.RECORD, "Band", true),
        ),
        "Band" to mapOf(
            1 to Field(1, "type", Base.ENUM, "BAND_TYPE", false),
            2 to Field(2, "grid", Base.I32, null, false),
            3 to Field(3, "milliHz", Base.I32, null, false),
            4 to Field(4, "gain10", Base.I32, null, false),
            5 to Field(5, "q100", Base.I32, null, false),
        ),
        "QueueItem" to mapOf(
            1 to Field(1, "track", Base.U64, null, false),
            2 to Field(2, "title", Base.STR, null, false),
            3 to Field(3, "artists", Base.STR, null, true),
            4 to Field(4, "mbids", Base.STR, null, true),
            5 to Field(5, "album", Base.STR, null, false),
            6 to Field(6, "durationMs", Base.U32, null, false),
            7 to Field(7, "lyrics", Base.BOOL, null, false),
            8 to Field(8, "lyricsPlain", Base.BOOL, null, false),
            9 to Field(9, "phoneFile", Base.U64, null, false),
        ),
        "LibRow" to mapOf(
            1 to Field(1, "name", Base.STR, null, false),
            2 to Field(2, "count", Base.U32, null, false),
            3 to Field(3, "first", Base.U64, null, false),
            4 to Field(4, "key", Base.STR, null, false),
            5 to Field(5, "mbid", Base.STR, null, false),
            6 to Field(6, "artist", Base.STR, null, false),
        ),
        "ImgEntry" to mapOf(
            1 to Field(1, "name", Base.STR, null, false),
            2 to Field(2, "mbid", Base.STR, null, false),
            3 to Field(3, "origin", Base.ENUM, "IMG_ORIGIN", true),
            4 to Field(4, "full", Base.BOOL, null, false),
            5 to Field(5, "shownS", Base.U32, null, false),
            6 to Field(6, "crc", Base.U32, null, false),
            7 to Field(7, "srcCrc", Base.U32, null, false),
        ),
        "LyrLine" to mapOf(
            1 to Field(1, "ms", Base.U32, null, false),
            2 to Field(2, "text", Base.STR, null, false),
        ),
        "EqSlot" to mapOf(
            1 to Field(1, "key", Base.STR, null, false),
            2 to Field(2, "label", Base.STR, null, false),
        ),
        "Graphic" to mapOf(
            1 to Field(1, "preamp10", Base.I32, null, false),
            2 to Field(2, "band", Base.RECORD, "GraphicBand", true),
        ),
        "GraphicBand" to mapOf(
            1 to Field(1, "grid", Base.I32, null, false),
            2 to Field(2, "gain10", Base.I32, null, false),
        ),
        "PlayEntry" to mapOf(
            1 to Field(1, "seq", Base.U32, null, false),
            2 to Field(2, "startLocalS", Base.I64, null, false),
            3 to Field(3, "playedMs", Base.U32, null, false),
            4 to Field(4, "durationMs", Base.U32, null, false),
            5 to Field(5, "track", Base.U64, null, false),
            6 to Field(6, "title", Base.STR, null, false),
            7 to Field(7, "artist", Base.STR, null, false),
            8 to Field(8, "artists", Base.STR, null, true),
            9 to Field(9, "mbids", Base.STR, null, true),
            10 to Field(10, "album", Base.STR, null, false),
            11 to Field(11, "albumArtist", Base.STR, null, false),
            12 to Field(12, "trackNo", Base.U16, null, false),
            13 to Field(13, "zoneS", Base.I32, null, false),
            14 to Field(14, "liveSeen", Base.BOOL, null, false),
            15 to Field(15, "phoneFile", Base.U64, null, false),
        ),
        "PhoneItem" to mapOf(
            1 to Field(1, "file", Base.U64, null, false),
            2 to Field(2, "rev", Base.U64, null, false),
            3 to Field(3, "size", Base.U64, null, false),
            4 to Field(4, "mime", Base.STR, null, false),
            5 to Field(5, "durationMs", Base.U32, null, false),
            6 to Field(6, "title", Base.STR, null, false),
            7 to Field(7, "artists", Base.STR, null, true),
            8 to Field(8, "album", Base.STR, null, false),
            9 to Field(9, "albumArtist", Base.STR, null, false),
            10 to Field(10, "trackNo", Base.U16, null, false),
            11 to Field(11, "discNo", Base.U16, null, false),
            12 to Field(12, "art", Base.STR, null, false),
            19 to Field(19, "rate", Base.U32, null, false),
            20 to Field(20, "bits", Base.U8, null, false),
        ),
    )
}
