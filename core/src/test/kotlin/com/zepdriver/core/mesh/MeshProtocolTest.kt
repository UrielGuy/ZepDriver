package com.zepdriver.core.mesh

import com.zepdriver.core.geo.Fix
import com.zepdriver.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MeshProtocolTest {
    private val now = 1_788_000_100_000L
    private val myNum = 0xA1B2C3D4L

    private fun fromRadio(v: FromRadio) = FromRadio.encode(v)

    /** Feeds a complete config dump; returns the events. */
    private fun dump(
        p: MeshProtocol,
        ownerName: String = "Disco Zebra",
        channelName: String = ZepMesh.CHANNEL_NAME,
        psk: ByteArray = ZepMesh.PSK,
        lora: LoRaConfig = LoRaConfig(usePreset = true, modemPreset = ZepMesh.MODEM_PRESET, region = ZepMesh.REGION, hopLimit = 3, txEnabled = true),
        extraChannels: List<Channel> = emptyList(),
    ): List<MeshEvent> {
        val nonce = ToRadio.decode(p.wantConfig()) as ToRadio.WantConfig
        val msgs = listOf(
            FromRadio.MyInfo(1, MyNodeInfo(myNum)),
            FromRadio.Metadata(2, DeviceMetadata("2.7.26.54e0d8d", 43)),
            FromRadio.Node(3, NodeInfo(myNum, User("!a1b2c3d4", ownerName, "DZ"))),
            FromRadio.Node(4, NodeInfo(0x11, User("!00000011", "Mutant Snail", "SNL"), Position(latitudeI = 407800000, longitudeI = -1192000000, time = 1788000000), lastHeard = 1788000050)),
            FromRadio.ConfigMsg(5, Config(lora)),
            FromRadio.ConfigMsg(6, Config(BluetoothConfig(true, BluetoothConfig.MODE_FIXED_PIN, ZepMesh.BLE_PIN.toLong()))),
            FromRadio.ChannelMsg(7, Channel(0, ChannelSettings(psk, channelName, moduleSettings = ModuleSettings(32)), Channel.ROLE_PRIMARY)),
        ) + extraChannels.mapIndexed { i, c -> FromRadio.ChannelMsg(8L + i, c) } +
            listOf(FromRadio.ConfigCompleteId(20, nonce.nonce))
        return msgs.flatMap { p.handleFromRadio(fromRadio(it)) }
    }

    @Test
    fun configDumpForProvisionedRadio() {
        val p = MeshProtocol { now }
        val events = dump(p)
        assertEquals(MeshEvent.MyInfo(myNum), events[0])
        assertEquals(myNum, p.myNodeNum)
        assertEquals("2.7.26.54e0d8d", p.firmwareVersion)
        assertTrue(MeshEvent.Owner("Disco Zebra", "DZ") in events)
        val v = events.filterIsInstance<MeshEvent.VehicleUpdate>().single().vehicle
        assertEquals(0x11L, v.nodeNum)
        assertEquals("Mutant Snail", v.name)
        assertEquals("SNL", v.shortName)
        assertEquals(40.78, v.position!!.lat, 1e-9)
        assertEquals(1788000000_000L, v.positionTimeMillis)
        assertEquals(1788000050_000L, v.lastHeardMillis)
        assertEquals(MeshEvent.ConfigComplete(false), events.last())
    }

    @Test
    fun configDumpNeedsProvisioning() {
        assertTrue((dump(MeshProtocol { now }, channelName = "LongFast", psk = byteArrayOf(1)).last() as MeshEvent.ConfigComplete).needsProvisioning)
        assertTrue((dump(MeshProtocol { now }, ownerName = "").last() as MeshEvent.ConfigComplete).needsProvisioning)
        assertTrue((dump(MeshProtocol { now }, lora = LoRaConfig(region = 0)).last() as MeshEvent.ConfigComplete).needsProvisioning)
        val extra = dump(MeshProtocol { now }, extraChannels = listOf(Channel(1, ChannelSettings(name = "LongFast"), Channel.ROLE_SECONDARY)))
        assertTrue((extra.last() as MeshEvent.ConfigComplete).needsProvisioning)
        // Owner differs from the car name the user chose.
        val p = MeshProtocol { now }.apply { carName = "Burnt Toast" }
        val cc = dump(p).last() as MeshEvent.ConfigComplete
        assertTrue(cc.needsProvisioning)
        assertTrue(cc.reasons.any { "car name" in it })
    }

    @Test
    fun decodesHandBuiltPositionPacket() {
        val p = MeshProtocol { now }
        p.handleFromRadio(fromRadio(FromRadio.MyInfo(1, MyNodeInfo(myNum))))
        // FromRadio{id=5, packet{from=0x12345678 to=broadcast decoded{portnum=3 payload=Position{lat 40.7864,
        // lon -119.2065, time 1788000000, ground_speed 8 km/h, ground_track 90.00000°, precision 32}}
        // id=0x0badcafe rx_time=1788000005 hop_limit=3}} – bytes from an independent encoder.
        val bytes = hex(
            """08 05 12 36 0d 78 56 34 12 15 ff ff ff ff 22 1e 08 03 12 1a 0d c0 82 4f 18 15 18 88 f2 b8 25 00 b7 92 6a
               78 08 80 01 c0 a8 a5 04 b8 01 20 35 fe ca ad 0b 3d 05 b7 92 6a 48 03""",
        )
        val msg = FromRadio.decode(bytes) as FromRadio.Packet
        assertEquals(5L, msg.id)
        assertEquals(0x12345678L, msg.packet.from)
        assertEquals(0x0BADCAFEL, msg.packet.id)
        assertEquals(1788000005L, msg.packet.rxTime)

        val v = (p.handleFromRadio(bytes).single() as MeshEvent.VehicleUpdate).vehicle
        assertEquals(0x12345678L, v.nodeNum)
        assertEquals("Car 5678", v.name) // no NodeInfo yet
        assertEquals(40.7864, v.position!!.lat, 1e-9)
        assertEquals(-119.2065, v.position!!.lon, 1e-9)
        assertEquals(1788000000_000L, v.positionTimeMillis)
        assertEquals(8 / 1.609344, v.speedMph!!, 1e-9)
        assertEquals(90.0, v.headingDeg!!, 1e-9)
        assertEquals(now, v.lastHeardMillis)

        // Names learned later via NODEINFO_APP are carried by subsequent updates.
        val ni = MeshPacket(from = 0x12345678, decoded = MeshData(PortNum.NODEINFO_APP, User("!12345678", "Zebra Car", "ZC").encode()))
        val named = (p.handleFromRadio(fromRadio(FromRadio.Packet(0, ni))).single() as MeshEvent.VehicleUpdate).vehicle
        assertEquals("Zebra Car", named.name)
        assertEquals(40.7864, named.position!!.lat, 1e-9)
        assertEquals(listOf("Zebra Car"), p.vehicles().map { it.name })
    }

    @Test
    fun decodesHandBuiltTextPacket() {
        val p = MeshProtocol { now }
        p.handleFromRadio(fromRadio(FromRadio.MyInfo(1, MyNodeInfo(myNum))))
        // FromRadio{id=6, packet{from=0x12345678 to=broadcast decoded{portnum=1 payload="Hi from Disco Zebra ❤"} id=0x01020304}}
        val bytes = hex(
            """08 06 12 2c 0d 78 56 34 12 15 ff ff ff ff 22 1b 08 01 12 17 48 69 20 66 72 6f 6d 20 44 69 73 63 6f
               20 5a 65 62 72 61 20 e2 9d a4 35 04 03 02 01""",
        )
        val text = p.handleFromRadio(bytes).filterIsInstance<MeshEvent.Text>().single().message
        assertEquals("Hi from Disco Zebra ❤", text.text)
        assertEquals(0x12345678L, text.fromNode)
        assertEquals("Car 5678", text.fromName)
        assertEquals(now, text.timeMillis)
        assertFalse(text.mine)
    }

    @Test
    fun ignoresOwnEncryptedOtherChannelAndDirectToOthers() {
        val p = MeshProtocol { now }
        p.handleFromRadio(fromRadio(FromRadio.MyInfo(1, MyNodeInfo(myNum))))
        val txt = MeshData(PortNum.TEXT_MESSAGE_APP, "x".toByteArray())
        assertTrue(p.handleFromRadio(fromRadio(FromRadio.Packet(0, MeshPacket(from = myNum, decoded = txt)))).isEmpty())
        assertTrue(p.handleFromRadio(fromRadio(FromRadio.Packet(0, MeshPacket(from = 7, encrypted = byteArrayOf(1, 2))))).isEmpty())
        assertTrue(p.handleFromRadio(fromRadio(FromRadio.Packet(0, MeshPacket(from = 7, channel = 1, decoded = txt)))).isEmpty())
        assertTrue(p.handleFromRadio(fromRadio(FromRadio.Packet(0, MeshPacket(from = 7, to = 99, decoded = txt)))).isEmpty())
        // Direct message to us is accepted.
        assertEquals(1, p.handleFromRadio(fromRadio(FromRadio.Packet(0, MeshPacket(from = 7, to = myNum, decoded = txt)))).filterIsInstance<MeshEvent.Text>().size)
    }

    @Test
    fun garbageDoesNotThrow() {
        val p = MeshProtocol { now }
        assertIs<MeshEvent.Log>(p.handleFromRadio(hex("12 ff 01")).single())
        assertIs<MeshEvent.Log>(p.handleFromRadio(fromRadio(FromRadio.Rebooted(0))).single())
        assertEquals(MeshProtocol.REBOOTED_LOG, (p.handleFromRadio(fromRadio(FromRadio.Rebooted(0))).single() as MeshEvent.Log).line)
    }

    @Test
    fun positionPacketEncodesFix() {
        val p = MeshProtocol { now }
        val fix = Fix(1_788_000_123_456L, LatLon(40.786400, -119.206500), speedMps = 2.5, bearingDeg = 271.5, accuracyM = 3.0)
        val tr = ToRadio.decode(p.positionPacket(fix)) as ToRadio.Packet
        val pkt = tr.packet
        assertEquals(NodeNum.BROADCAST, pkt.to)
        assertEquals(0, pkt.channel)
        assertEquals(0L, pkt.from)
        assertNotEquals(0L, pkt.id)
        assertEquals(ZepMesh.HOP_LIMIT, pkt.hopLimit)
        val data = pkt.decoded!!
        assertEquals(PortNum.POSITION_APP, data.portnum)
        val pos = Position.decode(data.payload)
        assertEquals(407864000, pos.latitudeI)
        assertEquals(-1192065000, pos.longitudeI)
        assertEquals(1_788_000_123L, pos.time)
        assertEquals(9L, pos.groundSpeed) // 2.5 m/s = 9 km/h
        assertEquals(27_150_000L, pos.groundTrack)
        assertEquals(32, pos.precisionBits)

        // Unknown speed/bearing are left out.
        val bare = Position.decode((ToRadio.decode(p.positionPacket(fix.copy(speedMps = null, bearingDeg = null))) as ToRadio.Packet).packet.decoded!!.payload)
        assertEquals(null, bare.groundSpeed)
        assertEquals(null, bare.groundTrack)

        // Another ZepDriver phone decodes it back.
        val other = MeshProtocol { now }
        val rx = MeshPacket(from = 0x55, to = NodeNum.BROADCAST, decoded = data, id = pkt.id)
        val v = (other.handleFromRadio(fromRadio(FromRadio.Packet(0, rx))).single() as MeshEvent.VehicleUpdate).vehicle
        assertEquals(271.5, v.headingDeg!!, 1e-6)
        assertEquals(9 / 1.609344, v.speedMph!!, 1e-9)
        assertEquals(1_788_000_123_000L, v.positionTimeMillis)
    }

    @Test
    fun textPacketIsCapped() {
        val p = MeshProtocol { now }
        val pkt = (ToRadio.decode(p.textPacket("hello")) as ToRadio.Packet).packet
        assertEquals(PortNum.TEXT_MESSAGE_APP, pkt.decoded!!.portnum)
        assertEquals("hello", String(pkt.decoded!!.payload, Charsets.UTF_8))
        assertEquals(NodeNum.BROADCAST, pkt.to)
        assertTrue(pkt.wantAck)

        val long = "❤".repeat(100) // 300 UTF-8 bytes
        val capped = (ToRadio.decode(p.textPacket(long)) as ToRadio.Packet).packet.decoded!!.payload
        assertTrue(capped.size <= MeshProtocol.MAX_TEXT_BYTES)
        assertEquals("❤".repeat(66), String(capped, Charsets.UTF_8)) // never splits a character
        assertEquals("❤".repeat(66), p.sentText(long).text)
        assertTrue(p.sentText("x").mine)

        val ids = (1..50).map { (ToRadio.decode(p.textPacket("x")) as ToRadio.Packet).packet.id }
        assertTrue(ids.all { it in 1..0xFFFFFFFFL })
        assertTrue(ids.toSet().size > 45)
    }

    @Test
    fun provisioningRequiresNodeNum() {
        assertFailsWith<IllegalStateException> { MeshProtocol().provisioningPackets("Zep") }
    }

    @Test
    fun provisioningPacketsDecodeToZepDriverSettings() {
        val p = MeshProtocol { now }
        val radioLora = LoRaConfig(
            usePreset = true, modemPreset = LoRaConfig.PRESET_LONG_FAST, region = LoRaConfig.REGION_UNSET, hopLimit = 7,
            txPower = 22, channelNum = 20, sx126xRxBoostedGain = true,
            unknown = ProtoWriter().varintAlways(106, 2).toByteArray(),
        )
        dump(
            p, ownerName = "Meshtastic c3d4", channelName = "", psk = byteArrayOf(1), lora = radioLora,
            extraChannels = listOf(Channel(1, ChannelSettings(name = "Friends"), Channel.ROLE_SECONDARY), Channel(2, null, Channel.ROLE_DISABLED)),
        )
        val packets = p.provisioningPackets("  Disco   Zebra of the Deep Playa Sea ")
        val admin = packets.map { bytes ->
            val pkt = (ToRadio.decode(bytes) as ToRadio.Packet).packet
            assertEquals(myNum, pkt.to)
            assertEquals(0L, pkt.from) // local client: no session passkey needed
            assertEquals(0, pkt.channel)
            assertNotEquals(0L, pkt.id)
            val d = pkt.decoded!!
            assertEquals(PortNum.ADMIN_APP, d.portnum)
            assertFalse(d.wantResponse)
            AdminMessage.decode(d.payload)
        }
        assertIs<AdminMessage.BeginEditSettings>(admin.first())
        assertIs<AdminMessage.CommitEditSettings>(admin.last())

        val owner = admin.filterIsInstance<AdminMessage.SetOwner>().single().user
        assertEquals("Disco Zebra of the Deep", owner.longName) // collapsed, ≤ 24 bytes
        assertTrue(owner.longName.toByteArray().size <= 24)
        assertEquals("DZOT", owner.shortName)

        val configs = admin.filterIsInstance<AdminMessage.SetConfig>().map { it.config }
        val lora = configs.mapNotNull { it.lora }.single()
        assertTrue(lora.usePreset)
        assertEquals(LoRaConfig.PRESET_MEDIUM_FAST, lora.modemPreset)
        assertEquals(LoRaConfig.REGION_US, lora.region)
        assertEquals(3, lora.hopLimit)
        assertTrue(lora.txEnabled)
        assertEquals(0L, lora.channelNum)
        assertEquals(22, lora.txPower) // radio's own settings kept
        assertTrue(lora.sx126xRxBoostedGain)
        assertEquals("d0 06 02", lora.unknown.toHex()) // unmodelled field (106) preserved

        val bt = configs.mapNotNull { it.bluetooth }.single()
        assertTrue(bt.enabled)
        assertEquals(BluetoothConfig.MODE_FIXED_PIN, bt.mode)
        assertEquals(123456L, bt.fixedPin)

        val pos = configs.mapNotNull { it.position }.single()
        assertFalse(pos.positionBroadcastSmartEnabled)
        assertFalse(pos.fixedPosition)
        assertEquals(PositionConfig.GPS_NOT_PRESENT, pos.gpsMode)
        assertTrue(pos.positionBroadcastSecs >= 3600)

        val chans = admin.filterIsInstance<AdminMessage.SetChannel>().map { it.channel }
        val primary = chans.single { it.index == 0 }
        assertEquals(Channel.ROLE_PRIMARY, primary.role)
        assertEquals("ZepDriver", primary.settings!!.name)
        assertContentEquals(ZepMesh.PSK, primary.settings!!.psk)
        assertEquals(32, primary.settings!!.psk.size)
        assertEquals(32, primary.settings!!.moduleSettings!!.positionPrecision)
        // The stale secondary channel is disabled; the already-disabled one is left alone.
        assertEquals(listOf(1), chans.filter { it.role == Channel.ROLE_DISABLED }.map { it.index })

        // After the radio applies them, a new dump reports nothing to do.
        val q = MeshProtocol { now }.apply { carName = "  Disco   Zebra of the Deep Playa Sea " }
        val done = dump(q, ownerName = owner.longName, lora = lora)
        assertEquals(MeshEvent.ConfigComplete(false), done.last())
    }

    @Test
    fun heartbeatAndDisconnect() {
        val p = MeshProtocol()
        assertIs<ToRadio.Heartbeat>(ToRadio.decode(p.heartbeat()))
        assertEquals(ToRadio.Disconnect, ToRadio.decode(p.disconnect()))
        val w = ToRadio.decode(p.wantConfig()) as ToRadio.WantConfig
        assertNotEquals(0L, w.nonce)
    }

    @Test
    fun namesAndPositionSchedule() {
        assertEquals("Disco Zebra", ZepMesh.longNameFor("  Disco \t Zebra "))
        assertEquals("DZ", ZepMesh.shortNameFor("Disco Zebra"))
        assertEquals("Zeph", ZepMesh.shortNameFor("Zephyr"))
        assertEquals("ÉZ", ZepMesh.shortNameFor("élan zebra")) // 3 bytes
        assertTrue(ZepMesh.shortNameFor("🦓🦓").toByteArray().size <= 4)
        assertEquals(24, ZepMesh.longNameFor("x".repeat(40)).length)
        assertEquals("ab", ZepMesh.truncateUtf8("ab🦓", 5))

        val a = Fix(0, LatLon(40.7864, -119.2065), 2.0, 90.0, 3.0)
        assertTrue(ZepMesh.shouldSendPosition(null, a, moving = true))
        assertFalse(ZepMesh.shouldSendPosition(a, a.copy(timeMillis = 30_000), moving = true))
        assertTrue(ZepMesh.shouldSendPosition(a, a.copy(timeMillis = 60_000), moving = true))
        assertFalse(ZepMesh.shouldSendPosition(a, a.copy(timeMillis = 5 * 60_000), moving = false))
        assertTrue(ZepMesh.shouldSendPosition(a, a.copy(timeMillis = 10 * 60_000), moving = false))
        // ~600 ft north after 20 s: send early.
        assertTrue(ZepMesh.shouldSendPosition(a, a.copy(timeMillis = 20_000, position = LatLon(40.7864 + 600 / 364_000.0, -119.2065)), moving = true))
        assertFalse(ZepMesh.shouldSendPosition(a, a.copy(timeMillis = 20_000, position = LatLon(40.7864 + 300 / 364_000.0, -119.2065)), moving = true))
    }

    @Test
    fun pskIsFixedAes256() {
        assertEquals(32, ZepMesh.PSK.size)
        assertContentEquals(ZepMesh.PSK, ZepMesh.PSK)
        ZepMesh.PSK[0] = 0 // returns a copy
        assertEquals(0x0f, ZepMesh.PSK[0].toInt())
    }
}
