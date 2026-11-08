package mihon.feature.tagfilter

import io.kotest.matchers.shouldBe
import mihon.feature.tagfilter.domain.TagFilterLogic
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.TagMatchMode

class TagFilterLogicTest {

    @Test
    fun `computeMatchingUrls with ALL returns intersection of URLs`() {
        val tagUrls = mapOf(
            "tag1" to setOf("/ch1", "/ch2", "/ch3"),
            "tag2" to setOf("/ch2", "/ch3", "/ch4"),
            "tag3" to setOf("/ch2", "/ch5"),
        )

        val result = TagFilterLogic.computeMatchingUrls(tagUrls, TagMatchMode.ALL)
        result shouldBe setOf("/ch2")
    }

    @Test
    fun `computeMatchingUrls with ALL returns empty when sets are disjoint`() {
        val tagUrls = mapOf(
            "tag1" to setOf("/ch1", "/ch2"),
            "tag2" to setOf("/ch3", "/ch4"),
        )

        val result = TagFilterLogic.computeMatchingUrls(tagUrls, TagMatchMode.ALL)
        result shouldBe emptySet()
    }

    @Test
    fun `computeMatchingUrls with ANY returns union of URLs`() {
        val tagUrls = mapOf(
            "tag1" to setOf("/ch1", "/ch2"),
            "tag2" to setOf("/ch2", "/ch3"),
            "tag3" to setOf("/ch4"),
        )

        val result = TagFilterLogic.computeMatchingUrls(tagUrls, TagMatchMode.ANY)
        result shouldBe setOf("/ch1", "/ch2", "/ch3", "/ch4")
    }

    @Test
    fun `computeMatchingUrls returns empty set for empty input`() {
        TagFilterLogic.computeMatchingUrls(emptyMap(), TagMatchMode.ALL) shouldBe emptySet()
        TagFilterLogic.computeMatchingUrls(emptyMap(), TagMatchMode.ANY) shouldBe emptySet()
    }

    @Test
    fun `countUnsyncedWorks correctly counts matching URLs absent from saved chapters`() {
        val matchingUrls = setOf("/ch1", "/ch2", "/ch3", "/ch4", "/ch5")
        val savedUrls = setOf("/ch1", "/ch2", "/ch99")

        // /ch3, /ch4, /ch5 are not in saved chapters -> 3 unsynced works
        TagFilterLogic.countUnsyncedWorks(matchingUrls, savedUrls) shouldBe 3
    }

    @Test
    fun `countUnsyncedWorks returns zero when all matching works are already saved`() {
        val matchingUrls = setOf("/ch1", "/ch2")
        val savedUrls = setOf("/ch1", "/ch2", "/ch3")

        TagFilterLogic.countUnsyncedWorks(matchingUrls, savedUrls) shouldBe 0
    }
}
