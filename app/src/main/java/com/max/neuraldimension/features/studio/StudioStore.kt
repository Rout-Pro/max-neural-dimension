package com.max.neuraldimension.features.studio

import android.content.Context
import java.io.File

/**
 * Web Development Studio (blueprint Part 15): fully local project storage.
 * Each project is a directory under filesDir/studio/ with plain text files
 * (index.html, style.css, script.js, ...). No network, no provider calls.
 */
data class StudioProject(val name: String, val dir: File) {
    fun files(): List<File> = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
}

class StudioStore(context: Context) {

    private val root = File(context.filesDir, "studio")

    init { root.mkdirs() }

    fun list(): List<StudioProject> =
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
            ?.map { StudioProject(it.name, it) } ?: emptyList()

    fun exists(name: String): Boolean = File(root, name).isDirectory

    fun create(name: String, template: Template): StudioProject {
        val clean = name.trim().ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val dir = File(root, clean)
        dir.mkdirs()
        template.files.forEach { (fname, content) -> File(dir, fname).writeText(content) }
        return StudioProject(clean, dir)
    }

    fun rename(p: StudioProject, newName: String): StudioProject? {
        val clean = newName.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_")
        if (clean.isBlank() || clean == p.name) return null
        val to = File(root, clean)
        if (to.exists()) return null
        return if (p.dir.renameTo(to)) StudioProject(clean, to) else null
    }

    fun delete(p: StudioProject) {
        p.dir.deleteRecursively()
    }

    fun read(f: File): String = runCatching { f.readText() }.getOrDefault("")

    fun write(f: File, content: String) {
        runCatching { f.writeText(content) }
    }

    fun addFile(p: StudioProject, name: String): File? {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (clean.isBlank()) return null
        val f = File(p.dir, clean)
        if (f.exists()) return null
        f.writeText("")
        return f
    }

    /**
     * Single self-contained HTML document: style.css inlined into <style>,
     * script.js into <script>. External CDN links (React etc.) pass through.
     */
    fun combinedHtml(p: StudioProject): String {
        val html = File(p.dir, "index.html").let { if (it.exists()) read(it) else "" }
        val css = File(p.dir, "style.css").let { if (it.exists()) read(it) else "" }
        val js = File(p.dir, "script.js").let { if (it.exists()) read(it) else "" }
        var doc = html
        // Inline local css/js - the preview loads from a data document, so file
        // references would not resolve. Element attributes (e.g. text/babel) survive.
        if (css.isNotBlank()) {
            val linkRe = Regex("<link[^>]*href=[\"']style\\.css[\"'][^>]*>", RegexOption.IGNORE_CASE)
            doc = if (linkRe.containsMatchIn(doc)) linkRe.replace(doc, "<style>\n$css\n</style>")
                  else doc.replace("</head>", "<style>\n$css\n</style>\n</head>", ignoreCase = true)
        }
        if (js.isNotBlank()) {
            val scriptRe = Regex("<script([^>]*)\\ssrc=[\"']script\\.js[\"']([^>]*)>\\s*</script>", RegexOption.IGNORE_CASE)
            doc = if (scriptRe.containsMatchIn(doc)) scriptRe.replace(doc, "<script$1$2>\n$js\n</script>")
                  else if (doc.contains("</body>", ignoreCase = true))
                      doc.replace("</body>", "<script>\n$js\n</script>\n</body>", ignoreCase = true)
                  else doc + "\n<script>\n$js\n</script>"
        }
        if (!doc.contains("<html", ignoreCase = true)) {
            doc = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body>$doc</body></html>"
        }
        return doc
    }

    enum class Template(val label: String, val files: Map<String, String>) {
        BLANK("Blank page", mapOf(
            "index.html" to "<!DOCTYPE html>\n<html>\n<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n<h1>Hello from MAX Studio</h1>\n<script src=\"script.js\"></script>\n</body>\n</html>\n",
            "style.css" to "body { font-family: sans-serif; background: #0b0714; color: #e6e1f5; padding: 24px; }\n",
            "script.js" to "console.log('studio-ready');\n"
        )),
        STARTER("Starter site", mapOf(
            "index.html" to "<!DOCTYPE html>\n<html>\n<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n<header><h1>My Site</h1></header>\n<main><p>Edit me in MAX Studio.</p><button id=\"go\">Tap me</button></main>\n<script src=\"script.js\"></script>\n</body>\n</html>\n",
            "style.css" to "body { font-family: sans-serif; margin: 0; background: #0b0714; color: #e6e1f5; }\nheader { background: #2d1a5e; padding: 16px 24px; }\nmain { padding: 24px; }\nbutton { background: #7c5cff; color: white; border: 0; padding: 10px 18px; border-radius: 10px; }\n",
            "script.js" to "document.getElementById('go').addEventListener('click', function() {\n  console.log('button-tapped');\n  alert('It works!');\n});\nconsole.log('studio-ready');\n"
        )),
        REACT("React (CDN)", mapOf(
            "index.html" to "<!DOCTYPE html>\n<html>\n<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<script crossorigin src=\"https://unpkg.com/react@18/umd/react.production.min.js\"></script>\n<script crossorigin src=\"https://unpkg.com/react-dom@18/umd/react-dom.production.min.js\"></script>\n<script src=\"https://unpkg.com/@babel/standalone/babel.min.js\"></script>\n<link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n<div id=\"root\"></div>\n<script type=\"text/babel\" src=\"script.js\"></script>\n</body>\n</html>\n",
            "style.css" to "body { font-family: sans-serif; background: #0b0714; color: #e6e1f5; padding: 24px; }\n",
            "script.js" to "function App() {\n  return <h1>React in MAX Studio</h1>;\n}\nReactDOM.createRoot(document.getElementById('root')).render(<App />);\nconsole.log('studio-ready');\n"
        ))
    }
}
