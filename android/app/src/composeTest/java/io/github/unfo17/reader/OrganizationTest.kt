package io.github.unfo17.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OrganizationTest {
    @Test fun categoryIdentityDeletionAndDelayedEditsMatchUpstream() {
        var stored: String? = null
        val organization = LibraryOrganization({ stored }, { stored = it; true })
        val fantasy = organization.create("奇幻")
        val read = organization.create("已阅")
        organization.assign(setOf("one", "two"), fantasy)
        val revision = organization.revisionOf("one")
        organization.rename(fantasy, "奇幻文学")
        assertEquals(fantasy, organization.groupOf("one"))
        assertEquals("奇幻文学", organization.nameOf(fantasy))
        organization.assign(setOf("one"), read)
        val changed = organization.assign(setOf("one", "two"), LibraryOrganization.UNFILED, mapOf("one" to revision, "two" to organization.revisionOf("two")))
        assertEquals(setOf("two"), changed)
        assertEquals(read, organization.groupOf("one"))
        val old = organization.snapshot()
        organization.remove(read)
        assertEquals(LibraryOrganization.UNFILED, organization.groupOf("one"))
        stored = organization.merged(old)
        assertFalse(organization.groups().containsKey(read))
        assertEquals(LibraryOrganization.UNFILED, organization.groupOf("one"))
        assertTrue(runCatching { organization.create("奇幻文学") }.isFailure)
        assertTrue(runCatching { organization.rename(fantasy, " ") }.isFailure)
        assertTrue(runCatching { organization.create("字".repeat(31)) }.isFailure)
        val recreated = LibraryOrganization({ stored }, { stored = it; true })
        assertEquals(organization.groups(), recreated.groups())
    }

    @Test fun legacyLabelMigrationIsIdempotentAndPreservesNewerAssignments() {
        var stored: String? = null
        val organization = LibraryOrganization({ stored }, { stored = it; true })
        val book = Book("a", "旧书", "", "unused.epub", category = "旧分类")
        stored = organization.mergedLegacy(listOf(book))
        val first = organization.groupOf("a")
        stored = organization.mergedLegacy(listOf(book))
        assertEquals(first, organization.groupOf("a")); assertEquals(1, organization.groups().size)
        organization.assign(setOf("a"), LibraryOrganization.UNFILED)
        stored = organization.mergedLegacy(listOf(book))
        assertEquals(LibraryOrganization.UNFILED, organization.groupOf("a"))
    }

    @Test fun failedBatchAssignmentLeavesAllBooksAndRevisionsUnchanged() {
        var stored: String? = null
        var writable = true
        val organization = LibraryOrganization({ stored }, { if (writable) { stored = it; true } else false })
        val group = organization.create("待阅读")
        organization.assign(setOf("one", "two"), group)
        val before = stored
        val revisions = listOf(organization.revisionOf("one"), organization.revisionOf("two"))
        writable = false
        assertTrue(runCatching { organization.assign(setOf("one", "two"), LibraryOrganization.UNFILED) }.isFailure)
        assertEquals(before, stored)
        assertEquals(revisions, listOf(organization.revisionOf("one"), organization.revisionOf("two")))
        assertEquals(group, organization.groupOf("one")); assertEquals(group, organization.groupOf("two"))
    }

    @Test fun metadataSearchNormalizesWidthAndRequiresEveryTerm() {
        val book = Book("abcdef1234", "ＡＢＣ・第一卷", "山 田", "unused.epub", category = "文 学", summary = "城市生活", tags = listOf("奇幻", "女性视角"))
        assertTrue(BookQuery("abc 山田 #奇幻").matches(book))
        assertTrue(BookQuery("城市 #文学").matches(book))
        assertFalse(BookQuery("abc 不存在").matches(book))
        assertFalse(BookQuery("#abc").matches(book))
        assertFalse(BookQuery("#").matches(book))
        assertTrue(BookQuery("abcdef1234", BookSearchField.ID).matches(book))
        assertFalse(BookQuery("abcdef", BookSearchField.ID).matches(book))
        assertFalse(BookQuery("山田", BookSearchField.TITLE).matches(book))
        assertTrue(BookQuery("女性视角", BookSearchField.TAGS).matches(book))
    }

    @Test fun libraryFiltersCombineCategoryAuthorStateSourceAndStableSort() {
        var stored: String? = null
        val organization = LibraryOrganization({ stored }, { stored = it; true })
        val group = organization.create("精选")
        val books = listOf(
            Book("a", "乙", " 山 田 ", "a.epub", lastRead = 20, addedAt = 40, sourceReference = "opaque"),
            Book("b", "甲", "山 田", "b.epub", lastRead = 20, addedAt = 10, progress = 1.0),
            Book("c", "丙", "", "c.epub", addedAt = 30))
        organization.assign(setOf("a", "b"), group)
        assertEquals(listOf("a", "c", "b"), LibraryFilter().apply(books, organization).map { it.id })
        assertEquals(listOf("a"), LibraryFilter(category = group, author = "山 田", reading = "reading", source = "linked").apply(books, organization).map { it.id })
        assertEquals(listOf("a", "b", "c"), LibraryFilter(sort = LibrarySort.RECENT_READ).apply(books, organization).map { it.id })
        assertEquals(listOf("c"), LibraryFilter(category = LibraryOrganization.UNFILED, reading = "unread").apply(books, organization).map { it.id })
        organization.remove(group)
        assertEquals(3, LibraryFilter(category = LibraryOrganization.UNFILED).apply(books, organization).size)
    }

    @Test fun categoryBackupRestoresEmptyGroupsAndNeverResurrectsDeletedOnes() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = Library.get(context)
        val source = File(context.cacheDir, "organization-book.txt").apply { writeText("第一章 分类备份\n唯一内容 ${System.nanoTime()}") }
        val backup = File(context.cacheDir, "organization-backup.zip")
        val group = library.organization.create("测试分类-${System.nanoTime().toString().takeLast(6)}")
        val empty = library.organization.create("空分类-${System.nanoTime().toString().takeLast(6)}")
        val book = library.import(Uri.fromFile(source))
        try {
            library.organization.assign(setOf(book.id), group)
            exportLibrary(context, Uri.fromFile(backup))
            library.organization.remove(group)
            restoreLibrary(context, Uri.fromFile(backup))
            assertEquals("未分类", library.records().first { it.id == book.id }.category)
            assertTrue(library.organization.groups().containsKey(empty))
            java.util.zip.ZipFile(backup).use { assertNotNull(it.getEntry("organization.json")) }
            assertFalse(book.removed)
        } finally { library.remove(library.books().first { it.id == book.id }); library.organization.remove(group); library.organization.remove(empty); source.delete(); backup.delete() }
    }
}
