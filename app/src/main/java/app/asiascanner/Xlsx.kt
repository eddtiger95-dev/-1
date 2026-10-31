package app.asiascanner

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class RowData(val row: Int, val name: String, val image: ByteArray?)

/**
 * Лёгкая работа с XLSX без Apache POI (POI тяжёлый и плохо дружит с Android).
 * Читает названия (столбец A) и картинки (обычные и «изображение в ячейке»),
 * при экспорте дописывает цены в B и E и вырезает картинки из файла.
 */
object Xlsx {

    private fun readZip(f: File): LinkedHashMap<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(BufferedInputStream(FileInputStream(f))).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) m[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return m
    }

    private fun parse(b: ByteArray): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(b))

    private fun serialize(d: Document): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        val bo = ByteArrayOutputStream()
        t.transform(DOMSource(d), StreamResult(bo))
        return bo.toByteArray()
    }

    private fun local(n: Node): String = n.nodeName.substringAfter(':')

    private fun elems(n: Node, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(x: Node) {
            var c = x.firstChild
            while (c != null) {
                if (c is Element) {
                    if (local(c) == name) out.add(c)
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(n)
        return out
    }

    /** Атрибут по локальному имени (r:id -> "id"). */
    private fun attrLocal(e: Element, name: String): String? {
        val at = e.attributes
        for (i in 0 until at.length) {
            val a = at.item(i)
            if (a.nodeName.substringAfter(':') == name) return a.nodeValue
        }
        return null
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList(base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() })
        for (p in target.split('/')) {
            when (p) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> {}
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun relsPath(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        return (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
    }

    /** id -> (type, resolved target) */
    private fun rels(z: Map<String, ByteArray>, part: String): Map<String, Pair<String, String>> {
        val b = z[relsPath(part)] ?: return emptyMap()
        val m = LinkedHashMap<String, Pair<String, String>>()
        for (e in elems(parse(b), "Relationship")) {
            m[e.getAttribute("Id")] = e.getAttribute("Type") to resolve(part, e.getAttribute("Target"))
        }
        return m
    }

    private fun firstSheet(z: Map<String, ByteArray>): String {
        val wb = parse(z["xl/workbook.xml"] ?: error("В файле нет xl/workbook.xml"))
        val sh = elems(wb, "sheet").firstOrNull() ?: error("В книге нет листов")
        val rid = attrLocal(sh, "id") ?: error("Лист без связи")
        return rels(z, "xl/workbook.xml")[rid]?.second ?: error("Не найден путь к листу")
    }

    private fun sharedStrings(z: Map<String, ByteArray>): List<String> {
        val b = z["xl/sharedStrings.xml"] ?: return emptyList()
        return elems(parse(b), "si").map { si -> elems(si, "t").joinToString("") { it.textContent } }
    }

    private fun colIdx(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n - 1
    }

    private fun rowNum(ref: String): Int = ref.filter { it.isDigit() }.toIntOrNull() ?: 0

    private fun cellText(c: Element, ss: List<String>): String = when (c.getAttribute("t")) {
        "s" -> ss.getOrNull(elems(c, "v").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: -1) ?: ""
        "inlineStr" -> elems(c, "t").joinToString("") { it.textContent }
        else -> elems(c, "v").firstOrNull()?.textContent ?: ""
    }

    /** Картинки, плавающие над ячейками (drawing). row -> bytes */
    private fun drawingImages(z: Map<String, ByteArray>, sheet: String, sheetDoc: Document): Map<Int, ByteArray> {
        val dr = elems(sheetDoc, "drawing").firstOrNull() ?: return emptyMap()
        val rid = attrLocal(dr, "id") ?: return emptyMap()
        val drawingPath = rels(z, sheet)[rid]?.second ?: return emptyMap()
        val dRels = rels(z, drawingPath)
        val ddoc = parse(z[drawingPath] ?: return emptyMap())
        val out = LinkedHashMap<Int, ByteArray>()
        for (anchor in elems(ddoc, "twoCellAnchor") + elems(ddoc, "oneCellAnchor")) {
            val from = elems(anchor, "from").firstOrNull() ?: continue
            val row0 = elems(from, "row").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: continue
            val blip = elems(anchor, "blip").firstOrNull() ?: continue
            val emb = attrLocal(blip, "embed") ?: continue
            val media = dRels[emb]?.second ?: continue
            val bytes = z[media] ?: continue
            out.putIfAbsent(row0 + 1, bytes)
        }
        return out
    }

    /** Картинки «в ячейке» (Excel 365, richData). row -> bytes */
    private fun richImages(z: Map<String, ByteArray>, sheetDoc: Document): Map<Int, ByteArray> {
        val relB = z["xl/richData/richValueRel.xml"] ?: return emptyMap()
        val rvB = z["xl/richData/rdrichvalue.xml"] ?: return emptyMap()
        val relIds = elems(parse(relB), "rel").map { attrLocal(it, "id") ?: "" }
        val relMap = rels(z, "xl/richData/richValueRel.xml")
        val rvs = elems(parse(rvB), "rv").map {
            elems(it, "v").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: -1
        }
        var vmToRv: (Int) -> Int = { it - 1 }
        z["xl/metadata.xml"]?.let { mb ->
            try {
                val md = parse(mb)
                val fm = elems(md, "futureMetadata").firstOrNull { it.getAttribute("name") == "XLRICHVALUE" }
                val rvbs = fm?.let {
                    elems(it, "bk").map { bk -> elems(bk, "rvb").firstOrNull()?.getAttribute("i")?.toIntOrNull() ?: -1 }
                }
                val vmeta = elems(md, "valueMetadata").firstOrNull()?.let {
                    elems(it, "bk").map { bk -> elems(bk, "rc").firstOrNull()?.getAttribute("v")?.toIntOrNull() ?: -1 }
                }
                if (rvbs != null && vmeta != null) {
                    vmToRv = { vm ->
                        val k = vmeta.getOrNull(vm - 1) ?: -1
                        rvbs.getOrNull(k) ?: -1
                    }
                }
            } catch (_: Exception) {
            }
        }
        val out = LinkedHashMap<Int, ByteArray>()
        for (c in elems(sheetDoc, "c")) {
            val vm = c.getAttribute("vm").toIntOrNull() ?: continue
            val li = rvs.getOrNull(vmToRv(vm)) ?: continue
            val rid = relIds.getOrNull(li) ?: continue
            val target = relMap[rid]?.second ?: continue
            val bytes = z[target] ?: continue
            out.putIfAbsent(rowNum(c.getAttribute("r")), bytes)
        }
        return out
    }

    /** Картинки WPS Office: в ячейке =DISPIMG("ID_..."), сами файлы в xl/cellimages.xml. row -> bytes */
    private fun wpsCellImages(z: Map<String, ByteArray>, sheetDoc: Document): Map<Int, ByteArray> {
        val cb = z["xl/cellimages.xml"] ?: return emptyMap()
        val rl = rels(z, "xl/cellimages.xml")
        val byId = HashMap<String, ByteArray>()
        for (ci in elems(parse(cb), "cellImage")) {
            val name = elems(ci, "cNvPr").firstOrNull()?.getAttribute("name") ?: continue
            val blip = elems(ci, "blip").firstOrNull() ?: continue
            val rid = attrLocal(blip, "embed") ?: continue
            val bytes = rl[rid]?.second?.let { z[it] } ?: continue
            byId[name] = bytes
        }
        val re = Regex("DISPIMG\\(\\s*\"([^\"]+)\"")
        val out = LinkedHashMap<Int, ByteArray>()
        for (c in elems(sheetDoc, "c")) {
            val f = elems(c, "f").firstOrNull()?.textContent ?: continue
            val id = re.find(f)?.groupValues?.get(1) ?: continue
            val b = byId[id] ?: continue
            out.putIfAbsent(rowNum(c.getAttribute("r")), b)
        }
        return out
    }

    /** Для диагностики: какие части с картинками есть в файле. */
    fun listParts(file: File): String = try {
        readZip(file).keys.filter {
            it.contains("media") || it.contains("drawing") || it.contains("cellimages") || it.contains("richData")
        }.joinToString(", ").ifEmpty { "картинок в файле нет" }
    } catch (e: Exception) {
        "не удалось прочитать файл"
    }

    fun read(file: File): List<RowData> {
        val z = readZip(file)
        val sheet = firstSheet(z)
        val ss = sharedStrings(z)
        val doc = parse(z[sheet] ?: error("Не найден лист"))
        val imgs = LinkedHashMap<Int, ByteArray>()
        imgs.putAll(drawingImages(z, sheet, doc))
        for ((k, v) in richImages(z, doc)) imgs.putIfAbsent(k, v)
        for ((k, v) in wpsCellImages(z, doc)) imgs.putIfAbsent(k, v)

        val names = HashMap<Int, String>()
        for (c in elems(doc, "c")) {
            val ref = c.getAttribute("r")
            if (ref.isEmpty() || colIdx(ref) != 0) continue
            val t = cellText(c, ss).trim()
            if (t.isNotEmpty()) names[rowNum(ref)] = t
        }
        val rows = (names.keys + imgs.keys).toSortedSet()
        return rows.map { RowData(it, names[it] ?: "", imgs[it]) }
    }

    fun export(src: File, results: Map<Int, Pair<Double?, Double?>>, out: OutputStream) {
        val z = readZip(src)
        val sheet = firstSheet(z)
        val doc = parse(z[sheet] ?: error("Не найден лист"))
        val sd = elems(doc, "sheetData").first()

        val rowEls = HashMap<Int, Element>()
        for (r in elems(sd, "row")) rowEls[r.getAttribute("r").toInt()] = r

        fun getRow(n: Int): Element {
            rowEls[n]?.let { return it }
            val e = doc.createElement("row")
            e.setAttribute("r", n.toString())
            var before: Node? = null
            var c = sd.firstChild
            while (c != null) {
                if (c is Element && (c.getAttribute("r").toIntOrNull() ?: 0) > n) {
                    before = c
                    break
                }
                c = c.nextSibling
            }
            sd.insertBefore(e, before)
            rowEls[n] = e
            return e
        }

        fun setCell(row: Element, col: Int, value: Double) {
            val ref = "${'A' + col}${row.getAttribute("r")}"
            var cell: Element? = null
            var before: Node? = null
            var c = row.firstChild
            while (c != null) {
                if (c is Element && local(c) == "c") {
                    val ci = colIdx(c.getAttribute("r"))
                    if (ci == col) {
                        cell = c
                        break
                    }
                    if (ci > col && before == null) before = c
                }
                c = c.nextSibling
            }
            if (cell == null) {
                cell = doc.createElement("c")
                cell.setAttribute("r", ref)
                row.insertBefore(cell, before)
            }
            cell.removeAttribute("t")
            cell.removeAttribute("vm")
            while (cell.firstChild != null) cell.removeChild(cell.firstChild)
            val v = doc.createElement("v")
            v.appendChild(doc.createTextNode(value.toString()))
            cell.appendChild(v)
            row.removeAttribute("spans")
        }

        for ((row, pair) in results) {
            val (p, a) = pair
            if (p != null) setCell(getRow(row), 1, p)   // B
            if (a != null) setCell(getRow(row), 4, a)   // E
        }

        val removed = HashSet<String>()

        // 1) картинки над ячейками
        val drEl = elems(doc, "drawing").firstOrNull()
        if (drEl != null) {
            val rid = attrLocal(drEl, "id")
            val drawingPath = if (rid != null) rels(z, sheet)[rid]?.second else null
            drEl.parentNode.removeChild(drEl)
            val sheetRels = relsPath(sheet)
            z[sheetRels]?.let { b ->
                val rd = parse(b)
                elems(rd, "Relationship").filter { it.getAttribute("Id") == rid }
                    .forEach { it.parentNode.removeChild(it) }
                z[sheetRels] = serialize(rd)
            }
            if (drawingPath != null) {
                removed += drawingPath
                removed += relsPath(drawingPath)
                rels(z, drawingPath).values.forEach { if (it.first.endsWith("/image")) removed += it.second }
            }
        }

        // 2) картинки «в ячейке» (richData)
        if (z.containsKey("xl/richData/richValueRel.xml")) {
            rels(z, "xl/richData/richValueRel.xml").values.forEach { removed += it.second }
            z.keys.filter { it.startsWith("xl/richData/") }.forEach { removed += it }
            if (z.containsKey("xl/metadata.xml")) removed += "xl/metadata.xml"
            for (c in elems(doc, "c")) {
                if (c.hasAttribute("vm")) {
                    c.removeAttribute("vm")
                    c.removeAttribute("t")
                    while (c.firstChild != null) c.removeChild(c.firstChild)
                }
            }
        }

        // 2b) картинки WPS (DISPIMG)
        if (z.containsKey("xl/cellimages.xml")) {
            removed += "xl/cellimages.xml"
            removed += relsPath("xl/cellimages.xml")
            rels(z, "xl/cellimages.xml").values.forEach { if (it.first.endsWith("/image")) removed += it.second }
            for (c in elems(doc, "c")) {
                val f = elems(c, "f").firstOrNull()?.textContent ?: continue
                if (f.contains("DISPIMG")) {
                    c.removeAttribute("t")
                    while (c.firstChild != null) c.removeChild(c.firstChild)
                }
            }
        }

        // 3) calcChain мог ссылаться на заменённые формулы
        if (z.containsKey("xl/calcChain.xml")) removed += "xl/calcChain.xml"

        z[sheet] = serialize(doc)

        if (removed.isNotEmpty()) {
            z["[Content_Types].xml"]?.let { b ->
                val ct = parse(b)
                elems(ct, "Override").filter { removed.contains(it.getAttribute("PartName").removePrefix("/")) }
                    .forEach { it.parentNode.removeChild(it) }
                z["[Content_Types].xml"] = serialize(ct)
            }
            val wbRels = relsPath("xl/workbook.xml")
            z[wbRels]?.let { b ->
                val rd = parse(b)
                elems(rd, "Relationship").filter {
                    removed.contains(resolve("xl/workbook.xml", it.getAttribute("Target")))
                }.forEach { it.parentNode.removeChild(it) }
                z[wbRels] = serialize(rd)
            }
        }

        ZipOutputStream(BufferedOutputStream(out)).use { zo ->
            for ((n, b) in z) {
                if (n in removed) continue
                zo.putNextEntry(ZipEntry(n))
                zo.write(b)
                zo.closeEntry()
            }
        }
    }
}
