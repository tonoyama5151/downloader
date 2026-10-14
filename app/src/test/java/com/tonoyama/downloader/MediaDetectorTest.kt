package com.tonoyama.downloader

import com.tonoyama.downloader.detect.MediaDetector
import com.tonoyama.downloader.detect.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDetectorTest {

    @Test
    fun mp4WithQueryIsFile() {
        assertEquals(MediaKind.FILE, MediaDetector.classify("https://cdn.example.com/v/movie.MP4?token=abc&e=1"))
    }

    @Test
    fun m3u8IsHls() {
        assertEquals(MediaKind.HLS, MediaDetector.classify("https://example.com/hls/master.m3u8"))
        assertEquals(MediaKind.HLS, MediaDetector.classify("https://example.com/play?format=m3u8"))
    }

    @Test
    fun segmentsAndOtherFilesAreIgnored() {
        assertNull(MediaDetector.classify("https://example.com/hls/seg001.ts"))
        assertNull(MediaDetector.classify("https://example.com/hls/chunk.m4s"))
        assertNull(MediaDetector.classify("https://example.com/image.jpg"))
        assertNull(MediaDetector.classify("blob:https://example.com/abc"))
        assertNull(MediaDetector.classify("data:video/mp4;base64,AAAA"))
    }

    @Test
    fun videoElementWithoutExtensionIsFile() {
        assertEquals(MediaKind.FILE, MediaDetector.classifyElementSource("https://example.com/stream/12345"))
        assertNull(MediaDetector.classifyElementSource("blob:https://example.com/abc"))
    }

    @Test
    fun displayNameIsDecodedLastSegment() {
        assertEquals("my video.mp4", MediaDetector.displayName("https://example.com/a/my%20video.mp4?x=1"))
    }

    @Test
    fun originKeepsPort() {
        assertEquals("https://example.com:8443", MediaDetector.originOf("https://example.com:8443/page"))
        assertEquals("https://example.com", MediaDetector.originOf("https://example.com/page"))
    }
}
