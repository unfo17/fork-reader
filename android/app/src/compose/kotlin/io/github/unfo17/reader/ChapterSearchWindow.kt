@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package io.github.unfo17.reader

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.getOrElse
import java.util.IdentityHashMap

/** Resolves TOC anchors against source nodes, including anchors inside a single paragraph. */
internal class ChapterSearchWindow private constructor(html: String) {
    data class Window(val start: Int, val end: Int)
    private val document = Jsoup.parse(html)
    private val heads = IdentityHashMap<Node, Int>()
    private val starts = IdentityHashMap<Node, Int>()
    private val ends = IdentityHashMap<Node, Int>()
    private var consumedCoordinate = 0
    private val raw = StringBuilder()
    private val coordinates = IntArray(html.length.coerceAtLeast(1))
    private var cachedNode: Element? = null
    private var cachedText = ""
    private var cachedMap = IntArray(0)
    private fun coordinate(index: Int): Int? = if (index in 0 until raw.length) coordinates[index] else null
    private var chapterStart = 0
    private var chapterEnd = Int.MAX_VALUE
    private var boundaries: List<Int> = listOf(0)
    data class ChapterSpan(val start: Int, val end: Int, val chapter: Int)

    init {
        var position = 0
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) { heads[node] = position++; starts[node] = raw.length }
            override fun tail(node: Node, depth: Int) {
                // Match the installed Readium 3.1.2 content iterator's text-node policy.
                if (node is TextNode && node.wholeText.isNotBlank()) {
                    val text = Parser.unescapeEntities(node.wholeText, false)
                    text.forEach { coordinates[raw.length] = position++; raw.append(it) }
                }
                ends[node] = raw.length
                position++
            }
        }, document.body())
    }

    private fun element(selector: String): Element? = if (selector.isBlank()) null else runCatching { document.selectFirst(selector) }.getOrNull()
    private fun anchor(fragment: String): Element? = Uri.decode(fragment).takeIf { it.isNotEmpty() }?.let { id ->
        document.getElementById(id) ?: document.getElementsByAttributeValue("name", id).firstOrNull()
    }

    private fun position(locator: Locator): Int? {
        val locations = locator.toJSON().optJSONObject("locations")
        locations?.optJSONObject("forkSourceRange")?.optJSONObject("start")?.let { point ->
            val parent = element(point.optString("selector"))
            val node = if (point.optInt("childNodeIndex", -1) < 0) parent else parent?.childNodes()?.getOrNull(point.optInt("childNodeIndex"))
            if (node is TextNode) {
                val start = starts[node] ?: return@let
                val index = start + point.optInt("offset").coerceIn(0, node.wholeText.length)
                coordinate(index)?.let { return it }
            } else if (node != null) heads[node]?.let { return it }
        }
        val selected = element(locations?.optString("cssSelector").orEmpty())
        val quote = locator.text.highlight.orEmpty()
        if (quote.isNotEmpty()) {
            val start = selected?.let { starts[it] } ?: 0
            val end = selected?.let { ends[it] } ?: raw.length
            var found = raw.indexOf(quote, start)
            while (found >= start && found + quote.length <= end) {
                val before = locator.text.before.orEmpty().takeLast(50)
                if (before.isEmpty() || raw.substring(0, found).endsWith(before)) return coordinate(found)
                found = raw.indexOf(quote, found + 1)
            }
        }
        val fragment = locator.locations.fragments.firstOrNull() ?: locator.href.toString().substringAfter('#', "")
        anchor(fragment)?.let { heads[it] }?.let { return it }
        return selected?.let { heads[it] }
    }

    private fun paragraphCoordinates(paragraph: Content.TextualElement): IntArray {
        val text = paragraph.text.orEmpty()
        if (text.isEmpty()) return IntArray(0)
        val selector = paragraph.locator.toJSON().optJSONObject("locations")?.optString("cssSelector").orEmpty()
        val node = element(selector) ?: error("无法精确定位当前章节的正文段落")
        val start = starts.getValue(node); val end = ends.getValue(node)
        if (start == end) return IntArray(text.length) { heads.getValue(node) }
        if (cachedNode !== node) {
            val normalized = StringBuilder(); val map = IntArray(end - start)
            for (index in start until end) {
                val char = raw[index]
                if (char == '\u200b' || char == '\u00ad') continue
                val whitespace = char == ' ' || char == '\t' || char == '\n' || char == '\r' || char == '\u000c'
                if (whitespace && (normalized.isEmpty() || normalized.last() == ' ')) continue
                map[normalized.length] = coordinates[index]
                normalized.append(if (whitespace) ' ' else char)
            }
            cachedNode = node; cachedText = normalized.toString(); cachedMap = map
        }
        val map = cachedMap
        var low = 0; var high = cachedText.length
        while (low < high) { val middle = (low + high) / 2; if (map[middle] < consumedCoordinate) low = middle + 1 else high = middle }
        val offset = cachedText.indexOf(text, low)
        require(offset >= 0) { "无法精确划分当前章节，请使用全书搜索" }
        consumedCoordinate = map[offset + text.length - 1] + 1
        return IntArray(text.length) { map[offset + it] }
    }

    fun window(paragraph: Content.TextualElement): Window {
        val map = paragraphCoordinates(paragraph)
        var first = 0; var last = map.size
        while (first < last && map[first] < chapterStart) first++
        while (last > first && map[last - 1] >= chapterEnd) last--
        return Window(first, last)
    }

    /** Boundaries can occur inside one paragraph; speech must not cross them in one WAV. */
    fun chapters(paragraph: Content.TextualElement): List<ChapterSpan> {
        val map = paragraphCoordinates(paragraph)
        if (map.isEmpty()) return emptyList()
        fun chapter(at: Int): Int { val found = boundaries.binarySearch(at); return if (found >= 0) found else (-found - 2).coerceAtLeast(0) }
        val spans = mutableListOf<ChapterSpan>()
        var start = 0; var current = chapter(map[0])
        for (at in 1 until map.size) {
            val next = chapter(map[at])
            if (next != current) { spans += ChapterSpan(start, at, current); start = at; current = next }
        }
        spans += ChapterSpan(start, map.size, current)
        return spans
    }

    companion object {
        fun resource(href: String) = href.substringBefore('#').substringBefore('?')
        suspend fun create(publication: Publication, href: String, current: Locator?, wholeResource: Boolean = false): ChapterSearchWindow? {
            fun flatten(links: List<Link>): List<Link> = links.flatMap { listOf(it) + flatten(it.children) }
            val toc = flatten(publication.tableOfContents).filter { resource(it.href.toString()) == resource(href) }
            if (toc.none { '#' in it.href.toString() }) return null
            val link = publication.readingOrder.firstOrNull { resource(it.href.toString()) == resource(href) } ?: error("当前章节已不在原书中")
            if (link.mediaType?.isHtml != true) return null
            val source = publication.get(link) ?: error("无法读取当前章节")
            val html = try {
                require(source.length().getOrElse { error("无法读取章节大小") } <= 8L * 1024 * 1024) { "当前章节正文过大，请使用全书搜索" }
                source.read().getOrElse { error("无法读取章节正文") }.toString(link.mediaType?.charset ?: Charsets.UTF_8)
            } finally { source.close() }
            val index = ChapterSearchWindow(html)
            val boundaries = toc.mapNotNull { entry ->
                val fragment = entry.href.toString().substringAfter('#', "")
                if (fragment.isEmpty()) 0 else index.anchor(fragment)?.let { index.heads[it] }
            }.map { if (it <= (index.coordinate(0) ?: 0)) 0 else it }.distinct().sorted()
            require(boundaries.isNotEmpty()) { "目录锚点无法定位，请使用全书搜索" }
            index.boundaries = (listOf(0) + boundaries).distinct().sorted()
            val at = if (wholeResource) 0 else current?.let { index.position(it) }
                ?: href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { index.anchor(it) }?.let { index.heads[it] }
                ?: if (boundaries.size == 1 || current?.locations?.progression == 0.0) 0 else null
            require(at != null) { "无法确定当前章节，请从目录打开章节后重试" }
            index.chapterStart = boundaries.lastOrNull { it <= at } ?: 0
            index.chapterEnd = boundaries.firstOrNull { it > at } ?: Int.MAX_VALUE
            return index
        }
    }
}
