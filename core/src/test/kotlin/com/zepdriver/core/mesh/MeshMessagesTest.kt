package com.zepdriver.core.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round trips for every hand-written message, plus wire bytes cross-checked against an independent encoder. */
class MeshMessagesTest {
    private val position = Position(
        latitudeI = 407864000, longitudeI = -1192065000, altitude = -12, time = 1788000000, locationSource = 3,
        timestamp = 1788000001, altitudeHae = -30, groundSpeed = 8, groundTrack = 9_000_000, satsInView = 7,
        seqNumber = 42, precisionBits = 32,
    )

    private fun assertPosition(p: Position) {
        assertEquals(407864000, p.latitudeI)
        assertEquals(-1192065000, p.longitudeI)
        assertEquals(-12, p.altitude)
        assertEquals(1788000000L, p.time)
        assertEquals(3, p.locationSource)
        assertEquals(1788000001L, p.timestamp)
        assertEquals(-30, p.altitudeHae)
        assertEquals(8L, p.groundSpeed)
        assertEquals(9_000_000L, p.groundTrack)
        assertEquals(7, p.satsInView)
        assertEquals(42L, p.seqNumber)
        assertEquals(32, p.precisionBits)
        assertEquals(40.7864, p.latitude!!, 1e-9)
    }

    @Test
    fun positionRoundTrip() {
        val bytes = position.encode()
        assertPosition(Position.decode(bytes))
        assertContentEquals(bytes, Position.decode(bytes).encode())
        // Explicit-presence fields: zero lat is still sent, absent stays absent.
        val zero = Position.decode(Position(latitudeI = 0, longitudeI = 0).encode())
        assertEquals(0, zero.latitudeI)
        assertNull(zero.groundSpeed)
        assertNull(Position.decode(ByteArray(0)).latitudeI)
    }

    @Test
    fun positionWireBytes() {
        // Produced by an independent (Python) wire-format encoder.
        val expected = hex("0d c0 82 4f 18 15 18 88 f2 b8 25 00 b7 92 6a 78 08 80 01 c0 a8 a5 04 b8 01 20")
        val p = Position(latitudeI = 407864000, longitudeI = -1192065000, time = 1788000000, groundSpeed = 8, groundTrack = 9_000_000, precisionBits = 32)
        assertEquals(expected.toHex(), p.encode().toHex())
    }

    @Test
    fun userRoundTrip() {
        val u = User("!a1b2c3d4", "Disco Zebra ❤", "DZ", User.HW_MODEL_HELTEC_V3, isLicensed = false, role = 0, publicKey = ByteArray(32) { it.toByte() })
        val d = User.decode(u.encode())
        assertEquals("!a1b2c3d4", d.id)
        assertEquals("Disco Zebra ❤", d.longName)
        assertEquals("DZ", d.shortName)
        assertEquals(43, d.hwModel)
        assertContentEquals(u.publicKey, d.publicKey)
        assertContentEquals(u.encode(), d.encode())
    }

    @Test
    fun dataAndPacketRoundTrip() {
        val data = MeshData(PortNum.TEXT_MESSAGE_APP, "hello".toByteArray(), wantResponse = true, dest = 1, source = 2, requestId = 3, replyId = 0xFFFFFFFFL, emoji = 1)
        val pkt = MeshPacket(
            from = 0xDEADBEEFL, to = NodeNum.BROADCAST, channel = 2, decoded = data, id = 0x80000001L, rxTime = 1788000000,
            rxSnr = -7.25f, hopLimit = 3, wantAck = true, priority = MeshPacket.PRIORITY_RELIABLE, rxRssi = -110, viaMqtt = true, hopStart = 3,
        )
        val d = MeshPacket.decode(pkt.encode())
        assertEquals(0xDEADBEEFL, d.from)
        assertEquals(0xFFFFFFFFL, d.to)
        assertEquals(2, d.channel)
        assertEquals(0x80000001L, d.id)
        assertEquals(1788000000L, d.rxTime)
        assertEquals(-7.25f, d.rxSnr)
        assertEquals(3, d.hopLimit)
        assertTrue(d.wantAck)
        assertEquals(70, d.priority)
        assertEquals(-110, d.rxRssi)
        assertTrue(d.viaMqtt)
        assertEquals(3, d.hopStart)
        val dd = assertNotNull(d.decoded)
        assertEquals(PortNum.TEXT_MESSAGE_APP, dd.portnum)
        assertEquals("hello", String(dd.payload))
        assertTrue(dd.wantResponse)
        assertEquals(0xFFFFFFFFL, dd.replyId)
        assertContentEquals(pkt.encode(), d.encode())

        val enc = MeshPacket.decode(MeshPacket(from = 5, encrypted = byteArrayOf(9, 9)).encode())
        assertNull(enc.decoded)
        assertContentEquals(byteArrayOf(9, 9), enc.encrypted)
    }

    @Test
    fun nodeInfoAndMyInfoRoundTrip() {
        val n = NodeInfo(0x12345678, User(longName = "Zep"), position, snr = 5.5f, lastHeard = 1788000000, channel = 0, viaMqtt = false, hopsAway = 0)
        val d = NodeInfo.decode(n.encode())
        assertEquals(0x12345678L, d.num)
        assertEquals("Zep", d.user!!.longName)
        assertPosition(d.position!!)
        assertEquals(5.5f, d.snr)
        assertEquals(1788000000L, d.lastHeard)
        assertEquals(0, d.hopsAway) // explicit presence survives a zero
        assertContentEquals(n.encode(), d.encode())

        val my = MyNodeInfo.decode(MyNodeInfo(0xA1B2C3D4L, rebootCount = 4, minAppVersion = 30200, pioEnv = "heltec-v3").encode())
        assertEquals(0xA1B2C3D4L, my.myNodeNum)
        assertEquals(4L, my.rebootCount)
        assertEquals(30200L, my.minAppVersion)
        assertEquals("heltec-v3", my.pioEnv)
    }

    @Test
    fun toRadioRoundTrip() {
        val want = ToRadio.decode(ToRadio.WantConfig(0xFEEDBEEFL).encode())
        assertEquals(ToRadio.WantConfig(0xFEEDBEEFL), want)
        assertEquals("18 01", ToRadio.WantConfig(1).encode().toHex())
        assertEquals(ToRadio.Disconnect, ToRadio.decode(ToRadio.Disconnect.encode()))
        assertEquals("20 01", ToRadio.Disconnect.encode().toHex())
        assertEquals(ToRadio.Heartbeat(7), ToRadio.decode(ToRadio.Heartbeat(7).encode()))
        assertEquals("3a 00", ToRadio.Heartbeat().encode().toHex()) // empty heartbeat still sets the oneof
        val pkt = ToRadio.decode(ToRadio.Packet(MeshPacket(to = 9, decoded = MeshData(PortNum.POSITION_APP, position.encode()))).encode())
        assertIs<ToRadio.Packet>(pkt)
        assertEquals(9L, pkt.packet.to)
        assertPosition(Position.decode(pkt.packet.decoded!!.payload))
    }

    @Test
    fun fromRadioRoundTrip() {
        val variants = listOf(
            FromRadio.Packet(1, MeshPacket(from = 3, decoded = MeshData(PortNum.TEXT_MESSAGE_APP, "x".toByteArray()))),
            FromRadio.MyInfo(2, MyNodeInfo(77)),
            FromRadio.Node(3, NodeInfo(5, User(longName = "A"))),
            FromRadio.ConfigMsg(4, Config(LoRaConfig(usePreset = true, region = 1))),
            FromRadio.LogRecord(5, "log line"),
            FromRadio.ConfigCompleteId(6, 0xCAFEF00DL),
            FromRadio.Rebooted(7),
            FromRadio.ChannelMsg(8, Channel(0, ChannelSettings(name = "ZepDriver"), Channel.ROLE_PRIMARY)),
            FromRadio.Metadata(9, DeviceMetadata("2.7.26.54e0d8d", 43)),
            FromRadio.ClientNotification(10, "hello"),
        )
        for (v in variants) {
            val d = FromRadio.decode(FromRadio.encode(v))
            assertEquals(v::class, d::class)
            assertEquals(v.id, d.id)
            assertContentEquals(FromRadio.encode(v), FromRadio.encode(d), "re-encode ${v::class.simpleName}")
        }
        val cc = FromRadio.decode(FromRadio.encode(FromRadio.ConfigCompleteId(0, 0xCAFEF00DL)))
        assertEquals(0xCAFEF00DL, (cc as FromRadio.ConfigCompleteId).nonce)
        // Unused variants (e.g. queueStatus = 11) decode to Other.
        val other = FromRadio.decode(hex("08 01 5a 02 08 01"))
        assertIs<FromRadio.Other>(other)
        assertEquals(11, other.field)
    }

    @Test
    fun channelRoundTrip() {
        val c = Channel(3, ChannelSettings(ZepMesh.PSK, "ZepDriver", id = 0x1234, uplinkEnabled = true, downlinkEnabled = true, moduleSettings = ModuleSettings(32, isMuted = true)), Channel.ROLE_SECONDARY)
        val d = Channel.decode(c.encode())
        assertEquals(3, d.index)
        assertEquals(Channel.ROLE_SECONDARY, d.role)
        val s = d.settings!!
        assertContentEquals(ZepMesh.PSK, s.psk)
        assertEquals("ZepDriver", s.name)
        assertEquals(0x1234L, s.id)
        assertTrue(s.uplinkEnabled && s.downlinkEnabled)
        assertEquals(32, s.moduleSettings!!.positionPrecision)
        assertTrue(s.moduleSettings!!.isMuted)
        assertContentEquals(c.encode(), d.encode())
    }

    @Test
    fun configRoundTripsKeepUnknownFields() {
        // LoRa with an unmodelled field (ignore_incoming = 103, repeated) and fem_lna_mode = 106.
        val extra = ProtoWriter().varintAlways(103, 0x1111).varintAlways(103, 0x2222).varintAlways(106, 2).toByteArray()
        val lora = LoRaConfig(
            usePreset = true, modemPreset = 4, bandwidth = 250, spreadFactor = 9, codingRate = 5, frequencyOffset = 0.5f, region = 1,
            hopLimit = 3, txEnabled = true, txPower = -2, channelNum = 20, overrideDutyCycle = true, sx126xRxBoostedGain = true,
            overrideFrequency = 915.5f, unknown = extra,
        )
        val l = Config.decode(Config(lora).encode()).lora!!
        assertTrue(l.usePreset)
        assertEquals(4, l.modemPreset)
        assertEquals(250L, l.bandwidth)
        assertEquals(9L, l.spreadFactor)
        assertEquals(5L, l.codingRate)
        assertEquals(0.5f, l.frequencyOffset)
        assertEquals(1, l.region)
        assertEquals(3, l.hopLimit)
        assertTrue(l.txEnabled)
        assertEquals(-2, l.txPower)
        assertEquals(20L, l.channelNum)
        assertTrue(l.overrideDutyCycle && l.sx126xRxBoostedGain)
        assertEquals(915.5f, l.overrideFrequency)
        assertEquals(listOf(0x1111L, 0x2222L), ProtoMessage.parse(l.encode()).repeatedUint32(103))
        assertContentEquals(extra, l.unknown)

        val bt = Config.decode(Config(BluetoothConfig(true, BluetoothConfig.MODE_FIXED_PIN, 123456)).encode()).bluetooth!!
        assertTrue(bt.enabled)
        assertEquals(BluetoothConfig.MODE_FIXED_PIN, bt.mode)
        assertEquals(123456L, bt.fixedPin)

        val pos = Config.decode(Config(PositionConfig(86400, true, true, 30, 0x81, 100, 30, PositionConfig.GPS_NOT_PRESENT)).encode()).position!!
        assertEquals(86400L, pos.positionBroadcastSecs)
        assertTrue(pos.positionBroadcastSmartEnabled && pos.fixedPosition)
        assertEquals(30L, pos.gpsUpdateInterval)
        assertEquals(0x81L, pos.positionFlags)
        assertEquals(100L, pos.broadcastSmartMinimumDistance)
        assertEquals(30L, pos.broadcastSmartMinimumIntervalSecs)
        assertEquals(PositionConfig.GPS_NOT_PRESENT, pos.gpsMode)

        val dev = Config.decode(Config(DeviceConfig(DeviceConfig.ROLE_CLIENT_MUTE, 900)).encode()).device!!
        assertEquals(DeviceConfig.ROLE_CLIENT_MUTE, dev.role)
        assertEquals(900L, dev.nodeInfoBroadcastSecs)

        // A section we don't model (display = 5) survives as raw bytes.
        val display = Config.decode(hex("2a 02 08 3c"))
        assertEquals(5, display.otherField)
        assertEquals("2a 02 08 3c", display.encode().toHex())
    }

    @Test
    fun adminRoundTrip() {
        val pk = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val msgs: List<AdminMessage> = listOf(
            AdminMessage.BeginEditSettings(),
            AdminMessage.CommitEditSettings(pk),
            AdminMessage.SetOwner(User(longName = "Disco Zebra", shortName = "DZ")),
            AdminMessage.SetChannel(Channel(0, ChannelSettings(name = "ZepDriver"), Channel.ROLE_PRIMARY)),
            AdminMessage.SetConfig(Config(BluetoothConfig(true, 1, 123456))),
            AdminMessage.GetChannelRequest(0),
            AdminMessage.GetOwnerRequest(),
            AdminMessage.GetConfigRequest(Config.TYPE_LORA),
            AdminMessage.RebootSeconds(5),
        )
        for (m in msgs) {
            val d = AdminMessage.decode(m.encode())
            assertEquals(m::class, d::class)
            assertContentEquals(m.encode(), d.encode())
        }
        assertContentEquals(pk, AdminMessage.decode(AdminMessage.CommitEditSettings(pk).encode()).sessionPasskey)
        // Field numbers: begin_edit_settings = 64, commit = 65 (2-byte tags), get_channel_request is 1-based.
        assertEquals("80 04 01", AdminMessage.BeginEditSettings().encode().toHex())
        assertEquals("88 04 01", AdminMessage.CommitEditSettings().encode().toHex())
        assertEquals("08 01", AdminMessage.GetChannelRequest(0).encode().toHex())
        assertEquals(0, (AdminMessage.decode(hex("08 01")) as AdminMessage.GetChannelRequest).index)
    }
}
