package com.orktts.app

import org.jsoup.Jsoup
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class Chapter(val title: String, val sentences: List<String>)

data class Book(val title: String, val coverBytes: ByteArray?, val chapters: List<Chapter>)

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

            val spine = opfDoc.getElementsByTagName("itemref")
            val chapters = ArrayList<Chapter>()
            for (i in 0 until spine.length) {
                val idref = (spine.item(i) as Element).getAttribute("idref")
                val href = idToHref[idref] ?: continue
                val path = resolvePath(opfDir, href)
                val bytes = readEntry(zip, path) ?: continue
                val html = String(bytes, Charsets.UTF_8)
                val text = Jsoup.parse(html).body().text()
                val sentences = splitSentences(text)
                if (sentences.isNotEmpty()) {
                    chapters.add(Chapter(title = "Capítulo ${chapters.size + 1}", sentences = sentences))
                }
            }

            return Book(title = title, coverBytes = coverBytes, chapters = chapters)
        }
    }

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
