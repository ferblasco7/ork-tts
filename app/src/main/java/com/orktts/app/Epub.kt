package com.orktts.app

import org.jsoup.Jsoup
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class Chapter(val title: String, val sentences: List<String>)

data class Book(val title: String, val author: String?, val coverBytes: ByteArray?, val chapters: List<Chapter>)

/** Parses an EPUB (a zip with an OPF manifest) into plain-text chapters split by sentence. */
object EpubParser {

    fun parse(file: File): Book {
        ZipFile(file).use { zip ->
            val containerDoc = parseXml(zip, "META-INF/container.xml")
            val opfPath = containerDoc.getElementsByTagName("rootfile").item(0)
                .let { it as Element }.getAttribute("full-path")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val opfDoc = parseXml(zip, opfPath)
            val manifest = opfDoc.getElementsByTagName("item")
            val idToHref = HashMap<String, String>()
            var coverId: String? = null
            for (i in 0 until manifest.length) {
                val item = manifest.item(i) as Element
                val id = item.getAttribute("id")
                idToHref[id] = item.getAttribute("href")
                if (item.getAttribute("properties").contains("cover-image")) {
                    coverId = id
                }
            }

            if (coverId == null) {
                val metas = opfDoc.getElementsByTagName("meta")
                for (i in 0 until metas.length) {
                    val meta = metas.item(i) as Element
                    if (meta.getAttribute("name") == "cover") {
                        coverId = meta.getAttribute("content")
                        break
                    }
                }
            }

            val coverBytes = coverId?.let { idToHref[it] }?.let { href ->
                readEntry(zip, resolvePath(opfDir, href))
            }

            val titleNodes = opfDoc.getElementsByTagName("dc:title")
            val title = if (titleNodes.length > 0) titleNodes.item(0).textContent else file.nameWithoutExtension

            val authorNodes = opfDoc.getElementsByTagName("dc:creator")
            val author = if (authorNodes.length > 0) cleanTitle(authorNodes.item(0).textContent) else null

            val tocTitles = loadTocTitles(zip, opfDoc, opfDir)

            val spine = opfDoc.getElementsByTagName("itemref")
            val chapters = ArrayList<Chapter>()
            for (i in 0 until spine.length) {
                val idref = (spine.item(i) as Element).getAttribute("idref")
                val href = idToHref[idref] ?: continue
                val path = resolvePath(opfDir, href)
                val bytes = readEntry(zip, path) ?: continue
                val html = String(bytes, Charsets.UTF_8)
                val doc = Jsoup.parse(html)
                val text = doc.body().text()
                val sentences = splitSentences(text)
                if (sentences.isNotEmpty()) {
                    val tocTitle = tocTitles[path]
                    val heading = doc.selectFirst("h1, h2, h3, h4, h5, header .title, .chapter-title, .title")
                        ?.text()?.let { cleanTitle(it) }
                    val titleTag = cleanTitle(doc.title())
                    val chapterTitle = tocTitle?.takeIf { it.isNotEmpty() }
                        ?: heading?.takeIf { it.isNotEmpty() && it.length <= 80 }
                        ?: titleTag.takeIf { it.isNotEmpty() }
                        ?: heading?.takeIf { it.isNotEmpty() }
                        ?: "Capítulo ${chapters.size + 1}"
                    chapters.add(Chapter(title = chapterTitle, sentences = sentences))
                }
            }

            return Book(title = title, author = author, coverBytes = coverBytes, chapters = chapters)
        }
    }

    /** Reads the EPUB2 NCX and/or EPUB3 nav document and maps each spine file path to its TOC label. */
    private fun loadTocTitles(
        zip: ZipFile,
        opfDoc: Document,
        opfDir: String
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        val manifest = opfDoc.getElementsByTagName("item")
        for (i in 0 until manifest.length) {
            val item = manifest.item(i) as Element
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            val properties = item.getAttribute("properties")
            when {
                mediaType == "application/x-dtbncx+xml" -> {
                    readNcxTitles(zip, opfDir, href, result)
                }
                properties.contains("nav") -> {
                    readNavTitles(zip, opfDir, href, result)
                }
            }
        }
        return result
    }

    private fun readNcxTitles(zip: ZipFile, opfDir: String, ncxHref: String, out: MutableMap<String, String>) {
        val ncxPath = resolvePath(opfDir, ncxHref)
        val ncxDir = ncxPath.substringBeforeLast('/', "")
        val bytes = readEntry(zip, ncxPath) ?: return
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            bytes.inputStream().use { factory.newDocumentBuilder().parse(it) }
        } catch (e: Exception) {
            return
        }
        val navPoints = doc.getElementsByTagName("navPoint")
        for (i in 0 until navPoints.length) {
            val navPoint = navPoints.item(i) as Element
            val labelNode = navPoint.getElementsByTagName("text").item(0) ?: continue
            val contentNode = navPoint.getElementsByTagName("content").item(0) as? Element ?: continue
            val src = contentNode.getAttribute("src").substringBefore('#')
            if (src.isEmpty()) continue
            val resolved = resolvePath(ncxDir, src)
            val label = cleanTitle(labelNode.textContent)
            if (label.isNotEmpty()) out.putIfAbsent(resolved, label)
        }
    }

    private fun readNavTitles(zip: ZipFile, opfDir: String, navHref: String, out: MutableMap<String, String>) {
        val navPath = resolvePath(opfDir, navHref)
        val navDir = navPath.substringBeforeLast('/', "")
        val bytes = readEntry(zip, navPath) ?: return
        val html = String(bytes, Charsets.UTF_8)
        val doc = Jsoup.parse(html)
        val tocNav = doc.selectFirst("nav[*|type=toc], nav#toc") ?: doc.selectFirst("nav") ?: return
        for (link in tocNav.select("a[href]")) {
            val href = link.attr("href").substringBefore('#')
            if (href.isEmpty()) continue
            val resolved = resolvePath(navDir, href)
            val label = cleanTitle(link.text())
            if (label.isNotEmpty()) out.putIfAbsent(resolved, label)
        }
    }

    private fun cleanTitle(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    private fun parseXml(zip: ZipFile, path: String): Document {
        val entry = zip.getEntry(path) ?: error("No se encontró $path en el EPUB")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return zip.getInputStream(entry).use { factory.newDocumentBuilder().parse(it) }
    }

    private fun readEntry(zip: ZipFile, path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun resolvePath(dir: String, href: String): String {
        if (dir.isEmpty()) return href
        return File(dir, href).path.replace('\\', '/')
    }

    private fun splitSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
