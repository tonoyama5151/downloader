package com.tonoyama.downloader

import com.tonoyama.downloader.hls.ByteRange
import com.tonoyama.downloader.hls.HlsParser
import com.tonoyama.downloader.hls.UnsupportedStreamException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Main",DEFAULT=YES,URI="audio/main.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
        https://other.example.com/1080p/index.m3u8
    """.trimIndent()

    @Test
    fun parsesMasterPlaylist() {
        assertTrue(HlsParser.isPlaylist(master))
        assertTrue(HlsParser.isMaster(master))
        val m = HlsParser.parseMaster(master, "https://example.com/video/master.m3u8")
        assertEquals(2, m.variants.size)

        val best = m.sortedVariants().first()
        assertEquals("1920x1080", best.resolution)
        assertEquals("https://other.example.com/1080p/index.m3u8", best.url)
        assertNull(m.audioUrlFor(best))

        val low = m.sortedVariants().last()
        assertEquals("https://example.com/video/360p/index.m3u8", low.url)
        assertEquals("avc1.4d401e,mp4a.40.2", low.codecs)
        assertEquals("https://example.com/video/audio/main.m3u8", m.audioUrlFor(low))
    }

    @Test
    fun parsesVodMediaPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:4.5,
            /abs/seg1.ts?sig=1
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parseMedia(text, "https://example.com/v/index.m3u8")
        assertEquals(2, media.segments.size)
        assertEquals("https://example.com/v/seg0.ts", media.segments[0].url)
        assertEquals("https://example.com/abs/seg1.ts?sig=1", media.segments[1].url)
        assertEquals(10.5, media.totalDuration, 0.001)
        assertTrue(media.ended)
        assertFalse(media.encrypted)
        HlsParser.validate(media)
    }

    @Test
    fun parsesByteRangesAndInitSegment() {
        val text = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="video.mp4",BYTERANGE="700@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@700
            video.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            video.mp4
        """.trimIndent()
        val media = HlsParser.parseMedia(text, "https://example.com/v/index.m3u8")
        assertEquals(ByteRange(700, 0), media.initSegment?.byteRange)
        assertEquals(ByteRange(1000, 700), media.segments[0].byteRange)
        assertEquals(ByteRange(500, 1700), media.segments[1].byteRange)
        assertEquals("bytes=1700-2199", media.segments[1].byteRange?.header)
        assertTrue(media.ended)
    }

    @Test(expected = UnsupportedStreamException::class)
    fun encryptedStreamIsRejected() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parseMedia(text, "https://example.com/index.m3u8")
        assertTrue(media.encrypted)
        HlsParser.validate(media)
    }

    @Test(expected = UnsupportedStreamException::class)
    fun liveStreamIsRejected() {
        val text = """
            #EXTM3U
            #EXTINF:6,
            seg100.ts
        """.trimIndent()
        HlsParser.validate(HlsParser.parseMedia(text, "https://example.com/index.m3u8"))
    }

    @Test
    fun keyMethodNoneIsAllowed() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFalse(HlsParser.parseMedia(text, "https://example.com/index.m3u8").encrypted)
    }
}
